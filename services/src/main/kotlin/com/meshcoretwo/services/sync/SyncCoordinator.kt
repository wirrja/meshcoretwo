// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sync

import android.util.Log
import com.meshcoretwo.services.advertisement.AdvertisementEvent
import com.meshcoretwo.services.channels.ChannelService
import com.meshcoretwo.services.channels.ChannelSyncResult
import com.meshcoretwo.services.contacts.ContactSyncResult
import com.meshcoretwo.services.notifications.NotificationService
import com.meshcoretwo.services.utilities.VContactIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates data synchronization between a MeshCore device and local storage: full sync
 * (contacts → channels → messages) on connect, and ongoing contact-discovery notifications.
 * Ported from `SyncCoordinator.swift` + `SyncCoordinator+Sync.swift`, scoped to what a working
 * "connect → sync → ready" path needs — see the "Deferred" list below for what a real
 * `~7300`-line PLAN.md estimate turned out to already be ported under other names, and what
 * genuinely remains for a later, smaller follow-up slice (tracked in PLAN.md's Phase 3 status).
 *
 * **Deferred, not yet ported:**
 * - **Advert-driven delta-sync** (`waitForAdvertContactSync`/`claimManualContactSync`/
 *   `performAdvertContactSync`/the whole `CheckedContinuation`-based waiter queue) — needs
 *   `AdvertisementService.setDeltaSyncHandler` to carry a `fullRefetch` parameter, which this
 *   port's `AdvertisementService.kt` doesn't have yet (a deliberate, separate follow-up: changing
 *   that signature has its own test-surface impact). Without it, [isSyncInProgress] is a plain
 *   `AtomicBoolean` claim (matching [com.meshcoretwo.services.channels.ChannelService.isSyncing]'s
 *   `compareAndSet` reasoning) rather than a claim-or-wait mechanism.
 * - **The resync retry loop itself** (`ConnectionManager+SyncRetry.swift`'s `startResyncLoop`/
 *   `scheduleChannelOnlyRetry` Task bookkeeping, backoff, and `resyncAttemptCount`) — belongs to
 *   `ConnectionManager` (the "core `ConnectionManager`" slice), the only place that can hold
 *   per-connection retry-loop state. [performFullSync] doubles as Swift's `performResync` (this
 *   port never split full-sync into a separate resync body — see [runFullSync]'s doc), and
 *   [retryChannels] is the one piece of `scheduleChannelOnlyRetry` that touches sync state
 *   ([onCleanChannelSync]) rather than pure Task/backoff bookkeeping.
 * - **CLI (`RoomAdminService`/`RepeaterAdminService`) and signed-room (`RoomServerService`)
 *   message routing** — `wireSignedMessageHandler`/`wireCLIMessageHandler`'s Swift responsibility.
 *   Not this class's job even in Swift terms once handler-wiring moved to construction time (see
 *   [SyncDependencies]'s doc); genuinely unrouted regardless, tracked on
 *   [com.meshcoretwo.services.messages.IncomingMessageService]'s class doc.
 * - **`onSyncActivityStarted`/`onSyncActivityEnded`/`onPhaseChanged`/`setSyncActivityCallbacks`** —
 *   Swift's SwiftUI "syncing pill" callback surface. [state] (a [StateFlow]) already carries the
 *   same information for a Compose consumer to observe directly — no callback multiplexing layer
 *   needed on top, matching this port's Flow/StateFlow-over-callbacks convention.
 * - **`refreshBlockedContactsCache`/`isBlockedSender`/`blockedSenderNames`/`deleteBlockedSenderMessages`**
 *   — superseded by [com.meshcoretwo.services.persistence.ContactStore.isBlockedSender]'s direct
 *   (non-cached) query, added in the "mention-count/blocked-sender backfill" slice.
 * - **`orphanDirectMessagesAdopted` handling** inside discovery-event monitoring — this port's
 *   [AdvertisementEvent] has no equivalent case (`OrphanDirectMessagesAdopted`, orphan-DM adoption
 *   itself isn't ported — see `AdvertisementService.kt`'s class doc), so [startDiscoveryEventMonitoring]
 *   only handles [AdvertisementEvent.NewContactDiscovered].
 * - **Post-sync channel diagnostics logging** (`logPostSyncChannelDiagnostics`) — pure logging,
 *   no persisted state; this port's business-logic services generally skip verbose logging (see
 *   e.g. [com.meshcoretwo.services.contacts.ContactService], [com.meshcoretwo.services.channels.ChannelService]).
 * - **`waitForPendingHandlers`/handler-drain wait** — already dropped on the
 *   [com.meshcoretwo.services.messages.IncomingMessageService] side (see its class doc); nothing
 *   here waits for it either.
 * - **`AppStateProvider`** itself doesn't exist as a type — [SyncDependencies.appStateProvider] is
 *   a plain nullable suspend lambda instead, same shape, no foreground/background Android
 *   lifecycle wiring exists yet to call it from.
 *
 * **`isSyncInProgress` is an [AtomicBoolean]**, not a `Mutex` — same `compareAndSet`
 * claim-or-reject reasoning as [com.meshcoretwo.services.channels.ChannelService.isSyncing].
 *
 * **`state`/`contactsVersion`/`conversationsVersion` are [StateFlow]s**, not Swift's `@MainActor
 * private(set) var` trio — Compose observes `StateFlow` directly; there is no analogue of
 * SwiftUI's `@Observable` macro to drive here.
 */
class SyncCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isSyncInProgress = AtomicBoolean(false)

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _contactsVersion = MutableStateFlow(0)
    val contactsVersion: StateFlow<Int> = _contactsVersion.asStateFlow()

    private val _conversationsVersion = MutableStateFlow(0)
    val conversationsVersion: StateFlow<Int> = _conversationsVersion.asStateFlow()

    var lastSyncDate: Instant? = null
        private set

    /** Called when channel sync completes with zero errors (including retries). For a future `ConnectionManager`'s smart-resync tracking. */
    var onCleanChannelSync: (suspend (radioID: UUID) -> Unit)? = null

    /** Called when a channel sync attempt starts, clean or partial. For a future `ConnectionManager`'s retry cooldown. */
    var onChannelSyncAttempted: (suspend (radioID: UUID) -> Unit)? = null

    private val dataEventsFlow = MutableSharedFlow<SyncDataEvent>(extraBufferCapacity = 64)

    /** A multicast stream of contacts/conversations-changed events — see [SyncDataEvent]'s doc for scope. */
    fun dataEvents(): Flow<SyncDataEvent> = dataEventsFlow

    /** Radio whose full contact fetch (`since == null`) last completed this coordinator's lifetime. */
    private var fullContactSyncCompletedRadioID: UUID? = null

    /** Radio that already spent its one invalid-watermark recovery full fetch this coordinator lifetime. */
    private var invalidWatermarkRecoveryRadioID: UUID? = null

    private var discoveryEventsJob: Job? = null
    private var suppressionWatchdogJob: Job? = null

    // MARK: - Notifications

    /** Notify that contacts data changed (triggers UI refresh). */
    suspend fun notifyContactsChanged() {
        _contactsVersion.update { it + 1 }
        dataEventsFlow.emit(SyncDataEvent.ContactsChanged)
    }

    /** Notify that conversations data changed (triggers UI refresh). */
    suspend fun notifyConversationsChanged() {
        _conversationsVersion.update { it + 1 }
        dataEventsFlow.emit(SyncDataEvent.ConversationsChanged)
    }

    // MARK: - Connection Lifecycle

    /**
     * Called when a connection is established: starts event monitoring, exports the device
     * private key for RX-log decryption, performs a full sync, and starts discovery-event
     * monitoring. Ported from `onConnectionEstablished`, minus handler-wiring (see
     * [SyncDependencies]'s doc for why there's nothing left to wire here) and the advert-claim
     * wait (see this class's doc).
     *
     * @return [FullSyncResult.SKIPPED] if a sync is already in progress.
     */
    suspend fun onConnectionEstablished(
        radioID: UUID,
        dependencies: SyncDependencies,
        forceFullSync: Boolean = false,
        channelSyncConfig: ChannelSyncConfig = ChannelSyncConfig.NONE,
    ): FullSyncResult {
        if (!isSyncInProgress.compareAndSet(false, true)) return FullSyncResult.SKIPPED
        try {
            // Suppress message notifications during sync — unread counts/badges still update,
            // only system notification posting is suppressed.
            dependencies.notificationService.isSuppressingNotifications = true
            startSuppressionWatchdog(dependencies.notificationService)

            try {
                dependencies.startEventMonitoring(radioID, false)

                try {
                    val privateKey = dependencies.exportPrivateKey()
                    dependencies.rxLogService.updatePrivateKey(privateKey)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Best-effort: direct-message decryption degrades, nothing else does.
                }

                try {
                    val knownRegions = dependencies.deviceStore.fetchDeviceByRadioId(radioID)?.knownRegions ?: emptyList()
                    dependencies.rxLogService.updateKnownRegions(knownRegions)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Best-effort: region labeling degrades, nothing else does — same reasoning as the private-key export above.
                }

                val syncResult = runFullSync(radioID, dependencies, forceFullSync, channelSyncConfig)

                // Intentionally after the full sync so adverts arriving during sync don't spam notifications.
                startDiscoveryEventMonitoring(dependencies, radioID)

                cancelSuppressionWatchdog()
                dependencies.notificationService.isSuppressingNotifications = false

                dependencies.incomingMessageService.startAutoFetch(radioID)

                return syncResult
            } catch (error: Exception) {
                // Same cleanup for a genuine failure and for cancellation — both leave the
                // connection without a usable sync, so both must release suppression before
                // propagating.
                cancelSuppressionWatchdog()
                dependencies.notificationService.isSuppressingNotifications = false
                throw error
            }
        } finally {
            isSyncInProgress.set(false)
        }
    }

    /**
     * Called when disconnecting from the device: resets sync-guard state, stops discovery-event
     * monitoring, and clears notification suppression. Ported from `onDisconnected`, minus the
     * advert-waiter/`unresolvedChannelIndices` resets (see this class's doc for why neither
     * exists here).
     */
    suspend fun onDisconnected(notificationService: NotificationService) {
        isSyncInProgress.set(false)
        fullContactSyncCompletedRadioID = null
        invalidWatermarkRecoveryRadioID = null

        cancelDiscoveryEventMonitoring()

        _state.value = SyncState.Idle

        cancelSuppressionWatchdog()
        notificationService.isSuppressingNotifications = false
    }

    // MARK: - Full Sync

    /**
     * Performs a full sync of contacts, channels, and messages, in that order. Ported from
     * `performFullSync`/`runFullSync` (merged here — this port has no `performResync` caller that
     * needs the split, see this class's doc).
     */
    suspend fun performFullSync(
        radioID: UUID,
        dependencies: SyncDependencies,
        forceFullSync: Boolean = false,
        channelSyncConfig: ChannelSyncConfig = ChannelSyncConfig.NONE,
    ): FullSyncResult {
        if (!isSyncInProgress.compareAndSet(false, true)) return FullSyncResult.SKIPPED
        try {
            return runFullSync(radioID, dependencies, forceFullSync, channelSyncConfig)
        } finally {
            isSyncInProgress.set(false)
        }
    }

    /** Full-sync body without the [isSyncInProgress] claim — callers must hold it already. */
    private suspend fun runFullSync(
        radioID: UUID,
        dependencies: SyncDependencies,
        forceFullSync: Boolean,
        channelSyncConfig: ChannelSyncConfig,
    ): FullSyncResult {
        _state.value = SyncState.Syncing(SyncProgress(SyncPhase.CONTACTS, 0, 0))

        try {
            val contactChannelResult = syncContactsAndChannels(radioID, dependencies, forceFullSync, channelSyncConfig)

            _state.value = SyncState.Syncing(SyncProgress(SyncPhase.MESSAGES, 0, 0))
            val messageStatus: SyncPhaseStatus = try {
                dependencies.incomingMessageService.pollAllMessages()
                SyncPhaseStatus.Clean
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                SyncPhaseStatus.Failed(error.message ?: "unknown error")
            }

            // Clear notification suppression immediately after the catch-up poll: everything up
            // to here ran suppressed, anything from the live event monitor afterward is genuinely new.
            cancelSuppressionWatchdog()
            dependencies.notificationService.isSuppressingNotifications = false

            notifyConversationsChanged()

            _state.value = SyncState.Synced
            lastSyncDate = Instant.now()

            return FullSyncResult(
                contacts = contactChannelResult.contacts,
                channels = contactChannelResult.channels,
                messages = messageStatus,
                channelRetryIndices = contactChannelResult.channelRetryIndices,
            )
        } catch (error: CancellationException) {
            _state.value = SyncState.Idle
            throw error
        } catch (error: Exception) {
            _state.value = SyncState.Failed(SyncCoordinatorError.SyncFailed(error.message ?: "unknown error"))
            throw error
        }
    }

    private data class ContactChannelSyncResult(
        val contacts: SyncPhaseStatus,
        val channels: SyncPhaseStatus,
        val channelRetryIndices: List<UByte>,
    )

    /** Syncs contacts and channels from the device (phases 1 and 2 of full sync). Ported from `syncContactsAndChannels`. */
    private suspend fun syncContactsAndChannels(
        radioID: UUID,
        dependencies: SyncDependencies,
        forceFullSync: Boolean,
        channelSyncConfig: ChannelSyncConfig,
    ): ContactChannelSyncResult {
        val device = dependencies.deviceStore.fetchDeviceByRadioId(radioID)

        // At capacity, force a pruning contact fetch (since == null) — offline eviction is
        // invisible to the incremental watermark. Excludes the virtual V-contact from the count.
        var atCapacity = false
        val maxContacts = device?.maxContacts?.toInt() ?: 0
        if (maxContacts > 0) {
            try {
                val keys = dependencies.contactStore.fetchContactPublicKeys(radioID)
                var realContactCount = keys.size
                val selfPublicKey = device?.publicKey
                val vContactKey = selfPublicKey?.let { VContactIdentity.publicKey(it) }
                if (vContactKey != null && keys.contains(vContactKey.toList())) realContactCount -= 1
                atCapacity = realContactCount >= maxContacts
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A count-read failure leaves atCapacity false.
            }
        }

        // Phase 1: Contacts (incremental unless forced full or at capacity)
        var ranInvalidWatermarkRecovery = false
        val lastContactSync: Instant? = if (forceFullSync || atCapacity) {
            null
        } else {
            when (val use = contactWatermarkUse(device?.lastContactSync)) {
                is ContactWatermarkUse.None -> null
                is ContactWatermarkUse.Incremental -> incrementalSince(use.watermark)
                is ContactWatermarkUse.Invalid -> {
                    if (invalidWatermarkRecoveryRadioID == radioID) {
                        incrementalSince(use.stored)
                    } else {
                        ranInvalidWatermarkRecovery = true
                        null
                    }
                }
            }
        }

        Log.d(
            SYNC_LOG_TAG,
            "syncContactsAndChannels: radioID=$radioID forceFullSync=$forceFullSync atCapacity=$atCapacity " +
                "maxContacts=$maxContacts lastContactSync=$lastContactSync (${if (lastContactSync == null) "full" else "incremental"} fetch)",
        )
        try {
            val contactResult = syncContactsPhase(radioID, dependencies, lastContactSync)
            Log.d(
                SYNC_LOG_TAG,
                "syncContactsAndChannels: contactsReceived=${contactResult.contactsReceived} " +
                    "isIncremental=${contactResult.isIncremental} lastSyncTimestamp=${contactResult.lastSyncTimestamp}",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(SYNC_LOG_TAG, "syncContactsAndChannels: contacts phase threw, aborting full sync", error)
            throw error
        }

        if (ranInvalidWatermarkRecovery) invalidWatermarkRecoveryRadioID = radioID

        try {
            val publicKeysByPrefix = dependencies.contactStore.fetchContactPublicKeysByPrefix(radioID)
            dependencies.rxLogService.updateContactPublicKeys(publicKeysByPrefix)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort.
        }

        // Phase 2: Channels (foreground only)
        var channelStatus: SyncPhaseStatus = SyncPhaseStatus.Skipped
        var channelRetryIndices: List<UByte> = emptyList()
        val shouldSyncChannels = dependencies.appStateProvider?.invoke() ?: true

        if (shouldSyncChannels) {
            val shouldSkipChannels = !forceFullSync &&
                channelSyncConfig.channelSyncSkipWindow > Duration.ZERO &&
                isWithinChannelSyncSkipWindow(channelSyncConfig)

            if (shouldSkipChannels) {
                channelStatus = SyncPhaseStatus.Skipped
            } else {
                _state.value = SyncState.Syncing(SyncProgress(SyncPhase.CHANNELS, 0, 0))
                val maxChannels = device?.maxChannels ?: 0u
                onChannelSyncAttempted?.invoke(radioID)

                val channelResult = try {
                    dependencies.channelService.syncChannels(radioID, maxChannels, channelSyncConfig.usePipelinedChannelRead)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    refreshRxLogChannels(radioID, dependencies)
                    return ContactChannelSyncResult(SyncPhaseStatus.Clean, SyncPhaseStatus.Partial, channelRetryIndices)
                }

                var channelPhaseClean = channelResult.isComplete
                val hasNonRetryableErrors = channelResult.errors.size > channelResult.retryableIndices.size
                var remainingRetryableIndices = channelResult.retryableIndices

                if (!channelResult.isComplete && channelResult.retryableIndices.isNotEmpty()) {
                    val retryResult = try {
                        dependencies.channelService.retryFailedChannels(radioID, channelResult.retryableIndices)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        channelPhaseClean = false
                        null
                    }
                    if (retryResult != null) {
                        if (retryResult.isComplete && !hasNonRetryableErrors) {
                            channelPhaseClean = true
                        } else {
                            remainingRetryableIndices = retryResult.retryableIndices
                            channelPhaseClean = false
                        }
                    }
                }

                if (channelPhaseClean) {
                    channelStatus = SyncPhaseStatus.Clean
                    onCleanChannelSync?.invoke(radioID)
                } else {
                    channelStatus = SyncPhaseStatus.Partial
                    channelRetryIndices = remainingRetryableIndices
                }
            }

            refreshRxLogChannels(radioID, dependencies)
        } else {
            channelStatus = SyncPhaseStatus.Skipped
        }

        return ContactChannelSyncResult(contacts = SyncPhaseStatus.Clean, channels = channelStatus, channelRetryIndices = channelRetryIndices)
    }

    /**
     * Retries a bounded set of channels that failed with retryable errors during the last sync,
     * outside the full-sync lock — the caller (`ConnectionManager`'s channel-only retry loop)
     * already fenced this against a superseding sync/disconnect via `services` identity, so this
     * doesn't compete with [isSyncInProgress] the way [performFullSync] does. Ported from
     * `ConnectionManager+SyncRetry.swift`'s `scheduleChannelOnlyRetry` body (the part that isn't
     * pure Task/backoff bookkeeping, which stays on `ConnectionManager` — see PLAN.md's
     * `ConnectionManager` recon).
     */
    suspend fun retryChannels(
        radioID: UUID,
        channelService: ChannelService,
        indices: List<UByte>,
    ): ChannelSyncResult {
        val result = channelService.retryFailedChannels(radioID, indices)
        if (result.isComplete) {
            onCleanChannelSync?.invoke(radioID)
        }
        return result
    }

    private fun isWithinChannelSyncSkipWindow(config: ChannelSyncConfig): Boolean {
        val now = Instant.now()
        val recentClean = config.lastCleanChannelSync?.let { Duration.between(it, now) < config.channelSyncSkipWindow } ?: false
        val recentAttempt = config.lastAttemptedChannelSync?.let { Duration.between(it, now) < config.channelSyncSkipWindow } ?: false
        return recentClean || recentAttempt
    }

    /** Runs contact sync and writes back the watermark when the result carries one. Ported from `syncContactsPhase`. */
    private suspend fun syncContactsPhase(radioID: UUID, dependencies: SyncDependencies, since: Instant?): ContactSyncResult {
        val result = dependencies.contactService.syncContacts(radioID, since)
        notifyContactsChanged()

        if (result.lastSyncTimestamp > 0u) {
            dependencies.deviceStore.updateDeviceLastContactSync(radioID, result.lastSyncTimestamp)
        }
        if (since == null) fullContactSyncCompletedRadioID = radioID
        return result
    }

    private suspend fun refreshRxLogChannels(radioID: UUID, dependencies: SyncDependencies) {
        try {
            val channels = dependencies.channelStore.fetchChannels(radioID)
            val secrets = channels.associate { it.index to it.secret }
            val names = channels.associate { it.index to it.name }
            dependencies.rxLogService.updateChannels(secrets, names)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Best-effort.
        }
    }

    // MARK: - Discovery Event Monitoring

    /**
     * Subscribes to [com.meshcoretwo.services.advertisement.AdvertisementService.events] for
     * ongoing contact discovery, started only after the initial sync so adverts arriving during
     * sync don't spam notifications. Ported from `startDiscoveryEventMonitoring`, trimmed to the
     * one case this port's [AdvertisementEvent] carries — see this class's doc.
     */
    private fun startDiscoveryEventMonitoring(dependencies: SyncDependencies, radioID: UUID) {
        discoveryEventsJob?.cancel()
        discoveryEventsJob = scope.launch {
            dependencies.advertisementService.events().collect { event ->
                when (event) {
                    is AdvertisementEvent.NewContactDiscovered -> {
                        dependencies.notificationService.postNewContactNotification(event.name, event.contactID, event.contactType)
                        notifyContactsChanged()
                    }
                    else -> {}
                }
            }
        }
    }

    /** Cancels the discovery event task so it releases the service references it captures. */
    fun cancelDiscoveryEventMonitoring() {
        discoveryEventsJob?.cancel()
        discoveryEventsJob = null
    }

    // MARK: - Notification Suppression Watchdog

    /** Force-clears notification suppression after 120s in case sync completes abnormally without clearing it. Ported from `startSuppressionWatchdog`. */
    private fun startSuppressionWatchdog(notificationService: NotificationService) {
        suppressionWatchdogJob?.cancel()
        suppressionWatchdogJob = scope.launch {
            delay(120_000)
            if (!notificationService.isSuppressingNotifications) return@launch
            notificationService.isSuppressingNotifications = false
        }
    }

    private fun cancelSuppressionWatchdog() {
        suppressionWatchdogJob?.cancel()
        suppressionWatchdogJob = null
    }

    // MARK: - Contact Watermark

    /** How to use a stored contact-sync watermark for one fetch round. Ported from `ContactWatermarkUse`. */
    sealed class ContactWatermarkUse {
        /** No successful contact sync stamp yet. */
        object None : ContactWatermarkUse()

        /** Stamp is usable for incremental `since = watermark - 1`. */
        data class Incremental(val watermark: UInt) : ContactWatermarkUse()

        /** Stamp is implausibly ahead of the reference; fetch with `since == null` this round. */
        data class Invalid(val stored: UInt) : ContactWatermarkUse()
    }

    companion object {
        private const val SYNC_LOG_TAG = "ContactSync"

        /** Contact watermark value meaning no contact sync has ever succeeded. */
        private const val NO_CONTACT_WATERMARK: UInt = 0u

        /** Seconds the incremental `since` filter is rewound from the stored watermark. */
        private val INCREMENTAL_SINCE_OVERLAP: Duration = Duration.ofSeconds(1)

        /** Max lead over the reference before a stored watermark is treated as unusable for incremental `since`. */
        private val CONTACT_WATERMARK_PLAUSIBILITY_SKEW: Duration = Duration.ofDays(2)

        /**
         * Decides whether a stored contact-sync watermark can drive incremental sync. Reference
         * clock is the phone's own clock (radio clock is disciplined to it within a small
         * tolerance elsewhere — see Swift's `syncDeviceTimeIfNeeded`, not yet ported). Ported from
         * `contactWatermarkUse(fromLastContactSync:referenceNow:)`.
         */
        internal fun contactWatermarkUse(raw: UInt?, referenceNow: Instant = Instant.now()): ContactWatermarkUse {
            if (raw == null || raw == NO_CONTACT_WATERMARK) return ContactWatermarkUse.None
            val referenceSeconds = referenceNow.epochSecond.toULong()
            val maxSkew = CONTACT_WATERMARK_PLAUSIBILITY_SKEW.seconds.toULong()
            val upperBound = if (referenceSeconds > ULong.MAX_VALUE - maxSkew) ULong.MAX_VALUE else referenceSeconds + maxSkew
            return if (raw.toULong() > upperBound) ContactWatermarkUse.Invalid(raw) else ContactWatermarkUse.Incremental(raw)
        }

        /** The `since` filter for an incremental contact fetch from a stored watermark. Ported from `incrementalSince`. */
        internal fun incrementalSince(watermark: UInt): Instant =
            Instant.ofEpochSecond(watermark.toLong()).minus(INCREMENTAL_SINCE_OVERLAP)
    }
}
