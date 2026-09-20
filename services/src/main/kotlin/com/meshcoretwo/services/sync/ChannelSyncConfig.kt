// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sync

import java.time.Duration
import java.time.Instant

/**
 * Controls whether channel re-sync is skipped on resync. Ported from `ChannelSyncConfig.swift`.
 *
 * [com.meshcoretwo.services.connection.DevicePlatform] now exists (ported with the connection-types
 * slice) and its `channelSyncConfig(...)` extension factory can build one of these from a detected
 * platform — see that class's doc. Wiring platform detection into `SyncCoordinator`'s resync path
 * is still deferred to `ConnectionManager` (not yet started), so callers here still pass [NONE] or
 * build a [ChannelSyncConfig] by hand.
 */
data class ChannelSyncConfig(
    /** If channels were synced more recently than this window, skip channel re-sync. */
    val channelSyncSkipWindow: Duration = Duration.ZERO,
    /** Timestamp of the last fully-clean channel sync for the current device. */
    val lastCleanChannelSync: Instant? = null,
    /** Timestamp of the last attempted channel sync, even if it was partial. */
    val lastAttemptedChannelSync: Instant? = null,
    /** Whether channel reads should use the windowed read pipeline (nRF52-over-BLE / ESP32-over-WiFi). */
    val usePipelinedChannelRead: Boolean = false,
) {
    companion object {
        /** No skip — used for WiFi connections, and whenever no platform-specific config applies. */
        val NONE = ChannelSyncConfig()
    }
}
