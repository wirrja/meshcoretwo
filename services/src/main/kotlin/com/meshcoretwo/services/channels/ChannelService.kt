// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.channels

import android.net.Uri
import com.meshcoretwo.protocol.ChannelInfo
import com.meshcoretwo.protocol.ChannelSessionOps
import com.meshcoretwo.protocol.ErrorCode
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.decodeHex
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.protocol.utf8Prefix
import com.meshcoretwo.services.persistence.ChannelDto
import com.meshcoretwo.services.persistence.ChannelFloodScope
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.NotificationLevel
import com.meshcoretwo.services.settings.SettingsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Service for channel (group) management: CRUD, secret hashing, and device sync. Ported from
 * `ChannelService.swift`, covering essentially the whole service — see the class-level "Deferred"
 * note for the small remainder.
 *
 * Declares [ChannelSessionOps] rather than the broader `MeshCoreSessionProtocol` since that's
 * all this service touches (matching [ContactService][com.meshcoretwo.services.contacts.ContactService]'s
 * precedent).
 *
 * **Deferred, not yet ported:**
 * - `draftClearHandler` injection — already `Optional` in Swift, so omitting it here is faithful
 *   to the type, not a shortcut; wire it once `DraftStore` exists.
 * - Unread-count / mention-tracking store operations (`incrementChannelUnreadCount`, ...) — not
 *   called by `ChannelService.swift` itself either; they belong to `MessageService`/UI, not
 *   ported yet. (The two unread-*count resets* [clearChannelMessages] itself calls *are* ported,
 *   along with the Message table they needed — see below. [setFavorite]/[setNotificationLevel]
 *   are also now ported, for the Phase 5 channel-management UI slice — see [ChannelStore]'s doc.)
 * - `setDraftClearHandler` — trivial wiring for the deferred dependency above.
 *
 * **`messageStore`** (added for the "HeardRepeats" slice, once the Message table existed) unblocks
 * [clearChannelMessages] — previously deferred in full, with only its channel-row-deletion half
 * ([clearChannel]) ported and a `TODO` standing in for the message-deletion call.
 *
 * **`channelSecretsSink`** (added for the "RxLog" slice) is the fresh-secrets counterpart of
 * `rxLogService`/`hasRxLogServiceWired` above — now that `RxLogService` exists, every successful
 * sync/set/clear forwards the device's current channel list through this narrow function-type
 * seam (mirroring [com.meshcoretwo.services.messages.IncomingMessageService]'s
 * `pendingAdvertResolver`) so newly-captured RF packets decrypt with up-to-date secrets, without
 * `channels` needing a hard dependency on the `rxlog` package.
 *
 * **`isSyncing` is an [AtomicBoolean]**, not the `Mutex` most actor ports in this codebase use:
 * Swift's `guard !isSyncing else { throw }` is a claim-or-reject test-and-set, not a
 * wait-for-the-lock operation, so `compareAndSet` expresses it directly without a mutex's
 * blocking-wait semantics (which would make a second caller wait instead of being rejected).
 */
class ChannelService(
    private val session: ChannelSessionOps,
    private val channelStore: ChannelStore,
    private val messageStore: MessageStore,
    private val channelSecretsSink: (suspend (List<ChannelDto>) -> Unit)? = null,
) {
    private val isSyncing = AtomicBoolean(false)

    /** Channel slots whose occupant changed (vacated, or rewritten with a different secret) on [radioID]. */
    data class SlotOccupantChange(val radioID: UUID, val indices: Set<UByte>)

    private val slotOccupantChangedFlow = MutableSharedFlow<SlotOccupantChange>(extraBufferCapacity = 8)

    /**
     * Fires when a slot's occupant changes, after the old occupant's messages were already wiped by
     * [ChannelStore] — an open chat for that slot must close instead of showing the next channel's
     * timeline over stale UI state. Ported from `setSlotOccupantChangedHandler` (upstream `70b4bcfd`),
     * as a multicast flow instead of a single installed handler; this port has no draft store or
     * cached chat coordinators to clear, so the open conversation is the only consumer.
     */
    fun slotOccupantChanges(): Flow<SlotOccupantChange> = slotOccupantChangedFlow

    private suspend fun notifySlotsChanged(radioID: UUID, indices: Set<UByte>) {
        if (indices.isNotEmpty()) slotOccupantChangedFlow.emit(SlotOccupantChange(radioID, indices))
    }

    /** Indices whose configured secret differs from the locally stored row. Slots with no prior row are first sightings, not occupant changes. */
    private fun secretChangedIndices(prior: List<ChannelDto>, configured: List<ChannelInfo>): Set<UByte> {
        val priorSecrets = prior.associate { it.index to it.secret }
        return configured.mapNotNull { info ->
            val priorSecret = priorSecrets[info.index] ?: return@mapNotNull null
            if (priorSecret.contentEquals(info.secret)) null else info.index
        }.toSet()
    }

    /** Forwards the device's current channel list through [channelSecretsSink], fail-open — a sink failure must not fail the caller's channel write/sync. */
    private suspend fun notifySecretsChanged(radioID: UUID) {
        val sink = channelSecretsSink ?: return
        try {
            sink(channelStore.fetchChannels(radioID))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort.
        }
    }

    // MARK: - Channel CRUD Operations

    /**
     * Fetches all channels for a device from the remote device.
     *
     * @throws ChannelServiceError.SyncAlreadyInProgress if another sync is running.
     */
    suspend fun syncChannels(radioID: UUID, maxChannels: UByte, usePipelinedRead: Boolean): ChannelSyncResult {
        if (!isSyncing.compareAndSet(false, true)) throw ChannelServiceError.SyncAlreadyInProgress
        try {
            if (usePipelinedRead) return syncChannelsPipelined(radioID, maxChannels)

            val syncErrors = mutableListOf<ChannelSyncError>()
            val configured = mutableListOf<ChannelInfo>()
            val unconfiguredIndices = mutableListOf<UByte>()
            val emptyNameWithSecretIndices = mutableListOf<UByte>()

            var consecutiveTimeouts = 0
            val circuitBreakerThreshold = 3
            val maxIndex = maxChannels.toInt()

            var i = 0
            while (i < maxIndex) {
                val index = i.toUByte()

                if (consecutiveTimeouts >= circuitBreakerThreshold) {
                    for (remaining in i until maxIndex) {
                        syncErrors.add(ChannelSyncError(remaining.toUByte(), ChannelSyncErrorType.CircuitBreaker, "Skipped due to circuit breaker"))
                    }
                    break
                }

                try {
                    val channelInfo = fetchChannel(index)
                    if (channelInfo != null) {
                        configured.add(channelInfo)
                        consecutiveTimeouts = 0
                        if (channelInfo.name.isEmpty()) emptyNameWithSecretIndices.add(index)
                    } else {
                        consecutiveTimeouts = 0
                        unconfiguredIndices.add(index)
                    }
                } catch (error: ChannelServiceError) {
                    val syncError = classifyError(error, index)
                    consecutiveTimeouts = nextConsecutiveFailureCount(syncError, consecutiveTimeouts)
                    syncErrors.add(syncError)
                }
                i++
            }

            // Persist the whole pass in a single transaction. Indices skipped by the circuit
            // breaker are left in neither list, so they are untouched.
            return finalizeChannelSync(radioID, maxChannels, configured, unconfiguredIndices, syncErrors)
        } finally {
            isSyncing.set(false)
        }
    }

    /**
     * Pipelined channel read for transports that support it: one bounded-window
     * [ChannelSessionOps.getChannels] exchange in place of N serial round-trips, then
     * acknowledged reconciliation of any dropped requests. Classification and the
     * single-transaction persist match the serial path so an index that could not be read lands
     * in neither the configured nor the unconfigured list and is therefore never deleted.
     */
    private suspend fun syncChannelsPipelined(radioID: UUID, maxChannels: UByte): ChannelSyncResult {
        val syncErrors = mutableListOf<ChannelSyncError>()
        val configured = mutableListOf<ChannelInfo>()
        val unconfiguredIndices = mutableListOf<UByte>()
        val emptyNameWithSecretIndices = mutableListOf<UByte>()

        // A hard send failure (e.g. disconnect mid-send) throws here, aborting the round with
        // nothing persisted; an idle stall returns a partial set to reconcile rather than throwing.
        val fetchResult = try {
            session.getChannels((0 until maxChannels.toInt()).map { it.toUByte() })
        } catch (error: MeshCoreError) {
            throw ChannelServiceError.SessionError(error)
        }

        for (info in fetchResult.received) {
            if (isChannelConfigured(info.name, info.secret)) {
                configured.add(info)
                if (info.name.isEmpty()) emptyNameWithSecretIndices.add(info.index)
            } else {
                unconfiguredIndices.add(info.index)
            }
        }

        // Reconcile dropped Write Commands with acknowledged reads. "Consecutive" failures are
        // meaningful again on this serial sub-loop, so the circuit breaker applies here. An
        // index still unread after reconcile stays in neither list, so its row is never deleted.
        val missing = fetchResult.missing
        var consecutiveTimeouts = 0
        val circuitBreakerThreshold = 3

        var i = 0
        while (i < missing.size) {
            val index = missing[i]

            if (consecutiveTimeouts >= circuitBreakerThreshold) {
                for (j in i until missing.size) {
                    syncErrors.add(ChannelSyncError(missing[j], ChannelSyncErrorType.CircuitBreaker, "Skipped due to circuit breaker"))
                }
                break
            }

            try {
                val channelInfo = fetchChannel(index)
                if (channelInfo != null) {
                    configured.add(channelInfo)
                    consecutiveTimeouts = 0
                    if (channelInfo.name.isEmpty()) emptyNameWithSecretIndices.add(index)
                } else {
                    consecutiveTimeouts = 0
                    unconfiguredIndices.add(index)
                }
            } catch (error: ChannelServiceError) {
                val syncError = classifyError(error, index)
                consecutiveTimeouts = nextConsecutiveFailureCount(syncError, consecutiveTimeouts)
                syncErrors.add(syncError)
            }
            i++
        }

        return finalizeChannelSync(radioID, maxChannels, configured, unconfiguredIndices, syncErrors)
    }

    /**
     * Persists a completed channel-read pass in a single transaction and reports the result.
     * Shared by the serial and pipelined paths so their classification-to-persist tail cannot
     * drift: it upserts configured channels, deletes stale rows at unconfigured slots, and
     * prunes orphans beyond capacity.
     */
    private suspend fun finalizeChannelSync(
        radioID: UUID,
        maxChannels: UByte,
        configured: List<ChannelInfo>,
        unconfiguredIndices: List<UByte>,
        syncErrors: List<ChannelSyncError>,
    ): ChannelSyncResult {
        val prior = try {
            channelStore.fetchChannels(radioID)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            emptyList()
        }
        try {
            channelStore.batchSaveChannels(radioID, configured, unconfiguredIndices, maxChannels)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val errorsWithFailures = syncErrors + configured.map { info ->
                ChannelSyncError(info.index, ChannelSyncErrorType.DatabaseError, "Batch persist failed: ${error.message}")
            }
            return ChannelSyncResult(channelsSynced = 0, errors = errorsWithFailures)
        }

        val remaining = channelStore.fetchChannels(radioID).map { it.index }.toSet()
        val vacated = prior.map { it.index }.toSet() - remaining
        notifySlotsChanged(radioID, vacated + secretChangedIndices(prior, configured))
        notifySecretsChanged(radioID)
        return ChannelSyncResult(channelsSynced = configured.size, errors = syncErrors)
    }

    /** Retries syncing only the channels that previously failed. */
    suspend fun retryFailedChannels(radioID: UUID, indices: List<UByte>): ChannelSyncResult {
        if (!isSyncing.compareAndSet(false, true)) throw ChannelServiceError.SyncAlreadyInProgress
        try {
            if (indices.isEmpty()) return ChannelSyncResult(channelsSynced = 0)

            // Brief delay before retry to allow transient issues to resolve.
            delay(500)

            val syncErrors = mutableListOf<ChannelSyncError>()
            val configured = mutableListOf<ChannelInfo>()

            // Circuit breaker for retry (stricter threshold than initial sync).
            var consecutiveTimeouts = 0
            val circuitBreakerThreshold = 2

            var i = 0
            while (i < indices.size) {
                val index = indices[i]

                if (consecutiveTimeouts >= circuitBreakerThreshold) {
                    for (j in i until indices.size) {
                        syncErrors.add(ChannelSyncError(indices[j], ChannelSyncErrorType.CircuitBreaker, "Skipped due to retry circuit breaker"))
                    }
                    break
                }

                try {
                    val channelInfo = fetchChannel(index)
                    if (channelInfo != null) {
                        configured.add(channelInfo)
                        consecutiveTimeouts = 0
                    } else {
                        consecutiveTimeouts = 0
                    }
                } catch (error: ChannelServiceError) {
                    val syncError = classifyError(error, index)
                    consecutiveTimeouts = nextConsecutiveFailureCount(syncError, consecutiveTimeouts)
                    syncErrors.add(syncError)
                }
                i++
            }

            // Nothing recovered: skip the persist round-trip.
            if (configured.isEmpty()) return ChannelSyncResult(channelsSynced = 0, errors = syncErrors)

            // Upsert the recovered channels in one transaction. Retry only re-fetches
            // previously failed slots, so it never deletes unconfigured slots or prunes by capacity.
            val prior = channelStore.fetchChannels(radioID)
            try {
                channelStore.batchSaveChannels(radioID, configured, emptyList(), null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val errorsWithFailures = syncErrors + configured.map { info ->
                    ChannelSyncError(info.index, ChannelSyncErrorType.DatabaseError, "Retry persist failed: ${error.message}")
                }
                return ChannelSyncResult(channelsSynced = 0, errors = errorsWithFailures)
            }

            notifySlotsChanged(radioID, secretChangedIndices(prior, configured))
            notifySecretsChanged(radioID)
            return ChannelSyncResult(channelsSynced = configured.size, errors = syncErrors)
        } finally {
            isSyncing.set(false)
        }
    }

    /** Fetches a single channel from the device with retry logic for transient BLE failures. */
    suspend fun fetchChannel(index: UByte): ChannelInfo? {
        // BLE operations can fail transiently due to RF interference or timing. Retry with
        // exponential backoff: shorter retries with jitter are more responsive than one long wait.
        val maxAttempts = 3
        var lastError: MeshCoreError = MeshCoreError.Timeout

        for (attempt in 1..maxAttempts) {
            try {
                val meshChannelInfo = session.getChannel(index)

                if (meshChannelInfo.index != index) throw ChannelServiceError.InvalidChannelIndex

                // Treat channel as unconfigured only when both name and secret are empty.
                if (!isChannelConfigured(meshChannelInfo.name, meshChannelInfo.secret)) return null

                return meshChannelInfo
            } catch (error: MeshCoreError) {
                // Non-retryable: channel not found on device (permanent error).
                val deviceError = error as? MeshCoreError.DeviceError
                if (deviceError?.deviceErrorCode == ErrorCode.NOT_FOUND) return null

                // Retryable: timeout errors are transient BLE issues.
                if (error is MeshCoreError.Timeout) {
                    lastError = error
                    if (attempt < maxAttempts) {
                        // Exponential backoff: 500ms, 1000ms, 2000ms with jitter.
                        val baseDelayMs = 500L * (1L shl (attempt - 1))
                        val jitterMs = Random.nextLong(-100, 101)
                        delay(baseDelayMs + jitterMs)
                        continue
                    }
                }

                // Non-retryable: other MeshCore errors (device errors, parse errors, etc.).
                throw ChannelServiceError.SessionError(error)
            }
        }

        // Unreachable in practice (every loop iteration returns, continues, or throws), kept to
        // satisfy the compiler and mirror the equally-unreachable tail throw in the Swift original.
        throw ChannelServiceError.SessionError(lastError)
    }

    /** Sets (creates or updates) a channel on the device. */
    suspend fun setChannel(radioID: UUID, index: UByte, name: String, passphrase: String) {
        val secret = hashSecret(passphrase)
        val truncatedName = name.utf8Prefix(MAX_USABLE_NAME_BYTES)

        val prior = channelStore.fetchChannel(radioID, index)
        try {
            session.setChannel(index, truncatedName, secret)
            channelStore.saveChannel(radioID, ChannelInfo(index, truncatedName, secret))
        } catch (error: MeshCoreError) {
            throw ChannelServiceError.SessionError(error)
        }
        if (prior != null && !prior.secret.contentEquals(secret)) notifySlotsChanged(radioID, setOf(index))
        notifySecretsChanged(radioID)
    }

    /** Sets a channel with a pre-computed secret (for advanced use cases). [secret] must be exactly 16 bytes. */
    suspend fun setChannelWithSecret(radioID: UUID, index: UByte, name: String, secret: ByteArray) {
        if (!validateSecret(secret)) throw ChannelServiceError.SecretHashingFailed

        val truncatedName = name.utf8Prefix(MAX_USABLE_NAME_BYTES)

        val prior = channelStore.fetchChannel(radioID, index)
        try {
            session.setChannel(index, truncatedName, secret)
            channelStore.saveChannel(radioID, ChannelInfo(index, truncatedName, secret))
        } catch (error: MeshCoreError) {
            throw ChannelServiceError.SessionError(error)
        }
        if (prior != null && !prior.secret.contentEquals(secret)) notifySlotsChanged(radioID, setOf(index))
        notifySecretsChanged(radioID)
    }

    /** Clears a channel by setting it to empty name and zero secret on the device, then deletes its local row. */
    suspend fun clearChannel(radioID: UUID, index: UByte) {
        // Get the channel id before clearing, so it can be reliably deleted (fetching after
        // the device write may not find the now-empty-named channel).
        val channelToDelete = channelStore.fetchChannel(radioID, index)

        try {
            session.setChannel(index, "", ByteArray(CHANNEL_SECRET_SIZE))
        } catch (error: MeshCoreError) {
            throw ChannelServiceError.SessionError(error)
        }

        // deleteChannel wipes the slot's messages with the row in one transaction; with no row left
        // to delete, wipe the messages directly.
        if (channelToDelete != null) {
            channelStore.deleteChannel(channelToDelete.id)
        } else {
            channelStore.deleteMessagesForChannel(radioID, index)
        }
        notifySlotsChanged(radioID, setOf(index))
        notifySecretsChanged(radioID)
    }

    /**
     * Resets a channel's unread/mention badges without touching its messages — same "conversation
     * was viewed" side effect as [com.meshcoretwo.services.contacts.ContactService.markConversationRead],
     * see that method's doc. Callers should re-run this on every reload while the conversation is open.
     */
    suspend fun markConversationRead(channelId: UUID) {
        channelStore.clearChannelUnreadCount(channelId)
        channelStore.clearChannelUnreadMentionCount(channelId)
    }

    /**
     * Deletes all of a channel's messages and resets its unread badges — leaving them set would
     * inflate the badge for a channel the user just emptied. Ported from `clearChannelMessages`.
     */
    suspend fun clearChannelMessages(radioID: UUID, channelIndex: UByte) {
        // Through ChannelStore, not MessageStore: only the former cascades Reaction/MessageRepeat/PendingSend.
        channelStore.deleteMessagesForChannel(radioID, channelIndex)

        channelStore.fetchChannel(radioID, channelIndex)?.let { channel ->
            channelStore.updateChannelLastMessage(channel.id, null)
            channelStore.clearChannelUnreadCount(channel.id)
            channelStore.clearChannelUnreadMentionCount(channel.id)
        }
    }

    // MARK: - Local Database Operations

    /** Gets all channels from local database for a device. */
    suspend fun getChannels(radioID: UUID): List<ChannelDto> = channelStore.fetchChannels(radioID)

    /** Live version of [getChannels] — see `ContactService.observeContacts` for why this exists. */
    fun observeChannels(radioID: UUID): Flow<List<ChannelDto>> = channelStore.observeChannels(radioID)

    /** Gets a specific channel from local database. */
    suspend fun getChannel(radioID: UUID, index: UByte): ChannelDto? = channelStore.fetchChannel(radioID, index)

    /** Gets channels that have messages (for chat list). */
    suspend fun getActiveChannels(radioID: UUID): List<ChannelDto> =
        channelStore.fetchChannels(radioID).filter { it.lastMessageDate != null }

    // MARK: - Public Channel (Slot 0)

    /** Creates or resets the public channel (slot 0). */
    suspend fun setupPublicChannel(radioID: UUID) {
        setChannelWithSecret(radioID, 0u, "Public", PUBLIC_CHANNEL_SECRET.copyOf())
    }

    /** Whether the public channel exists locally. */
    suspend fun hasPublicChannel(radioID: UUID): Boolean = channelStore.fetchChannel(radioID, 0u) != null

    // MARK: - App-Only Metadata

    /**
     * Sets a channel's favorite flag. Thin passthrough onto [ChannelStore.setChannelFavorite] —
     * see that method's doc for why this is a local-only write with no radio round-trip, unlike
     * [ContactService.updateContactPreferences][com.meshcoretwo.services.contacts.ContactService.updateContactPreferences]'s
     * equivalent for contacts.
     */
    suspend fun setFavorite(channelID: UUID, isFavorite: Boolean) = channelStore.setChannelFavorite(channelID, isFavorite)

    /** Sets a channel's notification level. Thin passthrough onto [ChannelStore.setChannelNotificationLevel]. */
    suspend fun setNotificationLevel(channelID: UUID, level: NotificationLevel) =
        channelStore.setChannelNotificationLevel(channelID, level)

    /**
     * Sets a channel's flood-scope preference. Thin passthrough onto
     * [ChannelStore.setChannelFloodScope] — local-only, like [setFavorite]/[setNotificationLevel];
     * pushing the resolved scope to the radio session is the caller's job (see
     * [com.meshcoretwo.services.connection.pushChannelFloodScope]), mirroring how
     * `ChannelInfoSheet.selectFloodScope`/`ChatViewModel.syncFloodScope` keep the persist and the
     * session push as two separate steps on iOS.
     */
    suspend fun setChannelFloodScope(channelID: UUID, floodScope: ChannelFloodScope) =
        channelStore.setChannelFloodScope(channelID, floodScope)

    /**
     * Resets every channel of [radioID] whose [ChannelFloodScope] is pinned to [region] back to
     * [ChannelFloodScope.Inherit]. Ported from `PersistenceStore+Devices.swift`'s
     * `removeDeviceKnownRegion(radioID:region:)`, which folds this reset into the same
     * `modelContext` transaction as the known-region removal itself; this port keeps the two
     * separate ([com.meshcoretwo.services.connection.removeKnownRegion] persists the device row
     * through [com.meshcoretwo.services.connection.ConnectionManager.updateDevice] and calls this
     * afterward) since [ChannelStore]/[com.meshcoretwo.services.persistence.DeviceStore] are
     * already two independent Room writes on this port, not one shared transaction.
     */
    suspend fun resetChannelsScopedToRegion(radioID: UUID, region: String) {
        channelStore.fetchChannels(radioID)
            .filter { it.floodScope == ChannelFloodScope.Region(region) }
            .forEach { channelStore.setChannelFloodScope(it.id, ChannelFloodScope.Inherit) }
    }

    // MARK: - Private Helpers

    /**
     * Classifies a failure into a [ChannelSyncError] for [index]. Only [ChannelServiceError] is
     * matched here (see [ChannelSyncErrorType]'s doc for why that's the only type that reaches
     * this point).
     */
    private fun classifyError(error: ChannelServiceError, index: UByte): ChannelSyncError = when (error) {
        is ChannelServiceError.SessionError -> when (val meshError = error.error) {
            is MeshCoreError.Timeout -> ChannelSyncError(index, ChannelSyncErrorType.Timeout, "Request timed out")
            is MeshCoreError.DeviceError -> ChannelSyncError(
                index,
                ChannelSyncErrorType.DeviceError(meshError.code),
                meshError.message ?: "Device error",
            )
            else -> ChannelSyncError(index, ChannelSyncErrorType.Unknown, meshError.message ?: "Unknown session error")
        }
        else -> ChannelSyncError(index, ChannelSyncErrorType.Unknown, error.message ?: "Unknown error")
    }

    private fun nextConsecutiveFailureCount(error: ChannelSyncError, currentCount: Int): Int =
        if (error.countsTowardCircuitBreaker) currentCount + 1 else 0

    companion object {
        private const val CHANNEL_SECRET_SIZE = 16

        /** Maximum usable bytes for names (firmware `char[32]` minus null terminator). */
        private const val MAX_USABLE_NAME_BYTES = 31

        private val PUBLIC_CHANNEL_SECRET = byteArrayOf(
            0x8B.toByte(), 0x33, 0x87.toByte(), 0xE9.toByte(),
            0xC5.toByte(), 0xCD.toByte(), 0xEA.toByte(), 0x6A,
            0xC9.toByte(), 0xE5.toByte(), 0xED.toByte(), 0xBA.toByte(),
            0xA1.toByte(), 0x15, 0xCD.toByte(), 0x72,
        )

        private const val CHANNEL_URI_SCHEME = "meshcore"
        private const val CHANNEL_URI_HOST = "channel"
        private const val CHANNEL_URI_PATH = "/add"
        private const val CHANNEL_URI_NAME_KEY = "name"
        private const val CHANNEL_URI_SECRET_KEY = "secret"
        private const val CHANNEL_URI_REGION_SCOPE_KEY = "region_scope"

        /** Hashes a passphrase into a 16-byte channel secret using SHA-256 (firmware uses the first 16 bytes). */
        fun hashSecret(passphrase: String): ByteArray {
            if (passphrase.isEmpty()) return ByteArray(CHANNEL_SECRET_SIZE)
            val digest = MessageDigest.getInstance("SHA-256").digest(passphrase.toByteArray(Charsets.UTF_8))
            return digest.copyOf(CHANNEL_SECRET_SIZE)
        }

        /** Validates that a secret has the correct size. */
        fun validateSecret(secret: ByteArray): Boolean = secret.size == CHANNEL_SECRET_SIZE

        /**
         * A slot is unconfigured only when both the name is empty and the secret is all zeros.
         * Internal (not private) so [com.meshcoretwo.services.nodeconfig.NodeConfigService] can
         * classify device channel slots the same way when reading them for config export/import.
         */
        internal fun isChannelConfigured(name: String, secret: ByteArray): Boolean = name.isNotEmpty() || !isZeroSecret(secret)

        private fun isZeroSecret(secret: ByteArray): Boolean = secret.all { it == 0.toByte() }

        /**
         * Builds a shareable `meshcore://channel/add` URI. Always includes `name` and `secret`.
         * Adds `region_scope` only for [ChannelFloodScope.Region].
         */
        fun exportChannelURI(name: String, secret: ByteArray, floodScope: ChannelFloodScope = ChannelFloodScope.Inherit): String {
            val builder = Uri.Builder()
                .scheme(CHANNEL_URI_SCHEME)
                .authority(CHANNEL_URI_HOST)
                .path(CHANNEL_URI_PATH)
                .appendQueryParameter(CHANNEL_URI_NAME_KEY, name)
                .appendQueryParameter(CHANNEL_URI_SECRET_KEY, secret.hexString.uppercase())

            if (floodScope is ChannelFloodScope.Region && floodScope.name.isNotEmpty()) {
                builder.appendQueryParameter(CHANNEL_URI_REGION_SCOPE_KEY, floodScope.name)
            }

            return builder.build().toString()
        }

        /**
         * Decodes a `meshcore://channel/add?name=&secret=&region_scope=` URI built by
         * [exportChannelURI] — the pasted-link counterpart of a scanned QR/tapped-link join, ported
         * from `MeshCoreURLParser.parseChannelURL(_:)`. Returns `null` for any malformed input:
         * wrong scheme/host/path, a missing `name`, or a `secret` that isn't valid hex of the right
         * byte length. [ChannelInvite.regionScope] is trimmed and byte-capped the same way
         * `MeshCoreURLParser.normalizedRegionScope` is (empty after trimming becomes `null`);
         * applying it to the joined channel is the caller's job — see
         * [com.meshcoretwo.android.channels.AddChannelViewModel] (`ChannelJoinFloodScopeApplier`
         * on iOS).
         */
        fun parseChannelURI(uri: String): ChannelInvite? {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
            if (parsed.scheme != CHANNEL_URI_SCHEME || parsed.host != CHANNEL_URI_HOST || parsed.path != CHANNEL_URI_PATH) return null
            val name = parsed.getQueryParameter(CHANNEL_URI_NAME_KEY)?.takeIf { it.isNotEmpty() } ?: return null
            val secret = parsed.getQueryParameter(CHANNEL_URI_SECRET_KEY)?.decodeHex()?.takeIf { validateSecret(it) } ?: return null
            val regionScope = normalizedRegionScope(parsed.getQueryParameter(CHANNEL_URI_REGION_SCOPE_KEY))
            return ChannelInvite(name, secret, regionScope)
        }

        /** Ported from `MeshCoreURLParser.normalizedRegionScope`: trims, then caps to
         * [SettingsService.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES] UTF-8 bytes; empty (before or after
         * capping) becomes `null` rather than an empty-string region. */
        private fun normalizedRegionScope(value: String?): String? {
            val trimmed = value?.trim() ?: return null
            if (trimmed.isEmpty()) return null
            val capped = trimmed.utf8Prefix(SettingsService.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES)
            return capped.ifEmpty { null }
        }
    }
}

/**
 * A channel invite decoded from a `meshcore://channel/add` link — see [ChannelService.parseChannelURI].
 * [regionScope] is the app-side flood-scope preference from the link's `region_scope` param, not a
 * radio field — `null` when absent or blank.
 */
data class ChannelInvite(val name: String, val secret: ByteArray, val regionScope: String? = null) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChannelInvite) return false
        return name == other.name && secret.contentEquals(other.secret) && regionScope == other.regionScope
    }

    override fun hashCode(): Int = 31 * (31 * name.hashCode() + secret.contentHashCode()) + (regionScope?.hashCode() ?: 0)
}
