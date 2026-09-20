// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.map

import android.content.Context
import androidx.annotation.StringRes
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.AppLanguageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.StatFs
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

private const val OFFLINE_BASE_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val OFFLINE_TOPO_STYLE_URL = "asset://topo-offline.json"

/** Which downloadable tile layer an [OfflinePack] belongs to. Ported from `OfflineMapLayer.swift`. */
enum class OfflineMapLayer(@StringRes val labelRes: Int, val maxDownloadZoom: Int) {
    BASE(R.string.offline_layer_base, 14),
    TOPO(R.string.offline_layer_topo, 17);

    val styleUrl: String
        get() = when (this) {
            BASE -> OFFLINE_BASE_STYLE_URL
            TOPO -> OFFLINE_TOPO_STYLE_URL
        }
}

/** Ported from `OfflinePackMetadata`; stored as the pack's opaque `context`/`metadata` bytes. */
data class OfflinePackMetadata(val name: String, val createdAtEpochMillis: Long, val layer: OfflineMapLayer) {
    fun toBytes(): ByteArray = JSONObject().apply {
        put("name", name)
        put("createdAt", createdAtEpochMillis)
        put("layer", layer.name)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(bytes: ByteArray?): OfflinePackMetadata? {
            bytes ?: return null
            return runCatching {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                OfflinePackMetadata(
                    name = json.getString("name"),
                    createdAtEpochMillis = json.getLong("createdAt"),
                    layer = OfflineMapLayer.entries.firstOrNull { it.name == json.optString("layer") } ?: OfflineMapLayer.BASE,
                )
            }.getOrNull()
        }
    }
}

/** Ported from `OfflineMapError`; `missingStyleResource` has no Android equivalent — every [OfflineMapLayer.styleUrl] always resolves. */
sealed class OfflineMapError(message: String) : Exception(message) {
    object InsufficientDiskSpace : OfflineMapError("Not enough free storage to download this region.")
    class DownloadFailed(reason: String) : OfflineMapError(reason)
}

/** A downloaded/downloading offline region. Ported from the `OfflinePack` struct. */
data class OfflinePack(
    val id: Long,
    val name: String,
    val createdAtEpochMillis: Long?,
    val layer: OfflineMapLayer,
    val completedFraction: Double,
    val completedBytes: Long,
    val downloadSpeedBytesPerSecond: Long?,
    val isComplete: Boolean,
    val isPaused: Boolean,
    internal val region: OfflineRegion,
)

/**
 * Downloads and tracks MapLibre offline tile packs for map access without a network connection.
 * Ported from `OfflineMapService.swift`. Lives in `app`, not `services`, for the same reason
 * `MapViewModel`/`MapScreen` do — it's a thin wrapper over `org.maplibre.android.offline.*`, and
 * `app/build.gradle.kts`'s MapLibre dependency comment already anticipated this file living here.
 *
 * Progress tracking differs from Swift's: MapLibre Android pushes per-region status via
 * [OfflineRegion.OfflineRegionObserver] instead of one app-wide `NotificationCenter` broadcast, so
 * there's no need for Swift's debounced `scheduleLoadPacks()` or its late-offline-DB-init retry —
 * each region's own observer keeps just that region current, and [OfflineManager.listOfflineRegions]
 * is always asynchronous (never synchronously `nil`). "Storage used" (in the settings screen) is the
 * sum of completed pack byte counts rather than iOS's actual offline sqlite database file size —
 * MapLibre Android's offline database path is an internal implementation detail, not public API.
 * `resumeAllPacks()` (Swift: called from `AppState`'s foreground-lifecycle hook) is instead called
 * once here, after the first [loadPacks] completes, since this service is a process-lifetime
 * singleton and this port has no app-foreground-lifecycle observer to hang a repeat call on.
 */
class OfflineMapService(context: Context) {
    private val appContext = context.applicationContext
    private fun localized(@StringRes id: Int): String = AppLanguageManager.wrap(appContext).getString(id)
    private val offlineManager = OfflineManager.getInstance(appContext)
    private val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _packs = MutableStateFlow<List<OfflinePack>>(emptyList())
    val packs: StateFlow<List<OfflinePack>> = _packs.asStateFlow()

    private val _isNetworkAvailable = MutableStateFlow(hasValidatedInternet())
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    private val _lastPackError = MutableStateFlow<String?>(null)
    val lastPackError: StateFlow<String?> = _lastPackError.asStateFlow()

