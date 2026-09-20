// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.services.ServiceContainer
import com.meshcoretwo.services.sync.ChannelSyncConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Sync retry policy (initial sync, resync loop, channel-only retry) over the connection lifecycle
 * [ConnectionManager] owns. Ported from `Sync/ConnectionManager+SyncRetry.swift`. [SyncCoordinator]'s
 * class doc explains why `performFullSync` doubles as Swift's `performResync` (no separate method
 * needed) and why [SyncCoordinator.retryChannels] — not a Task-bookkeeping wrapper — is the only
 * new method that slice needed.
 *
 * No `beginResyncActivity`/`endResyncActivity` bracket: that pair exists in Swift only to paper
 * over the "syncing" pill flickering to "failed" between retry attempts — a UI polish concern.
 * This port's `syncCoordinator.state` already reflects each attempt's real state directly (see
 * [SyncCoordinator]'s "Deferred" list), and nothing here needs a synthetic activity signal on top
 * of it.
 */

/** Performs initial sync with an automatic resync loop on failure. Ported from `performInitialSync`. */
internal suspend fun ConnectionManager.performInitialSyncImpl(
    radioID: UUID,
    boundServices: ServiceContainer,
    transportType: TransportType = TransportType.BLUETOOTH,
    context: String = "",
    forceFullSync: Boolean = false,
): Boolean {
    val channelSyncConfig = currentChannelSyncConfigImpl(radioID, transportType)
    return try {
        val result = boundServices.syncCoordinator.onConnectionEstablished(radioID, boundServices.syncDependencies, forceFullSync, channelSyncConfig)

        if (result.channelRetryIndices.isNotEmpty()) {
            scheduleChannelOnlyRetryImpl(radioID, boundServices, result.channelRetryIndices)
        }

        if (result.isConnectionUsable) return true

        if (!connectionIntent.wantsConnection) return false
        startResyncLoopImpl(radioID, boundServices, transportType, forceFullSync)
        false
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (!connectionIntent.wantsConnection) return false
        startResyncLoopImpl(radioID, boundServices, transportType, forceFullSync)
        false
    }
}

/**
 * Starts a retry loop to resync after initial sync failure. Retries every 2 seconds, disconnects
 * after [ConnectionManager.MAX_RESYNC_ATTEMPTS] failures. Ported from `startResyncLoop`.
 */
internal fun ConnectionManager.startResyncLoopImpl(
    radioID: UUID,
    boundServices: ServiceContainer,
    transportType: TransportType = TransportType.BLUETOOTH,
    forceFullSync: Boolean = false,
) {
    resyncJob?.cancel()
    resyncAttemptCount = 0

    resyncJob = scope.launch {
        while (isActive) {
            delay(ConnectionManager.RESYNC_INTERVAL_MILLIS)
            if (!isActive) break

            // Fence on services identity so a loop orphaned by a later reconnect cycle neither
            // resyncs nor disconnects against a container this manager has since replaced.
            if (!connectionIntent.wantsConnection || !connectionState.isOperational || services !== boundServices) break

            resyncAttemptCount += 1

            val channelSyncConfig = currentChannelSyncConfigImpl(radioID, transportType)
            val success = try {
                boundServices.syncCoordinator.performFullSync(radioID, boundServices.syncDependencies, forceFullSync, channelSyncConfig).isConnectionUsable
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                false
            }

            if (success) {
                resyncAttemptCount = 0

                if (!connectionIntent.wantsConnection || !connectionState.isOperational || services !== boundServices) break

                setConnectionState(DeviceConnectionState.READY)
                syncDeviceTimeIfNeededImpl()

                if (!connectionIntent.wantsConnection || !connectionState.isOperational || services !== boundServices) break

                // Re-authenticate room sessions before onDeviceSynced.
                val sessionIDs = sessionsAwaitingReauth.toSet()
                if (sessionIDs.isNotEmpty()) {
                    boundServices.remoteNodeService.handleBLEReconnection(sessionIDs)
                }

                if (!connectionIntent.wantsConnection || !connectionState.isOperational || services !== boundServices) break

                // Only clear consumed IDs after confirming the loop is still valid — any IDs
                // appended during the await survive.
                sessionsAwaitingReauth.removeAll(sessionIDs)

                onDeviceSynced?.invoke()
                break
            }

            if (resyncAttemptCount >= ConnectionManager.MAX_RESYNC_ATTEMPTS) {
                onResyncFailed?.invoke()
                disconnect(DisconnectReason.RESYNC_FAILED)
                break
            }
        }
        resyncJob = null
    }
}

/**
 * Schedules a bounded channel-only retry after a partial channel phase. Keeps contacts/messages
 * out of the retry path when the connection is otherwise usable. Ported from `scheduleChannelOnlyRetry`.
 */
internal fun ConnectionManager.scheduleChannelOnlyRetryImpl(radioID: UUID, boundServices: ServiceContainer, indices: List<UByte>) {
    val initialIndices = indices.toSortedSet().toList()
    if (initialIndices.isEmpty()) return

    channelRetryJob?.cancel()

    channelRetryJob = scope.launch {
        var pendingIndices = initialIndices

        for (attempt in 1..ConnectionManager.MAX_CHANNEL_RETRY_ATTEMPTS) {
            if (!isActive) break
            val delayMillis = maxOf(ConnectionManager.CHANNEL_RETRY_INITIAL_DELAY_MILLIS, 2_000L shl (attempt - 1))
            delay(delayMillis)
            if (!isActive) break

            if (!connectionIntent.wantsConnection || !connectionState.isOperational || services !== boundServices) break

            val result = boundServices.syncCoordinator.retryChannels(radioID, boundServices.channelService, pendingIndices)

            if (result.isComplete) {
                pendingIndices = emptyList()
                break
            }

            pendingIndices = result.retryableIndices
            if (pendingIndices.isEmpty()) break
        }
        channelRetryJob = null
    }
}

/** Cancels any resync retry loop in progress. Ported from `cancelResyncLoop`. */
internal fun ConnectionManager.cancelResyncLoopImpl() {
    resyncJob?.cancel()
    resyncJob = null
    resyncAttemptCount = 0
}

/** Ported from `cancelChannelRetry`. */
internal fun ConnectionManager.cancelChannelRetryImpl() {
    channelRetryJob?.cancel()
    channelRetryJob = null
}

/** Builds a channel sync config for the current device and transport. Ported from `currentChannelSyncConfig`. */
internal fun ConnectionManager.currentChannelSyncConfigImpl(radioID: UUID, transportType: TransportType): ChannelSyncConfig {
    val usePipelinedChannelRead = detectedPlatform == DevicePlatform.NRF52 && transportType == TransportType.BLUETOOTH ||
        detectedPlatform == DevicePlatform.ESP32 && transportType == TransportType.WIFI

    return detectedPlatform.channelSyncConfig(
        lastCleanChannelSync = lastCleanChannelSync?.takeIf { it.first == radioID }?.second,
        lastAttemptedChannelSync = lastAttemptedChannelSync?.takeIf { it.first == radioID }?.second,
        usePipelinedChannelRead = usePipelinedChannelRead,
    )
}
