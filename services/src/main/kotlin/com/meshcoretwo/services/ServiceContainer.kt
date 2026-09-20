// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services

import android.content.Context
import com.meshcoretwo.protocol.ContactMessage
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.FullMeshCoreSessionOps
import com.meshcoretwo.services.advertisement.AdvertisementService
import com.meshcoretwo.services.channels.ChannelService
import com.meshcoretwo.services.connection.DeviceConnectionState
import com.meshcoretwo.services.contacts.ContactCleanupCoordinator
import com.meshcoretwo.services.contacts.ContactService
import com.meshcoretwo.services.device.DeviceService
import com.meshcoretwo.services.diagnostics.BinaryProtocolService
import com.meshcoretwo.services.logging.DebugLogBuffer
import com.meshcoretwo.services.messages.IncomingMessageService
import com.meshcoretwo.services.messages.MessageService
import com.meshcoretwo.services.nodeconfig.NodeConfigService
import com.meshcoretwo.services.nodesnapshot.NodeSnapshotService
import com.meshcoretwo.services.notifications.NotificationActionHandler
import com.meshcoretwo.services.notifications.NotificationCoordinator
import com.meshcoretwo.services.notifications.NotificationService
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.DebugLogRetention
import com.meshcoretwo.services.persistence.DebugLogStore
import com.meshcoretwo.services.persistence.DeviceStore
import com.meshcoretwo.services.persistence.DiscoveredNodeStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.MessageRepeatStore
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.NodeStatusSnapshotStore
import com.meshcoretwo.services.persistence.PendingSendStore
import com.meshcoretwo.services.persistence.ReactionStore
import com.meshcoretwo.services.persistence.RemoteNodeSessionStore
import com.meshcoretwo.services.persistence.RoomMessageStore
import com.meshcoretwo.services.persistence.RxLogStore
import com.meshcoretwo.services.persistence.TracePathStore
import com.meshcoretwo.services.reactions.ReactionService
import com.meshcoretwo.services.remotenode.CLIResponse
import com.meshcoretwo.services.remotenode.RemoteNodeService
import com.meshcoretwo.services.remotenode.RepeaterAdminService
import com.meshcoretwo.services.remotenode.RoomAdminService
import com.meshcoretwo.services.remotenode.RoomServerService
import com.meshcoretwo.services.repeats.HeardRepeatsService
import com.meshcoretwo.services.rxlog.RxLogService
import com.meshcoretwo.services.security.KeychainService
import com.meshcoretwo.services.sendqueue.ChatSendQueueService
import com.meshcoretwo.services.settings.SettingsService
import com.meshcoretwo.services.sync.ChannelSyncConfig
import com.meshcoretwo.services.sync.FullSyncResult
import com.meshcoretwo.services.sync.SyncCoordinator
import com.meshcoretwo.services.sync.SyncDependencies
import com.meshcoretwo.services.tracepath.TracePathService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/**
 * Composition root for the services layer. Ported from `ServiceContainer.swift`, sub-slice "A2"
 * of the `ConnectionManager` vertical slice (PLAN.md's p.11) — see PLAN.md's "Connection-инфраструктура"
 * A1/A2 status entries for the full recon.
 *
 * ## Lifetime
 *
 * Per-connection, not a singleton, matching Swift: the not-yet-ported `ConnectionManager`
 * (sub-slices B/C/D) is expected to build a fresh [ServiceContainer] on every connection and
 * [tearDown] it on disconnect. [radioID] is required at construction (mirroring Swift's
 * `buildServicesAndSaveDevice` resolving the device record before instantiating the container),
 * since [ChatSendQueueService] needs it to scope its pending-send rows and [SyncDependencies]
 * needs it wired into the `startEventMonitoring` closure below. `init` also reassigns
 * [DebugLogBuffer.shared] to this container's [debugLogBuffer], so a stale container's services
 * must not keep running past [tearDown].
 *
 * ## Dependency injection
 *
 * Kotlin has no partial-class/actor-isolation equivalent to preserve, so this is a plain
 * constructor: every service is built in dependency order so a fully-wired container exists as
 * soon as `init` returns, same as Swift. One structural difference from Swift's single
 * `dataStore: any PersistenceStoreProtocol` — this port has no monolithic persistence façade, so
 * [database] is used to build the per-domain `*Store` classes directly (matching every other
 * vertical slice's convention — see [com.meshcoretwo.services.persistence.ContactStore]'s
 * siblings).
 *
 * [session] is typed as [FullMeshCoreSessionOps] — the umbrella of every `*SessionOps` interface
 * this container's services collectively need — rather than the narrower interface each
 * individual service declares, because the container is the one place that needs a single value
 * satisfying all of them at once. A production caller passes a real
 * [com.meshcoretwo.protocol.MeshCoreSession] (which declares conformance to this interface); a
 * test passes a single fake implementing it. Wiring a live session through here for the first
 * time is what caught [com.meshcoretwo.protocol.MeshCoreSession] missing two composite-interface
 * declarations ([com.meshcoretwo.protocol.RemoteNodeSessionOps],
 * [com.meshcoretwo.protocol.BinaryProtocolSessionOps]) — see that class's doc.
 *
 * `NotificationService` callback wiring (`onQuickReply`/`onChannelQuickReply`/`onMarkAsRead`/
 * `onChannelMarkAsRead`/`onRoomMarkAsRead` → [notificationActionHandler]) is done here, in `init`
 * — this is the gap [NotificationActionHandler]'s and [NotificationService]'s class docs both
 * flagged as "composition root's job", now closed. [NotificationActionHandler.configure] (the
 * app-layer `isConnectionReady`/`localNodeName` inputs) is deliberately **not** called here — that
 * stays the `app` layer's job, same as Swift's `AppState.wireServicesIfConnected`.
 *
 * [nodeConfigService]'s [com.meshcoretwo.services.nodeconfig.NodeConfigService.setOnPostIdentityImport]
 * callback is **not** wired here, unlike [notificationActionHandler] above — this container's
 * constructor has no reference to the owning
 * [ConnectionManager][com.meshcoretwo.services.connection.ConnectionManager], and the callback
 * needs [com.meshcoretwo.services.connection.reconcileIdentity], a `ConnectionManager` extension.
 * It's wired instead in `ConnectionManagerSession.buildServicesAndSaveDeviceImpl`, right after this
 * container is constructed — matching where Swift wires the same callback (`ConnectionManager`'s
 * own `buildServicesAndSaveDevice`, not `Device`/service construction).
 *
 * ## Deferred (not constructed here)
 *
 * Nothing left at the service-container level: [nodeSnapshotService] (the last item this doc
 * listed here) is ported now — see [com.meshcoretwo.services.nodesnapshot.NodeSnapshotService]'s
 * class doc for what's still deferred *inside* it (the RemoteNodes status/telemetry/neighbor UI
 * that would actually call it).
 *
 * [warmUp] and `resetRemoteNodeConnections()` (the other two Swift app-launch conveniences this
 * doc used to list as deferred) are both ported now: [warmUp] below, called from
 * [com.meshcoretwo.services.connection.buildServicesAndSaveDeviceImpl]; the latter as
 * [com.meshcoretwo.services.persistence.RemoteNodeSessionStore.resetAllConnections], called from
 * `ConnectionManager.activate()` (see [com.meshcoretwo.services.connection.ConnectionManager]'s
 * class doc).
 *
 * ## Event monitoring
 *
 * [startEventMonitoring] mirrors Swift's `ServiceContainer.startEventMonitoring`, with one
 * structural difference: this port's [SyncCoordinator.onConnectionEstablished] already needs a
 * "start every service's event monitoring" callback ([SyncDependencies.startEventMonitoring] —
 * see its doc), so [startEventMonitoring] itself is exposed both as that callback's
 * implementation and as a directly-callable method, rather than something a future
 * `ConnectionManager` calls before separately calling `syncCoordinator.onConnectionEstablished`.
 * [onConnectionEstablished] is a thin forwarding convenience so a future `ConnectionManager` needs
 * only this container plus [radioID], not [syncDependencies] threaded through separately.
 *
 * `binaryProtocolService` is intentionally **not** started/stopped here, matching Swift's
 * `ServiceContainer` (which never calls it either) — its event monitoring is a per-remote-session
 * concern, not a per-connection one. `remoteNodeService.stopEventMonitoring()` is likewise omitted
 * from [stopEventMonitoring]: "RemoteNodeService event monitoring is per-session, handled
 * internally" (Swift's own comment, still true here).
 *
 * There is no Kotlin equivalent of Swift's `tearDown()` finishing every `AsyncStream` (`SyncDataEvent`,
 * `AdvertisementEvent`, ...) so consumer `for-await` loops release the services they capture: this
 * port's streams are [kotlinx.coroutines.flow.SharedFlow]s, which have no "finish" — a collector's
 * job ends when *it* is cancelled (its own [kotlinx.coroutines.CoroutineScope]), not when the
 * producer stops emitting. Nothing in this port launches such a collector against container
 * services yet, so there is nothing to cancel here; a future consumer must own its own job.
 */
