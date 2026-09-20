// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.messages

import com.meshcoretwo.protocol.ChannelMessage
import com.meshcoretwo.protocol.ContactMessage
import com.meshcoretwo.protocol.EventFilter
import com.meshcoretwo.protocol.MeshCoreSessionProtocol
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.protocol.MessageResult
import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.DeliveryContext
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.utilities.MentionUtilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

/**
 * Receives incoming direct and channel messages from the mesh and persists them as
 * [MessageDto] rows. Ported from `MessagePollingService.swift` (the session-facing listener)
 * merged with `SyncCoordinator+MessageHandlers.swift`'s ingestion pipeline: Swift splits those
 * responsibilities because `SyncCoordinator` wires closures into an independently-owned
 * `MessagePollingService`; this port has no `SyncCoordinator` yet, so both fold into one class.
 *
 * **Deferred, not yet ported** (tracked in PLAN.md's Phase 3 status):
 * - `waitForPendingHandlers` — existed only to gate notification resume after a sync drain;
 *   nothing in this port needs that ordering yet.
 *
 * **Signed/room messages** (`textType` 0x02) are routed, not dropped — see [roomMessageHandler]'s
 * doc. **CLI (`textType` 0x01) messages** are routed too, not dropped — see [cliMessageHandler]'s
 * doc.
 *
 * **Unread counts, local notifications, self-mention detection, and blocked-sender filtering**
 * (added for the mention-count/blocked-sender backfill slice) *are* ported:
 * - **Self-mention detection** uses [com.meshcoretwo.services.utilities.MentionUtilities] against
 *   [selfNodeNameProvider]'s name, matching `SyncCoordinator.handleIncomingMessage`'s
 *   `hasSelfMention` computation (including the channel-only "don't flag your own outgoing echo"
 *   `senderNodeName != selfNodeName` guard — direct messages need no such guard, see
 *   [handleContactMessage]'s comment on why the firmware can't echo a self-DM here).
 * - **Blocked-sender filtering** uses [contactStore]'s [com.meshcoretwo.services.persistence.ContactStore.isBlockedSender]
 *   (a direct query, not Swift's `SyncCoordinator.blockedNames` cache — see that method's doc):
 *   a blocked channel sender's messages are dropped before saving, matching
 *   `SyncCoordinator+MessageHandlers.swift`'s `if isBlockedSender(senderNodeName) { return }`. A
 *   blocked *contact*'s direct messages still save (Swift does the same — only the unread/
 *   notification side effect is skipped, via [IncomingMessageNotifying.notifyDirectMessage]'s own
 *   `contact.isBlocked` guard).
 * - **Unread counts and local notifications** run through [notifying] (`null` by default) —
 *   satisfied by `com.meshcoretwo.services.notifications.NotificationCoordinator`, same
 *   narrow-interface trick as [pendingAdvertResolver]/[reactionHandling]/[rxLogCorrelation] so
 *   this class doesn't need to know the `notifications` package exists when a caller has no use
 *   for it.
 *
 * **`isPolling` is `Mutex`-guarded**, matching [MessageService]'s `pendingAcks` reasoning:
 * Kotlin has no actor isolation, so the flag guarding against the event listener and
 * [pollAllMessages] double-processing the same message needs an explicit lock.
 *
 * **Orphan-DM materialization** — a direct message whose sender prefix matches no local contact
 * is resolved through [pendingAdvertResolver] (when supplied — it's `null` by default) before
 * falling back to persisting with `contactID = null`. Wired to
 * `AdvertisementService.materializeContactForPendingAdvert` as a narrow function type rather than
 * a hard dependency on `AdvertisementService` itself, so this class and its tests don't need to
 * know about the advertisement package at all when the caller has no use for it.
 *
 * **Reaction handling** — when [reactionHandling] is supplied (`null` by default), a
 * reaction-formatted incoming message is matched to its target and persisted as a `Reaction` row
 * instead of an ordinary [MessageDto], mirroring Swift's `handleDMReaction`/`handleChannelReaction`
 * short-circuit. Same narrow-interface trick as [pendingAdvertResolver] — see
 * [ReactionHandling]'s doc. [selfNodeNameProvider] supplies the local node's advertised name a
 * channel reaction needs to recognize a reaction to the user's *own* outgoing message.
 *
 * **RX-log path correlation** — when [rxLogCorrelation] is supplied (`null` by default),
 * [MessageDto.pathNodes]/`pathLength`/`routeType` are filled in from the logged RF packet the
 * message decrypted from, mirroring Swift's `lookupRxLogEntry`. A miss (or no correlator at all)
 * reproduces exactly this port's pre-existing behavior — the wire `pathLength` verbatim,
 * `pathNodes`/`routeType` both `null` — so the feature is strictly additive. Same narrow-interface
 * trick as [pendingAdvertResolver]/[reactionHandling] — see [RxLogCorrelating]'s doc.
 *
 * **Extra flood paths** — when [pathHarvesting] is supplied (`null` by default), a channel message
 * that reaches this radio more than once over *different* paths keeps every distinct route instead
 * of dropping the later copies: copies arriving after the save are attached on the duplicate
 * check, copies already in the RX log before it are collected right after. See
 * [IncomingPathHarvesting]'s doc.
 */