    private data class ByteSnapshot(val bytes: Long, val atMillis: Long)

    private val observedRegions = mutableMapOf<Long, OfflineRegion>()
    private val statusCache = mutableMapOf<Long, OfflineRegionStatus>()
    private val metadataCache = mutableMapOf<Long, OfflinePackMetadata?>()
    private val byteSnapshots = mutableMapOf<Long, ByteSnapshot>()
    private val downloadSpeeds = mutableMapOf<Long, Long>()
    private val highWaterMarks = mutableMapOf<Long, Double>()
    private val userPausedRegionIds = mutableSetOf<Long>()
    private val deletingRegionIds = mutableSetOf<Long>()
    private var hasResumedOnLaunch = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isNetworkAvailable.value = true
        }

        override fun onLost(network: Network) {
            _isNetworkAvailable.value = hasValidatedInternet()
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, networkCallback) }
        loadPacks()
    }

    private fun hasValidatedInternet(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun loadPacks() {
        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = offlineRegions?.toList().orEmpty()
                val currentIds = regions.map { it.id }.toSet()
                observedRegions.keys.retainAll(currentIds)
                statusCache.keys.retainAll(currentIds)
                metadataCache.keys.retainAll(currentIds)
                byteSnapshots.keys.retainAll(currentIds)
                downloadSpeeds.keys.retainAll(currentIds)
                highWaterMarks.keys.retainAll(currentIds)

                regions.forEach(::attachRegion)
                rebuildPacks()

                if (!hasResumedOnLaunch) {
                    hasResumedOnLaunch = true
                    resumeAllPacks()
                }
            }

            override fun onError(error: String) {
                _lastPackError.value = error
            }
        })
    }

    private fun attachRegion(region: OfflineRegion) {
        if (observedRegions.put(region.id, region) != null) return
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                recordStatus(region.id, status)
                rebuildPacks()
            }

            override fun onError(error: OfflineRegionError) {
                _lastPackError.value = error.message
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                _lastPackError.value = localized(R.string.offline_err_tile_limit)
            }
        })
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) {
                status ?: return
                recordStatus(region.id, status)
                rebuildPacks()
            }

            override fun onError(error: String?) {
                _lastPackError.value = error
            }
        })
    }

    private fun recordStatus(id: Long, status: OfflineRegionStatus) {
        val now = SystemClock.elapsedRealtime()
        val currentBytes = status.completedResourceSize
        byteSnapshots[id]?.let { previous ->
            val elapsedSeconds = (now - previous.atMillis) / 1000.0
            if (elapsedSeconds > 0.5 && currentBytes > previous.bytes) {
                downloadSpeeds[id] = ((currentBytes - previous.bytes) / elapsedSeconds).toLong()
            } else if (currentBytes == previous.bytes) {
                downloadSpeeds[id] = 0L
            }
        }
        byteSnapshots[id] = ByteSnapshot(currentBytes, now)
        statusCache[id] = status
    }

    private fun rebuildPacks() {
        _packs.value = observedRegions.values.map { region ->
            val status = statusCache[region.id]
            val metadata = metadataCache.getOrPut(region.id) { OfflinePackMetadata.fromBytes(region.metadata) }
            val rawFraction = when {
                status == null -> 0.0
                status.isComplete -> 1.0
                status.requiredResourceCount > 0 -> status.completedResourceCount.toDouble() / status.requiredResourceCount
                else -> 0.0
            }
            val fraction = maxOf(rawFraction, highWaterMarks[region.id] ?: 0.0)
            highWaterMarks[region.id] = fraction
            val isComplete = status?.isComplete == true
            val isActive = status != null && !isComplete && status.downloadState == OfflineRegion.STATE_ACTIVE
            OfflinePack(
                id = region.id,
                name = metadata?.name ?: localized(R.string.offline_unknown_region),
                createdAtEpochMillis = metadata?.createdAtEpochMillis,
                layer = metadata?.layer ?: OfflineMapLayer.BASE,
                completedFraction = fraction,
                completedBytes = status?.completedResourceSize ?: 0L,
                downloadSpeedBytesPerSecond = if (isActive) downloadSpeeds[region.id] else null,
                isComplete = isComplete,
                isPaused = status != null && !isComplete && status.downloadState == OfflineRegion.STATE_INACTIVE,
                region = region,
            )
        }
    }

    /** Throws [OfflineMapError] on failure. One pack per requested layer, all sharing [name]/[bounds]. */
    suspend fun downloadRegion(name: String, bounds: LatLngBounds, layers: Set<OfflineMapLayer>, minZoom: Int = 10) {
        if (availableDiskSpaceBytes() < MINIMUM_DISK_SPACE_BYTES) throw OfflineMapError.InsufficientDiskSpace
        val now = System.currentTimeMillis()
        val pixelRatio = appContext.resources.displayMetrics.density
        for (layer in layers) {
            val metadata = OfflinePackMetadata(name, now, layer)
            val definition = OfflineTilePyramidRegionDefinition(
                layer.styleUrl,
                bounds,
                minZoom.toDouble(),
                layer.maxDownloadZoom.toDouble(),
                pixelRatio,
            )
            val region = suspendCancellableCoroutine { continuation ->
                offlineManager.createOfflineRegion(
                    definition,
                    metadata.toBytes(),
                    object : OfflineManager.CreateOfflineRegionCallback {
                        override fun onCreate(offlineRegion: OfflineRegion) {
                            continuation.resume(offlineRegion)
                        }

                        override fun onError(error: String) {
                            continuation.resumeWithException(OfflineMapError.DownloadFailed(error))
                        }
                    },
                )
            }
            attachRegion(region)
            region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        }
        loadPacks()
    }

    suspend fun deletePack(pack: OfflinePack) {
        if (!deletingRegionIds.add(pack.id)) return
        try {
            suspendCancellableCoroutine { continuation ->
                pack.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() {
                        continuation.resume(Unit)
                    }

                    override fun onError(error: String) {
                        // Swift logs and proceeds to reload regardless — deletion best-effort.
                        continuation.resume(Unit)
                    }
                })
            }
        } finally {
            deletingRegionIds.remove(pack.id)
            observedRegions.remove(pack.id)
            statusCache.remove(pack.id)
            metadataCache.remove(pack.id)
            byteSnapshots.remove(pack.id)
            downloadSpeeds.remove(pack.id)
            highWaterMarks.remove(pack.id)
            rebuildPacks()
        }
    }

    fun pausePack(pack: OfflinePack) {
        userPausedRegionIds.add(pack.id)
        pack.region.setDownloadState(OfflineRegion.STATE_INACTIVE)
    }

    fun resumePack(pack: OfflinePack) {
        userPausedRegionIds.remove(pack.id)
        pack.region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    fun resumeAllPacks() {
        observedRegions.values.forEach { region ->
            if (region.id !in userPausedRegionIds) region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        }
    }

    fun clearLastPackError() {
        _lastPackError.value = null
    }

    fun availableDiskSpaceBytes(): Long =
        runCatching { StatFs(appContext.filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    companion object {
        private const val MINIMUM_DISK_SPACE_BYTES = 100_000_000L

        /** Estimated download size using per-zoom average byte sizes. Ported from `estimatedDownloadSize`. */
        fun estimatedDownloadSizeBytes(
            south: Double,
            west: Double,
            north: Double,
            east: Double,
            minZoom: Int,
            maxZoom: Int,
            layer: OfflineMapLayer,
        ): Long {
            val bytesPerTile: Map<Int, Long> = when (layer) {
                OfflineMapLayer.BASE -> mapOf(10 to 15_000L, 11 to 25_000L, 12 to 45_000L, 13 to 70_000L, 14 to 100_000L)
                OfflineMapLayer.TOPO -> mapOf(
                    10 to 15_000L, 11 to 18_000L, 12 to 22_000L, 13 to 25_000L,
                    14 to 30_000L, 15 to 35_000L, 16 to 40_000L, 17 to 45_000L,
                )
            }
            val overhead = 500_000L
            var total = 0L
            for (z in minZoom..maxZoom) {
                val n = (1L shl z).toDouble()
                val xMin = floor((west + 180) / 360 * n).toInt()
                val xMax = floor((east + 180) / 360 * n).toInt()

                val latRadNorth = Math.toRadians(north)
                val latRadSouth = Math.toRadians(south)
                val yMin = floor((1 - ln(tan(latRadNorth) + 1 / cos(latRadNorth)) / Math.PI) / 2 * n).toInt()
                val yMax = floor((1 - ln(tan(latRadSouth) + 1 / cos(latRadSouth)) / Math.PI) / 2 * n).toInt()

                val tileCount = (abs(xMax - xMin) + 1) * (abs(yMax - yMin) + 1)
                total += tileCount * (bytesPerTile[z] ?: 10_000L)
            }
            return total + overhead
        }
    }
}
