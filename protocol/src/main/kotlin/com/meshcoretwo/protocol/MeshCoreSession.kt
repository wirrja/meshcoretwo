// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Main session class for MeshCore device communication.
 *
 * `MeshCoreSession` coordinates all communication with a MeshCore mesh networking device over a
 * [MeshTransport] (typically Bluetooth LE). It provides high-level APIs for connection
 * management, contact discovery, messaging, device configuration, and telemetry.
 *
 * ## Kotlin-specific notes
 *
 * Swift's `MeshCoreSession` is an `actor`, whose isolation gives every method mutually-exclusive
 * access to instance state, but *reentrant* at `await` points — two calls can interleave while
 * one is suspended, but never run their synchronous code concurrently. A single [Mutex] held for
 * an entire suspend method body would be strictly more restrictive than that (and risks
 * deadlocking a caller against the receive loop), so per [MockTransport]'s established pattern,
 * locks here are held only across brief, synchronous field access — never spanning a `transport`
 * call, a dispatcher wait, or a `delay` — which is the correct translation of actor reentrancy,
 * not a shortcut. A single-reference field that is always replaced atomically as a whole
 * ([selfInfo], [cachedTime], [isRunning]) uses `@Volatile` instead, since interface methods like
 * [MessagingSessionOps.currentSelfInfo] expose it as a plain (non-suspend) property.
 *
 * This class implements each `*SessionOps` interface incrementally as the corresponding
 * `MeshCoreSession+*.swift` file is ported (see PLAN.md); the interface list here grows across
 * commits. Because Kotlin has no partial classes or private cross-file extensions, the bulk of
 * each ported file's logic lives in an `internal suspend fun MeshCoreSession.xyz(...)` extension
 * function in its own file (mirroring Swift's per-topic file split), with a one-line `override`
 * here delegating to it — preserving both real interface conformance and per-file traceability.
 *
 * Swift injects a `Clock` for deterministic timeout testing (`TestClock`). Kotlin's
 * `kotlinx-coroutines-test` gives the same determinism via virtual time (`runTest`,
 * `TestScope.advanceTimeBy`) without a production-code seam, so no `Clock` abstraction is ported;
 * timeouts here use plain `kotlinx.coroutines.delay`/`withTimeoutOrNull`.
 *
 * Declares [FullMeshCoreSessionOps] (which folds in [RemoteNodeSessionOps],
 * [BinaryProtocolSessionOps], and [NodeConfigSessionOps], among others) even though this class
 * adds no new members for those — every method they require was already implemented separately
 * (via [RemoteAccessSessionOps]/[DiagnosticsSessionOps] plus the [MeshCoreSessionProtocol]-inherited
 * [SessionEventStreaming]/[ContactSessionOps]/[ChannelSessionOps]). Kotlin's nominal typing does
 * not infer that automatically the way those interfaces' own class docs imply — a value typed as
 * [MeshCoreSession] was not assignable where [RemoteNodeSessionOps]/[BinaryProtocolSessionOps]
 * was required until those were added to [FullMeshCoreSessionOps] (caught wiring the
 * services-layer composition root, [com.meshcoretwo.services.ServiceContainer], which is the
 * first real caller to pass a live session into
 * [com.meshcoretwo.services.remotenode.RemoteNodeService]/
 * [com.meshcoretwo.services.diagnostics.BinaryProtocolService]/
 * [com.meshcoretwo.services.nodeconfig.NodeConfigService] side by side with everything else).
 * [NodeConfigSessionOps] no longer needs a separate declaration here now that
 * [FullMeshCoreSessionOps] lists it directly — see that interface's class doc.
 */
class MeshCoreSession(
    internal val transport: MeshTransport,
    internal val configuration: SessionConfiguration = SessionConfiguration.default,
) : FullMeshCoreSessionOps {
    internal val dispatcher = EventDispatcher()
    internal val requestResponseSerializer = RequestResponseSerializer()

    /**
     * Serializes the read-modify-write of the granular "other params" setters. The inner wire
     * exchanges already serialize on [requestResponseSerializer], but a read-modify-write spans
     * several of them, so without this outer guard two concurrent setters each write back the
     * full config and silently revert each other's change. This is a distinct lock from
     * [requestResponseSerializer], which is not reentrant; nesting the two would deadlock.
     */
    internal val otherParamsSerializer = RequestResponseSerializer()

    /**
     * Owns this session's background loops so they can be cancelled together on [stop]. Also
     * used to launch work that must survive a caller's cancellation (e.g. [getMessage]'s
     * coalesced fetch), mirroring Swift's unstructured `Task { ... }`.
     */
    internal val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // MARK: - Contact State

    private val contactMutex = Mutex()
    internal val contactManager = ContactManager()

    /** Runs [block] against [contactManager] under its guarding lock. */
    internal suspend fun <T> withContactManager(block: (ContactManager) -> T): T =
        contactMutex.withLock { block(contactManager) }

    // MARK: - Device State

    @Volatile
    internal var selfInfo: SelfInfo? = null

    /** Returns the device's self info after session start. Populated after [start] completes. */
    override val currentSelfInfo: SelfInfo? get() = selfInfo

    @Volatile
    private var cachedTime: Instant? = null

    /** Returns the last known device time, or `null` if it has not been queried. */
    val deviceTime: Instant? get() = cachedTime

    internal fun updateCachedTime(time: Instant) {
        cachedTime = time
    }

    // MARK: - Lifecycle State

    private val lifecycleMutex = Mutex()

    @Volatile
    private var isRunning = false

    private var receiveJob: Job? = null
    private var autoMessageFetchJob: Job? = null
    private var autoMessageDrainJob: Job? = null
    private var autoContactRefreshJob: Job? = null
    private var isAutoFetchingMessages = false
    private var autoMessageDrainRequested = false
    private var autoContactRefreshRequested = false

    /** Set by [MeshCoreSessionMessaging]'s `getMessage` while a fetch is in flight; see that file. */
    internal var inFlightGetMessage: kotlinx.coroutines.Deferred<MessageResult>? = null

    // MARK: - Connection State

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    /**
     * Provides an observable connection state stream for UI binding.
     *
     * Unlike Swift's hand-rolled `AsyncStream` + per-subscriber continuation dictionary,
     * [MutableStateFlow] already yields its current value immediately to each new collector and
     * then every subsequent change, so no manual replay bookkeeping is needed here.
     */
    override val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()

    private fun updateConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    // MARK: - Lifecycle

    /**
     * Connects to the device and starts the session.
     *
     * @param reconnectingAttempt When non-null, publishes `.reconnecting(attempt:)` instead of
     *   `.connecting` before establishing the transport/session.
     * @param disconnectTransportOnFailure When `false`, a failed `appStart` handshake unwinds the
     *   session without disconnecting the transport. Pass `false` when the caller does not own
     *   the link — e.g. a session rebuild over a transport that an OS-level reconnect is still
     *   recovering.
     * @throws MeshTransportError if the transport connection fails.
     * @throws MeshCoreError.Timeout if the device doesn't respond to appStart.
     */
    suspend fun start(reconnectingAttempt: Int? = null, disconnectTransportOnFailure: Boolean = true) {
        if (lifecycleMutex.withLock { isRunning }) return

        updateConnectionState(
            if (reconnectingAttempt != null) {
                ConnectionState.Reconnecting(maxOf(1, reconnectingAttempt))
            } else {
                ConnectionState.Connecting
            },
        )
        try {
            transport.connect()
        } catch (error: Throwable) {
            updateConnectionState(ConnectionState.Failed(error as? MeshTransportError ?: MeshTransportError.ConnectionFailed(error.message ?: "unknown")))
            throw error
        }
        lifecycleMutex.withLock { isRunning = true }
        updateConnectionState(ConnectionState.Connected)

        receiveJob = sessionScope.launch { receiveLoop() }

        try {
            selfInfo = sendAppStartImpl()
        } catch (error: Throwable) {
            lifecycleMutex.withLock {
                isRunning = false
                receiveJob?.cancel()
                receiveJob = null
            }
            if (disconnectTransportOnFailure) {
                transport.disconnect()
            }
            updateConnectionState(ConnectionState.Failed(error as? MeshTransportError ?: MeshTransportError.ConnectionFailed(error.message ?: "unknown")))
            throw error
        }
    }

    /**
     * Stops the session and disconnects from the device.
     *
     * Safe to call multiple times. After calling this method, the session cannot be reused.
     *
     * @param disconnectTransport When `false`, the transport link is left open. Pass `false` when
     *   the caller does not own the link — e.g. tearing down a superseded session while a newer
     *   reconnect cycle still rides on the same transport.
     */
    suspend fun stop(disconnectTransport: Boolean = true) {
        lifecycleMutex.withLock { isRunning = false }
        stopAutoMessageFetching()
        lifecycleMutex.withLock {
            autoContactRefreshJob?.cancel()
            autoContactRefreshJob = null
            autoContactRefreshRequested = false
            receiveJob?.cancel()
        }
        dispatcher.finishAllSubscriptions()
        if (disconnectTransport) {
            transport.disconnect()
        }
        updateConnectionState(ConnectionState.Disconnected)
    }

    // MARK: - Events

    /**
     * Subscribes to all events from the device.
     *
     * Each subscriber receives all events independently. Supports bounded buffering of up to 100
     * events.
     */
    override suspend fun events(): Flow<MeshEvent> = dispatcher.subscribe()

    /**
     * Subscribes to events passing the given filter.
     *
     * Prefer this over [events] when the consumer only cares about a narrow slice of events. The
     * filter is evaluated at dispatch time, so non-matching events never enter the subscription's
     * 100-slot bounded buffer.
     */
    override suspend fun events(filter: EventFilter): Flow<MeshEvent> = dispatcher.subscribe(filter::matches)

    /**
     * Waits for an event matching an [EventFilter] with timeout. Implemented in
     * `MeshCoreSessionEventWaiting.kt` as the `waitForEvent(predicate, timeout)` extension
     * function this delegates to.
     */
    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? =
        waitForEvent(predicate = filter::matches, timeout = timeout)

    // MARK: - Auto Message Fetching

    /**
     * Starts automatic message fetching.
     *
     * When enabled, the session automatically fetches pending messages from the device whenever
     * it receives a `messagesWaiting` notification. Call [stopAutoMessageFetching] to disable.
     */
    override suspend fun startAutoMessageFetching() {
        val (alreadyFetching, events) = lifecycleMutex.withLock {
            if (isAutoFetchingMessages) {
                true to null
            } else {
                isAutoFetchingMessages = true
                false to dispatcher.subscribe()
            }
        }
        if (alreadyFetching || events == null) return

        autoMessageFetchJob = sessionScope.launch { autoMessageFetchLoop(events) }
    }

    /** Stops the automatic fetching started by [startAutoMessageFetching]. */
    override fun stopAutoMessageFetching() {
        isAutoFetchingMessages = false
        autoMessageDrainRequested = false
        autoMessageFetchJob?.cancel()
        autoMessageFetchJob = null
        autoMessageDrainJob?.cancel()
        autoMessageDrainJob = null
    }

    private suspend fun autoMessageFetchLoop(events: Flow<MeshEvent>) {
        events.collect { event ->
            if (!isAutoFetchingMessages) return@collect
            if (event is MeshEvent.MessagesWaiting) {
                requestAutoMessageDrain()
            }
        }
    }

    private fun requestAutoMessageDrain() {
        autoMessageDrainRequested = true
        if (autoMessageDrainJob != null) return
        autoMessageDrainJob = sessionScope.launch { runAutoMessageDrainLoop() }
    }

    private suspend fun runAutoMessageDrainLoop() {
        try {
            while (isAutoFetchingMessages && autoMessageDrainRequested) {
                autoMessageDrainRequested = false
                try {
                    while (isAutoFetchingMessages) {
                        val result = getMessageImpl()
                        if (result is MessageResult.NoMoreMessages) break
                        delay(100)
                    }
                } catch (error: Throwable) {
                    // Swift logs and continues here; this module takes no logging dependency (see ContactManager).
                }
            }
        } finally {
            autoMessageDrainJob = null
        }
    }

    private fun requestAutoContactRefresh() {
        autoContactRefreshRequested = true
        if (autoContactRefreshJob != null) return
        autoContactRefreshJob = sessionScope.launch { runAutoContactRefreshLoop() }
    }

    private suspend fun runAutoContactRefreshLoop() {
        try {
            while (autoContactRefreshRequested) {
                autoContactRefreshRequested = false
                try {
                    ensureContacts(force = true)
                } catch (error: Throwable) {
                    // Swift logs and continues (except CancellationError, which breaks); mirror that.
                    if (error is kotlinx.coroutines.CancellationException) break
                }
            }
        } finally {
            autoContactRefreshJob = null
        }
    }

    // MARK: - Receive Path

    private suspend fun receiveLoop() {
        transport.receivedData.collect { data -> handleReceivedData(data) }
        // Stream ended - transport disconnected. Publish the loss only when it ended
        // unexpectedly: during stop() or a failed-start unwind the session has already
        // published its terminal state, so a late .disconnected here would overwrite it.
        if (!isRunning) return
        dispatcher.dispatch(MeshEvent.ConnectionStateChanged(ConnectionState.Disconnected))
        updateConnectionState(ConnectionState.Disconnected)
        isRunning = false
    }

    /**
     * Handles raw data received from the device.
     *
     * Per the MeshCore Companion Radio Protocol, each BLE notification is a complete frame. No
     * reassembly or buffering is needed - we parse each packet directly.
     */
    private suspend fun handleReceivedData(data: ByteArray) {
        if (data.isEmpty()) return

        var event = PacketParser.parse(data)

        // Re-parse push status responses with correct layout for room servers.
        val statusEvent = event as? MeshEvent.StatusResponseEvent
        if (statusEvent != null && statusEvent.response.layout == StatusResponse.Layout.REPEATER) {
            val contact = withContactManager { it.getByKeyPrefix(statusEvent.response.publicKeyPrefix) }
            if (contact != null && contact.type == ContactType.ROOM) {
                event = StatusResponseParser.parse(data.copyOfRange(1, data.size), layout = StatusResponse.Layout.ROOM_SERVER)
            }
        }

        trackContactChanges(event)
        dispatcher.dispatch(event)
    }

    /** Tracks contact-related changes from received events. */
    private suspend fun trackContactChanges(event: MeshEvent) {
        val needsAutoRefresh = withContactManager { manager ->
            manager.trackChanges(event)
            manager.isAutoUpdateEnabled && manager.needsRefresh
        }

        if (needsAutoRefresh) {
            when (event) {
                is MeshEvent.Advertisement, is MeshEvent.PathUpdate, is MeshEvent.NewContact -> requestAutoContactRefresh()
                else -> {}
            }
        }

        when (event) {
            is MeshEvent.CurrentTime -> cachedTime = event.time
            is MeshEvent.SelfInfoEvent -> selfInfo = event.info
            else -> {}
        }
    }

    // MARK: - Test Support

    /**
     * Dispatches an event directly to subscribers, bypassing the transport and parser.
     *
     * For tests only — use to verify subscriber behavior without crafting wire bytes.
     */
    internal fun dispatchForTesting(event: MeshEvent) {
        dispatcher.dispatch(event)
    }

    /**
     * Seeds [selfInfo] for tests so callers that depend on [currentSelfInfo] (e.g. ACK precompute)
     * can run without simulating an `APP_START` round-trip.
     */
    internal fun installSelfInfoForTest(info: SelfInfo) {
        selfInfo = info
    }

    /** Returns the dispatcher's active subscription count. For tests only. */
    internal val subscriberCountForTest: Int get() = dispatcher.subscriberCountForTest

    // MARK: - Interface Conformance
    //
    // Each override below is a one-line delegate to the `xyzImpl` extension function carrying
    // the real logic in its own MeshCoreSession+*.kt-equivalent file (see the class doc). The
    // "Impl" suffix exists only to dodge a Kotlin rule with no workaround: a member function
    // always shadows an extension function of the identical name/signature, so an unsuffixed
    // extension would recurse into itself instead of being called by its override.

    // ContactSessionOps
    override suspend fun getContacts(since: Instant?): List<MeshContact> = getContactsImpl(since)
    override suspend fun getContactsReportingTotal(since: Instant?): ContactFetchResult = getContactsReportingTotalImpl(since)
    override suspend fun getContact(publicKey: ByteArray): MeshContact? = getContactImpl(publicKey)
    override suspend fun addContact(contact: MeshContact) = addContactImpl(contact)
    override suspend fun removeContact(publicKey: ByteArray) = removeContactImpl(publicKey)
    override suspend fun resetPath(publicKey: ByteArray) = resetPathImpl(publicKey)
    override suspend fun shareContact(publicKey: ByteArray) = shareContactImpl(publicKey)
    override suspend fun exportContact(publicKey: ByteArray?): String = exportContactImpl(publicKey)
    override suspend fun importContact(cardData: ByteArray) = importContactImpl(cardData)
    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags) = changeContactFlagsImpl(contact, flags)

    // MessagingSessionOps
    override suspend fun sendMessage(destination: ByteArray, text: String, timestamp: Instant, attempt: UByte): MessageSentInfo =
        sendMessageImpl(destination, text, timestamp, attempt)
    override suspend fun sendChannelMessage(channel: UByte, text: String, timestamp: Instant) =
        sendChannelMessageImpl(channel, text, timestamp)

    // ChannelSessionOps
    override suspend fun getChannel(index: UByte): ChannelInfo = getChannelImpl(index)
    override suspend fun getChannels(indices: List<UByte>): ChannelsFetchResult = getChannelsImpl(indices)
    override suspend fun setChannel(index: UByte, name: String, secret: ByteArray) = setChannelImpl(index, name, secret)

    // MessageFetchSessionOps
    override suspend fun getMessage(timeout: Double?): MessageResult = getMessageImpl(timeout)

    // ConfigurationSessionOps
    override suspend fun sendAppStart(): SelfInfo = sendAppStartImpl()
    override suspend fun queryDevice(): DeviceCapabilities = queryDeviceImpl()
    override suspend fun getBattery(): BatteryInfo = getBatteryImpl()
    override suspend fun getTime(): Instant = getTimeImpl()
    override suspend fun setTime(date: Instant) = setTimeImpl(date)
    override suspend fun setName(name: String) = setNameImpl(name)
    override suspend fun setCoordinates(latitude: Double, longitude: Double) = setCoordinatesImpl(latitude, longitude)
    override suspend fun setTxPower(power: Byte) = setTxPowerImpl(power)
    override suspend fun setRadio(frequency: Double, bandwidth: Double, spreadingFactor: UByte, codingRate: UByte, clientRepeat: Boolean?) =
        setRadioImpl(frequency, bandwidth, spreadingFactor, codingRate, clientRepeat)
    override suspend fun getRepeatFreq(): List<FrequencyRange> = getRepeatFreqImpl()
    override suspend fun setOtherParams(
        manualAddContacts: Boolean,
        telemetryModeEnvironment: UByte,
        telemetryModeLocation: UByte,
        telemetryModeBase: UByte,
        advertisementLocationPolicy: UByte,
        multiAcks: UByte?,
    ) = setOtherParamsImpl(
        manualAddContacts,
        telemetryModeEnvironment,
        telemetryModeLocation,
        telemetryModeBase,
        advertisementLocationPolicy,
        multiAcks,
    )
    override suspend fun setDevicePin(pin: UInt) = setDevicePinImpl(pin)
    override suspend fun getAutoAddConfig(): AutoAddConfig = getAutoAddConfigImpl()
    override suspend fun setAutoAddConfig(config: AutoAddConfig) = setAutoAddConfigImpl(config)
    override suspend fun setPathHashMode(mode: UByte) = setPathHashModeImpl(mode)
    override suspend fun setDefaultFloodScope(name: String, scope: FloodScope) = setDefaultFloodScopeImpl(name, scope)
    override suspend fun getDefaultFloodScope(): DefaultFloodScope? = getDefaultFloodScopeImpl()
    override suspend fun reboot() = rebootImpl()
    override suspend fun factoryReset() = factoryResetImpl()
    override suspend fun getStatsCore(): CoreStats = getStatsCoreImpl()
    override suspend fun getStatsRadio(): RadioStats = getStatsRadioImpl()
    override suspend fun getStatsPackets(): PacketStats = getStatsPacketsImpl()
    override suspend fun getCustomVars(): Map<String, String> = getCustomVarsImpl()
    override suspend fun setCustomVar(key: String, value: String) = setCustomVarImpl(key, value)
    override suspend fun exportPrivateKey(): ByteArray = exportPrivateKeyImpl()
    override suspend fun importPrivateKey(key: ByteArray) = importPrivateKeyImpl(key)
    override suspend fun sign(data: ByteArray, chunkSize: Int, timeout: Double?): ByteArray = signImpl(data, chunkSize, timeout)

    // AdvertisingSessionOps (getContact/setName/setCoordinates already satisfied above)
    override suspend fun sendAdvertisement(flood: Boolean) = sendAdvertisementImpl(flood)

    // DiagnosticsSessionOps / RemoteAccessSessionOps (requestStatus/requestTelemetry/requestNeighbours/
    // sendPathDiscovery/getMessage overlap between the two role interfaces; one override satisfies both)
    override suspend fun requestStatus(publicKey: ByteArray): StatusResponse = requestStatusImpl(publicKey)
    override suspend fun requestStatus(publicKey: ByteArray, type: ContactType): StatusResponse = requestStatusTypedImpl(publicKey, type)
    override suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse = requestTelemetryImpl(publicKey)
    override suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: UByte,
        offset: UShort,
        orderBy: UByte,
        pubkeyPrefixLength: UByte,
    ): NeighboursResponse = requestNeighboursImpl(publicKey, count, offset, orderBy, pubkeyPrefixLength)
    override suspend fun fetchAllNeighbours(publicKey: ByteArray, orderBy: UByte, pubkeyPrefixLength: UByte): NeighboursResponse =
        fetchAllNeighboursImpl(publicKey, orderBy, pubkeyPrefixLength)
    override suspend fun requestMMA(publicKey: ByteArray, start: Instant, end: Instant): MMAResponse = requestMMAImpl(publicKey, start, end)
    override suspend fun requestACL(publicKey: ByteArray): ACLResponse = requestACLImpl(publicKey)
    override suspend fun getSelfTelemetry(): TelemetryResponse = getSelfTelemetryImpl()
    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo = sendPathDiscoveryImpl(destination)
    override suspend fun sendTrace(tag: UInt?, authCode: UInt?, flags: UByte, path: ByteArray?): MessageSentInfo =
        sendTraceImpl(tag, authCode, flags, path)

    // RemoteAccessSessionOps
    override suspend fun sendLogin(destination: ByteArray, password: String): MessageSentInfo = sendLoginImpl(destination, password)
    override suspend fun sendLogout(destination: ByteArray) = sendLogoutImpl(destination)
    override suspend fun sendCommand(destination: ByteArray, command: String, timestamp: Instant): MessageSentInfo =
        sendCommandImpl(destination, command, timestamp)
    override suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: UInt): MessageSentInfo = sendKeepAliveImpl(publicKey, syncSince)
    override suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse = requestOwnerInfoImpl(publicKey)
    override suspend fun sendMessageWithRetry(
        destination: ByteArray,
        text: String,
        timestamp: Instant,
        maxAttempts: Int,
        floodAfter: Int,
        maxFloodAttempts: Int,
        timeout: Double?,
    ): MessageSentInfo? = sendMessageWithRetryImpl(destination, text, timestamp, maxAttempts, floodAfter, maxFloodAttempts, timeout)
}
