// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import com.meshcoretwo.protocol.RemoteAccessSessionOps
import com.meshcoretwo.protocol.readUInt32LE
import com.meshcoretwo.services.messages.MessageServiceConfig
import com.meshcoretwo.services.notifications.NotificationService
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.NotificationLevel
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionStore
import com.meshcoretwo.services.persistence.RoomMessageDto
import com.meshcoretwo.services.persistence.RoomMessageStore
import com.meshcoretwo.services.persistence.generateRoomMessageDeduplicationKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

/**
 * Service for room-server interactions: joining rooms, posting messages, and receiving room
 * messages. Ported from `RoomServerService.swift`. Sits alongside
 * [com.meshcoretwo.services.remotenode.RepeaterAdminService]/
 * [com.meshcoretwo.services.remotenode.RoomAdminService] as a thin layer over
 * [RemoteNodeService] for session lifecycle, but owns its own persistence
 * ([RoomMessageStore]) and send-with-retry flow — the two services above never send message
 * content, only CLI/status/telemetry traffic.
 *
 * The Swift constructor also takes `dataStore: any PersistenceStoreProtocol`; narrowed here to
 * the three stores this class's ported methods actually call ([RemoteNodeSessionStore]/
 * [RoomMessageStore]/[ContactStore]), the same narrowing precedent `RemoteNodeService` established.
 *
 * `CommandAuditLogger` is dropped, matching every other service in this package (pure logging,
 * never gates behavior — see [RemoteNodeService]'s class doc).
 *
 * [postMessage]'s background send runs on [scope] (`SupervisorJob` + `Dispatchers.Default`) —
 * the direct Kotlin analogue of Swift's detached `Task { [weak self] in ... }`: `postMessage`
 * returns the freshly-saved [MessageStatus.PENDING] row immediately so the UI can render it
 * optimistically, while the actual send/ACK-wait continues in the background and reports its
 * outcome through [events]. [retryMessage], by contrast, is a blocking `suspend fun` that awaits
 * its own send — matching Swift, which never wraps `retryMessage`'s body in a `Task`.
 *
 * [radioID] (added by upstream `e410eb8d`) scopes [handleIncomingMessage]'s and
 * [getConnectedSession]'s public-key-prefix session lookups to this device: a room server's key
 * prefix is mesh-wide, so an unscoped lookup could resolve to another radio's stale session row
 * for the same physical node.
 */
