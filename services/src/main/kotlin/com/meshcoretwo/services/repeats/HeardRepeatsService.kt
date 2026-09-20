// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.repeats

import com.meshcoretwo.protocol.PayloadType
import com.meshcoretwo.services.messages.DeduplicationKey
import com.meshcoretwo.services.messages.IncomingPathHarvesting
import com.meshcoretwo.services.persistence.DecryptStatus
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageRepeatDto
import com.meshcoretwo.services.persistence.MessageRepeatStore
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.RxLogDto
import com.meshcoretwo.services.rxlog.HeardRepeatProcessing
import com.meshcoretwo.services.utilities.ChannelMessageFormat
import com.meshcoretwo.services.utilities.ChannelRXCorrelation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Instant
import java.util.UUID

/**
 * Correlates RX-log entries to known messages, in two directions. Ported from
 * `HeardRepeatsService.swift`, called by [com.meshcoretwo.services.rxlog.RxLogService] through the
 * narrow [HeardRepeatProcessing] seam (that direction of dependency — rxlog calling out to this
 * package — is why the interface lives in `rxlog`, not here; see its doc) and by
 * [com.meshcoretwo.services.messages.IncomingMessageService] through [IncomingPathHarvesting].
 *
 * - **Outgoing: heard repeats.** A logged packet echoing a message this radio sent is recorded
 *   every time, identical hop list and all — each echo is evidence of propagation. Matched by
 *   exact channel + sender timestamp + message body; the sender-name prefix is stripped by
 *   [ChannelMessageFormat.parse] first, since `findSentChannelMessage` already scopes to this
 *   radio and a repeater rename between send and echo shouldn't break correlation.
 * - **Incoming: extra flood paths.** The same message reaching this radio again over a *different*
 *   path becomes an extra arrival on the same bubble instead of a dropped duplicate. Matched on
 *   the content-based [DeduplicationKey], because a repeater's copy shares `(channelIndex,
 *   senderTimestamp)` with the original and nothing else distinguishes it from a different
 *   message carrying the same stamp. Identical paths are *not* recorded: for an incoming message
 *   only a distinct route says something new.
 *
 * Both kinds share one storage shape ([MessageRepeatDto]) and one counter
 * ([com.meshcoretwo.services.persistence.MessageDto.heardRepeats]) — the UI reads it as "heard N
 * times" for outgoing and "arrived over N paths" for incoming.
 *
 * No `Mutex`/actor-replacement needed: unlike [com.meshcoretwo.services.messages.MessageService]'s
 * `pendingAcks` or [com.meshcoretwo.services.messages.IncomingMessageService]'s `isPolling`, this
 * service's only mutable field is [configure]'s [radioID], which is set once per connection before
 * any RX-log event can arrive (the caller always calls `configure` before `startEventMonitoring`)
 * — there's no concurrent writer to race.
 */
