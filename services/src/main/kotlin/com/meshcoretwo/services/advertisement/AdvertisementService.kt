// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.advertisement

import com.meshcoretwo.protocol.AdvertisementSessionOps
import com.meshcoretwo.protocol.EventFilter
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.DeviceStore
import com.meshcoretwo.services.persistence.DiscoveredNodeStore
import com.meshcoretwo.services.persistence.toMeshContact
import com.meshcoretwo.services.utilities.VContactIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Listens for the device's own contact-table change notifications (advertisements, path
 * updates, auto-deletions, storage-full) and drives a debounced, coalesced incremental contact
 * re-sync — plus a small outbound self-advertisement facade. Ported from
 * `AdvertisementService.swift` + `AdvertisementService+DeltaSync.swift`, merged into one file (at
 * ~350 Kotlin lines the Swift split for a 1129-line type isn't needed) and trimmed to an MVP: the
 * radio parses adverts and mutates its own contact table itself — this service only reacts to
 * *change notifications* and decides when to re-fetch, it never parses an advert packet itself.
 *
 * `.newContact` (0x8A, manual-add-mode notifications) upserts a Discover row directly
 * ([handleNewAdvertEvent]); a successful delta-sync round also refreshes one per drained key in
 * [reconcile], mirroring Swift's `reconcile()`. Inbound hop-count stamping
 * (`setInboundHopCount`) lives in `RxLogService`, not here — it comes from RX-log advert packets,
 * not this service's change-notification events (matches Swift's split too).
 *
 * **Deferred, not yet ported** (tracked in PLAN.md's Phase 3 status):
 * - `.pathResponse`/`.traceData`/trace-flavored `.rxLogData` (TracePath UI) — not this port's
 *   event monitoring scope; `TracePathService`/`BinaryProtocolService` own that path independently.
 * - `adoptOrphanedDirectMessages` and its `ConversationsChanged`/`OrphanDirectMessagesAdopted`
 *   events — its unread/mention/blocked/reaction dependencies are all already deferred elsewhere
 *   in this port. [materializeContactForPendingAdvert] covers the *live* case (a DM arriving
 *   inside the debounce window); this only rescues DMs that arrived before the app knew the key.
 * - The escalation heuristics (`escalateMissingUnknownKeys`, `escalateUndeliveredPathUpdates`,
 *   `escalateToFullRefetch`) that recover from a radio-RTC/watermark skew edge case this port
 *   can't yet reproduce — the delta-sync handler here is always called as a plain incremental
 *   fetch, with no `fullRefetch` flag plumbed through.
 * - Mid-round delete rollback (`contactsDeletedDuringSync` and its rollback) — pure hardening
 *   against a concurrent full/manual sync racing this one, which only matters once
 *   `SyncCoordinator` exists to run one. A 0x8F arriving mid-round can therefore have its local
 *   delete "resurrected" by an in-flight incremental save; narrow window, matches no SyncCoordinator existing yet.
 * - `deltaSyncGeneration` — Swift needs it because a cancelled `Task` can still be racing a new
 *   one; Kotlin structured concurrency's `Job?.cancel()` on a single nullable field is sufficient.
 * - The 5-attempt failure budget (`maxConsecutiveDeltaSyncFailures`) is kept, but Swift's
 *   busy-specific backoff constant is applied directly rather than via a second scheduling path.
 */
class AdvertisementService(
    private val session: AdvertisementSessionOps,
    private val contactStore: ContactStore,
    private val deviceStore: DeviceStore,
    private val discoveredNodeStore: DiscoveredNodeStore,
    private val debounceMs: Long = 5_000,
    private val minIntervalMs: Long = 30_000,
    private val busyBackoffMs: Long = 5_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private var currentRadioID: UUID? = null
    private var eventListenerJob: Job? = null
    private var deltaSyncJob: Job? = null
    private var deltaSyncHandler: (suspend () -> AdvertContactSyncOutcome)? = null
    private var isSyncingContacts = false
    private var lastDeltaSyncEnd: Instant? = null
    private var consecutiveDeltaSyncFailures = 0

    private val pendingAdvertKeys = mutableSetOf<List<Byte>>()
    private val pendingPathKeys = mutableSetOf<List<Byte>>()
    private val pendingAdvertReceiveTimes = mutableMapOf<List<Byte>, Instant>()

    private val eventsFlow = MutableSharedFlow<AdvertisementEvent>(extraBufferCapacity = 64)

    /** A multicast stream of advertisement/discovery notifications; every subscriber sees every event. */
    fun events(): Flow<AdvertisementEvent> = eventsFlow

    // MARK: - Outbound Facade

    /** Broadcasts a self-advertisement. */
    suspend fun sendSelfAdvertisement(flood: Boolean = false) = wrapSessionError { session.sendAdvertisement(flood) }

    /** Sets the device's advertised name. */
    suspend fun setAdvertName(name: String) = wrapSessionError { session.setName(name) }

    /** Sets the device's advertised GPS coordinates. */
    suspend fun setAdvertLocation(latitude: Double, longitude: Double) = wrapSessionError { session.setCoordinates(latitude, longitude) }

    private suspend fun wrapSessionError(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: MeshCoreError) {
            throw AdvertisementError.SessionError(error)
        }
    }

    // MARK: - Delta Sync Wiring

    /** Installs the handler a debounced delta-sync round invokes to actually re-fetch contacts (e.g. `contactService.syncContacts(radioID, since)`). */
    fun setDeltaSyncHandler(handler: suspend () -> AdvertContactSyncOutcome) {
        deltaSyncHandler = handler
    }

    /**
     * Marks whether a full/manual contact sync is in progress elsewhere. While `true`, a pending
     * delta-sync round is deferred without spending the failure budget; flipping back to `false`
     * resumes any work that piled up in the meantime.
     */
    suspend fun setSyncingContacts(syncing: Boolean) {
        val shouldResume = mutex.withLock {
            isSyncingContacts = syncing
            !syncing && (pendingAdvertKeys.isNotEmpty() || pendingPathKeys.isNotEmpty())
        }
        if (shouldResume) scheduleDeltaSync()
    }

    // MARK: - Event Monitoring

    /** Starts monitoring device change-notification events for [radioID]. */
    fun startEventMonitoring(radioID: UUID) {
        eventListenerJob?.cancel()
        currentRadioID = radioID
        val filter = EventFilter { event ->
            event is MeshEvent.Advertisement ||
                event is MeshEvent.NewContact ||
                event is MeshEvent.PathUpdate ||
                event is MeshEvent.ContactDeleted ||
                event is MeshEvent.ContactsFull
        }
        eventListenerJob = scope.launch {
            session.events(filter).collect { event -> handleEvent(event, radioID) }
        }
    }

    /** Stops event monitoring and any in-flight delta-sync scheduling, clearing per-connection pending state. */
    suspend fun stopEventMonitoring() {
        eventListenerJob?.cancelAndJoin()
        eventListenerJob = null
        deltaSyncJob?.cancelAndJoin()
        currentRadioID = null
        mutex.withLock {
            deltaSyncJob = null
            pendingAdvertKeys.clear()
            pendingPathKeys.clear()
            pendingAdvertReceiveTimes.clear()
        }
    }

    private suspend fun handleEvent(event: MeshEvent, radioID: UUID) {
        when (event) {
            is MeshEvent.Advertisement -> handleAdvertEvent(event.publicKey, radioID)
            is MeshEvent.NewContact -> handleNewAdvertEvent(event.contact, radioID)
            is MeshEvent.PathUpdate -> handlePathUpdateEvent(event.publicKey)
            is MeshEvent.ContactDeleted -> handleContactDeletedEvent(event.publicKey, radioID)
            is MeshEvent.ContactsFull -> handleContactsFullEvent()
            else -> {}
        }
    }

    /** 0x80 change notification: the radio already updated a contact row. */
    private suspend fun handleAdvertEvent(publicKey: ByteArray, radioID: UUID) {
        val receivedAt = Instant.now()
        val known = touchContactHeardWithRetry(radioID, publicKey, receivedAt)
        if (known != null) {
            if (known) eventsFlow.emit(AdvertisementEvent.ContactUpdated)
            recordPendingAdvertKey(publicKey, receivedAt)
        }
        scheduleDeltaSync()
    }

    /**
     * Retries [ContactStore.touchContactHeard] once: a transient store error must not
     * permanently drop the advert, but recording without a successful touch is also wrong (it
     * can announce a long-known contact as new). Returns `null` if both attempts fail.
     */
    private suspend fun touchContactHeardWithRetry(radioID: UUID, publicKey: ByteArray, at: Instant): Boolean? {
        repeat(2) { attempt ->
            try {
                return contactStore.touchContactHeard(radioID, publicKey, at)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (attempt == 1) return null
            }
        }
        return null
    }

    /** Records a 0x80 key for the next delta-sync round. */
    private suspend fun recordPendingAdvertKey(publicKey: ByteArray, receivedAt: Instant) {
        val key = publicKey.toList()
        mutex.withLock {
            pendingAdvertKeys.add(key)
            pendingAdvertReceiveTimes[key] = receivedAt
        }
    }

    /**
     * 0x8A: manual-add-mode new-contact notification. Upserts (or refreshes) the contact's
     * Discover row directly and announces first-time discoveries — independent of the delta-sync
     * path, which only reconciles Discover rows for contacts synced via [handleAdvertEvent]'s 0x80.
     */
    private suspend fun handleNewAdvertEvent(contact: MeshContact, radioID: UUID) {
        try {
            val (node, isNew) = discoveredNodeStore.upsertDiscoveredNode(radioID, contact)
            eventsFlow.emit(AdvertisementEvent.ContactUpdated)
            if (isNew) {
                eventsFlow.emit(AdvertisementEvent.NewContactDiscovered(node.name, node.id, node.nodeType))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort Discover upsert; a dropped 0x8A just means the row waits for the next advert.
        }
    }

    /**
     * 0x81: path changed on the radio. Recorded separately from advert keys — a path change is
     * not on-air heard evidence, so [handleAdvertEvent]'s `touchContactHeard` doesn't run here.
     */
    private suspend fun handlePathUpdateEvent(publicKey: ByteArray) {
        mutex.withLock { pendingPathKeys.add(publicKey.toList()) }
        scheduleDeltaSync()
    }

    /** 0x8F: the device auto-deleted a contact (overwrite-oldest). */
    private suspend fun handleContactDeletedEvent(publicKey: ByteArray, radioID: UUID) {
        val selfPublicKey = deviceStore.fetchDeviceByRadioId(radioID)?.publicKey
        if (selfPublicKey != null && VContactIdentity.isVContact(publicKey, selfPublicKey)) {
            // ZephCore CLI `set v.contact off` also pushes 0x8F for the V-key; that's not
            // overwrite-oldest (no slot freed) — preserve the local row, don't touch storage-full.
            return
        }

        val key = publicKey.toList()
        mutex.withLock {
            pendingAdvertKeys.remove(key)
            pendingPathKeys.remove(key)
            pendingAdvertReceiveTimes.remove(key)
        }

        val contact = contactStore.fetchContact(radioID, publicKey) ?: return
        contactStore.deleteContact(contact.id)
        eventsFlow.emit(AdvertisementEvent.ContactDeletedCleanup(contact.id, publicKey))
        eventsFlow.emit(AdvertisementEvent.NodeStorageFullChanged(isFull = false))
        eventsFlow.emit(AdvertisementEvent.ContactUpdated)
    }

    /** 0x90: device node storage is full. */
    private suspend fun handleContactsFullEvent() {
        eventsFlow.emit(AdvertisementEvent.NodeStorageFullChanged(isFull = true))
    }

    // MARK: - Pending-Advert Materialization

    /**
     * Creates a local contact from a pending 0x80 key so a DM that arrives inside the
     * advert delta-sync debounce window can be attributed to a real contact instead of persisting
     * orphaned. Matches [prefix] against [pendingAdvertKeys] only (keys the radio already
     * auto-added); exactly one match is required, an ambiguous or absent prefix returns `null`.
     * The key stays pending so the debounced round still reconciles normally.
     */
    suspend fun materializeContactForPendingAdvert(prefix: ByteArray, radioID: UUID): ContactDto? {
        if (prefix.isEmpty()) return null
        val match = mutex.withLock {
            pendingAdvertKeys.singleOrNull { it.size >= prefix.size && it.subList(0, prefix.size) == prefix.toList() }
        } ?: return null

        return try {
            val meshContact = session.getContact(match.toByteArray()) ?: return null
            val (id, _) = contactStore.saveContact(radioID, meshContact)
            val receivedAt = mutex.withLock { pendingAdvertReceiveTimes[match] } ?: Instant.now()
            try {
                contactStore.touchContactHeard(radioID, match.toByteArray(), receivedAt)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Best-effort recency stamp; the contact row itself already saved successfully.
            }
            try {
                discoveredNodeStore.upsertDiscoveredNode(radioID, meshContact)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Best-effort Discover upsert; the contact row itself already saved successfully.
            }
            eventsFlow.emit(AdvertisementEvent.ContactUpdated)
            contactStore.fetchContact(id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    // MARK: - Delta Sync

    private suspend fun scheduleDeltaSync(baseDelayMs: Long = debounceMs) {
        mutex.withLock {
            if (deltaSyncJob != null) return@withLock
            var delayMs = baseDelayMs
            lastDeltaSyncEnd?.let {
                val untilMinInterval = Duration.between(Instant.now(), it.plusMillis(minIntervalMs)).toMillis()
                delayMs = maxOf(delayMs, untilMinInterval)
            }
            deltaSyncJob = scope.launch {
                delay(delayMs.coerceAtLeast(0))
                runDeltaSync()
            }
        }
    }

    private suspend fun runDeltaSync() {
        val handler = deltaSyncHandler
        if (handler == null) {
            mutex.withLock { deltaSyncJob = null }
            return
        }

        var drained: Set<List<Byte>>
        var drainedReceiveTimes: Map<List<Byte>, Instant>
        var drainedPathKeys: Set<List<Byte>>
        var radioID: UUID?

        mutex.withLock {
            if (isSyncingContacts) {
                deltaSyncJob = null
                return
            }
            if (pendingAdvertKeys.isEmpty() && pendingPathKeys.isEmpty()) {
                deltaSyncJob = null
                return
            }
            drained = pendingAdvertKeys.toSet()
            pendingAdvertKeys.clear()
            drainedReceiveTimes = drained.mapNotNull { key -> pendingAdvertReceiveTimes.remove(key)?.let { key to it } }.toMap()
            drainedPathKeys = pendingPathKeys.toSet()
            pendingPathKeys.clear()
            radioID = currentRadioID
        }

        val preRoundKnownKeys = radioID?.let { contactStore.fetchContactPublicKeys(it) }

        val outcome = handler()

        when (outcome) {
            AdvertContactSyncOutcome.BUSY -> {
                mutex.withLock {
                    pendingAdvertKeys.addAll(drained)
                    pendingAdvertReceiveTimes.putAll(drainedReceiveTimes)
                    pendingPathKeys.addAll(drainedPathKeys)
                    deltaSyncJob = null
                }
                scheduleDeltaSync(busyBackoffMs)
            }
            AdvertContactSyncOutcome.NOT_READY -> {
                mutex.withLock { deltaSyncJob = null }
            }
            AdvertContactSyncOutcome.FAILED -> {
                val shouldDrop = mutex.withLock {
                    consecutiveDeltaSyncFailures++
                    lastDeltaSyncEnd = Instant.now()
                    deltaSyncJob = null
                    consecutiveDeltaSyncFailures >= MAX_CONSECUTIVE_DELTA_SYNC_FAILURES
                }
                if (shouldDrop) {
                    mutex.withLock {
                        consecutiveDeltaSyncFailures = 0
                        pendingPathKeys.addAll(drainedPathKeys)
                    }
                } else {
                    mutex.withLock {
                        pendingAdvertKeys.addAll(drained)
                        pendingAdvertReceiveTimes.putAll(drainedReceiveTimes)
                        pendingPathKeys.addAll(drainedPathKeys)
                    }
                }
                scheduleDeltaSync()
            }
            AdvertContactSyncOutcome.SYNCED -> {
                mutex.withLock {
                    consecutiveDeltaSyncFailures = 0
                    lastDeltaSyncEnd = Instant.now()
                    deltaSyncJob = null
                }
                radioID?.let { rid -> reconcile(rid, drained, drainedReceiveTimes, preRoundKnownKeys) }
                val hasMoreWork = mutex.withLock { pendingAdvertKeys.isNotEmpty() || pendingPathKeys.isNotEmpty() }
                if (hasMoreWork) scheduleDeltaSync()
            }
        }
    }

    /**
     * Post-sync bookkeeping: stamps drained keys' `lastHeardTimestamp` now that a round has run
     * (a key discovered mid-round may not have had a row yet when the advert first arrived),
     * refreshes each drained key's Discover row from its now-current contact record, and emits
     * [AdvertisementEvent.NewContactDiscovered] for keys the pre-round snapshot proves are new. A
     * failed pre-round snapshot ([preRoundKnownKeys] `null`) suppresses new-contact notifications
     * for this round rather than risk a false one. Ported from `reconcile(_:insertedKeys:)`
     * (`AdvertisementService+DeltaSync.swift`).
     */
    private suspend fun reconcile(
        radioID: UUID,
        drained: Set<List<Byte>>,
        drainedReceiveTimes: Map<List<Byte>, Instant>,
        preRoundKnownKeys: Set<List<Byte>>?,
    ) {
        val insertedKeys = if (preRoundKnownKeys != null) drained - preRoundKnownKeys else emptySet()

        for (key in drained) {
            val receivedAt = drainedReceiveTimes[key] ?: Instant.now()
            try {
                contactStore.touchContactHeard(radioID, key.toByteArray(), receivedAt)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Best-effort recency stamp.
            }

            val contact = contactStore.fetchContact(radioID, key.toByteArray()) ?: continue
            try {
                discoveredNodeStore.upsertDiscoveredNode(radioID, contact.toMeshContact())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Best-effort Discover refresh.
            }

            if (key in insertedKeys) {
                eventsFlow.emit(AdvertisementEvent.NewContactDiscovered(contact.displayName, contact.id, contact.type))
            }
        }
        eventsFlow.emit(AdvertisementEvent.ContactUpdated)
    }

    companion object {
        /** Failed delta syncs tolerated before a round drops its drained advert keys. */
        private const val MAX_CONSECUTIVE_DELTA_SYNC_FAILURES = 5
    }
}