class RoomServerService(
    private val session: RemoteAccessSessionOps,
    private val remoteNodeService: RemoteNodeService,
    private val sessionStore: RemoteNodeSessionStore,
    private val messageStore: RoomMessageStore,
    private val contactStore: ContactStore,
    private val radioID: UUID,
    private val config: MessageServiceConfig = MessageServiceConfig(),
    private val notificationService: NotificationService? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    /** Tracks message ids currently being retried, to prevent concurrent retry attempts for the same message. */
    private val inFlightRetries = mutableSetOf<UUID>()

    private val eventsFlow = MutableSharedFlow<RoomServerEvent>(extraBufferCapacity = 64)

    /** Self public-key prefix for author comparison. Set from `SelfInfo` when the device connects. */
    @Volatile
    private var selfPublicKeyPrefix: ByteArray? = null

    /** Sets the self public-key prefix from `SelfInfo`. Call this when device info is received. */
    fun setSelfPublicKeyPrefix(prefix: ByteArray) {
        selfPublicKeyPrefix = prefix.copyOfRange(0, minOf(4, prefix.size))
    }

    // MARK: - Events

    /** A fresh, multicast stream of room-server events — every subscriber receives every event, matching [RemoteNodeEvent]'s doc. */
    fun events(): Flow<RoomServerEvent> = eventsFlow

    // MARK: - Room Management

    /**
     * Joins a room server by creating a session and authenticating. Automatically syncs message
     * history based on local state.
     *
     * @param pathLength Path length for timeout calculation (0 = direct).
     * @param onTimeoutKnown Optional callback invoked with the timeout in seconds once firmware responds.
     */
    suspend fun joinRoom(
        radioID: UUID,
        contact: ContactDto,
        password: String?,
        rememberPassword: Boolean = true,
        pathLength: UByte = 0u,
        onTimeoutKnown: (suspend (Int) -> Unit)? = null,
    ): RemoteNodeSessionDto {
        val remoteSession = remoteNodeService.createSession(radioID, contact)

        remoteNodeService.login(remoteSession.id, password, pathLength, onTimeoutKnown)

        // Store the password only after a successful login.
        if (password != null && rememberPassword) remoteNodeService.storePassword(password, contact.publicKey)

        // Attempt additional history sync if needed (non-blocking — never fails the join).
        syncHistoryIfPossible(remoteSession.id)

        return sessionStore.fetchSession(remoteSession.id) ?: throw RemoteNodeError.SessionNotFound
    }

    /**
     * Reconnects to an existing room session and syncs any missed messages. Use when
     * re-authenticating to a room after an app restart or BLE reconnection.
     *
     * @param pathLength Optional path length hint (0 = use shortest known path).
     */
    suspend fun reconnectRoom(sessionID: UUID, pathLength: UByte = 0u): RemoteNodeSessionDto {
        val remoteSession = sessionStore.fetchSession(sessionID) ?: throw RemoteNodeError.SessionNotFound
        if (!remoteSession.isRoom) throw RemoteNodeError.InvalidResponse

        remoteNodeService.login(sessionID, pathLength = pathLength)

        syncHistoryIfPossible(sessionID)

        return sessionStore.fetchSession(sessionID) ?: throw RemoteNodeError.SessionNotFound
    }

    /** Leaves a room by sending logout and removing the session. [publicKey] is used for keychain cleanup. */
    suspend fun leaveRoom(sessionID: UUID, publicKey: ByteArray) {
        remoteNodeService.logout(sessionID)
        remoteNodeService.removeSession(sessionID, publicKey)
    }

    // MARK: - Message Posting

    /**
     * Posts a message to a room server.
     *
     * Posts use plain text; the room server converts it to signed-plain when pushing to other
     * clients. The server does not push messages back to their authors, so the local message
     * record is created immediately.
     *
     * @return The saved message, in [MessageStatus.PENDING] status — see class doc for how the
     *   send actually completes.
     */
    suspend fun postMessage(sessionID: UUID, text: String): RoomMessageDto {
        val remoteSession = sessionStore.fetchSession(sessionID) ?: throw RoomServerError.SessionNotFound
        if (!remoteSession.canPost) throw RoomServerError.PermissionDenied

        val timestamp = Instant.now()
        val messageID = UUID.randomUUID()

        val messageDto = RoomMessageDto(
            id = messageID,
            sessionID = sessionID,
            authorKeyPrefix = selfPublicKeyPrefix ?: ByteArray(4),
            authorName = "Me",
            text = text,
            timestamp = timestamp.epochSecond.toUInt(),
            isFromSelf = true,
            status = MessageStatus.PENDING,
            maxRetryAttempts = config.maxAttempts,
        )

        messageStore.saveMessage(messageDto)

        // Send in the background so the UI can show the pending message immediately.
        scope.launch {
            try {
                // sendMessageWithRetry requires the full 32-byte public key for path reset.
                val sentInfo = session.sendMessageWithRetry(
                    destination = remoteSession.publicKey,
                    text = text,
                    timestamp = timestamp,
                    maxAttempts = config.maxAttempts,
                    floodAfter = config.floodAfter,
                    maxFloodAttempts = config.maxFloodAttempts,
                )

                if (sentInfo != null) {
                    // Success — update to delivered. Handle database errors gracefully: don't lose the send-success outcome.
                    bestEffort {
                        messageStore.updateMessageStatus(messageID, MessageStatus.DELIVERED, sentInfo.expectedAck.toAckCodeUInt(), sentInfo.suggestedTimeoutMs)
                    }
                    eventsFlow.emit(RoomServerEvent.StatusUpdated(messageID, MessageStatus.DELIVERED))
                } else {
                    // All retries exhausted — radio transmitted but no ACK received. Marked sent
                    // (not failed) since the message likely reached the room server.
                    bestEffort { messageStore.updateMessageStatus(messageID, MessageStatus.SENT) }
                    eventsFlow.emit(RoomServerEvent.StatusUpdated(messageID, MessageStatus.SENT))
                }
                // Sort-date only — no sync bookmark, to avoid clock-skew issues.
                bestEffort { sessionStore.updateRoomActivity(sessionID) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // Send failed with an error — do not update activity.
                bestEffort { messageStore.updateMessageStatus(messageID, MessageStatus.FAILED) }
                eventsFlow.emit(RoomServerEvent.StatusUpdated(messageID, MessageStatus.FAILED))
            }
        }

        // Return the pending message immediately so the UI can display it.
        return messageDto
    }

    /** Retries sending a failed room message. */
    suspend fun retryMessage(id: UUID): RoomMessageDto {
        // Guard against concurrent retries of the same message.
        if (!mutex.withLock { inFlightRetries.add(id) }) {
            throw RoomServerError.SendFailed("Retry already in progress")
        }
        try {
            val message = messageStore.fetchMessage(id) ?: throw RoomServerError.SendFailed("Message not found")
            if (message.status != MessageStatus.FAILED) throw RoomServerError.SendFailed("Message is not in failed state")

            val remoteSession = sessionStore.fetchSession(message.sessionID) ?: throw RoomServerError.SessionNotFound

            // Update to pending/retrying.
            val newRetryAttempt = message.retryAttempt + 1
            messageStore.updateMessageRetryStatus(id, MessageStatus.PENDING, newRetryAttempt, config.maxAttempts)
            eventsFlow.emit(RoomServerEvent.StatusUpdated(id, MessageStatus.PENDING))

            // Retry send with retry logic. sendMessageWithRetry requires the full 32-byte public key for path reset.
            try {
                val sentInfo = session.sendMessageWithRetry(
                    destination = remoteSession.publicKey,
                    text = message.text,
                    timestamp = Instant.now(),
                    maxAttempts = config.maxAttempts,
                    floodAfter = config.floodAfter,
                    maxFloodAttempts = config.maxFloodAttempts,
                )

                if (sentInfo != null) {
                    bestEffort { messageStore.updateMessageStatus(id, MessageStatus.DELIVERED, sentInfo.expectedAck.toAckCodeUInt(), sentInfo.suggestedTimeoutMs) }
                    eventsFlow.emit(RoomServerEvent.StatusUpdated(id, MessageStatus.DELIVERED))
                } else {
                    bestEffort { messageStore.updateMessageStatus(id, MessageStatus.SENT) }
                    eventsFlow.emit(RoomServerEvent.StatusUpdated(id, MessageStatus.SENT))
                }
                // Sort date so the room moves to the top of the conversation list.
                bestEffort { sessionStore.updateRoomActivity(message.sessionID) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                bestEffort { messageStore.updateMessageStatus(id, MessageStatus.FAILED) }
                eventsFlow.emit(RoomServerEvent.StatusUpdated(id, MessageStatus.FAILED))
            }

            return messageStore.fetchMessage(id) ?: throw RoomServerError.SendFailed("Failed to fetch message after retry")
        } finally {
            mutex.withLock { inFlightRetries.remove(id) }
        }
    }

    // MARK: - Incoming Messages

    /**
     * Handles an incoming room message. Call from `MessagePollingService`/`AdvertisementService`
     * equivalent when a signed-plain message arrives from a room.
     *
     * Messages arrive with the room server's key as [senderPublicKeyPrefix] (6 bytes) and the
     * original author's 4-byte key prefix in the payload as [authorPrefix]. Room servers don't
     * push messages back to their authors, so incoming messages should never be from self —
     * checked defensively anyway.
     *
     * @return The saved message, or `null` if the sender isn't a known room or the message was a duplicate.
     */
    suspend fun handleIncomingMessage(
        senderPublicKeyPrefix: ByteArray,
        timestamp: UInt,
        authorPrefix: ByteArray,
        text: String,
    ): RoomMessageDto? {
        val remoteSession = sessionStore.fetchSessionByPrefix(radioID, senderPublicKeyPrefix)?.takeIf { it.isRoom } ?: return null

        // Receiving any message (even a duplicate) proves the session is active.
        if (!remoteSession.isConnected) {
            val recovered = try {
                sessionStore.markRoomSessionConnected(remoteSession.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                false
            }
            if (recovered) eventsFlow.emit(RoomServerEvent.ConnectionRecovered(remoteSession.id))
        }

        val dedupKey = generateRoomMessageDeduplicationKey(timestamp, authorPrefix, text)
        if (messageStore.isDuplicateMessage(remoteSession.id, dedupKey)) return null

        // Defensive check: room servers shouldn't push our own messages back.
        val isFromSelf = selfPublicKeyPrefix?.let { self ->
            self.copyOfRange(0, minOf(4, self.size)).contentEquals(authorPrefix.copyOfRange(0, minOf(4, authorPrefix.size)))
        } ?: false

        val authorName = contactStore.findContactNameByKeyPrefix(authorPrefix)

        val messageDto = RoomMessageDto(
            sessionID = remoteSession.id,
            authorKeyPrefix = authorPrefix,
            authorName = authorName,
            text = text,
            timestamp = timestamp,
            isFromSelf = isFromSelf,
        )

        messageStore.saveMessage(messageDto)

        // Update the sync bookmark and sort date.
        sessionStore.updateRoomActivity(remoteSession.id, timestamp)

        eventsFlow.emit(RoomServerEvent.MessageReceived(messageDto))

        if (!isFromSelf) {
            sessionStore.incrementUnreadCount(remoteSession.id)
            notificationService?.postRoomMessageNotification(
                roomName = remoteSession.name,
                sessionID = remoteSession.id,
                senderName = authorName,
                messageText = text,
                messageID = messageDto.id,
                notificationLevel = remoteSession.notificationLevel,
            )
        }

        return messageDto
    }

    // MARK: - Message Retrieval

    /** Fetches messages for a room session, oldest first. */
    suspend fun fetchMessages(sessionID: UUID, limit: Int = 50, offset: Int = 0): List<RoomMessageDto> =
        messageStore.fetchMessages(sessionID, limit, offset)

    /** Marks a room as read (resets its unread count). Call when the user views the conversation. */
    suspend fun markAsRead(sessionID: UUID) = sessionStore.resetUnreadCount(sessionID)

    /** Sets a room's favorite flag. Thin passthrough onto [RemoteNodeSessionStore.setFavorite]. */
    suspend fun setFavorite(sessionID: UUID, isFavorite: Boolean) = sessionStore.setFavorite(sessionID, isFavorite)

    /** Sets a room's notification level. Thin passthrough onto [RemoteNodeSessionStore.setNotificationLevel]. */
    suspend fun setNotificationLevel(sessionID: UUID, level: NotificationLevel) = sessionStore.setNotificationLevel(sessionID, level)

    // MARK: - Session Queries

    /** Fetches all room sessions for a device. */
    suspend fun fetchRoomSessions(radioID: UUID): List<RemoteNodeSessionDto> =
        sessionStore.fetchSessions(radioID).filter { it.isRoom }

    /** Live version of [fetchRoomSessions] — see `ContactService.observeContacts` for why this exists. */
    fun observeRoomSessions(radioID: UUID): Flow<List<RemoteNodeSessionDto>> =
        sessionStore.observeSessions(radioID).map { sessions -> sessions.filter { it.isRoom } }

    /** Checks if a contact is a known room server with an active session. */
    suspend fun getConnectedSession(publicKeyPrefix: ByteArray): RemoteNodeSessionDto? {
        val remoteSession = sessionStore.fetchSessionByPrefix(radioID, publicKeyPrefix) ?: return null
        return remoteSession.takeIf { it.isRoom && it.isConnected }
    }

    // MARK: - Private Helpers

    /** Attempts to sync history, using the advert path first, then falling back to path discovery. Never fails the join. */
    private suspend fun syncHistoryIfPossible(sessionID: UUID) {
        try {
            val remoteSession = sessionStore.fetchSession(sessionID) ?: return
            val contact = contactStore.findContactByPublicKey(remoteSession.publicKey) ?: return

            // Strategy: 1) if the contact has a path from advertisement (not flood-routed), try it
            // first; 2) if that fails or the contact is flood-routed, trigger path discovery; 3)
            // wait for the discovery result and retry.
            if (!contact.isFloodRouted) {
                try {
                    remoteNodeService.requestHistorySync(sessionID)
                    return
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Advert path didn't work — fall through to path discovery.
                }
            }

            val hasDirectRoute = discoverPathAndWait(sessionID)
            if (!hasDirectRoute) return

            // Retry with the newly discovered path.
            remoteNodeService.requestHistorySync(sessionID)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Don't fail the join — messages will arrive via the normal flow.
        }
    }

    /** Discovers a path and waits for a direct route, polling for up to [timeoutMs]. */
    private suspend fun discoverPathAndWait(sessionID: UUID, timeoutMs: Long = 10_000): Boolean {
        val remoteSession = sessionStore.fetchSession(sessionID) ?: return false
        val contact = contactStore.findContactByPublicKey(remoteSession.publicKey) ?: return false

        // Already direct?
        if (!contact.isFloodRouted) return true

        try {
            session.sendPathDiscovery(remoteSession.publicKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return false
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            val updated = contactStore.findContactByPublicKey(remoteSession.publicKey)
            if (updated != null && !updated.isFloodRouted) return true
        }
        return false
    }

    /** Runs [block], swallowing (but logging nothing — see class doc) any non-cancellation failure. */
    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort bookkeeping after an already-committed send outcome; Swift logs and continues.
        }
    }

    private fun ByteArray.toAckCodeUInt(): UInt = if (size >= 4) readUInt32LE(0) else 0u
}
