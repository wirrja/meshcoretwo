// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sendqueue

import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.services.channels.ChannelService
import com.meshcoretwo.services.connection.DeviceConnectionState
import com.meshcoretwo.services.messages.FirmwareDeviceErrorCode
import com.meshcoretwo.services.messages.MessageService
import com.meshcoretwo.services.messages.MessageServiceError
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.PendingSendDto
import com.meshcoretwo.services.persistence.PendingSendKind
import com.meshcoretwo.services.persistence.PendingSendStore
import com.meshcoretwo.services.reactions.ReactionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Owns the DM and channel send queues for a connection. Ported from `ChatSendQueueService.swift`
 * (a Swift `@MainActor final class`) using plain suspend functions — this port has no actor
 * isolation to preserve, so the Swift `nonisolated static` helper split (needed there so the
 * off-main `send` closures could call actor-independent logic) collapses into ordinary private
 * methods here.
 *
 * Startup behaviour: [hydrate] reads [PendingSendDto] rows from [pendingSendStore] for
 * [radioID] and enqueues them. The not-yet-ported `ConnectionManager` should call [hydrate] once
 * after constructing this service and before exposing it to callers, so two callers active during
 * a single connection cannot trigger duplicate replay.
 *
 * Transport-open signal: the drain step suspends via [TransportOpenedSignal.wait] with a
 * [withTimeoutOrNull] wrapper standing in for Swift's `withCooperativeTimeout`. The
 * connection-state observation started by [observeConnectionState] fires the signal each time the
 * connection enters [DeviceConnectionState.READY]. Rows are never deleted while waiting.
 * [TransportOpenedSignal.clear] is called only after a successful send (not before each attempt)
 * so that a fire signal landing during a successful send doesn't get wiped before the next failed
 * send needs it.
 *
 * **Deferred, not yet ported** (tracked in PLAN.md's status section):
 * - `ConnectionManager` calling [hydrate]/[shutdown]/[observeConnectionState] at the right points
 *   in the connection lifecycle — no `ConnectionManager` exists yet in this port, so callers (or
 *   tests) must drive this service's lifecycle directly for now.
 * - `PersistenceStore.warmUp()`'s `purgeLegacyAttemptCountRows` — Swift needs it to reconcile a
 *   nullable `attemptCount` column against pre-migration rows; this port's [PendingSendDto]
 *   attemptCount is non-null from the start (see [com.meshcoretwo.services.persistence.PendingSendEntity]'s
 *   class doc), so there is nothing to purge.
 */
class ChatSendQueueService(
    val radioID: UUID,
    private val messageStore: MessageStore,
    private val contactStore: ContactStore,
    private val pendingSendStore: PendingSendStore,
    private val messageService: MessageService,
    private val channelService: ChannelService,
    private val reactionService: ReactionService,
    private val config: ChatSendQueueConfig = ChatSendQueueConfig.DEFAULT,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val triggers = TransportOpenedSignal()

    private val failureCounterMutex = Mutex()
    private val channelFetchFailureCounts = mutableMapOf<UUID, Int>()

    private var hasHydrated = false

    /** Job consuming the connection-state stream installed by [observeConnectionState]. Cancelled in [shutdown]. */
    private var connectionStateJob: Job? = null

    private val dmQueue: SendQueue<DirectMessageEnvelope> = SendQueue(
        send = ::drainDirectMessage,
        onError = { _, envelope -> pendingSendStore.deletePendingSendsForMessage(envelope.messageID) },
        onDrain = { },
    )

    private val channelQueue: SendQueue<ChannelMessageEnvelope> = SendQueue(
        send = ::drainChannelMessage,
        onError = { _, envelope -> pendingSendStore.deletePendingSendsForMessage(envelope.messageID) },
        onDrain = { },
    )

    // MARK: - Enqueue

    /**
     * Enqueue a DM envelope. Persists a [PendingSendDto] row first; the queue's drain reads it
     * back on the next send attempt. Throws [ChatSendQueueServiceError.PersistFailed] if the
     * write fails so the caller can surface the failure instead of silently dropping the queued
     * send.
     */
    suspend fun enqueueDM(envelope: DirectMessageEnvelope) {
        persist(pendingSendDto(envelope, radioID))
        dmQueue.enqueue(envelope)
    }

    suspend fun enqueueChannel(envelope: ChannelMessageEnvelope) {
        persist(pendingSendDto(envelope, radioID))
        channelQueue.enqueue(envelope)
    }

    /**
     * Signals the DM queue that a [PendingSendDto] row already exists for this envelope. Used by
     * a manual-retry path where the row has already been written in one transaction; calling
     * [enqueueDM] here would double-persist. The drain still reads the row back via
     * `hasPendingSend` on the next send attempt, so the in-memory enqueue is the only step left.
     */
    suspend fun signalDMEnqueued(envelope: DirectMessageEnvelope) {
        dmQueue.enqueue(envelope)
    }

    private suspend fun persist(dto: PendingSendDto) {
        try {
            pendingSendStore.insertPendingSendAssigningSequence(dto)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ChatSendQueueServiceError.PersistFailed(error)
        }
    }

    // MARK: - Connection-state observation

    /**
     * Starts observing the connection-state stream and fires the transport-open trigger exactly
     * once each time the connection enters [DeviceConnectionState.READY]. Gating on `READY` (not
     * the first `isConnected` edge) keeps hydrated sends parked through the initial-sync window,
     * so they do not contend with sync's reads on the radio's link. Calling again replaces the
     * previous observation.
     */
    fun observeConnectionState(initial: DeviceConnectionState, events: Flow<DeviceConnectionState>) {
        connectionStateJob?.cancel()
        if (initial.canDrainSendQueue) transportDidOpen()
        connectionStateJob = scope.launch {
            var previous = initial
            events.collect { state ->
                if (!previous.canDrainSendQueue && state.canDrainSendQueue) transportDidOpen()
                previous = state
            }
        }
    }

    /**
     * Fired by the connection-state observation started by [observeConnectionState] each time the
     * connection enters [DeviceConnectionState.READY]. Fires the trigger that wakes any drain
     * attempt suspended in [TransportOpenedSignal.wait].
     *
     * Fire-and-forget job is intentional: [TransportOpenedSignal.fire] is idempotent (arming an
     * already-armed bit is a no-op), so a tight reconnect cycle collapses to a single armed
     * trigger.
     */
    fun transportDidOpen() {
        scope.launch { triggers.fire() }
    }

    // MARK: - Hydration

    /**
     * Called once after construction, before this service is exposed to callers. Reads every
     * [PendingSendDto] row for [radioID] and enqueues each envelope. Subsequent calls are no-ops.
     */
    suspend fun hydrate() {
        if (hasHydrated) return
        hasHydrated = true
        try {
            val rows = pendingSendStore.fetchPendingSends(radioID)
            for (dto in rows) {
                when (dto.kind) {
                    PendingSendKind.DM -> dto.directMessageEnvelope()?.let { dmQueue.enqueue(it) }
                    PendingSendKind.CHANNEL -> dto.channelMessageEnvelope()?.let { channelQueue.enqueue(it) }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Best-effort hydration; matches this codebase's no-logging-in-business-services convention.
        }
    }

    /** Test-only: drain readiness for synchronizing tests. */
    internal suspend fun awaitDrainCompletion() {
        dmQueue.awaitDrainCompletion()
        channelQueue.awaitDrainCompletion()
    }

    /**
     * Cancel both queues' drains and the connection-state observation, so a torn-down service
     * never receives the next connection's edge and its underlying jobs can complete. `PendingSend`
     * rows survive in the database, so the next service's [hydrate] replays them on reconnect.
     */
    suspend fun shutdown() {
        connectionStateJob?.cancel()
        connectionStateJob = null
        dmQueue.cancelDrain()
        channelQueue.cancelDrain()
    }

    // MARK: - DM drain

    private suspend fun drainDirectMessage(envelope: DirectMessageEnvelope) {
        // Outer catch: the queue-routed catch sites in MessageService do not broadcast `.failed`
        // themselves; failMessageAndRethrow only writes the DB state and rethrows. Any
        // non-cancellation escape from this function is a terminal failure for the envelope, so
        // notifyMessageFailed fires exactly once before the queue's onError removes the row.
        try {
            val contact: ContactDto = when (val outcome = classifyRead { contactStore.fetchContact(envelope.contactID) }) {
                is ReadOutcome.Found -> outcome.value
                is ReadOutcome.Missing -> {
                    bestEffort { pendingSendStore.deletePendingSendsForMessage(envelope.messageID) }
                    bestEffort { messageStore.updateMessageStatus(envelope.messageID, MessageStatus.FAILED) }
                    return
                }
                is ReadOutcome.Transient -> parkAndCancel(envelope.messageID, "DM")
            }

            val preflight = try {
                preflightAndBump(envelope.messageID, "DM") ?: return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                bestEffort { messageStore.updateMessageStatusUnlessDelivered(envelope.messageID, MessageStatus.PENDING) }
                parkAndCancel(envelope.messageID, "DM")
            }

            try {
                if (envelope.isResend) {
                    messageService.resendDirectMessage(envelope.messageID, contact, preflight.preserveTimestamp)
                } else {
                    messageService.sendPendingDirectMessage(envelope.messageID, contact, preflight.preserveTimestamp)
                }
                bestEffort { pendingSendStore.deletePendingSendsForMessage(envelope.messageID) }
                triggers.clear()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isTransientDirectMessageError(error)) throw error
                bestEffort { messageStore.updateMessageStatusUnlessDelivered(envelope.messageID, MessageStatus.PENDING) }
                parkAndCancel(envelope.messageID, "DM")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            messageService.notifyMessageFailed(envelope.messageID)
            throw error
        }
    }

    // MARK: - Channel drain

    private suspend fun drainChannelMessage(envelope: ChannelMessageEnvelope) {
        try {
            val preflight = try {
                preflightAndBump(envelope.messageID, "channel") ?: return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                bestEffort { messageStore.updateMessageStatusUnlessDelivered(envelope.messageID, MessageStatus.PENDING) }
                parkAndCancel(envelope.messageID, "channel")
            }

            try {
                // resendChannelMessage stamps a fresh wire timestamp so the mesh dedup ring
                // treats the retry as a new broadcast. Reaction indexing hashes off that exact
                // timestamp, so the resent packet must be indexed under the post-resend value.
                val indexTimestamp: UInt = if (envelope.isResend) {
                    messageService.resendChannelMessage(envelope.messageID, preflight.preserveTimestamp)
                } else {
                    messageService.sendPendingChannelMessage(envelope.messageID)
                    envelope.messageTimestamp
                }
                envelope.localNodeName?.let { nodeName ->
                    reactionService.indexChannelMessage(envelope.messageID, envelope.channelIndex, nodeName, envelope.messageText, indexTimestamp)
                }
                bestEffort { pendingSendStore.deletePendingSendsForMessage(envelope.messageID) }
                resetChannelFetchFailureCount(envelope.messageID)
                triggers.clear()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isTransientChannelMessageError(error)) throw error

                if (isChannelMessageNotFound(error)) {
                    // Disambiguate pool exhaustion (transient) from a stale channel index
                    // (terminal) by refreshing the radio's view of the channel. Gate on
                    // disambiguateAfterAttempts so the common pool-exhaustion burst parks
                    // without an extra BLE round-trip; only persistent NOT_FOUND warrants the
                    // fetchChannel cost.
                    if (preflight.postBumpCount < config.disambiguateAfterAttempts) {
                        bestEffort { messageStore.updateMessageStatusUnlessDelivered(envelope.messageID, MessageStatus.PENDING) }
                        parkAndCancel(envelope.messageID, "channel")
                    }

                    val stillExists: Boolean
                    try {
                        stillExists = channelService.fetchChannel(envelope.channelIndex) != null
                        resetChannelFetchFailureCount(envelope.messageID)
                    } catch (fetchError: CancellationException) {
                        throw fetchError
                    } catch (fetchError: Throwable) {
                        val failures = incrementChannelFetchFailureCount(envelope.messageID)
                        if (failures >= config.maxConsecutiveFetchChannelFailures) {
                            resetChannelFetchFailureCount(envelope.messageID)
                            throw error
                        }
                        bestEffort { messageStore.updateMessageStatusUnlessDelivered(envelope.messageID, MessageStatus.PENDING) }
                        parkAndCancel(envelope.messageID, "channel")
                    }
                    if (!stillExists) {
                        resetChannelFetchFailureCount(envelope.messageID)
                        throw error
                    }
                    // Channel still exists per device — NOT_FOUND was pool exhaustion. Park on
                    // the transport-open trigger.
                }

                bestEffort { messageStore.updateMessageStatusUnlessDelivered(envelope.messageID, MessageStatus.PENDING) }
                parkAndCancel(envelope.messageID, "channel")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            messageService.notifyMessageFailed(envelope.messageID)
            throw error
        }
    }

    private suspend fun incrementChannelFetchFailureCount(messageID: UUID): Int =
        failureCounterMutex.withLock {
            val next = (channelFetchFailureCounts[messageID] ?: 0) + 1
            channelFetchFailureCounts[messageID] = next
            next
        }

    private suspend fun resetChannelFetchFailureCount(messageID: UUID) {
        failureCounterMutex.withLock { channelFetchFailureCounts.remove(messageID) }
    }

    // MARK: - Shared drain helpers

    private data class PreflightResult(val postBumpCount: Int, val preserveTimestamp: Boolean)

    /**
     * Combines the `hasPendingSend` gate, the top-of-drain `attemptCount` bump, and the
     * `preserveTimestamp` computation every DM and channel drain runs before any wire-affecting
     * work. Returns `null` if the row is gone (terminal — abandon envelope). Throws on a
     * transient store failure so the caller can park and retry.
     */
    private suspend fun preflightAndBump(messageID: UUID, kind: String): PreflightResult? {
        when (val outcome = classifyRead { if (pendingSendStore.hasPendingSend(messageID)) true else null }) {
            is ReadOutcome.Found -> Unit
            is ReadOutcome.Missing -> return null
            is ReadOutcome.Transient -> throw outcome.error
        }

        val postBumpCount = pendingSendStore.incrementPendingSendAttemptCount(messageID) ?: return null
        return PreflightResult(postBumpCount = postBumpCount, preserveTimestamp = postBumpCount > 1)
    }

    /**
     * Park the drain on the transport-open trigger up to [ChatSendQueueConfig.transportWaitTimeoutMs],
     * then throw [CancellationException] to drive [SendQueue]'s requeue protocol. Sites that need
     * to revert message status to [MessageStatus.PENDING] before parking must do so themselves —
     * this helper does not write status.
     */
    private suspend fun parkAndCancel(messageID: UUID, kind: String): Nothing {
        waitForTransportOpen(messageID, kind)
        throw CancellationException("$kind drain parked for messageID=$messageID")
    }

    /**
     * Returns `true` if the signal fired, `false` on timeout or cancelled-mid-wait (caller treats
     * both as "requeue"). A genuine outer cancellation is absorbed here (matching
     * `parkAndCancel`'s unconditional fresh throw), mirroring Swift's `Task.isCancelled` branch.
     */
    private suspend fun waitForTransportOpen(messageID: UUID, kind: String): Boolean =
        try {
            withTimeoutOrNull(config.transportWaitTimeoutMs) { triggers.wait() } != null
        } catch (error: CancellationException) {
            false
        }

    private sealed class ReadOutcome<out T> {
        data class Found<T>(val value: T) : ReadOutcome<T>()
        object Missing : ReadOutcome<Nothing>()
        data class Transient(val error: Throwable) : ReadOutcome<Nothing>()
    }

    /** Wraps a store read into a tri-state outcome so the drain can distinguish "row deleted" (terminal) from "store fault" (transient). */
    private suspend fun <T> classifyRead(work: suspend () -> T?): ReadOutcome<T> =
        try {
            val value = work()
            if (value != null) ReadOutcome.Found(value) else ReadOutcome.Missing
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ReadOutcome.Transient(error)
        }

    /** Runs [block], swallowing (but logging nothing — see class doc) any non-cancellation failure. */
    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Best-effort bookkeeping; Swift's `try?` call sites swallow the same failures.
        }
    }

    /**
     * Classify a send error as transient (park the envelope and wait on a transport-open trigger)
     * or terminal (drop the row). The transient `deviceCode` differs between DM and channel paths.
     */
    private fun isTransientError(error: Throwable, deviceCode: UByte): Boolean {
        if (error is MessageServiceError) {
            return when (error) {
                is MessageServiceError.SessionError -> isTransientError(error.error, deviceCode)
                is MessageServiceError.NotConnected -> true
                is MessageServiceError.ContactNotFound,
                is MessageServiceError.ChannelNotFound,
                is MessageServiceError.SendFailed,
                is MessageServiceError.InvalidRecipient,
                is MessageServiceError.MessageTooLong,
                -> false
            }
        }
        val meshError = error as? MeshCoreError ?: return false
        return when {
            meshError is MeshCoreError.Timeout -> true
            meshError is MeshCoreError.NotConnected -> true
            meshError is MeshCoreError.ConnectionLost -> true
            meshError is MeshCoreError.BluetoothPoweredOff -> true
            meshError is MeshCoreError.SessionNotStarted -> true
            meshError is MeshCoreError.DeviceError -> meshError.code == deviceCode
            else -> false
        }
    }

    private fun isTransientDirectMessageError(error: Throwable): Boolean =
        isTransientError(error, FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL)

    private fun isTransientChannelMessageError(error: Throwable): Boolean =
        isTransientError(error, FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND)

    /**
     * Returns `true` if [error] is `MeshCoreError.DeviceError(channelMessageNotFound)` or
     * `MessageServiceError.SessionError` wrapping the same. Used by the channel drain catch to
     * recognise the firmware NOT_FOUND signal regardless of which [MessageService] entry point
     * surfaced it.
     */
    private fun isChannelMessageNotFound(error: Throwable): Boolean {
        if (error is MeshCoreError.DeviceError) return error.code == FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND
        if (error is MessageServiceError.SessionError) {
            val underlying = error.error
            if (underlying is MeshCoreError.DeviceError) return underlying.code == FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND
        }
        return false
    }
}