class IncomingMessageService(
    private val session: MeshCoreSessionProtocol,
    private val messageStore: MessageStore,
    private val contactStore: ContactStore,
    private val channelStore: ChannelStore,
    private val pendingAdvertResolver: (suspend (prefix: ByteArray, radioID: UUID) -> ContactDto?)? = null,
    private val reactionHandling: ReactionHandling? = null,
    private val selfNodeNameProvider: (suspend (radioID: UUID) -> String?)? = null,
    private val rxLogCorrelation: RxLogCorrelating? = null,
    private val notifying: IncomingMessageNotifying? = null,
    /**
     * Routes a decoded SIGNED (room) message's fields to
     * `com.meshcoretwo.services.remotenode.RoomServerService.handleIncomingMessage`, when supplied
     * (`null` by default) — same narrow-interface-lambda trick as [pendingAdvertResolver]/
     * [reactionHandling]/[rxLogCorrelation], so this class and its tests don't need to depend on
     * the `remotenode` package when a caller has no use for it. See [handleSignedMessage]'s doc for
     * what's extracted and why a missing handler (or a malformed message) silently drops, same as
     * today's pre-wiring behavior.
     */
    private val roomMessageHandler: (suspend (senderPublicKeyPrefix: ByteArray, timestamp: UInt, authorPrefix: ByteArray, text: String) -> Unit)? = null,
    /**
     * Routes a decoded CLI-response (`textType` 0x01) message to whichever admin service the
     * sending contact belongs to, when supplied (`null` by default) — same narrow-interface-lambda
     * trick as [roomMessageHandler], so this class and its tests don't need to depend on the
     * `remotenode` package's admin services when a caller has no use for them. Unlike
     * [roomMessageHandler], dispatch between `RepeaterAdminService.invokeCLIHandler` and
     * `RoomAdminService.invokeCLIHandler` (by `contact.type`) and the wire echo-prefix stripping
     * both live in the lambda supplied by the composition root rather than in this class, because
     * both need [com.meshcoretwo.services.remotenode.CLIResponse]/`ContactType` — types this class
     * otherwise never imports. See [handleCLIMessage]'s doc for what's resolved before the lambda
     * runs and why a missing contact silently drops, same as today's pre-wiring behavior.
     */
    private val cliMessageHandler: (suspend (message: ContactMessage, contact: ContactDto) -> Unit)? = null,
    /**
     * Attaches extra flood copies of an incoming message to the row that already exists, when
     * supplied (`null` by default) — see [IncomingPathHarvesting]. Without it, a duplicate is
     * dropped exactly as before.
     */
    private val pathHarvesting: IncomingPathHarvesting? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private var currentRadioID: UUID? = null
    private var eventListenerJob: Job? = null
    private var isAutoFetchEnabled = false
    private var isPolling = false

    private val receivedEventsFlow = MutableSharedFlow<IncomingMessageEvent>(extraBufferCapacity = 64)

    /** A multicast stream of persisted incoming messages; every subscriber sees every event. */
    fun receivedEvents(): Flow<IncomingMessageEvent> = receivedEventsFlow

    // MARK: - Event Monitoring

    /** Starts event monitoring for message handlers without enabling auto-fetch. Call before a sync drain so handlers are ready for polled messages. */
    fun startMessageEventMonitoring(radioID: UUID) {
        currentRadioID = radioID
        startEventMonitoring()
    }

    /** Stops event monitoring. Also stops auto-fetch if it was running. */
    suspend fun stopMessageEventMonitoring() {
        if (isAutoFetchEnabled) {
            stopAutoFetch()
        } else {
            stopEventMonitoring()
            currentRadioID = null
        }
    }

    // MARK: - Auto-Fetch Control

    /** Starts automatic message fetching for a device: enables the session's auto-fetch and monitors for incoming messages. */
    suspend fun startAutoFetch(radioID: UUID) {
        if (isAutoFetchEnabled) return
        currentRadioID = radioID
        isAutoFetchEnabled = true
        if (eventListenerJob == null) startEventMonitoring()
        session.startAutoMessageFetching()
    }

    /** Stops automatic message fetching. */
    suspend fun stopAutoFetch() {
        if (!isAutoFetchEnabled) return
        isAutoFetchEnabled = false
        session.stopAutoMessageFetching()
        stopEventMonitoring()
    }

    /** Whether auto-fetch is currently enabled. */
    val isAutoFetching: Boolean get() = isAutoFetchEnabled

    private fun startEventMonitoring() {
        eventListenerJob?.cancel()
        eventListenerJob = scope.launch {
            session.events(EventFilter.anyContactMessage.or(EventFilter.anyChannelMessage)).collect { event ->
                if (!isActive) return@collect
                when (event) {
                    is MeshEvent.ContactMessageReceived -> {
                        if (mutex.withLock { isPolling }) return@collect
                        handleContactMessage(event.message, DeliveryContext.Live)
                    }
                    is MeshEvent.ChannelMessageReceived -> {
                        if (mutex.withLock { isPolling }) return@collect
                        handleChannelMessage(event.message, DeliveryContext.Live)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun stopEventMonitoring() {
        eventListenerJob?.cancel()
        eventListenerJob = null
    }

    // MARK: - Manual Polling

    /**
     * Manually polls all waiting messages from the device's stored queue until it reports empty.
     * @return The count of contact/channel messages retrieved (binary channel datagrams are
     *   drained but not counted — they aren't user-visible messages, and this port has no
     *   listener for them yet).
     */
    suspend fun pollAllMessages(): Int {
        mutex.withLock { isPolling = true }
        try {
            var count = 0
            // One anchor for the whole drain so every backlog message shares a sort date and
            // forms a single contiguous block positioned at delivery time.
            val blockAnchor = Instant.now()

            while (true) {
                when (val result = session.getMessage()) {
                    is MessageResult.ContactMessageResult -> {
                        count++
                        handleContactMessage(result.message, DeliveryContext.InitialSync(blockAnchor))
                    }
                    is MessageResult.ChannelMessageResult -> {
                        count++
                        handleChannelMessage(result.message, DeliveryContext.InitialSync(blockAnchor))
                    }
                    is MessageResult.ChannelDatagramResult -> {
                        // Not a user-visible message; drain without counting.
                    }
                    is MessageResult.NoMoreMessages -> return count
                }
            }
        } finally {
            mutex.withLock { isPolling = false }
        }
    }

    // MARK: - Private Message Handlers

    private suspend fun handleContactMessage(message: ContactMessage, context: DeliveryContext) {
        val radioID = currentRadioID ?: return

        when (TextType.fromValue(message.textType)) {
            TextType.CLI_DATA -> handleCLIMessage(message, radioID)
            TextType.SIGNED -> handleSignedMessage(message)
            else -> {
                val contact = contactStore.fetchContactByPrefix(radioID, message.senderPublicKeyPrefix)
                    ?: resolvePendingAdvertContact(message.senderPublicKeyPrefix, radioID)
                handleIncomingMessage(IncomingMessageKind.Direct(message, contact), context, radioID)
            }
        }
    }

    /**
     * Routes a room-server message to [roomMessageHandler] —
     * `com.meshcoretwo.services.remotenode.RoomServerService.handleIncomingMessage`'s doc covers
     * what it does with these fields. [ContactMessage.signature] carries the 4-byte author key
     * prefix for a SIGNED message (already decoded at parse time by
     * `com.meshcoretwo.protocol.ContactMessageParser`, not a real cryptographic signature — see
     * [ContactMessage]'s doc); a missing/short value means an unparseable envelope, so it's dropped
     * defensively rather than routed with bad data, mirroring Swift's
     * `guard let authorPrefix = message.signature?.prefix(4), authorPrefix.count == 4 else { return }`
     * (`SyncCoordinator+MessageHandlers.swift`'s `handleIncomingSignedMessage`). No-op (silently
     * drops, same as before this was wired) when [roomMessageHandler] is `null` — this port doesn't
     * post a notification or emit a live-UI event for a received room message yet, matching Swift's
     * own `handleIncomingSignedMessage` doing that at its call site rather than inside the room
     * service itself; that's a separate, later gap (see `SyncDataEvent`'s doc).
     */
    private suspend fun handleSignedMessage(message: ContactMessage) {
        val handler = roomMessageHandler ?: return
        val authorPrefix = message.signature?.takeIf { it.size >= 4 }?.copyOfRange(0, 4) ?: return
        handler(message.senderPublicKeyPrefix, message.senderTimestamp.epochSecond.toUInt(), authorPrefix, message.text)
    }

    /**
     * Resolves the sending contact and hands off to [cliMessageHandler]. A CLI response only ever
     * comes from an already-known repeater/room contact (the admin session that requested it was
     * established against that contact), so — unlike a DM — no [pendingAdvertResolver] fallback
     * applies; an unresolved sender is dropped defensively, matching Swift's
     * `handleIncomingCLIMessage`'s `else { logger.warning(...) }` branch (this port skips the log,
     * same as this class's other silent defensive drops — see [handleSignedMessage]). No-op when
     * [cliMessageHandler] is `null` — this port has no CLI admin screen wired up yet, so nothing
     * consumes CLI responses in production, matching the current pre-wiring behavior.
     */
    private suspend fun handleCLIMessage(message: ContactMessage, radioID: UUID) {
        val handler = cliMessageHandler ?: return
        val contact = contactStore.fetchContactByPrefix(radioID, message.senderPublicKeyPrefix) ?: return
        handler(message, contact)
    }

    /**
     * Falls back to [pendingAdvertResolver] when no local contact matches a DM's sender prefix —
     * the sender may already be a pending advert the radio auto-added but this app hasn't yet
     * pulled down via a delta-sync round. Fail-open: a resolver error or absence just means the
     * message persists with `contactID = null`, matching the pre-existing orphan-DM behavior.
     */
    private suspend fun resolvePendingAdvertContact(prefix: ByteArray, radioID: UUID): ContactDto? {
        val resolver = pendingAdvertResolver ?: return null
        return try {
            resolver(prefix, radioID)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    private suspend fun handleChannelMessage(message: ChannelMessage, context: DeliveryContext) {
        val radioID = currentRadioID ?: return
        val channel = channelStore.fetchChannel(radioID, message.channelIndex)
        handleIncomingMessage(IncomingMessageKind.Channel(message, channel), context, radioID)
    }

    private sealed class IncomingMessageKind {
        data class Direct(val message: ContactMessage, val contact: ContactDto?) : IncomingMessageKind()
        data class Channel(val message: ChannelMessage, val channel: com.meshcoretwo.services.persistence.ChannelDto?) : IncomingMessageKind()
    }

    /**
     * Shared ingestion pipeline for incoming direct and channel messages: timestamp correction,
     * dedup, persistence, and last-message bookkeeping. Ported from
     * `SyncCoordinator.handleIncomingMessage`, minus the deferred concerns listed in this
     * class's doc.
     */
    private suspend fun handleIncomingMessage(kind: IncomingMessageKind, context: DeliveryContext, radioID: UUID) {
        val text: String
        val senderNodeName: String?
        val senderTimestamp: Instant
        val textTypeRaw: UByte
        val snr: Double?
        val pathLength: UByte
        val contactID: UUID?
        val channelIndex: UByte?
        val senderKeyPrefix: ByteArray?

        when (kind) {
            is IncomingMessageKind.Direct -> {
                // The firmware cannot surface a self-DM here: decrypt runs against a contact's
                // ECDH shared secret and the local self_id is never in contacts[], so an echo
                // of the user's own DM never reaches this path.
                text = kind.message.text
                senderNodeName = null
                senderTimestamp = kind.message.senderTimestamp
                textTypeRaw = kind.message.textType
                snr = kind.message.snr
                pathLength = kind.message.pathLength
                contactID = kind.contact?.id
                channelIndex = null
                senderKeyPrefix = kind.message.senderPublicKeyPrefix
            }
            is IncomingMessageKind.Channel -> {
                val (parsedSender, body) = parseChannelMessage(kind.message.text)
                senderNodeName = parsedSender
                text = body
                senderTimestamp = kind.message.senderTimestamp
                textTypeRaw = kind.message.textType
                snr = kind.message.snr
                pathLength = kind.message.pathLength
                contactID = null
                channelIndex = kind.message.channelIndex
                senderKeyPrefix = null
            }
        }

        // Self-mention detection. Channel messages additionally exclude the sender's own name so
        // a user's outgoing message, echoed back as a channel message, is never flagged as a
        // mention of themselves — direct messages need no such guard (see handleContactMessage's
        // comment on why the firmware can't echo a self-DM here). Ported from
        // `SyncCoordinator.handleIncomingMessage`'s `hasSelfMention` computation.
        val selfNodeName = resolveSelfNodeName(radioID)
        val hasSelfMention = selfNodeName.isNotEmpty() &&
            (kind !is IncomingMessageKind.Channel || senderNodeName != selfNodeName) &&
            MentionUtilities.containsSelfMention(text, selfNodeName)

        val timestamp = senderTimestamp.epochSecond.toUInt()
        val receiveTime = Instant.now()
        val (finalTimestamp, wasCorrected) = correctTimestampIfNeeded(timestamp, receiveTime)

        val sortDate = when (context) {
            is DeliveryContext.Live -> receiveTime
            is DeliveryContext.InitialSync -> context.anchor
        }

        // Content-based key (stable across retry attempts, unlike the RX-log packet hash) —
        // deliberately built from the pre-correction timestamp, matching Swift exactly.
        val deduplicationKey = DeduplicationKey.contentBased(contactID, channelIndex, senderNodeName, timestamp, text)

        // Clamp an unknown wire textType to plain rather than let it propagate as garbage.
        val resolvedTextType = TextType.fromValue(textTypeRaw) ?: TextType.PLAIN_TEXT

        // Channel correlation joins decrypted 0x88 rows on the deduplication key; direct messages
        // still match by sender timestamp, then by sender prefix.
        val pathData = resolvePathData(radioID, channelIndex, timestamp, senderKeyPrefix, pathLength, if (channelIndex != null) deduplicationKey else null)

        val messageDto = MessageDto(
            id = UUID.randomUUID(),
            radioID = radioID,
            contactID = contactID,
            channelIndex = channelIndex,
            text = text,
            timestamp = finalTimestamp,
            createdAt = receiveTime,
            sortDate = sortDate,
            direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED,
            textType = resolvedTextType,
            ackCode = null,
            pathLength = pathData.pathLength,
            snr = snr,
            pathNodes = pathData.pathNodes,
            senderKeyPrefix = senderKeyPrefix,
            senderNodeName = senderNodeName,
            isRead = false,
            replyToID = null,
            roundTripTime = null,
            sendCount = 1,
            retryAttempt = 0,
            maxRetryAttempts = 0,
            deduplicationKey = deduplicationKey,
            reactionSummary = null,
            // Recorded only when corrected: reaction hashing must key off what the sender
            // actually claimed (and hashed into the wire reaction), not our corrected value.
            senderTimestamp = if (wasCorrected) timestamp else null,
            routeType = pathData.routeType,
            heardRepeats = 0,
            containsSelfMention = hasSelfMention,
            mentionSeen = false,
            regionScope = pathData.regionScope,
            regionScopeMatches = pathData.regionScopeMatches,
        )

        val existing = try {
            messageStore.fetchMessage(deduplicationKey, radioID)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null // fail-open: proceed with the save rather than risk silently dropping a message
        }
        if (existing != null) {
            recordExtraArrival(kind, existing, pathData, snr, receiveTime)
            return
        }

        // Discard channel messages from a blocked sender before they're ever saved. Ported from
        // `SyncCoordinator+MessageHandlers.swift`'s `if isBlockedSender(senderNodeName) { return }`.
        // Direct messages from a blocked contact still save — see this class's doc.
        if (kind is IncomingMessageKind.Channel && contactStore.isBlockedSender(radioID, senderNodeName)) return

        if (tryHandleReaction(kind, text, channelIndex, senderNodeName, receiveTime, radioID)) return

        messageStore.saveMessage(messageDto)

        // Copies that arrived before this row existed are still in the RX log — no live
        // duplicate-drop could have caught them, so collect them now and re-read the message so
        // every consumer below sees the final arrival count.
        val savedDto = if (kind is IncomingMessageKind.Channel) {
            harvestAndRefreshChannelMessage(messageDto, radioID, channelIndex!!, timestamp)
        } else {
            messageDto
        }

        when (kind) {
            is IncomingMessageKind.Direct -> {
                kind.contact?.let { contact ->
                    contactStore.updateContactLastMessage(contact.id, Instant.now())
                    receivedEventsFlow.emit(IncomingMessageEvent.DirectMessageReceived(savedDto, contact))
                }
            }
            is IncomingMessageKind.Channel -> {
                kind.channel?.let { channelStore.updateChannelLastMessage(it.id, Instant.now()) }
                receivedEventsFlow.emit(IncomingMessageEvent.ChannelMessageReceived(savedDto, channelIndex!!))
            }
        }

        tryIndexForReactions(kind, savedDto.id, text, timestamp, channelIndex, senderNodeName)
        tryNotify(kind, savedDto, senderNodeName, hasSelfMention, channelIndex, radioID)
    }

    /** Unread-counter/notification side effect for a just-saved message. Best-effort — a failure here must not undo the already-saved message. */
    private suspend fun tryNotify(
        kind: IncomingMessageKind,
        message: MessageDto,
        senderNodeName: String?,
        hasSelfMention: Boolean,
        channelIndex: UByte?,
        radioID: UUID,
    ) {
        val notifying = notifying ?: return
        try {
            when (kind) {
                is IncomingMessageKind.Direct -> notifying.notifyDirectMessage(message, kind.contact, hasSelfMention)
                is IncomingMessageKind.Channel -> notifying.notifyChannelMessage(message, kind.channel, channelIndex!!, senderNodeName, hasSelfMention, radioID)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort.
        }
    }

    /**
     * Short-circuit for a reaction-formatted message: if [reactionHandling] consumes [text] as a
     * reaction, the caller must not persist it as an ordinary message. Fail-open — a throwing
     * handler must not drop a message that turns out not to be a reaction.
     */
    private suspend fun tryHandleReaction(
        kind: IncomingMessageKind,
        text: String,
        channelIndex: UByte?,
        senderNodeName: String?,
        receiveTime: Instant,
        radioID: UUID,
    ): Boolean {
        val handling = reactionHandling ?: return false
        return try {
            when (kind) {
                is IncomingMessageKind.Direct -> kind.contact?.let { handling.handleDirectReaction(text, it, radioID) } ?: false
                is IncomingMessageKind.Channel -> handling.handleChannelReaction(text, channelIndex!!, senderNodeName, resolveSelfNodeName(radioID), receiveTime, radioID)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            false
        }
    }

    private suspend fun resolveSelfNodeName(radioID: UUID): String {
        val provider = selfNodeNameProvider ?: return ""
        return try {
            provider(radioID) ?: ""
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ""
        }
    }

    /**
     * Looks up path data via [rxLogCorrelation]. Fail-open to the "uncorrelated" result (the
     * wire [defaultPathLength] verbatim, `pathNodes`/`routeType` both `null`) when no correlator
     * is supplied or it throws — matches [RxLogCorrelating]'s "strictly additive" contract.
     */
    private suspend fun resolvePathData(
        radioID: UUID,
        channelIndex: UByte?,
        senderTimestamp: UInt,
        senderKeyPrefix: ByteArray?,
        defaultPathLength: UByte,
        channelDeduplicationKey: String?,
    ): RxLogPathData {
        val correlator = rxLogCorrelation ?: return RxLogPathData(pathNodes = null, pathLength = defaultPathLength, routeType = null)
        return try {
            correlator.lookupPathData(radioID, channelIndex, senderTimestamp, senderKeyPrefix, defaultPathLength, channelDeduplicationKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            RxLogPathData(pathNodes = null, pathLength = defaultPathLength, routeType = null)
        }
    }

    /**
     * Records a duplicate channel message as an extra arrival on the row it collided with, when a
     * path is actually known for it. Ported from `recordArrivalAndSkipDuplicate`'s channel branch
     * — a `null` path is unknown, not 0-hop, so it's never written as an extra. Direct messages
     * are unaffected: a DM arrives over one path by definition. Best-effort — the duplicate is
     * dropped either way.
     */
    private suspend fun recordExtraArrival(kind: IncomingMessageKind, existing: MessageDto, pathData: RxLogPathData, snr: Double?, receiveTime: Instant) {
        if (kind !is IncomingMessageKind.Channel) return
        val harvester = pathHarvesting ?: return
        val pathNodes = pathData.pathNodes ?: return
        try {
            harvester.recordDistinctPathIfNeeded(existing, pathNodes, pathData.pathLength, snr, rssi = null, receivedAt = receiveTime, rxLogEntryID = null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort.
        }
    }

    /**
     * Harvests extra flood copies already in the RX log onto a just-saved channel message and
     * returns the re-read row (or [messageDto] unchanged when nothing was collected or either step
     * failed). Ported from `harvestAndRefreshChannelMessage`.
     */
    private suspend fun harvestAndRefreshChannelMessage(messageDto: MessageDto, radioID: UUID, channelIndex: UByte, senderTimestamp: UInt): MessageDto {
        val harvester = pathHarvesting ?: return messageDto
        val correlator = rxLogCorrelation ?: return messageDto
        return try {
            harvester.harvestIncomingPaths(messageDto, correlator.decodedChannelEntries(radioID, channelIndex, senderTimestamp))
            messageStore.fetchMessage(messageDto.id) ?: messageDto
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            messageDto
        }
    }

    /** Indexes a just-saved message for future reaction targeting. Best-effort — a failure here must not undo the already-saved message. */
    private suspend fun tryIndexForReactions(kind: IncomingMessageKind, messageID: UUID, text: String, timestamp: UInt, channelIndex: UByte?, senderNodeName: String?) {
        val handling = reactionHandling ?: return
        try {
            when (kind) {
                is IncomingMessageKind.Direct -> kind.contact?.let { handling.indexDirectMessage(messageID, it.id, text, timestamp) }
                is IncomingMessageKind.Channel -> senderNodeName?.let { handling.indexChannelMessage(messageID, channelIndex!!, it, text, timestamp) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort.
        }
    }

    // MARK: - Static Helpers

    companion object {
        /** 5 minutes: how far a claimed sender timestamp may sit in the future before it's distrusted. */
        private val TIMESTAMP_TOLERANCE_FUTURE: Duration = Duration.ofMinutes(5)

        /** ~6 months: how far in the past a claimed sender timestamp may sit before it's distrusted. */
        private val TIMESTAMP_TOLERANCE_PAST: Duration = Duration.ofDays(6L * 30)

        /**
         * Corrects an implausible sender-claimed timestamp (clock wrong or malicious) to the
         * receive time. Ported from `SyncCoordinator.correctTimestampIfNeeded`.
         */
        internal fun correctTimestampIfNeeded(timestamp: UInt, receiveTime: Instant): Pair<UInt, Boolean> {
            val claimed = Instant.ofEpochSecond(timestamp.toLong())
            val tooFarInFuture = claimed.isAfter(receiveTime.plus(TIMESTAMP_TOLERANCE_FUTURE))
            val tooFarInPast = claimed.isBefore(receiveTime.minus(TIMESTAMP_TOLERANCE_PAST))
            return if (tooFarInFuture || tooFarInPast) {
                receiveTime.epochSecond.toUInt() to true
            } else {
                timestamp to false
            }
        }

        /**
         * Parses a channel message's `"NodeName: text"` wire format. Ported from
         * `SyncCoordinator.parseChannelMessage`.
         * @return The sender name (trimmed) and message body (trimmed), or `(null, text)` verbatim if there's no colon.
         */
        internal fun parseChannelMessage(text: String): Pair<String?, String> {
            val parts = text.split(":", limit = 2)
            if (parts.size > 1) {
                return parts[0].trim() to parts[1].trim()
            }
            return null to text
        }
    }
}
