// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.android.R
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.DeviceConnectionState
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.connection.rxLogService
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DecryptStatus
import com.meshcoretwo.services.persistence.RxLogDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Ported from `RxLogViewModel.RouteFilter`. */
enum class RxLogRouteFilter(@StringRes val labelRes: Int) {
    ALL(R.string.common_all),
    FLOOD_ONLY(R.string.rx_flood_only),
    DIRECT_ONLY(R.string.rx_direct_only),
}

/** Ported from `RxLogViewModel.DecryptFilter`. */
enum class RxLogDecryptFilter(@StringRes val labelRes: Int) {
    ALL(R.string.common_all),
    DECRYPTED(R.string.rx_decrypted),
    FAILED(R.string.saved_failed),
}

private val FAILED_DECRYPT_STATUSES = setOf(
    DecryptStatus.HMAC_FAILED,
    DecryptStatus.DECRYPT_FAILED,
    DecryptStatus.NO_MATCHING_KEY,
    DecryptStatus.DM_NO_MATCHING_KEY,
)

/** Caps how many entries the live view keeps in memory, matching [RxLogService]'s own on-disk prune threshold. */
private const val MAX_ENTRIES = 1000

/**
 * Backs the RX Log screen (`RxLogScreen.kt`) — a port of `RxLogViewModel.swift`, trimmed to what
 * that class actually does (no region-footer subscription, which isn't ported — see
 * `RxLogService`'s class doc). Subscribes to [ConnectionManager.rxLogService]'s live
 * [com.meshcoretwo.services.rxlog.RxLogService.entryStream] while connected, same
 * subscribe-on-READY / cancel-on-disconnect shape as [com.meshcoretwo.android.chat.ChatListViewModel]'s
 * event job, prepending each new entry (capped at [MAX_ENTRIES], matching the on-disk prune
 * target) rather than re-fetching the whole log per packet.
 *
 * Node names for path-hop/sender-prefix resolution are built here, not read from a service-level
 * cache — [contactService] already exposes a flat contact list, and Swift's own
 * `buildNodeNameMap` (ported verbatim below) is itself pure, so there was no reason to duplicate a
 * name cache inside [com.meshcoretwo.services.rxlog.RxLogService].
 */
class RxLogViewModel(private val connectionManager: ConnectionManager) : ViewModel() {
    private val _entries = MutableStateFlow<List<RxLogDto>>(emptyList())
    val entries: StateFlow<List<RxLogDto>> = _entries.asStateFlow()

    private val _groupCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val groupCounts: StateFlow<Map<String, Int>> = _groupCounts.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _nodeNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val nodeNames: StateFlow<Map<String, String>> = _nodeNames.asStateFlow()

    private val _routeFilter = MutableStateFlow(RxLogRouteFilter.ALL)
    val routeFilter: StateFlow<RxLogRouteFilter> = _routeFilter.asStateFlow()

    private val _decryptFilter = MutableStateFlow(RxLogDecryptFilter.ALL)
    val decryptFilter: StateFlow<RxLogDecryptFilter> = _decryptFilter.asStateFlow()

    private var streamJob: Job? = null

    init {
        viewModelScope.launch {
            connectionManager.connectionStateEvents.collect { state ->
                if (state == DeviceConnectionState.READY) {
                    subscribe()
                } else {
                    unsubscribe()
                    _isConnected.value = false
                }
            }
        }
    }

    fun setRouteFilter(filter: RxLogRouteFilter) {
        _routeFilter.value = filter
    }

    fun setDecryptFilter(filter: RxLogDecryptFilter) {
        _decryptFilter.value = filter
    }

    fun clearLog() {
        viewModelScope.launch {
            connectionManager.rxLogService?.clearEntries()
            _entries.value = emptyList()
            _groupCounts.value = emptyMap()
        }
    }

    private fun subscribe() {
        val service = connectionManager.rxLogService ?: return
        streamJob?.cancel()
        _isConnected.value = true
        viewModelScope.launch {
            _entries.value = service.loadExistingEntries()
            rebuildGroupCounts()
            loadNodeNames()
        }
        streamJob = viewModelScope.launch {
            service.entryStream().collect { entry -> appendEntry(entry) }
        }
    }

    private fun unsubscribe() {
        streamJob?.cancel()
        streamJob = null
    }

    private fun appendEntry(entry: RxLogDto) {
        val updated = (listOf(entry) + _entries.value).let { if (it.size > MAX_ENTRIES) it.dropLast(it.size - MAX_ENTRIES) else it }
        _entries.value = updated
        _groupCounts.value = _groupCounts.value.toMutableMap().apply { merge(entry.packetHash, 1, Int::plus) }
    }

    private fun rebuildGroupCounts() {
        _groupCounts.value = _entries.value.groupingBy { it.packetHash }.eachCount()
    }

    private suspend fun loadNodeNames() {
        val radioID = connectionManager.lastConnectedRadioID ?: return
        val contacts = connectionManager.contactService?.getContacts(radioID) ?: return
        _nodeNames.value = buildNodeNameMap(contacts)
    }

    class Factory(private val connectionManager: ConnectionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RxLogViewModel(connectionManager) as T
    }

    companion object {
        /**
         * Builds a map from public-key prefix (1-3 byte, hex-encoded) to display name. Only
         * stores prefixes that uniquely identify a single contact at that length — ported from
         * `RxLogViewModel.buildNodeNameMap(from:)`. Keyed by hex string rather than `ByteArray`
         * (which has reference, not structural, equality in Kotlin) — the same adaptation
         * `ContactManager.getByPublicKey` already uses.
         */
        fun buildNodeNameMap(contacts: List<ContactDto>): Map<String, String> {
            val map = mutableMapOf<String, String>()
            for (prefixLength in 1..3) {
                val prefixCounts = mutableMapOf<String, Pair<String, Int>>()
                for (contact in contacts) {
                    if (contact.publicKey.size < prefixLength) continue
                    val prefix = contact.publicKey.copyOfRange(0, prefixLength).hexString
                    val existing = prefixCounts[prefix]
                    prefixCounts[prefix] = if (existing != null) existing.first to existing.second + 1 else contact.displayName to 1
                }
                for ((prefix, entry) in prefixCounts) {
                    if (entry.second == 1) map[prefix] = entry.first
                }
            }
            return map
        }
    }
}

/** Applies [RxLogViewModel]'s route/decrypt filters. Ported from `RxLogViewModel.filteredEntries`. */
fun List<RxLogDto>.filterEntries(routeFilter: RxLogRouteFilter, decryptFilter: RxLogDecryptFilter): List<RxLogDto> = filter { entry ->
    val routeMatches = when (routeFilter) {
        RxLogRouteFilter.ALL -> true
        RxLogRouteFilter.FLOOD_ONLY -> entry.isFlood
        RxLogRouteFilter.DIRECT_ONLY -> !entry.isFlood
    }
    val decryptMatches = when (decryptFilter) {
        RxLogDecryptFilter.ALL -> true
        RxLogDecryptFilter.DECRYPTED -> entry.decryptStatus == DecryptStatus.SUCCESS
        RxLogDecryptFilter.FAILED -> entry.decryptStatus in FAILED_DECRYPT_STATUSES
    }
    routeMatches && decryptMatches
}

/** Deduplicates consecutive-by-hash entries to one row per unique packet. Ported from `RxLogView.displayEntries`'s `groupDuplicates` branch. */
fun List<RxLogDto>.deduplicatedByHash(): List<RxLogDto> {
    val seen = mutableSetOf<String>()
    return filter { seen.add(it.packetHash) }
}