class ServiceContainer(
    context: Context,
    val session: FullMeshCoreSessionOps,
    database: MeshCoreDatabase,
    val radioID: UUID,
    appStateProvider: (suspend () -> Boolean)? = null,
    connectionStateEvents: Flow<DeviceConnectionState>? = null,
    initialConnectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    /**
     * Defaults to the real Keystore-backed constructor; overridable so tests can supply
     * [KeychainService]'s plain-`SharedPreferences` constructor instead — Robolectric (this
     * module's JVM unit-test environment) has no working `AndroidKeyStore` provider, so the
     * default would throw [com.meshcoretwo.services.security.KeychainError.InitializationFailed]
     * there. See [KeychainService]'s "Testing note".
     */
    val keychainService: KeychainService = KeychainService(context),
) {
    // MARK: - Persistence

    private val contactStore = ContactStore(database)
    private val deviceStore = DeviceStore(database)
    private val messageStore = MessageStore(database)
    private val channelStore = ChannelStore(database)
    private val messageRepeatStore = MessageRepeatStore(database)
    private val reactionStore = ReactionStore(database)
    private val remoteNodeSessionStore = RemoteNodeSessionStore(database)
    private val roomMessageStore = RoomMessageStore(database)
    private val rxLogStore = RxLogStore(database)
    private val pendingSendStore = PendingSendStore(database)
    private val tracePathStore = TracePathStore(database)
    val discoveredNodeStore = DiscoveredNodeStore(database)
    private val debugLogStore = DebugLogStore(database)
    private val nodeStatusSnapshotStore = NodeStatusSnapshotStore(database)

    // MARK: - Independent services

    val notificationService = NotificationService(context)
    val syncCoordinator = SyncCoordinator()
    val inlineImageDimensionsStore = InlineImageDimensionsStore(context)

    /** Reassigned onto [DebugLogBuffer.shared] in `init` — see that property's doc. */
    val debugLogBuffer = DebugLogBuffer(debugLogStore)

    // MARK: - Core services, built so every dependency exists before its consumer

    val heardRepeatsService = HeardRepeatsService(messageStore, messageRepeatStore)
    val rxLogService = RxLogService(session, rxLogStore, channelStore, contactStore, discoveredNodeStore, heardRepeatsService, messageStore)
    val remoteNodeService = RemoteNodeService(session, remoteNodeSessionStore, contactStore, keychainService)

    private val contactCleanupCoordinator = ContactCleanupCoordinator(
        contactStore, messageStore, remoteNodeSessionStore, syncCoordinator, notificationService, remoteNodeService, radioID,
    )

    val contactService = ContactService(session, contactStore, deviceStore, messageStore, syncCoordinator, contactCleanupCoordinator)
    val reactionService = ReactionService(messageStore, reactionStore)
    val messageService = MessageService(session, messageStore, contactStore, channelStore, messageRepeatStore)

    val channelService = ChannelService(
        session, channelStore, messageStore,
        channelSecretsSink = { channels -> rxLogService.updateChannels(channels) },
    )

    val settingsService = SettingsService(session)
    val deviceService = DeviceService(deviceStore)
    val advertisementService = AdvertisementService(session, contactStore, deviceStore, discoveredNodeStore)

    private val notificationCoordinator = NotificationCoordinator(notificationService, contactStore, channelStore)

    /**
     * Constructed ahead of [incomingMessageService] (which routes incoming room messages to
     * [RoomServerService.handleIncomingMessage]) even though the rest of "Remote node services"
     * below is grouped later — every one of its own dependencies is already available this early.
     */
    val roomServerService = RoomServerService(
        session, remoteNodeService, remoteNodeSessionStore, roomMessageStore, contactStore, radioID,
        notificationService = notificationService,
    )

    /**
     * Also constructed ahead of [incomingMessageService] for the same reason as [roomServerService]
     * above — it routes incoming CLI-response messages to whichever of these two admin services
     * the sending contact belongs to.
     */
    val repeaterAdminService = RepeaterAdminService(session, remoteNodeService, remoteNodeSessionStore)
    val roomAdminService = RoomAdminService(remoteNodeService, remoteNodeSessionStore)

    /** Replaces Swift's `MessagePollingService` + `SyncCoordinator+MessageHandlers` split — see its class doc. */
    val incomingMessageService = IncomingMessageService(
        session, messageStore, contactStore, channelStore,
        pendingAdvertResolver = advertisementService::materializeContactForPendingAdvert,
        reactionHandling = reactionService,
        selfNodeNameProvider = { radioID -> deviceStore.fetchDeviceByRadioId(radioID)?.nodeName },
        rxLogCorrelation = rxLogService,
        notifying = notificationCoordinator,
        roomMessageHandler = { prefix, timestamp, authorPrefix, text ->
            roomServerService.handleIncomingMessage(prefix, timestamp, authorPrefix, text)
        },
        cliMessageHandler = { message, contact ->
            // Strip a firmware-echoed wire prefix ("XX|") first, matching Swift's
            // `handleIncomingCLIMessage` — same reply text reaches admin services regardless of
            // firmware echo support.
            val routed = CLIResponse.splitEchoedPrefix(message.text)?.let { (_, body) ->
                ContactMessage(
                    senderPublicKeyPrefix = message.senderPublicKeyPrefix,
                    pathLength = message.pathLength,
                    textType = message.textType,
                    senderTimestamp = message.senderTimestamp,
                    signature = message.signature,
                    text = body,
                    snr = message.snr,
                )
            } ?: message
            if (contact.type == ContactType.ROOM) {
                roomAdminService.invokeCLIHandler(routed, contact)
            } else {
                repeaterAdminService.invokeCLIHandler(routed, contact)
            }
        },
        pathHarvesting = heardRepeatsService,
    )

    val binaryProtocolService = BinaryProtocolService(session)
    val tracePathService = TracePathService(binaryProtocolService, tracePathStore)
    val nodeConfigService = NodeConfigService(session, settingsService, channelService, syncCoordinator)
    val nodeSnapshotService = NodeSnapshotService(nodeStatusSnapshotStore)

    // MARK: - Chat send queue

    val chatSendQueueService = ChatSendQueueService(
        radioID, messageStore, contactStore, pendingSendStore, messageService, channelService, reactionService,
    )

    // MARK: - Notification actions

    val notificationActionHandler = NotificationActionHandler(
        contactStore, channelStore, messageStore, reactionStore, messageService, notificationService, roomServerService, syncCoordinator,
    )

    /** The narrow dependency surface [SyncCoordinator.onConnectionEstablished] needs — see [onConnectionEstablished]. */
    val syncDependencies = SyncDependencies(
        deviceStore = deviceStore,
        contactStore = contactStore,
        channelStore = channelStore,
        contactService = contactService,
        channelService = channelService,
        incomingMessageService = incomingMessageService,
        notificationService = notificationService,
        advertisementService = advertisementService,
        rxLogService = rxLogService,
        appStateProvider = appStateProvider,
        startEventMonitoring = { rId, enableAutoFetch -> startEventMonitoring(rId, enableAutoFetch) },
        exportPrivateKey = { settingsService.exportPrivateKey() },
    )

    init {
        DebugLogBuffer.shared = debugLogBuffer

        // Without this no notification channels exist, and Android 8+ silently drops every post
        // to an unregistered channel.
        notificationService.setup()
        notificationService.onQuickReply = notificationActionHandler::handleQuickReply
        notificationService.onChannelQuickReply = notificationActionHandler::handleChannelQuickReply
        notificationService.onMarkAsRead = notificationActionHandler::handleMarkAsRead
        notificationService.onChannelMarkAsRead = notificationActionHandler::handleChannelMarkAsRead
        notificationService.onRoomMarkAsRead = notificationActionHandler::handleRoomMarkAsRead

        if (connectionStateEvents != null) {
            chatSendQueueService.observeConnectionState(initialConnectionState, connectionStateEvents)
        }
    }

    // MARK: - Startup hygiene

    /**
     * Startup-time DB hygiene, ported from `PersistenceStore.warmUp()`. Reduces to
     * [PendingSendStore.purgeOrphanPendingSends] alone: Room has no lazy-init concern to force
     * (unlike SwiftData, it opens eagerly, so there's no `fetchCount(Device)` warm-up fetch to
     * port), and `purgeLegacyAttemptCountRows` has no Android analogue — see
     * [com.meshcoretwo.services.persistence.PendingSendEntity]'s `attemptCount` doc for why.
     *
     * Call once this container's own [radioID]/[DeviceDto][com.meshcoretwo.services.persistence.DeviceDto]
     * row is already persisted (matching Swift's ordering comment in `buildServicesAndSaveDevice`:
     * "Persist before warmUp so purgeOrphanPendingSends sees the in-progress radio's Device row and
     * does not classify its PendingSends as orphans"). Best-effort — the caller is expected to
     * swallow failures the same way Swift's `ConnectionManager` does.
     */
    suspend fun warmUp() {
        pendingSendStore.purgeOrphanPendingSends()
    }

    // MARK: - Connection lifecycle

    /**
     * Runs the full "connect → sync → discovery" path for [radioID] via [syncCoordinator]. Thin
     * forwarding convenience over [SyncDependencies] — see this class's doc.
     */
    suspend fun onConnectionEstablished(
        forceFullSync: Boolean = false,
        channelSyncConfig: ChannelSyncConfig = ChannelSyncConfig.NONE,
    ): FullSyncResult = syncCoordinator.onConnectionEstablished(radioID, syncDependencies, forceFullSync, channelSyncConfig)

    /** Stops event monitoring and resets [syncCoordinator]'s per-connection state. */
    suspend fun onDisconnected() {
        stopEventMonitoring()
        syncCoordinator.onDisconnected(notificationService)
    }

    // MARK: - Event monitoring

    /**
     * Starts every service's event monitoring for [radioID]. Ported from Swift's
     * `ServiceContainer.startEventMonitoring`, minus the `eventMonitoringState` tri-state guard
     * (Swift needs it because two racing callers can each pass a `guard` before the first
     * `await`; every start call here is a direct sequence of idempotent per-service starts, so a
     * second overlapping call just re-arms jobs that are already running rather than
     * double-starting a resource). The debug-log-prune and node-snapshot-prune `Task`s **are**
     * ported: each a best-effort fire-and-forget coroutine on its own throwaway scope, matching
     * Swift's bare `Task { }` (unstructured, never awaited or cancelled by this method).
     */
    suspend fun startEventMonitoring(radioID: UUID = this.radioID, enableAutoFetch: Boolean = true) {
        heardRepeatsService.configure(radioID)
        advertisementService.startEventMonitoring(radioID)
        rxLogService.startEventMonitoring(radioID)
        messageService.startEventMonitoring()
        messageService.startAckExpiryChecking()
        remoteNodeService.startEventMonitoring()
        incomingMessageService.startMessageEventMonitoring(radioID)
        if (enableAutoFetch) incomingMessageService.startAutoFetch(radioID)

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                debugLogStore.pruneDebugLogEntries(
                    cutoff = Instant.now().minusSeconds(DebugLogRetention.WINDOW_SECONDS),
                    keepCount = DebugLogRetention.MAX_ENTRIES,
                )
            }
        }

        // Prune node status snapshots older than 1 year.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            nodeSnapshotService.pruneOldSnapshots(Instant.now().minus(java.time.Duration.ofDays(365)))
        }
    }

    /** Stops every service's event monitoring. Ported from Swift's `ServiceContainer.stopEventMonitoring`. */
    suspend fun stopEventMonitoring() {
        advertisementService.stopEventMonitoring()
        rxLogService.stopEventMonitoring()
        rxLogService.flushPendingEntries()
        messageService.stopEventMonitoring()
        messageService.stopAckExpiryChecking()
        incomingMessageService.stopMessageEventMonitoring()

        // Flush debug log buffer.
        debugLogBuffer.shutdown()
    }

    /**
     * Full container teardown. Ported from Swift's `ServiceContainer.tearDown`, minus finishing
     * event streams (no Kotlin equivalent — see this class's doc) and minus clearing
     * `messagePollingService`'s handlers (this port's [IncomingMessageService] takes its handlers
     * at construction, not via late setter injection — see [SyncDependencies]'s doc).
     */
    suspend fun tearDown() {
        stopEventMonitoring()
        syncCoordinator.cancelDiscoveryEventMonitoring()

        notificationService.onQuickReply = null
        notificationService.onChannelQuickReply = null
        notificationService.onMarkAsRead = null
        notificationService.onChannelMarkAsRead = null
        notificationService.onRoomMarkAsRead = null

        chatSendQueueService.shutdown()
    }
}
