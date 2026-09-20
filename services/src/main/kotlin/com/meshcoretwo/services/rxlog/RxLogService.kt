// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rxlog

import com.meshcoretwo.protocol.ChannelCrypto
import com.meshcoretwo.protocol.DirectMessageCrypto
import com.meshcoretwo.protocol.Ed25519ToX25519
import com.meshcoretwo.protocol.EventFilter
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.protocol.ParsedRxLogData
import com.meshcoretwo.protocol.PayloadType
import com.meshcoretwo.protocol.RouteType
import com.meshcoretwo.protocol.RxLogSessionOps
import com.meshcoretwo.protocol.TransportCodeRegionResolver
import com.meshcoretwo.protocol.decodePathLen
import com.meshcoretwo.protocol.readUInt16LE
import com.meshcoretwo.protocol.readUInt32LE
import com.meshcoretwo.services.messages.RxLogCorrelating
import com.meshcoretwo.services.messages.RxLogPathData
import com.meshcoretwo.services.persistence.ChannelDto
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.DecryptStatus
import com.meshcoretwo.services.persistence.DiscoveredNodeStore
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.RxLogDecryptionUpdate
import com.meshcoretwo.services.persistence.RxLogDto
import com.meshcoretwo.services.persistence.RxLogRegionUpdate
import com.meshcoretwo.services.persistence.RxLogRetention
import com.meshcoretwo.services.persistence.RxLogStore
import com.meshcoretwo.services.persistence.toRxLogDto
import com.meshcoretwo.services.utilities.ChannelRXCorrelation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Processes RX-log events (every RF packet the radio hears — 0x88 `logData` pushes) by
 * best-effort-decrypting channel/DM payloads to extract a sender timestamp, persisting the
 * result, and correlating it back onto incoming [com.meshcoretwo.services.persistence.MessageDto]
 * rows via [RxLogCorrelating]. Ported from `RxLogService.swift` merged with the correlation
 * function `SyncCoordinator.lookupRxLogEntry` (in `SyncCoordinator+HandlerHelpers.swift`) — same
 * merge reasoning as [com.meshcoretwo.services.messages.IncomingMessageService]/
 * [com.meshcoretwo.services.advertisement.AdvertisementService]: this port has no
 * `SyncCoordinator` to own the correlation call site separately.
 *
 * [process] also stamps the `DiscoveredNode` ("Discover" list) inbound hop count for
 * flood-routed advert packets ([PayloadType.ADVERT] + [RouteType.isFlood]), ported from the
 * `dataStore.setInboundHopCount` call in `RxLogService.swift`'s `process(_:)`. Advertisement
 * *event* handling (0x80/0x8A) and Discover-row upserts live in `AdvertisementService` instead —
 * this is the only piece of Discover-row maintenance that belongs here, since it reads a field
 * (inbound hop count) only RX-log packets carry.
 *
 * **`heardRepeatProcessing`** (added for the "HeardRepeats" slice) receives every successfully
 * decrypted group-text payload's plaintext, transiently, via [RxLogDto.decodedText] — see that
 * field's doc for why it's never persisted. `null` (the default) is faithful to Swift too: its
 * constructor takes `HeardRepeatsService?` as an optional dependency the app can simply omit.
 *
 * **Deferred, not yet ported** (tracked in PLAN.md's Phase 3 status):
 * - **Flood-region scope resolution** (`+RegionResolution`, `RegionScopeSemantics`, the
 *   `regionScope`/`regionScopeMatches` fields and their reprocess sweep) — needs
 *   `Device.knownRegions`, which isn't on [com.meshcoretwo.services.persistence.DeviceEntity]
 *   (deferred to whichever slice ports `SettingsService`, which owns writing it). It's also a
 *   pure chat-footer UI label ("this packet flew under flood-region `de-hh`"), not behavior —
 *   nothing routes on it.
 * - **Contact-name attribution** (`fromContactName`/`updateContactNames`) — the RX Log viewer
 *   slice resolves path-hop/sender node names in the `app` layer instead (see
 *   `RxLogViewModel.kt`'s `buildNodeNameMap`, a direct port of the Swift view model's function of
 *   the same name), so this service never needs a contact-name cache of its own.
 *
 * **`entryStream()`/`loadExistingEntries()`/`decryptEntry()`** (added for the RX Log viewer
 * slice) back the live log screen: [entryStream] rebroadcasts every [process]-ed entry via a
 * [MutableSharedFlow] (this port's usual `EventBroadcaster` replacement, same as
 * `AdvertisementService`/`IncomingMessageService`'s event flows), [loadExistingEntries] reads the
 * persisted tail back and re-runs [decryptEntry] over each row so [RxLogDto.decodedText] — never
 * persisted — is fresh for display, and [decryptEntry] is the shared re-decrypt path both use.
 *
 * Mutable caches (`channelSecrets`/`channelNames`/`myPrivateKey`/`contactPublicKeysByPrefix`)
 * are `Mutex`-guarded, matching this port's established actor-replacement convention.
 */
class RxLogService(
    private val session: RxLogSessionOps,
    private val rxLogStore: RxLogStore,
    private val channelStore: ChannelStore,
    private val contactStore: ContactStore,
    private val discoveredNodeStore: DiscoveredNodeStore,
    private val heardRepeatProcessing: HeardRepeatProcessing? = null,
    /**
     * Backs the region-reprocess sweep's message correlation ([reprocessRegionEntries]). `null`
     * by default, same narrow-additive convention as [heardRepeatProcessing] — a caller with no
     * use for region labeling (e.g. a test that only exercises decrypt/correlate) doesn't need to
     * supply one, and [process]'s own per-packet region resolution still runs either way (it only
     * touches [RxLogDto], not [com.meshcoretwo.services.persistence.MessageDto]).
     */
    private val messageStore: MessageStore? = null,
) : RxLogCorrelating {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private var radioID: UUID? = null
    private var eventListenerJob: Job? = null

    private var channelSecrets: Map<UByte, ByteArray> = emptyMap()
    private var channelNames: Map<UByte, String> = emptyMap()
    private var myPrivateKey: ByteArray? = null
    private var contactPublicKeysByPrefix: Map<UByte, List<ByteArray>> = emptyMap()

    /** `Device.knownRegions` as last pushed by [updateKnownRegions]. */
    private var knownRegions: List<String> = emptyList()

    /** Precomputed `(name, scopeKey)` pairs for [knownRegions], rebuilt by [updateKnownRegions]. */
    private var regionScopeKeyCache: List<Pair<String, ByteArray>> = emptyList()

    private var isReprocessingChannels = false
    private var isReprocessingDMs = false
    private var isReprocessingRegions = false

    /** Multicast broadcaster for newly persisted entries — the RX Log viewer's live feed. */
    private val entryFlow = MutableSharedFlow<RxLogDto>(extraBufferCapacity = 64)

    /**
     * Multicast broadcaster fired after a region-reprocess sweep actually changes at least one
     * already-persisted message's region — an open chat screen re-fetches on this so a stale
     * chip catches up without the user leaving and reopening the conversation. Carries no payload
     * (unlike iOS's `regionUpdateBroadcaster`'s `[UUID]`): every consumer this port has today
     * (`ConversationViewModel`) already does a full reload on any relevant event, so a message-id
     * list would go unused — see [regionUpdateEvents]'s doc.
     */
    private val regionUpdateFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** A fresh multicast stream that fires once per region-reprocess sweep with a real change. Ported from `regionUpdateEvents()`, trimmed to a `Unit` signal — see [regionUpdateFlow]'s doc. */
    fun regionUpdateEvents(): Flow<Unit> = regionUpdateFlow

    /** Returns a fresh multicast stream of newly persisted entries. Ported from `entryStream()`. */
    fun entryStream(): Flow<RxLogDto> = entryFlow

    // MARK: - Event Monitoring

    /** Starts monitoring RX-log events for [radioID], loading current channel secrets/contact keys first. */
    fun startEventMonitoring(radioID: UUID) {
        eventListenerJob?.cancel()
        this.radioID = radioID
        eventListenerJob = scope.launch {
            loadSecretsFromDatabase(radioID)
            session.events(EventFilter.rxLogData).collect { event ->
                if (event is MeshEvent.RxLogData) process(event.data)
            }
        }
    }

    /** Stops event monitoring. Uses `cancelAndJoin` (not a bare `cancel`) so a caller that awaits this can rely on the listener having actually unsubscribed — see `AdvertisementService.stopEventMonitoring`'s doc for the same reasoning. */
    suspend fun stopEventMonitoring() {
        eventListenerJob?.cancelAndJoin()
        eventListenerJob = null
        radioID = null
    }

    /** Commits any RX-log entries still buffered by [RxLogStore]. Ported from `flushPendingRxLogEntries()`'s call site in `ServiceContainer.stopEventMonitoring`/`MC1App`'s background handler. */
    suspend fun flushPendingEntries() = rxLogStore.flushPendingEntries()

    private suspend fun loadSecretsFromDatabase(radioID: UUID) {
        try {
            val channels = channelStore.fetchChannels(radioID)
            updateChannels(channels)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort: an empty cache just means every packet stays undecrypted until the
            // next updateChannels/sync call refreshes it.
        }

        try {
            val contacts = contactStore.fetchContacts(radioID)
            val byPrefix = contacts.filter { it.publicKey.isNotEmpty() }.groupBy({ it.publicKey[0].toUByte() }, { it.publicKey })
            updateContactPublicKeys(byPrefix)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort, same reasoning as above.
        }
    }

    // MARK: - Cache Updates (called by ChannelService/ContactService as secrets change)

    /** Rebuilds the channel cache from a fresh channel list — call after every channel sync/write so newly-captured packets decrypt with current secrets. */
    suspend fun updateChannels(channels: List<ChannelDto>) {
        updateChannels(channels.associate { it.index to it.secret }, channels.associate { it.index to it.name })
    }

    /** Updates the channel secret/name cache, then re-processes any recent [DecryptStatus.NO_MATCHING_KEY] entries. */
    suspend fun updateChannels(secrets: Map<UByte, ByteArray>, names: Map<UByte, String>) {
        mutex.withLock {
            channelSecrets = secrets
            channelNames = names
        }
        if (secrets.isNotEmpty()) reprocessNoMatchingKeyEntries()
    }

    /**
     * Updates the device private key used for DM-decrypt correlation. The exported key is 64
     * bytes: `[expanded_scalar:32][nonce:32]` — only the first 32 (the Curve25519 scalar) is
     * needed. Re-processes any recent [DecryptStatus.DM_NO_MATCHING_KEY] entries once set.
     */
    suspend fun updatePrivateKey(key: ByteArray?) {
        val trimmed = key?.takeIf { it.size >= 32 }?.copyOf(32)
        mutex.withLock { myPrivateKey = trimmed }
        if (trimmed != null) reprocessDMEntries()
    }

    /**
     * Updates the contact-public-key cache used for DM-decrypt correlation, converting each
     * Ed25519 identity key to Curve25519 for ECDH. Re-processes any recent
     * [DecryptStatus.DM_NO_MATCHING_KEY] entries once the cache is non-empty.
     */
    suspend fun updateContactPublicKeys(keysByPrefix: Map<UByte, List<ByteArray>>) {
        val converted = keysByPrefix.mapValues { (_, keys) -> keys.mapNotNull { Ed25519ToX25519.convertPublicKey(it) } }.filterValues { it.isNotEmpty() }
        mutex.withLock { contactPublicKeysByPrefix = converted }
        if (converted.isNotEmpty()) reprocessDMEntries()
    }

    /**
     * Updates the known-flood-region cache and re-resolves every retained transport-coded entry
     * against it. Ported from `updateKnownRegions(_:)`. Runs even for an empty list — matching
     * Swift's "so sticky labels can clear" reasoning — but no-ops when [regions] is unchanged from
     * last time, so a redundant call (e.g. every connect, regardless of whether the region list
     * actually moved) doesn't force a full sweep.
     */
    suspend fun updateKnownRegions(regions: List<String>) {
        val changed = mutex.withLock {
            if (knownRegions == regions) {
                false
            } else {
                knownRegions = regions
                regionScopeKeyCache = regions.mapNotNull { name -> TransportCodeRegionResolver.deriveScopeKey(name)?.let { name to it } }
                true
            }
        }
        if (changed) reprocessRegionEntries()
    }

    // MARK: - Reprocessing

    /** Re-processes recent [DecryptStatus.NO_MATCHING_KEY] entries now that fresh channel secrets are available. Reentrancy-guarded. */
    private suspend fun reprocessNoMatchingKeyEntries() {
        if (isReprocessingChannels) return
        isReprocessingChannels = true
        try {
            val radioID = radioID ?: return
            val cutoff = Instant.now().minus(REPROCESS_WINDOW)
            val entries = try {
                rxLogStore.fetchRecentEntriesByDecryptStatus(radioID, DecryptStatus.NO_MATCHING_KEY, cutoff)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return
            }
            if (entries.isEmpty()) return

            val updates = mutableListOf<RxLogDecryptionUpdate>()
            val decryptedEntries = mutableListOf<RxLogDto>()
            for (entry in entries) {
                val decrypted = decryptChannelPayload(entry.packetPayload) ?: continue
                updates.add(RxLogDecryptionUpdate(entry.id, decrypted.channelIndex, decrypted.channelName, decrypted.timestamp))
                decryptedEntries.add(
                    entry.copy(
                        channelIndex = decrypted.channelIndex,
                        channelName = decrypted.channelName,
                        decryptStatus = DecryptStatus.SUCCESS,
                        senderTimestamp = decrypted.timestamp,
                        decodedText = decrypted.text,
                    ),
                )
            }
            if (updates.isNotEmpty()) {
                rxLogStore.batchUpdateRxLogDecryption(updates)
                // Process for heard repeats after the DB update, matching Swift's ordering.
                heardRepeatProcessing?.let { processing -> decryptedEntries.forEach { processing.processForRepeats(it) } }
            }
        } finally {
            isReprocessingChannels = false
        }
    }

    /** Re-processes recent [DecryptStatus.DM_NO_MATCHING_KEY] entries now that the private key and/or contact keys are available. Reentrancy-guarded. */
    private suspend fun reprocessDMEntries() {
        if (isReprocessingDMs) return
        isReprocessingDMs = true
        try {
            val radioID = radioID ?: return
            val (privateKey, keysByPrefix) = mutex.withLock { myPrivateKey to contactPublicKeysByPrefix }
            if (privateKey == null || keysByPrefix.isEmpty()) return

            val cutoff = Instant.now().minus(REPROCESS_WINDOW)
            val entries = try {
                rxLogStore.fetchRecentEntriesByDecryptStatus(radioID, DecryptStatus.DM_NO_MATCHING_KEY, cutoff)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return
            }
            if (entries.isEmpty()) return

            val updates = mutableListOf<RxLogDecryptionUpdate>()
            for (entry in entries) {
                if (entry.packetPayload.size < 2) continue
                if (entry.routeType != RouteType.DIRECT && entry.routeType != RouteType.TC_DIRECT) continue
                val senderPrefix = entry.packetPayload[1].toUByte()
                val candidateKeys = keysByPrefix[senderPrefix] ?: continue
                val timestamp = candidateKeys.firstNotNullOfOrNull { candidateKey ->
                    DirectMessageCrypto.extractTimestamp(entry.packetPayload, privateKey, candidateKey)
                } ?: continue
                updates.add(RxLogDecryptionUpdate(entry.id, null, null, timestamp))
            }
            if (updates.isNotEmpty()) rxLogStore.batchUpdateRxLogDecryption(updates)
        } finally {
            isReprocessingDMs = false
        }
    }

    /**
     * Re-resolves every retained transport-coded entry's region against the current known-regions
     * cache, and correlates any changed result onto already-persisted messages. Ported from
     * `reprocessRegionEntries()`/`runReprocessPass(radioID:)`, simplified to this port's
     * established boolean reentrancy guard (see [reprocessNoMatchingKeyEntries]/
     * [reprocessDMEntries]) rather than Swift's dirty-flag/continuation loop: a call that arrives
     * while one is already running is simply dropped, not queued — the next successful
     * [updateKnownRegions] call (or the next connect) re-scans the *entire* retained window again
     * from scratch, so nothing is permanently missed, only delayed. Unlike
     * [reprocessNoMatchingKeyEntries]/[reprocessDMEntries], there's no decrypt-status/time-window
     * filter — every retained transport-coded entry is a candidate, since a newly-known region can
     * make an hours-old entry resolvable.
     */
    private suspend fun reprocessRegionEntries() {
        if (isReprocessingRegions) return
        isReprocessingRegions = true
        try {
            val radioID = radioID ?: return
            val cache = mutex.withLock { regionScopeKeyCache }
            val entries = try {
                rxLogStore.fetchEntriesWithTransportCode(radioID, REGION_REPROCESS_FETCH_LIMIT)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return
            }
            if (entries.isEmpty()) return

            val rxLogUpdates = mutableListOf<RxLogRegionUpdate>()
            val changedEntries = mutableListOf<RxLogDto>()
            for (entry in entries) {
                val fields = resolveRegionStorage(cache, entry.transportCode, entry.payloadTypeBits, entry.packetPayload)
                if (fields.regionScope == entry.regionScope && fields.regionScopeMatches == entry.regionScopeMatches) continue
                rxLogUpdates.add(RxLogRegionUpdate(entry.id, fields.regionScope, fields.regionScopeMatches))
                changedEntries.add(entry.copy(regionScope = fields.regionScope, regionScopeMatches = fields.regionScopeMatches))
            }
            if (rxLogUpdates.isEmpty()) return
            rxLogStore.batchUpdateRxLogRegion(rxLogUpdates)

            val messageStore = messageStore ?: return
            var touchedAny = false
            for (entry in changedEntries) {
                val senderTimestamp = entry.senderTimestamp ?: continue
                try {
                    val touched = when {
                        entry.channelIndex != null ->
                            messageStore.batchUpdateChannelMessageRegion(radioID, entry.channelIndex, senderTimestamp, entry.regionScope, entry.regionScopeMatches)
                        entry.payloadType == PayloadType.TEXT_MESSAGE && entry.packetPayload.size >= 2 ->
                            messageStore.batchUpdateDMMessageRegion(radioID, entry.packetPayload[1].toUByte(), senderTimestamp, entry.regionScope, entry.regionScopeMatches)
                        else -> emptyList()
                    }
                    if (touched.isNotEmpty()) touchedAny = true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Best-effort — a missed correlation just means one message keeps a stale/absent region until the next sweep.
                }
            }
            if (touchedAny) regionUpdateFlow.emit(Unit)
        } finally {
            isReprocessingRegions = false
        }
    }

    /**
     * Shared region-match logic for [process] (live receive) and [reprocessRegionEntries]
     * (backfill), replaying [TransportCodeRegionResolver.matchRegions] against [cache]. A `null`/
     * short [transportCode] or an empty cache both short-circuit to "no match" without doing any
     * HMACs, matching Swift's `resolveRegionStorage`.
     */
    private fun resolveRegionStorage(
        cache: List<Pair<String, ByteArray>>,
        transportCode: ByteArray?,
        payloadTypeBits: UByte,
        payload: ByteArray,
    ): RegionScopeSemantics.StorageFields {
        if (transportCode == null || transportCode.size < 2 || cache.isEmpty()) return RegionScopeSemantics.StorageFields(null, emptyList())
        val code0 = transportCode.readUInt16LE(0)
        val match = TransportCodeRegionResolver.matchRegions(cache, code0, payloadTypeBits, payload)
        return RegionScopeSemantics.storageFields(match)
    }

    // MARK: - Processing

    /** Processes one parsed RX-log event: best-effort decrypt, persist, prune. */
    suspend fun process(parsed: ParsedRxLogData) {
        val radioID = radioID ?: return

        var channelIndex: UByte? = null
        var channelName: String? = null
        var decryptStatus = DecryptStatus.NOT_APPLICABLE
        var senderTimestamp: UInt? = null
        var decodedText: String? = null

        if (parsed.payloadType == PayloadType.GROUP_TEXT || parsed.payloadType == PayloadType.GROUP_DATA) {
            val decrypted = decryptChannelPayload(parsed.packetPayload)
            if (decrypted != null) {
                channelIndex = decrypted.channelIndex
                channelName = decrypted.channelName
                senderTimestamp = decrypted.timestamp
                decodedText = decrypted.text
                decryptStatus = DecryptStatus.SUCCESS
            } else if (parsed.packetPayload.size >= 1 + ChannelCrypto.MAC_SIZE + 16) {
                decryptStatus = DecryptStatus.NO_MATCHING_KEY
            } else {
                decryptStatus = DecryptStatus.PENDING
            }
        }

        if (parsed.payloadType == PayloadType.TEXT_MESSAGE && (parsed.routeType == RouteType.DIRECT || parsed.routeType == RouteType.TC_DIRECT)) {
            val decrypted = decryptDMPayload(parsed.packetPayload)
            if (decrypted != null) {
                senderTimestamp = decrypted.timestamp
                decodedText = decrypted.text
                decryptStatus = DecryptStatus.SUCCESS
            } else {
                decryptStatus = DecryptStatus.DM_NO_MATCHING_KEY
            }
        }

        // Only flood-routed adverts accumulate a hop path; a direct-routed advert's path length
        // is the remaining route, not hops traversed, so it's excluded. A reserved or truncated
        // encoding skips the write rather than stamping a wrong value.
        if (parsed.payloadType == PayloadType.ADVERT && parsed.routeType.isFlood) {
            val payload = parsed.packetPayload
            val inboundHops = decodePathLen(parsed.pathLength)?.hopCount
            if (payload.size >= PacketBuilder.PUBLIC_KEY_SIZE && inboundHops != null) {
                val advertiserPubKey = payload.copyOfRange(0, PacketBuilder.PUBLIC_KEY_SIZE)
                val advertTimestamp = if (payload.size >= PacketBuilder.PUBLIC_KEY_SIZE + 4) {
                    payload.readUInt32LE(PacketBuilder.PUBLIC_KEY_SIZE)
                } else {
                    null
                }
                try {
                    discoveredNodeStore.setInboundHopCount(radioID, advertiserPubKey, inboundHops, advertTimestamp)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Best-effort hop stamp.
                }
            }
        }

        val regionFields = if (parsed.routeType.hasTransportCode) {
            resolveRegionStorage(mutex.withLock { regionScopeKeyCache }, parsed.transportCode, parsed.payloadTypeBits, parsed.packetPayload)
        } else {
            RegionScopeSemantics.StorageFields(null, emptyList())
        }

        val dto = parsed.toRxLogDto(
            radioID = radioID,
            channelIndex = channelIndex,
            channelName = channelName,
            decryptStatus = decryptStatus,
            senderTimestamp = senderTimestamp,
            regionScope = regionFields.regionScope,
            regionScopeMatches = regionFields.regionScopeMatches,
            decodedText = decodedText,
        )

        try {
            // Pruning is batched into RxLogStore's own flush, not run per packet — see its class doc.
            rxLogStore.saveRxLogEntry(dto)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort persistence; a dropped entry just means one fewer correlation candidate.
        }

        // Emit to stream consumers (the RX Log viewer) — matching Swift's ordering (right after
        // persist+prune, before heard-repeats processing).
        entryFlow.emit(dto)

        // Process for heard repeats (inline await provides natural backpressure, preventing
        // unbounded work accumulation under high RX volume) — matching Swift's ordering (after
        // persist+prune, unconditionally; [HeardRepeatProcessing] does its own payload-type/
        // decrypt-status/decodedText guards).
        heardRepeatProcessing?.processForRepeats(dto)
    }

    /**
     * Loads the persisted tail (newest first) and re-runs [decryptEntry] over each row so
     * [RxLogDto.decodedText] — transient, never persisted — is populated for display. Ported
     * from `loadExistingEntries()`.
     */
    suspend fun loadExistingEntries(): List<RxLogDto> {
        val radioID = radioID ?: return emptyList()
        return try {
            rxLogStore.fetchEntries(radioID).map { decryptEntry(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            emptyList()
        }
    }

    /**
     * Re-derives [RxLogDto.decodedText] (and, for a channel message, the channel attribution)
     * against the current secret caches — the display-time counterpart to [process]'s persist-time
     * decrypt, since decrypted text is never persisted. Ported from `decryptEntry(_:)`.
     */
    suspend fun decryptEntry(entry: RxLogDto): RxLogDto {
        if (entry.payloadType == PayloadType.TEXT_MESSAGE && (entry.routeType == RouteType.DIRECT || entry.routeType == RouteType.TC_DIRECT)) {
            val decrypted = decryptDMPayload(entry.packetPayload) ?: return entry
            return entry.copy(senderTimestamp = decrypted.timestamp, decodedText = decrypted.text)
        }
        if (entry.payloadType == PayloadType.GROUP_TEXT || entry.payloadType == PayloadType.GROUP_DATA) {
            val decrypted = decryptChannelPayload(entry.packetPayload) ?: return entry
            return entry.copy(
                channelIndex = decrypted.channelIndex,
                channelName = decrypted.channelName,
                senderTimestamp = decrypted.timestamp,
                decodedText = decrypted.text,
            )
        }
        return entry
    }

    /** Deletes every entry for the active connection's [radioID]. Ported from the viewer's "clear log" action. */
    suspend fun clearEntries() {
        radioID?.let { rxLogStore.clearRxLogEntries(it) }
    }

    /** Result of a successful channel-payload decrypt: enough to persist and correlate, plus the plaintext [HeardRepeatProcessing] needs to match a sent message (never persisted — see [RxLogDto.decodedText]'s doc). */
    private data class DecryptedChannelPayload(val channelIndex: UByte, val channelName: String, val timestamp: UInt, val text: String)

    /**
     * Tries every cached channel secret against a group-text/data payload
     * (`[channelHash:1][MAC:2][ciphertext:N]`) until one validates. The leading byte is a
     * truncated hash, not the slot index, so there's no shortcut — every secret must be tried.
     */
    private suspend fun decryptChannelPayload(payload: ByteArray): DecryptedChannelPayload? {
        if (payload.size < 1 + ChannelCrypto.MAC_SIZE + 16) return null
        val encryptedPayload = payload.copyOfRange(1, payload.size)
        val secrets = mutex.withLock { channelSecrets }
        val names = mutex.withLock { channelNames }
        for ((index, secret) in secrets) {
            val result = ChannelCrypto.decrypt(encryptedPayload, secret)
            if (result is ChannelCrypto.DecryptResult.Success) {
                return DecryptedChannelPayload(index, names[index] ?: "Channel $index", result.timestamp, result.text)
            }
        }
        return null
    }

    /**
     * Tries every candidate contact public key for the DM payload's sender-prefix byte. Returns
     * the full decrypt result (timestamp + text) so [decryptEntry] can surface the plaintext for
     * display; [process] uses only the timestamp for correlation, matching Swift's
     * `tryDecryptDM`/`extractTimestamp` split.
     */
    private suspend fun decryptDMPayload(payload: ByteArray): DirectMessageCrypto.DecryptResult.Success? {
        if (payload.size < DirectMessageCrypto.MIN_PACKET_SIZE) return null
        val senderPrefix = payload[1].toUByte()
        val privateKey = mutex.withLock { myPrivateKey } ?: return null
        val candidateKeys = mutex.withLock { contactPublicKeysByPrefix[senderPrefix] } ?: return null
        return candidateKeys.firstNotNullOfOrNull { candidateKey ->
            DirectMessageCrypto.decrypt(payload, privateKey, candidateKey) as? DirectMessageCrypto.DecryptResult.Success
        }
    }

    // MARK: - RxLogCorrelating

    override suspend fun lookupPathData(
        radioID: UUID,
        channelIndex: UByte?,
        senderTimestamp: UInt,
        senderPublicKeyPrefix: ByteArray?,
        defaultPathLength: UByte,
        channelDeduplicationKey: String?,
    ): RxLogPathData {
        val uncorrelated = RxLogPathData(pathNodes = null, pathLength = defaultPathLength, routeType = null)
        try {
            if (channelIndex != null && channelDeduplicationKey != null) {
                val matched = ChannelRXCorrelation.matching(
                    decodedChannelEntries(radioID, channelIndex, senderTimestamp),
                    channelDeduplicationKey,
                ).firstOrNull()
                return if (matched != null) {
                    RxLogPathData(
                        pathNodes = matched.pathNodes,
                        pathLength = matched.pathLength,
                        routeType = matched.routeType,
                        regionScope = matched.regionScope,
                        regionScopeMatches = matched.regionScopeMatches,
                    )
                } else {
                    // Deliberately no fall-through to findRxLogEntry: the newest row sharing
                    // (channelIndex, senderTimestamp) whose plaintext did *not* match is, by
                    // definition, some other message's packet. Swift returns uncorrelated here too.
                    uncorrelated
                }
            }

            val primary = rxLogStore.findRxLogEntry(radioID, channelIndex, senderTimestamp)
            if (primary != null) {
                return RxLogPathData(
                    pathNodes = primary.pathNodes,
                    pathLength = primary.pathLength,
                    routeType = primary.routeType,
                    regionScope = primary.regionScope,
                    regionScopeMatches = primary.regionScopeMatches,
                )
            }

            // DM fallback: the primary timestamp-based lookup requires RX-log decryption to have
            // already extracted the sender timestamp, which may not have happened yet — match by
            // the unencrypted sender-prefix byte within a recent receive-time window instead.
            if (channelIndex == null && senderPublicKeyPrefix != null && senderPublicKeyPrefix.isNotEmpty()) {
                val fallback = rxLogStore.findRxLogEntryBySenderPrefix(radioID, senderPublicKeyPrefix[0].toUByte(), Instant.now().minus(DM_FALLBACK_WINDOW))
                if (fallback != null) {
                    return RxLogPathData(
                        pathNodes = fallback.pathNodes,
                        pathLength = fallback.pathLength,
                        routeType = fallback.routeType,
                        regionScope = fallback.regionScope,
                        regionScopeMatches = fallback.regionScopeMatches,
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return uncorrelated
        }
        return uncorrelated
    }

    override suspend fun decodedChannelEntries(radioID: UUID, channelIndex: UByte, senderTimestamp: UInt): List<RxLogDto> = try {
        rxLogStore.fetchRxLogEntries(radioID, channelIndex, senderTimestamp).map { decryptEntry(it) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        emptyList()
    }

    companion object {
        /** How far back a reprocess sweep looks for entries that failed decryption due to missing keys, matching Swift's `Date().addingTimeInterval(-60)`. */
        private val REPROCESS_WINDOW: Duration = Duration.ofSeconds(60)

        /** DM sender-prefix fallback receive-time window, matching Swift's `Date().addingTimeInterval(-30)`. */
        private val DM_FALLBACK_WINDOW: Duration = Duration.ofSeconds(30)

        /** Region-reprocess sweep's candidate-fetch bound, matching Swift's `regionReprocessFetchLimit` (retention `keepCount` + `pruneThreshold`). */
        private const val REGION_REPROCESS_FETCH_LIMIT: Int = RxLogRetention.KEEP_COUNT + RxLogRetention.PRUNE_THRESHOLD
    }
}