class HeardRepeatsService(
    private val messageStore: MessageStore,
    private val messageRepeatStore: MessageRepeatStore,
) : HeardRepeatProcessing, IncomingPathHarvesting {
    private val eventsFlow = MutableSharedFlow<HeardRepeatEvent>(extraBufferCapacity = 64)

    private var radioID: UUID? = null

    /** Returns a fresh stream of heard-repeat events. Registration is synchronous, so events emitted after this call are never dropped. */
    fun events(): Flow<HeardRepeatEvent> = eventsFlow

    /** Configures the service with the connected radio. Must be called once before processing any RX-log entries. */
    fun configure(radioID: UUID) {
        this.radioID = radioID
    }

    /**
     * Checks whether [entry] echoes a sent channel message — or is an extra flood copy of an
     * incoming one this radio already stored — and if so records it and emits a
     * [HeardRepeatEvent]. Only processes successfully-decrypted group-text entries whose
     * [RxLogDto.decodedText] is set. Ported from `processForRepeats`.
     *
     * @return The updated count if something was recorded, `null` otherwise.
     */
    override suspend fun processForRepeats(entry: RxLogDto): Int? {
        if (entry.payloadType != PayloadType.GROUP_TEXT) return null
        if (entry.decryptStatus != DecryptStatus.SUCCESS) return null
        val decodedText = entry.decodedText ?: return null
        val channelIndex = entry.channelIndex ?: return null
        val senderTimestamp = entry.senderTimestamp ?: return null
        val radioID = radioID ?: return null

        // Body after the first colon is the stored outgoing text. The sender name is a join key
        // only for incoming extras (through [DeduplicationKey]), not for sent echoes:
        // findSentChannelMessage already scopes to this radio.
        val (senderName, messageText) = ChannelMessageFormat.parse(decodedText) ?: return null

        if (isDuplicateRepeat(entry.id)) return null

        return try {
            val sent = messageStore.findSentChannelMessage(radioID, channelIndex, senderTimestamp, messageText)
            if (sent != null) return recordSentEcho(sent, entry)

            // Not one of our own echoes: an incoming message this radio already stored may be
            // reaching us again over a different path.
            val key = DeduplicationKey.contentBased(
                contactID = null,
                channelIndex = channelIndex,
                senderNodeName = senderName,
                timestamp = senderTimestamp,
                content = messageText,
            )
            val incoming = messageStore.fetchMessage(key, radioID) ?: return null
            recordDistinctPathIfNeeded(
                message = incoming,
                pathNodes = entry.pathNodes,
                pathLength = entry.pathLength,
                snr = entry.snr,
                rssi = entry.rssi,
                receivedAt = entry.receivedAt,
                rxLogEntryID = entry.id,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    /** Records a sent message's echo, identical hop list and all — every re-broadcast of our own packet is evidence of propagation. Ported from `recordSentEcho`. */
    private suspend fun recordSentEcho(message: MessageDto, entry: RxLogDto): Int {
        messageRepeatStore.saveMessageRepeat(
            MessageRepeatDto(
                id = UUID.randomUUID(),
                messageID = message.id,
                receivedAt = entry.receivedAt,
                pathNodes = entry.pathNodes,
                pathLength = entry.pathLength,
                snr = entry.snr,
                rssi = entry.rssi,
                rxLogEntryID = entry.id,
            ),
        )
        val newCount = messageStore.incrementMessageHeardRepeats(message.id)
        eventsFlow.emit(HeardRepeatEvent(message.id, newCount))
        return newCount
    }

    override suspend fun recordDistinctPathIfNeeded(
        message: MessageDto,
        pathNodes: ByteArray,
        pathLength: UByte,
        snr: Double?,
        rssi: Int?,
        receivedAt: Instant,
        rxLogEntryID: UUID?,
    ): Int? {
        val canonicalPath = message.pathNodes
        if (canonicalPath == null) {
            // An unknown path is "not correlated yet", not a 0-hop arrival: adopt this one onto
            // the message instead of hanging an extra off nothing.
            try {
                messageStore.adoptIncomingPathIfUnknown(message.id, pathNodes, pathLength)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Best-effort: the message keeps its unknown path and a later copy may adopt one.
            }
            return null
        }
        if (pathNodes.contentEquals(canonicalPath)) return null

        return try {
            if (rxLogEntryID != null && messageRepeatStore.messageRepeatExists(rxLogEntryID)) return null
            val existing = messageRepeatStore.fetchMessageRepeats(message.id)
            if (existing.any { it.pathNodes.contentEquals(pathNodes) }) return null

            messageRepeatStore.saveMessageRepeat(
                MessageRepeatDto(
                    id = UUID.randomUUID(),
                    messageID = message.id,
                    receivedAt = receivedAt,
                    pathNodes = pathNodes,
                    pathLength = pathLength,
                    snr = snr,
                    rssi = rssi,
                    rxLogEntryID = rxLogEntryID,
                ),
            )
            val newCount = messageStore.incrementMessageHeardRepeats(message.id)
            eventsFlow.emit(HeardRepeatEvent(message.id, newCount))
            newCount
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    override suspend fun harvestIncomingPaths(message: MessageDto, decodedCandidates: List<RxLogDto>) {
        if (message.isOutgoing || message.channelIndex == null) return
        var current = message
        for (entry in ChannelRXCorrelation.matching(decodedCandidates, message.deduplicationKey)) {
            val wasUnknown = current.pathNodes == null
            recordDistinctPathIfNeeded(
                message = current,
                pathNodes = entry.pathNodes,
                pathLength = entry.pathLength,
                snr = entry.snr,
                rssi = entry.rssi,
                receivedAt = entry.receivedAt,
                rxLogEntryID = entry.id,
            )
            // The first candidate may have been adopted as the message's own path — re-read it so
            // the next candidate compares against that path rather than adopting again.
            if (wasUnknown) {
                val key = current.deduplicationKey ?: continue
                val refreshed = try {
                    messageStore.fetchMessage(key, current.radioID)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    null
                }
                if (refreshed != null) current = refreshed
            }
        }
    }

    /**
     * Returns a message's recorded repeats, sorted oldest first. Used when opening the Repeat
     * Details sheet to catch any missed repeats. Ported from `refreshRepeats`.
     */
    suspend fun refreshRepeats(messageID: UUID): List<MessageRepeatDto> = try {
        messageRepeatStore.fetchMessageRepeats(messageID)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        emptyList()
    }

    /** Whether a repeat has already been recorded for this RX-log entry. Fails open (assumes duplicate) to avoid double-counting on a store error. */
    private suspend fun isDuplicateRepeat(entryID: UUID): Boolean = try {
        messageRepeatStore.messageRepeatExists(entryID)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        true
    }
}
