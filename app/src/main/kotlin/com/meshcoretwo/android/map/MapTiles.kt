// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.map

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.core.content.edit
import com.meshcoretwo.services.utilities.HTTP_USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Outcome of fetching one provider's [MapBaseStyle.probeUrl]. */
sealed interface MapProbeResult {
    data class Reachable(val millis: Long) : MapProbeResult
    data class Unreachable(val reason: String) : MapProbeResult
}

/**
 * Checks whether a map host actually delivers data from the current network. Reads up to
 * [PROBE_BYTES] of [MapBaseStyle.probeUrl]: under the Russian "16 KB curtain" the TLS handshake
 * and the first ~16 KB succeed and the rest stalls, so a HEAD request or a small file would
 * report a blocked host as fine.
 */
object MapTileProbe {
    private const val PROBE_BYTES = 48 * 1024
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    suspend fun probe(style: MapBaseStyle): MapProbeResult = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(style.probeUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", HTTP_USER_AGENT)
            }
            val code = connection.responseCode
            if (code !in 200..299) return@withContext MapProbeResult.Unreachable("HTTP $code")
            connection.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (total < PROBE_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
            }
            MapProbeResult.Reachable(SystemClock.elapsedRealtime() - started)
        } catch (e: IOException) {
            MapProbeResult.Unreachable(e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            MapProbeResult.Unreachable(e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }
}

/**
 * Which basemap the map screens use. Process-wide, like `MapLibre.getInstance`: every map
 * controller calls [resolve] (via [setBaseStyle]) without having a container at hand.
 *
 * For [MapTileProviderId.AUTO] the choice is probed once per process and cached. The last
 * working provider is tried first so a normal launch costs one small request. Without validated
 * internet nothing is probed and the last working provider is used as is, because offline packs
 * only serve the style URL they were downloaded with.
 */
object MapTiles {
    private const val PREFS_NAME = "map_tiles"
    private const val KEY_SELECTED = "selected"
    private const val KEY_CUSTOM_URL = "customUrl"
    private const val KEY_LAST_AUTO = "lastAuto"

    private lateinit var appContext: Context
    private val prefs: SharedPreferences by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val mutex = Mutex()
    private var resolved: MapBaseStyle? = null

    private val _selected = MutableStateFlow(MapTileProviderId.AUTO)
    val selected: StateFlow<MapTileProviderId> = _selected.asStateFlow()

    private val _customUrl = MutableStateFlow("")
    val customUrl: StateFlow<String> = _customUrl.asStateFlow()

    /** The provider [AUTO][MapTileProviderId.AUTO] settled on in this process, for the settings screen. */
    private val _autoResolved = MutableStateFlow<MapTileProviderId?>(null)
    val autoResolved: StateFlow<MapTileProviderId?> = _autoResolved.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        _selected.value = MapTileProviderId.fromName(prefs.getString(KEY_SELECTED, null)) ?: MapTileProviderId.AUTO
        _customUrl.value = prefs.getString(KEY_CUSTOM_URL, null).orEmpty()
    }

    fun select(id: MapTileProviderId, customUrl: String? = null) {
        prefs.edit {
            putString(KEY_SELECTED, id.name)
            customUrl?.let { putString(KEY_CUSTOM_URL, it.trim()) }
        }
        _selected.value = id
        customUrl?.let { _customUrl.value = it.trim() }
        resolved = null
    }

    /** The style the map screens should load now; probes only for [MapTileProviderId.AUTO]. */
    suspend fun resolve(): MapBaseStyle = mutex.withLock {
        resolved?.let { return it }
        val style = when (val id = _selected.value) {
            MapTileProviderId.AUTO -> resolveAuto()
            MapTileProviderId.CUSTOM -> MapBaseStyle.custom(_customUrl.value) ?: MapBaseStyle.openFreeMap
            else -> checkNotNull(MapBaseStyle.builtIn(id))
        }
        resolved = style
        style
    }

    /** The style offline packs are created against right now; `null` when it forbids bulk download. */
    suspend fun offlineStyle(): MapBaseStyle? = resolve().takeIf { it.offlineStyleUrl != null }

    /** Probes every built-in provider (and a valid custom one) in parallel, for the settings screen. */
    suspend fun probeAll(): Map<MapTileProviderId, MapProbeResult> = coroutineScope {
        val targets = MapTileProviderId.autoCandidates.mapNotNull { id -> MapBaseStyle.builtIn(id)?.let { id to it } } +
            listOfNotNull(MapBaseStyle.custom(_customUrl.value)?.let { MapTileProviderId.CUSTOM to it })
        targets.map { (id, style) -> async { id to MapTileProbe.probe(style) } }.awaitAll().toMap()
    }

    /** Drops the cached choice so the next [resolve] probes again, e.g. after a network change. */
    fun invalidate() {
        resolved = null
    }

    private suspend fun resolveAuto(): MapBaseStyle {
        val lastWorking = MapTileProviderId.fromName(prefs.getString(KEY_LAST_AUTO, null))
            ?.let(MapBaseStyle::builtIn)
            ?: MapBaseStyle.openFreeMap
        if (!hasValidatedInternet()) return lastWorking.also { _autoResolved.value = it.provider }

        if (MapTileProbe.probe(lastWorking) is MapProbeResult.Reachable) {
            return lastWorking.also { _autoResolved.value = it.provider }
        }
        val candidates = MapTileProviderId.autoCandidates.mapNotNull(MapBaseStyle::builtIn).filter { it != lastWorking }
        val results = coroutineScope { candidates.map { async { it to MapTileProbe.probe(it) } }.awaitAll() }
        val chosen = results.firstOrNull { it.second is MapProbeResult.Reachable }?.first ?: lastWorking
        prefs.edit { putString(KEY_LAST_AUTO, chosen.provider.name) }
        _autoResolved.value = chosen.provider
        return chosen
    }

    private fun hasValidatedInternet(): Boolean {
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork ?: return false) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
