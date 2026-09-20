// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.messages

import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.EventFilter
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.MeshCoreSessionProtocol
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.protocol.MessageSentInfo
import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.protocol.readUInt32LE
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageRepeatStore
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.utilities.AckCodeBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * Service for sending messages with ACK tracking. Ported from `MessageService.swift` and its
 * `+SendDM`/`+SendChannel`/`+ACK`/`+SendHelpers` extensions, covering: single-attempt direct
 * message send, the direct-message app-layer retry loop with flood-routing fallback, channel
 * broadcast send, and ACK lifecycle tracking (listener + expiry checker).
 *
 * **`contactService` was never wired to anything.** Swift's constructor takes `contactService:
 * ContactService?` and stores it, but grep across every `MessageService+*.swift` file shows zero
 * reads of that property — the retry loop's path-reset bookkeeping calls `dataStore.saveContact`
 * directly instead (ported here as [contactStore].saveContact). So the "deferred: `contactService`
 * injection" note this class doc used to carry was based on a misreading; no such dependency was
 * ever needed.
 *
 * **Deferred, not yet ported** (tracked in PLAN.md's Phase 3 status):
 * - `SendQueue`/`ChatSendQueueService` — the app-layer send queue that drains pending sends in
 *   the background and owns broadcasting `.failed` for its own catch sites (see
 *   [performQueuedDirectMessageSend]'s doc). [sendPendingDirectMessage]/[resendDirectMessage] are
 *   ported (the retry-loop *body* a queue would call), but nothing here drains a queue
 *   automatically — callers invoke them directly.
 * - `hasOutgoingSentDM`/the orphan-ACK diagnostic in `handleAcknowledgement` — observability
 *   only, never gates behavior; dropped along with this codebase's general no-logging-in-
 *   business-services convention (see `ContactService`/`ChannelService`).
 * - `floodFallbackOnRetry`/`triggerPathDiscoveryAfterFlood` — dead fields even in Swift; see
 *   [MessageServiceConfig]'s class doc.
 *
 * Declares [MeshCoreSessionProtocol] (not a narrower role interface) because Swift's own
 * `MessageService` does too — unlike `ContactService`/`ChannelService`, it genuinely needs both
 * the messaging ops and the event stream.
 *
 * **`pendingAcks`/[inFlightRetries] are `Mutex`-guarded**, not bare fields sitting on the actor
 * the way Swift's `var pendingAcks: [UUID: PendingAck]`/`var inFlightRetries: Set<UUID>` do —
 * Kotlin has no actor isolation, so concurrent access from [startEventMonitoring]'s listener
 * coroutine, [startAckExpiryChecking]'s checker coroutine, and any caller's
 * `sendDirectMessage`/`sendMessageWithRetry`/`sendChannelMessage` needs an explicit lock, the
 * same pattern established for [com.meshcoretwo.protocol.MockTransport] in Phase 1.
 */
class MessageService(
    private val session: MeshCoreSessionProtocol,
    private val messageStore: MessageStore,
    private val contactStore: ContactStore,
    private val channelStore: ChannelStore,
    private val messageRepeatStore: MessageRepeatStore,
    private val config: MessageServiceConfig = MessageServiceConfig(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val pendingAcks = mutableMapOf<UUID, PendingAck>()

    /** Message IDs currently draining through the retry loop, guarding against a concurrent second drain of the same message. */
    private val inFlightRetries = mutableSetOf<UUID>()

    private val statusEventsFlow = MutableSharedFlow<MessageStatusEvent>(extraBufferCapacity = 64)

    private var eventListenerJob: Job? = null
    private var ackCheckJob: Job? = null
    private var checkIntervalMs: Long = 5_000

    // MARK: - Event Listening

    /**
     * Starts the session ACK event listener. Subscribes to [EventFilter.anyAcknowledgement] and
     * routes each one through [handleAcknowledgement]. Call after the connection is established;
     * without this, pending DMs stay [MessageStatus.SENT] even when ACKs arrive.
     *
     * Independent lifecycle from [startAckExpiryChecking] — pair both the same way a caller
     * pairs their stop variants ([stopEventMonitoring] here, [stopAckExpiryChecking]/
     * [stopAndFailAllPending] there).
     */
    fun startEventMonitoring() {
        eventListenerJob?.cancel()
        eventListenerJob = scope.launch {
            session.events(EventFilter.anyAcknowledgement).collect { event ->
                if (!isActive) return@collect
                if (event is MeshEvent.Acknowledgement) {
                    handleAcknowledgement(event.code, event.tripTime)
                }
            }
        }
    }

    /** Stops the session ACK event listener. Does not stop the ACK expiry checker. */
    fun stopEventMonitoring() {
        eventListenerJob?.cancel()
        eventListenerJob = null
    }

    // MARK: - ACK Handling

    /**
     * Processes an acknowledgement from the session event stream: finds the in-flight message
     * whose accumulated ack codes contain this one, marks it delivered, writes the delivery
     * status + round-trip time, and removes the entry. The sole writer of [MessageStatus.DELIVERED]
     * on the late-ACK path.
     */
    internal suspend fun handleAcknowledgement(code: ByteArray, tripTime: UInt?) {
        val match = mutex.withLock {
            val entry = pendingAcks.entries.firstOrNull { it.value.matches(code) && !it.value.isDelivered } ?: return@withLock null
            entry.value.isDelivered = true
            entry.key to entry.value.contactID
        } ?: return
        val (messageID, contactID) = match

        bestEffort {
            messageStore.updateMessageAck(id = messageID, ackCode = code.toAckCodeUInt(), status = MessageStatus.DELIVERED, roundTripTime = tripTime)
            contactStore.updateContactLastMessage(contactID, Instant.now())
        }

        mutex.withLock { pendingAcks.remove(messageID) }

        statusEventsFlow.emit(MessageStatusEvent.StatusResolved(messageID, MessageStatus.DELIVERED, tripTime))
    }

    // MARK: - Status Events

    /** A multicast stream of outbound-message lifecycle events; every subscriber sees every event. */
    fun statusEvents(): Flow<MessageStatusEvent> = statusEventsFlow

    /** Broadcasts [MessageStatusEvent.Failed] for a message from outside this service's own send paths. */
    suspend fun notifyMessageFailed(messageID: UUID) {
        statusEventsFlow.emit(MessageStatusEvent.Failed(messageID))
    }

    // MARK: - Read

    /**
     * Most recent [limit] messages for a direct conversation, returned oldest first for display.
     * Thin passthrough onto [MessageStore.fetchMessages] — kept here (not read directly by UI
     * callers) so the chat screen talks only to services, matching every other vertical slice's
     * convention. No real windowed pagination yet; [offset] exists for callers who want it, but
     * the chat conversation screen's first slice just loads the latest [limit] once (see
     * PLAN.md's Phase 5 chat slice).
     */
    suspend fun getMessages(contactID: UUID, limit: Int = 50, offset: Int = 0): List<MessageDto> =
        messageStore.fetchMessages(contactID, limit, offset)

    /** Most recent [limit] messages for a channel conversation, returned oldest first for display. See [getMessages] (contact overload). */
    suspend fun getMessages(radioID: UUID, channelIndex: UByte, limit: Int = 50, offset: Int = 0): List<MessageDto> =
        messageStore.fetchMessages(radioID, channelIndex, limit, offset)

    /**
     * A single message by id, or `null` if it no longer exists — the "Path map" screen's own fetch
     * (pushed with just a message id, same nav-arg-and-refetch shape as this port's other pushed
     * map screens, e.g. `NeighborSnrMapViewModel`), rather than threading the already-loaded
     * [MessageDto] through navigation. Thin passthrough onto [MessageStore.fetchMessage], same
     * reasoning as [getMessages].
     */
    suspend fun getMessage(id: UUID): MessageDto? = messageStore.fetchMessage(id)

    // MARK: - Delete

    /**
     * Deletes a single message — the chat conversation screen's long-press "Delete" action.
     * Ported from `ChatViewModel.deleteMessage`. Cascades the message's heard-repeat rows
     * ([MessageRepeatStore.deleteMessageRepeats], mirroring `Message.repeats`'s SwiftData
     * `.cascade` relationship — [com.meshcoretwo.services.persistence.MessageRepeatEntity]'s class
     * doc flagged "nothing in this port deletes a message row yet" as exactly this gap) and
     * recomputes the owning conversation's cached `lastMessageDate` from the remaining messages, so
     * the chat list's sort position stays correct after deleting its newest message. The chat
     * list's preview *text* needs no such recompute — it's already a live per-open query, not a
     * cache (see `ChatListViewModel`'s doc) — so deleting the newest message there self-corrects on
     * its own. Reaction/pending-send rows referencing this message are left orphaned, matching
     * Swift: neither `Reaction` nor `PendingSend` carries a cascade relationship onto `Message`
     * either. No `syncCoordinator?.notifyConversationsChanged()` call to mirror —
     * [contactStore]/[channelStore]'s `updateContactLastMessage`/`updateChannelLastMessage` row
     * write already wakes their own reactive `observeContacts`/`observeChannels` streams.
     *
     * A no-op if [id] doesn't resolve to an existing message.
     */
    suspend fun deleteMessage(id: UUID) {
        val message = messageStore.fetchMessage(id) ?: return
        messageStore.deleteMessage(id)
        messageRepeatStore.deleteMessageRepeats(id)

        val contactID = message.contactID
        val channelIndex = message.channelIndex
        when {
            contactID != null -> {
                val latest = messageStore.fetchMessages(contactID, limit = 1).firstOrNull()?.sortDate
                contactStore.updateContactLastMessage(contactID, latest)
            }
            channelIndex != null -> {
                val channel = channelStore.fetchChannel(message.radioID, channelIndex) ?: return
                val latest = messageStore.fetchMessages(message.radioID, channelIndex, limit = 1).firstOrNull()?.sortDate
                channelStore.updateChannelLastMessage(channel.id, latest)
            }
        }
    }

    // MARK: - Send Direct Message

    /**
     * Sends a direct message to a contact with a single send attempt — no automatic retry.
     *
     * @throws MessageServiceError.InvalidRecipient if [contact] is a repeater.
     * @throws MessageServiceError.MessageTooLong if [text] exceeds 150 UTF-8 bytes.
     * @throws MessageServiceError.SessionError if the send fails.
     */
    suspend fun sendDirectMessage(
        text: String,
        contact: ContactDto,
        textType: TextType = TextType.PLAIN_TEXT,
        replyToID: UUID? = null,
    ): MessageDto {
        validateDirectMessage(text, contact)

        val messageID = UUID.randomUUID()
        val now = Instant.now()
        val timestamp = now.epochSecond.toUInt()

        messageStore.saveMessage(createOutgoingMessage(messageID, contact.radioID, contact.id, text, timestamp, textType, replyToID))

        val predictedAck: ByteArray
        val sentInfo: MessageSentInfo
        try {
            // Precompute the expected ACK before the send so the persistent listener cannot
            // race trackPendingAck on short direct links.
            val senderPublicKey = session.currentSelfInfo?.publicKey ?: throw MessageServiceError.NotConnected
            predictedAck = AckCodeBuilder.expectedAck(timestamp, 0u, text, senderPublicKey)
            trackPendingAck(messageID, contact.id, predictedAck, checkIntervalMs)

            try {
                sentInfo = withPoolBackoff(FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL, config.poolBackoff) {
                    session.sendMessage(destination = contact.publicKey, text = text, timestamp = now, attempt = 0u)
                }
            } catch (error: Throwable) {
                mutex.withLock { pendingAcks.remove(messageID) }
                throw error
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            statusEventsFlow.emit(MessageStatusEvent.Failed(messageID))
            failMessageAndRethrow(error, messageID)
        }

        val ackTimeoutMs = sentInfo.suggestedTimeoutMs.toLong() * 12 / 10

        mutex.withLock {
            val tracking = pendingAcks[messageID]
            if (!sentInfo.expectedAck.contentEquals(predictedAck)) {
                trackPendingAckLocked(messageID, contact.id, sentInfo.expectedAck, ackTimeoutMs)
            } else if (tracking != null) {
                tracking.timeoutMs = ackTimeoutMs
                tracking.sentAt = Instant.now()
            }
        }

        // Post-send bookkeeping. The radio accepted the send, so a failure here must not mark
        // .failed or drop the pendingAcks entry; the genuine ACK or the expiry checker owns the
        // terminal state now.
        bestEffort {
            messageStore.updateMessageAck(id = messageID, ackCode = sentInfo.expectedAck.toAckCodeUInt(), status = MessageStatus.SENT)
            contactStore.updateContactLastMessage(contact.id, Instant.now())
            statusEventsFlow.emit(MessageStatusEvent.StatusResolved(messageID, MessageStatus.SENT, null))
        }

        return messageStore.fetchMessage(messageID) ?: throw MessageServiceError.SendFailed("Failed to fetch saved message")
    }

    private fun validateDirectMessage(text: String, contact: ContactDto) {
        if (contact.type == ContactType.REPEATER) throw MessageServiceError.InvalidRecipient
        if (text.toByteArray(Charsets.UTF_8).size > MAX_DIRECT_MESSAGE_LENGTH) throw MessageServiceError.MessageTooLong
    }

    // MARK: - Send with Automatic Retry

    /**
     * Sends a direct message with automatic retry and flood-routing fallback: attempts direct
     * routing up to [MessageServiceConfig.floodAfter] times, then switches to flood routing for
     * up to [MessageServiceConfig.maxFloodAttempts] more, returning as soon as an ACK arrives.
     *
     * The message is saved [MessageStatus.PENDING] immediately and [onMessageCreated] is invoked
     * before the send loop runs, so a caller can update the UI optimistically.
     *
     * @throws MessageServiceError.InvalidRecipient if [contact] is a repeater.
     * @throws MessageServiceError.MessageTooLong if [text] exceeds 150 UTF-8 bytes.
     * @throws MessageServiceError.SessionError if the send fails.
     */
    suspend fun sendMessageWithRetry(
        text: String,
        contact: ContactDto,
        textType: TextType = TextType.PLAIN_TEXT,
        replyToID: UUID? = null,
        timeoutMs: Long = 0,
        onMessageCreated: (suspend (MessageDto) -> Unit)? = null,
    ): MessageDto {
        validateDirectMessage(text, contact)

        val messageID = UUID.randomUUID()
        val now = Instant.now()
        val timestamp = now.epochSecond.toUInt()

        val messageDto = createOutgoingMessage(messageID, contact.radioID, contact.id, text, timestamp, textType, replyToID)
        messageStore.saveMessage(messageDto)

        onMessageCreated?.invoke(messageDto)

        // Capture initial routing state to detect changes.
        val initialPathLength = contact.outPathLength

        try {
            val sentInfo = sendDirectMessageWithRetryLoop(
                messageID = messageID,
                contactID = contact.id,
                radioID = contact.radioID,
                publicKey = contact.publicKey,
                text = text,
                timestamp = now,
                timestampRaw = timestamp,
                timeoutMs = if (timeoutMs > 0) timeoutMs else null,
            )
            return finalizeSend(messageID, contact.id, contact.radioID, contact.publicKey, sentInfo, initialPathLength)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            statusEventsFlow.emit(MessageStatusEvent.Failed(messageID))
            failMessageAndRethrow(error, messageID)
        }
    }

    /**
     * Creates a pending message without sending it — for showing it in the UI immediately and
     * draining it later via [sendPendingDirectMessage].
     */
    suspend fun createPendingMessage(
        text: String,
        contact: ContactDto,
        textType: TextType = TextType.PLAIN_TEXT,
        replyToID: UUID? = null,
    ): MessageDto {
        validateDirectMessage(text, contact)

        val messageID = UUID.randomUUID()
        val timestamp = Instant.now().epochSecond.toUInt()
        val messageDto = createOutgoingMessage(messageID, contact.radioID, contact.id, text, timestamp, textType, replyToID)
        messageStore.saveMessage(messageDto)
        contactStore.updateContactLastMessage(contact.id, messageDto.createdAt)
        return messageDto
    }

    /**
     * Drains a `.pending` DM through the app-layer retry loop. Does not bump [MessageDto.sendCount]
     * or broadcast [MessageStatusEvent.Resent] — both bubble-affecting side effects belong to
     * [resendDirectMessage].
     *
     * @param preserveTimestamp True when a prior drain attempt may already have placed the packet
     *   on the wire (queue auto-park-and-retry), so the recipient's dedup ring catches the
     *   duplicate rather than rendering it twice. False on the first drain, where a fresh
     *   timestamp keeps mesh repeaters from filtering the packet.
     */
    suspend fun sendPendingDirectMessage(messageID: UUID, contact: ContactDto, preserveTimestamp: Boolean = false): MessageDto =
        performQueuedDirectMessageSend(messageID, contact, preserveTimestamp, isResend = false)

    /**
     * Resends an already-sent DM: re-runs the app-layer retry loop, increments [MessageDto.sendCount],
     * and broadcasts [MessageStatusEvent.Resent] so the bubble surfaces "Sent N times".
     *
     * @param preserveTimestamp True on queue auto-park-and-retry of the resend; false on the first
     *   drain attempt so the wire packet hashes distinctly from the original and clears repeater
     *   dedup rings.
     */
    suspend fun resendDirectMessage(messageID: UUID, contact: ContactDto, preserveTimestamp: Boolean = false): MessageDto =
        performQueuedDirectMessageSend(messageID, contact, preserveTimestamp, isResend = true)

    /**
     * Shared body of [sendPendingDirectMessage]/[resendDirectMessage]. Ported from
     * `performQueuedDirectMessageSend`.
     *
     * Unlike [sendMessageWithRetry]'s catch, this one does **not** broadcast
     * [MessageStatusEvent.Failed] before [failMessageAndRethrow] — in Swift, that broadcast is the
     * caller's responsibility: an inline non-queue catch site yields it itself, while a
     * queue-routed one (this method, meant to be driven by the not-yet-ported `ChatSendQueueService`)
     * delegates to the queue's own outer catch. Calling [sendPendingDirectMessage]/
     * [resendDirectMessage] directly — as any caller here must, since there's no queue yet — means
     * a failure here will fail the message row but will *not* emit [MessageStatusEvent.Failed].
     * That's a faithful port of Swift's division of responsibility, not a gap introduced here; a
     * future `SendQueue` slice is where that broadcast belongs.
     */
    private suspend fun performQueuedDirectMessageSend(messageID: UUID, contact: ContactDto, preserveTimestamp: Boolean, isResend: Boolean): MessageDto {
        val claimed = mutex.withLock { if (messageID in inFlightRetries) false else { inFlightRetries.add(messageID); true } }
        if (!claimed) throw MessageServiceError.SendFailed("Retry already in progress")

        try {
            val initialPathLength = contact.outPathLength
            val existingMessage = messageStore.fetchMessage(messageID) ?: throw MessageServiceError.SendFailed("Message not found")

            // Fresh timestamp on first drain — mesh repeaters deduplicate by packet content, so
            // reusing the original would be dropped. Queue auto-retry passes preserveTimestamp:
            // true so the duplicate landing on a recipient that already saw the first send gets
            // caught by their dedup ring rather than rendered as a second copy.
            val wireTimestamp: Instant
            val wireTimestampRaw: UInt
            if (preserveTimestamp) {
                wireTimestampRaw = existingMessage.timestamp
                wireTimestamp = Instant.ofEpochSecond(wireTimestampRaw.toLong())
            } else {
                wireTimestamp = Instant.now()
                wireTimestampRaw = wireTimestamp.epochSecond.toUInt()
                messageStore.updateMessageTimestamp(messageID, wireTimestampRaw)
            }

            try {
                val sentInfo = sendDirectMessageWithRetryLoop(
                    messageID = messageID,
                    contactID = contact.id,
                    radioID = contact.radioID,
                    publicKey = contact.publicKey,
                    text = existingMessage.text,
                    timestamp = wireTimestamp,
                    timestampRaw = wireTimestampRaw,
                    timeoutMs = null,
                )

                // Bump only on a confirmed successful resend so sendCount counts landed
                // user-initiated sends, not queue-driven attempts; bookkeeping failures are
                // swallowed because the on-air retransmission already happened.
                if (isResend && sentInfo != null) bestEffort { messageStore.incrementMessageSendCount(messageID) }

                val message = finalizeSend(messageID, contact.id, contact.radioID, contact.publicKey, sentInfo, initialPathLength)

                // Broadcast after the DB write so the downstream refetch sees both the bumped
                // sendCount and the committed terminal status.
                if (isResend && sentInfo != null) statusEventsFlow.emit(MessageStatusEvent.Resent(messageID))

                return message
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failMessageAndRethrow(error, messageID)
            }
        } finally {
            mutex.withLock { inFlightRetries.remove(messageID) }
        }
    }

    // MARK: - Direct Message Retry Loop

    /**
     * Sends a direct message with app-layer retry logic and UI notifications. Manages the retry
     * loop at the app layer (instead of delegating to MeshCore) to provide per-attempt UI
     * feedback. On each attempt: updates the message status, broadcasts
     * [MessageStatusEvent.Retrying], switches to flood routing after
     * [MessageServiceConfig.floodAfter] failed attempts, and broadcasts
     * [MessageStatusEvent.RoutingChanged] when routing switches.
     *
     * @param timestamp The message timestamp — must remain constant across retries.
     * @param timestampRaw The same timestamp as a `UInt`. Callers must pass the same integer used
     *   to build [timestamp]: resampling inside the loop would break expected-ACK precomputation,
     *   since the firmware hash is keyed off the exact value that ends up on the wire.
     * @return [MessageSentInfo] if an ACK was received, `null` if all attempts were exhausted.
     */
    private suspend fun sendDirectMessageWithRetryLoop(
        messageID: UUID,
        contactID: UUID,
        radioID: UUID,
        publicKey: ByteArray,
        text: String,
        timestamp: Instant,
        timestampRaw: UInt,
        timeoutMs: Long?,
    ): MessageSentInfo? {
        var attempts = 0
        var floodAttempts = 0
        var isFloodMode = false

        while (attempts < config.maxAttempts && (!isFloodMode || floodAttempts < config.maxFloodAttempts)) {
            // Update database and notify UI of retry status (only after the first attempt fails).
            if (attempts > 0) {
                messageStore.updateMessageRetryStatus(messageID, MessageStatus.RETRYING, retryAttempt = attempts - 1, maxRetryAttempts = config.maxAttempts - 1)
                statusEventsFlow.emit(MessageStatusEvent.Retrying(messageID, attempts - 1, config.maxAttempts - 1))
            }

            // Switch to flood routing after floodAfter direct attempts.
            if (attempts == config.floodAfter && !isFloodMode) {
                try {
                    session.resetPath(publicKey)
                    isFloodMode = true

                    session.getContact(publicKey)?.let { contactStore.saveContact(radioID, it) }
                    statusEventsFlow.emit(MessageStatusEvent.RoutingChanged(contactID, isFlood = true))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Continue anyway — device might handle it.
                    isFloodMode = true
                }
            }

            // Precompute the expected ACK CRC before the send so the persistent ACK listener
            // cannot race ahead of trackPendingAck on short direct links.
            val senderPublicKey = session.currentSelfInfo?.publicKey ?: throw MessageServiceError.NotConnected
            val predictedAck = AckCodeBuilder.expectedAck(timestampRaw, attempts.toUByte(), text, senderPublicKey)

            // Pre-send floor must outlive one checkExpiredAcks tick; otherwise a BLE round-trip
            // could let the checker expire the speculative entry before sendMessage returns and
            // we overwrite the timeout with the authoritative sentInfo-derived value.
            val preSendTimeoutMs = maxOf(timeoutMs ?: config.minTimeoutMs, checkIntervalMs)
            trackPendingAck(messageID, contactID, predictedAck, preSendTimeoutMs)

            val sentInfo: MessageSentInfo
            try {
                sentInfo = withPoolBackoff(FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL, config.poolBackoff) {
                    session.sendMessage(destination = publicKey, text = text, timestamp = timestamp, attempt = attempts.toUByte())
                }
            } catch (error: Throwable) {
                // Drop the whole speculative entry on send failure. A partial remove would leave
                // an empty-codes PendingAck visible to checkExpiredAcks if failMessageAndRethrow
                // is ever bypassed.
                mutex.withLock { pendingAcks.remove(messageID) }
                throw error
            }

            // If the persistent ACK listener consumed our predicted ACK during sendMessage's
            // suspension, the entry is already removed or marked delivered. Short-circuit before
            // waitForEvent so the retry loop doesn't clobber .delivered via
            // updateMessageRetryStatus and broadcast a duplicate DM.
            val tracked = mutex.withLock { pendingAcks[messageID] }
            if (tracked == null || tracked.isDelivered) return sentInfo

            val ackTimeoutMs = timeoutMs ?: maxOf(config.minTimeoutMs, sentInfo.suggestedTimeoutMs.toLong() * 12 / 10)

            mutex.withLock {
                val tracking = pendingAcks[messageID]
                if (!sentInfo.expectedAck.contentEquals(predictedAck)) {
                    trackPendingAckLocked(messageID, contactID, sentInfo.expectedAck, ackTimeoutMs)
                } else if (tracking != null) {
                    // Re-stamp sentAt so checkExpiredAcks measures timeout from send-return, not
                    // from the speculative insert.
                    tracking.timeoutMs = ackTimeoutMs
                    tracking.sentAt = Instant.now()
                }
            }

            val ackEvent = session.waitForEvent(EventFilter.acknowledgement(sentInfo.expectedAck), ackTimeoutMs / 1000.0)

            if (ackEvent != null) return sentInfo

            // Listener may have consumed the ACK between the pre-send guard and waitForEvent's
            // subscription becoming active; re-check before retrying so we don't resend a DM the
            // firmware already delivered.
            val isDeliveredNow = mutex.withLock { pendingAcks[messageID]?.isDelivered }
            if (isDeliveredNow != false) return sentInfo

            // ACK timeout — increment counters and retry.
            attempts++
            if (isFloodMode) floodAttempts++
        }

        return null
    }

    /**
     * Checks if a contact's routing changed and broadcasts [MessageStatusEvent.RoutingChanged] if
     * so. Called after the retry loop to detect if routing switched between direct and flood
     * during the send. Swallows all failures — this is best-effort UI notification, not a
     * delivery-affecting write.
     */
    private suspend fun checkAndNotifyRoutingChange(publicKey: ByteArray, contactID: UUID, radioID: UUID, initialPathLength: UByte) {
        try {
            val updatedContact = session.getContact(publicKey) ?: return
            val newPathLength = updatedContact.outPathLength
            if (newPathLength != initialPathLength) {
                contactStore.saveContact(radioID, updatedContact)
                val isNowFlood = newPathLength == PacketBuilder.FLOOD_PATH_SENTINEL
                statusEventsFlow.emit(MessageStatusEvent.RoutingChanged(contactID, isNowFlood))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort; Swift logs and continues here too.
        }
    }

    /**
     * Resolves the retry loop's outcome into a terminal (or still-in-flight) DB write. Ported from
     * `finalizeSend`.
     *
     * A missing or already-[PendingAck.isDelivered] tracking entry means [handleAcknowledgement]
     * (or the expiry checker) already owns the terminal state, so this only drops any residual
     * entry. A non-null [sentInfo] with a live entry means the loop's own `waitForEvent` matched
     * the ACK — take ownership and record the `.sent -> .delivered` upgrade. A `null` [sentInfo]
     * means the retry budget was spent without an ACK: [MessageStore.clearRetryingToSent] owns
     * rolling `.retrying` back to `.sent` (terminal-safe — a no-op if the checker already failed
     * the row in the loop's await-gap), and [MessageService.checkExpiredAcks] owns the eventual
     * give-up, so a late-but-legitimate ACK can still upgrade the row.
     */
    private suspend fun finalizeSend(messageID: UUID, contactID: UUID, radioID: UUID, publicKey: ByteArray, sentInfo: MessageSentInfo?, initialPathLength: UByte): MessageDto {
        val tracking = mutex.withLock { pendingAcks[messageID] }

        if (tracking == null || tracking.isDelivered) {
            mutex.withLock { pendingAcks.remove(messageID) }
        } else if (sentInfo != null) {
            mutex.withLock { pendingAcks.remove(messageID) }
            messageStore.updateMessageAck(id = messageID, ackCode = sentInfo.expectedAck.toAckCodeUInt(), status = MessageStatus.DELIVERED)
            contactStore.updateContactLastMessage(contactID, Instant.now())
            statusEventsFlow.emit(MessageStatusEvent.StatusResolved(messageID, MessageStatus.DELIVERED, null))
        } else {
            val movedToSent = messageStore.clearRetryingToSent(messageID)
            if (movedToSent) statusEventsFlow.emit(MessageStatusEvent.StatusResolved(messageID, MessageStatus.SENT, null))
        }

        checkAndNotifyRoutingChange(publicKey, contactID, radioID, initialPathLength)

        return messageStore.fetchMessage(messageID) ?: throw MessageServiceError.SendFailed("Failed to fetch message")
    }

    // MARK: - Send Channel Message

    /**
     * Sends a broadcast message to a channel. No acknowledgement is expected or tracked for
     * channel messages.
     *
     * @return The id and wire timestamp of the created message.
     * @throws MessageServiceError.MessageTooLong if [text] exceeds [MAX_CHANNEL_MESSAGE_TOTAL_LENGTH] UTF-8 bytes.
     * @throws MessageServiceError.SessionError if the send fails.
     */
    suspend fun sendChannelMessage(
        text: String,
        channelIndex: UByte,
        radioID: UUID,
        textType: TextType = TextType.PLAIN_TEXT,
    ): Pair<UUID, UInt> {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_CHANNEL_MESSAGE_TOTAL_LENGTH) throw MessageServiceError.MessageTooLong

        val messageID = UUID.randomUUID()
        val now = Instant.now()
        val timestamp = now.epochSecond.toUInt()

        messageStore.saveMessage(createOutgoingChannelMessage(messageID, radioID, channelIndex, text, timestamp, textType))

        try {
            withPoolBackoff(FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND, config.poolBackoff) {
                session.sendChannelMessage(channelIndex, text, now)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            statusEventsFlow.emit(MessageStatusEvent.Failed(messageID))
            failMessageAndRethrow(error, messageID)
        }

        // Post-broadcast bookkeeping. The broadcast already left the radio, so a failure here
        // must not mark .failed — that would lie about delivery.
        bestEffort {
            messageStore.updateMessageStatus(messageID, MessageStatus.SENT)
            statusEventsFlow.emit(MessageStatusEvent.StatusResolved(messageID, MessageStatus.SENT, null))
            channelStore.fetchChannel(radioID, channelIndex)?.let { channelStore.updateChannelLastMessage(it.id, Instant.now()) }
        }

        return messageID to timestamp
    }

    /** Creates a pending channel message without sending it — for optimistic UI. */
    suspend fun createPendingChannelMessage(
        text: String,
        channelIndex: UByte,
        radioID: UUID,
        textType: TextType = TextType.PLAIN_TEXT,
    ): MessageDto {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_CHANNEL_MESSAGE_TOTAL_LENGTH) throw MessageServiceError.MessageTooLong

        val messageID = UUID.randomUUID()
        val timestamp = Instant.now().epochSecond.toUInt()
        val messageDto = createOutgoingChannelMessage(messageID, radioID, channelIndex, text, timestamp, textType)
        messageStore.saveMessage(messageDto)
        return messageDto
    }

    /** Sends an already-created pending channel message (see [createPendingChannelMessage]). */
    suspend fun sendPendingChannelMessage(messageID: UUID) {
        val radioID: UUID
        val channelIndex: UByte
        val text: String
        val timestamp: UInt
        try {
            val message = messageStore.fetchMessage(messageID) ?: throw MessageServiceError.SendFailed("Message not found")
            val idx = message.channelIndex ?: throw MessageServiceError.SendFailed("Not a channel message")
            radioID = message.radioID
            channelIndex = idx
            text = message.text
            timestamp = message.timestamp

            withPoolBackoff(FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND, config.poolBackoff) {
                session.sendChannelMessage(channelIndex, text, Instant.ofEpochSecond(timestamp.toLong()))
            }
            messageStore.updateMessageStatus(messageID, MessageStatus.SENT)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            failMessageAndRethrow(error, messageID)
        }

        bestEffort {
            statusEventsFlow.emit(MessageStatusEvent.StatusResolved(messageID, MessageStatus.SENT, null))
            channelStore.fetchChannel(radioID, channelIndex)?.let { channelStore.updateChannelLastMessage(it.id, Instant.now()) }
        }
    }

    /**
     * Resends an existing channel message ("Send Again"), incrementing its send count. Retransmits
     * the same [MessageDto.text] rather than creating a duplicate row, using a new wire timestamp
     * (unless [preserveTimestamp]) so the mesh treats it as a fresh broadcast.
     *
     * @return The wire timestamp written to the message row. Reaction indexing keys off this value
     *   via `SHA256(text || timestamp.littleEndian)`; callers that index reactions for the resent
     *   packet must pass this value rather than the pre-resend timestamp.
     */
    suspend fun resendChannelMessage(messageID: UUID, preserveTimestamp: Boolean = false): UInt {
        // Catch 1: validation guards + timestamp update + send + status flip. Once .sent is
        // committed here, catch 2's per-attempt bookkeeping below cannot retroactively flip it.
        var sentCommitted = false
        val radioID: UUID
        val channelIndex: UByte
        val wireTimestamp: UInt
        try {
            val message = messageStore.fetchMessage(messageID) ?: throw MessageServiceError.SendFailed("Message not found")
            val idx = message.channelIndex ?: throw MessageServiceError.SendFailed("Not a channel message")
            radioID = message.radioID
            channelIndex = idx

            val wireInstant: Instant
            if (preserveTimestamp) {
                wireTimestamp = message.timestamp
                wireInstant = Instant.ofEpochSecond(wireTimestamp.toLong())
            } else {
                val now = Instant.now()
                wireTimestamp = now.epochSecond.toUInt()
                wireInstant = now
            }

            if (!preserveTimestamp) messageStore.updateMessageTimestamp(messageID, wireTimestamp)
            withPoolBackoff(FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND, config.poolBackoff) {
                session.sendChannelMessage(channelIndex, message.text, wireInstant)
            }
            messageStore.updateMessageStatus(messageID, MessageStatus.SENT)
            sentCommitted = true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            failMessageAndRethrow(error, messageID)
        }

        // Catch 2: per-attempt bookkeeping. Status is already .sent in DB and the broadcast
        // happened. Failures here log only — sendCount/heardRepeats-clear are non-load-bearing
        // for delivery semantics; the next inbound ack/repeat event will reconverge.
        bestEffort {
            messageStore.incrementMessageSendCount(messageID)
            messageStore.updateMessageHeardRepeats(messageID, 0)
            messageRepeatStore.deleteMessageRepeats(messageID)
        }

        if (sentCommitted) statusEventsFlow.emit(MessageStatusEvent.Resent(messageID))

        return wireTimestamp
    }

    private fun createOutgoingMessage(
        id: UUID,
        radioID: UUID,
        contactID: UUID,
        text: String,
        timestamp: UInt,
        textType: TextType,
        replyToID: UUID?,
    ): MessageDto {
        val now = Instant.now()
        return MessageDto(
            id = id,
            radioID = radioID,
            contactID = contactID,
            channelIndex = null,
            text = text,
            timestamp = timestamp,
            createdAt = now,
            sortDate = now,
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.PENDING,
            textType = textType,
            ackCode = null,
            pathLength = 0u,
            snr = null,
            pathNodes = null,
            senderKeyPrefix = null,
            senderNodeName = null,
            isRead = false,
            replyToID = replyToID,
            roundTripTime = null,
            sendCount = 1,
            retryAttempt = 0,
            maxRetryAttempts = 0,
            deduplicationKey = null,
            reactionSummary = null,
            senderTimestamp = null,
            routeType = null,
            heardRepeats = 0,
        )
    }

    private fun createOutgoingChannelMessage(
        id: UUID,
        radioID: UUID,
        channelIndex: UByte,
        text: String,
        timestamp: UInt,
        textType: TextType,
    ): MessageDto {
        val now = Instant.now()
        return MessageDto(
            id = id,
            radioID = radioID,
            contactID = null,
            channelIndex = channelIndex,
            text = text,
            timestamp = timestamp,
            createdAt = now,
            sortDate = now,
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.PENDING,
            textType = textType,
            ackCode = null,
            pathLength = 0u,
            snr = null,
            pathNodes = null,
            senderKeyPrefix = null,
            senderNodeName = null,
            isRead = false,
            replyToID = null,
            roundTripTime = null,
            sendCount = 1,
            retryAttempt = 0,
            maxRetryAttempts = 0,
            deduplicationKey = null,
            reactionSummary = null,
            senderTimestamp = null,
            routeType = null,
            heardRepeats = 0,
        )
    }

    // MARK: - Periodic ACK Checking

    /**
     * Starts periodic checking for expired ACKs — a background task that fails a DM awaiting an
     * ACK once its per-entry give-up deadline elapses (see [checkExpiredAcks]).
     *
     * Independent lifecycle from [startEventMonitoring]/[stopEventMonitoring]. Counterparts:
     * [stopAckExpiryChecking] (stop only) and [stopAndFailAllPending] (stop and fail every
     * in-flight DM, for full teardown).
     */
    fun startAckExpiryChecking(intervalMs: Long = 5_000) {
        checkIntervalMs = intervalMs
        ackCheckJob?.cancel()
        ackCheckJob = scope.launch {
            while (isActive) {
                delay(checkIntervalMs)
                if (!isActive) break
                try {
                    checkExpiredAcks()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Best-effort periodic check; Swift logs and continues here too.
                }
            }
        }
    }

    /** Stops the periodic ACK expiry checking. Leaves in-flight DMs [MessageStatus.SENT] so a reconnect can still receive their ACKs. */
    fun stopAckExpiryChecking() {
        ackCheckJob?.cancel()
        ackCheckJob = null
    }

    /**
     * Checks for expired ACKs and advances their delivery state. The give-up deadline for each
     * entry is `max(ackGiveUpWindowMs, tracking.timeoutMs)`: the window is a floor and
     * post-loop grace on fast presets; on slow presets the deadline follows the attempt's own
     * ACK wait so this never fires mid-attempt.
     */
    suspend fun checkExpiredAcks() {
        val now = Instant.now()
        val windowMs = config.ackGiveUpWindowMs

        val expiredIDs = mutex.withLock {
            pendingAcks.filterValues { !it.isDelivered && Duration.between(it.sentAt, now).toMillis() > maxOf(windowMs, it.timeoutMs) }.keys.toList()
        }

        for (messageID in expiredIDs) {
            val didFail = messageStore.updateMessageStatusUnlessDelivered(messageID, MessageStatus.FAILED)
            val removed = mutex.withLock { pendingAcks.remove(messageID) }
            if (removed != null && !removed.isDelivered && didFail) {
                statusEventsFlow.emit(MessageStatusEvent.Failed(messageID))
            }
        }
    }

    /** Fails all pending messages awaiting ACK — use when disconnecting to mark every in-flight message failed. */
    suspend fun failAllPendingMessages() {
        val pendingIDs = mutex.withLock {
            val snapshot = pendingAcks.filterValues { !it.isDelivered }.keys.toList()
            pendingAcks.clear()
            snapshot
        }
        for (messageID in pendingIDs) {
            val didFail = messageStore.updateMessageStatusUnlessDelivered(messageID, MessageStatus.FAILED)
            if (didFail) statusEventsFlow.emit(MessageStatusEvent.Failed(messageID))
        }
    }

    /** Stops ACK checking and fails all pending messages. Use only for explicit full teardown — see [stopAckExpiryChecking]. */
    suspend fun stopAndFailAllPending() {
        ackCheckJob?.cancel()
        ackCheckJob = null
        failAllPendingMessages()
    }

    /** The current number of pending ACKs being tracked, including undelivered messages still inside the give-up window. */
    suspend fun pendingAckCount(): Int = mutex.withLock { pendingAcks.size }

    /** Whether ACK expiry checking is currently active. */
    val isAckExpiryCheckingActive: Boolean get() = ackCheckJob != null

    // MARK: - Private Helpers

    private suspend fun trackPendingAck(messageID: UUID, contactID: UUID, ackCode: ByteArray, timeoutMs: Long) {
        mutex.withLock { trackPendingAckLocked(messageID, contactID, ackCode, timeoutMs) }
    }

    /** Same as [trackPendingAck], for call sites that already hold [mutex]. */
    private fun trackPendingAckLocked(messageID: UUID, contactID: UUID, ackCode: ByteArray, timeoutMs: Long) {
        val existing = pendingAcks[messageID]
        if (existing != null) {
            existing.addAckCode(ackCode)
            existing.sentAt = Instant.now()
            existing.timeoutMs = timeoutMs
        } else {
            pendingAcks[messageID] = PendingAck(messageID, contactID, ackCode, Instant.now(), timeoutMs)
        }
    }

    private suspend fun failMessageAndRethrow(error: Throwable, messageID: UUID): Nothing {
        mutex.withLock { pendingAcks.remove(messageID) }
        // Not wrapped in bestEffort: Swift's failMessageAndRethrow doesn't catch this call's own
        // errors either, so a persistence failure here would (as in Swift) mask the original error.
        messageStore.updateMessageStatusUnlessDelivered(messageID, MessageStatus.FAILED)
        if (error is MeshCoreError) throw MessageServiceError.SessionError(error)
        throw error
    }

    /** Runs [block], swallowing (but logging nothing — see class doc) any non-cancellation failure. */
    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort bookkeeping after an already-committed send; Swift logs and continues.
        }
    }

    /**
     * In-loop absorption of short pool-exhaustion bursts. The firmware briefly returns
     * `deviceError(transientCode)` when its outbound pool is full; this sleeps according to
     * [config] between attempts (default 500ms/1s/2s with ±20% jitter) and re-throws once
     * [PoolBackoffConfig.attemptCap] is reached.
     */
    private suspend fun <T> withPoolBackoff(transientCode: UByte, config: PoolBackoffConfig, operation: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return operation()
            } catch (error: MeshCoreError) {
                val deviceError = error as? MeshCoreError.DeviceError
                if (deviceError == null || deviceError.code != transientCode || attempt >= config.attemptCap) throw error
                val base = config.baseDelayMs * Math.pow(config.exponentBase, attempt.toDouble())
                val jitterFactor = config.jitterRange.start + Random.nextDouble() * (config.jitterRange.endInclusive - config.jitterRange.start)
                delay((base * jitterFactor).toLong())
                attempt++
            }
        }
    }

    private fun ByteArray.toAckCodeUInt(): UInt = if (size >= 4) readUInt32LE(0) else 0u

    companion object {
        /**
         * App-enforced direct-message text cap; under the firmware's binding 160-byte buffer
         * limit. Ported from `ProtocolLimits.maxDirectMessageLength` — public (not `private`, as
         * this file's other constants are) so UI-layer input bars can enforce/display it without
         * duplicating the number.
         */
        const val MAX_DIRECT_MESSAGE_LENGTH = 150

        /**
         * Total limit for channel messages, including the "NodeName: " prefix repeaters add.
         * Ported from `ProtocolLimits.maxChannelMessageTotalLength`. Prefer
         * [maxChannelMessageLength] over this raw total in UI code — it already accounts for the
         * prefix.
         *
         * 139, not the wire's 172-byte `channelDataMaxPayloadBytes`-adjacent frame budget: a
         * Region 1-hop `GRP_TXT` composes to a 173-byte `0x88` RX-log frame at the old 147-byte
         * total, which overflows the 172-byte companion RX-log buffer many firmwares use and
         * silently drops the heard-repeat push. 139 is the largest total that still fits. Ported
         * from upstream `98eaf3c4` ("fix(chats): cap channel text for 172-byte rx log"); the
         * frame-byte-accounting helper that commit also added
         * (`ProtocolLimits.groupTextRxLogFrameByteCount`) isn't ported — nothing here consumes a
         * frame-size calculation, only this cap.
         */
        const val MAX_CHANNEL_MESSAGE_TOTAL_LENGTH = 139

        /**
         * Max user text bytes for a channel message, after reserving room for the `"NodeName: "`
         * prefix repeaters prepend on the wire. Ported from
         * `ProtocolLimits.maxChannelMessageLength(nodeNameByteCount:)`.
         */
        fun maxChannelMessageLength(nodeNameByteCount: Int): Int =
            maxOf(0, MAX_CHANNEL_MESSAGE_TOTAL_LENGTH - nodeNameByteCount - 2)
    }
}
