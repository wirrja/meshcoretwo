// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.sync

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import com.meshcoretwo.protocol.AdvertisementSessionOps
import com.meshcoretwo.protocol.AutoAddConfig
import com.meshcoretwo.protocol.BatteryInfo
import com.meshcoretwo.protocol.ChannelInfo
import com.meshcoretwo.protocol.ChannelSessionOps
import com.meshcoretwo.protocol.ChannelsFetchResult
import com.meshcoretwo.protocol.ConnectionState
import com.meshcoretwo.protocol.ContactFetchResult
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactSessionOps
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.CoreStats
import com.meshcoretwo.protocol.DefaultFloodScope
import com.meshcoretwo.protocol.DeviceCapabilities
import com.meshcoretwo.protocol.EventFilter
import com.meshcoretwo.protocol.FloodScope
import com.meshcoretwo.protocol.FrequencyRange
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.MeshCoreSessionProtocol
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.protocol.MessageResult
import com.meshcoretwo.protocol.MessageSentInfo
import com.meshcoretwo.protocol.PacketStats
import com.meshcoretwo.protocol.RadioStats
import com.meshcoretwo.protocol.RxLogSessionOps
import com.meshcoretwo.protocol.SelfInfo
import com.meshcoretwo.services.advertisement.AdvertisementEvent
import com.meshcoretwo.services.advertisement.AdvertisementService
import com.meshcoretwo.services.channels.ChannelService
import com.meshcoretwo.services.contacts.ContactService
import com.meshcoretwo.services.messages.IncomingMessageService
import com.meshcoretwo.services.notifications.NotificationPreferences
import com.meshcoretwo.services.notifications.NotificationService
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.persistence.DeviceStore
import com.meshcoretwo.services.persistence.DiscoveredNodeStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.rxlog.RxLogService
import com.meshcoretwo.services.persistence.RxLogStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNotificationManager
import java.time.Instant
import java.util.UUID

/**
 * Exercises [SyncCoordinator] end-to-end against real [ContactService]/[ChannelService]/
 * [IncomingMessageService]/[AdvertisementService]/[RxLogService]/[NotificationService] instances
 * (each backed by an in-memory Room database via Robolectric, matching every other slice's test
 * convention) and hand-written session-ops fakes — the same "real service, fake wire" pattern as
 * [com.meshcoretwo.services.contacts.ContactServiceTest]/[com.meshcoretwo.services.channels.ChannelServiceTest]/
 * [com.meshcoretwo.services.messages.IncomingMessageServiceTest], just combined under one
 * coordinator instead of exercised individually.
 */
@RunWith(RobolectricTestRunner::class)
class SyncCoordinatorTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var deviceStore: DeviceStore
    private lateinit var contactStore: ContactStore
    private lateinit var channelStore: ChannelStore
    private lateinit var messageStore: MessageStore
    private lateinit var rxLogStore: RxLogStore

    private lateinit var contactSession: FakeContactSessionOps
    private lateinit var channelSession: FakeChannelSessionOps
    private lateinit var incomingSession: FakeIncomingSession
    private lateinit var advertisementSession: FakeAdvertisementSession
    private lateinit var rxLogSession: FakeRxLogSession

    private lateinit var contactService: ContactService
    private lateinit var channelService: ChannelService
    private lateinit var incomingMessageService: IncomingMessageService
    private lateinit var advertisementService: AdvertisementService
    private lateinit var rxLogService: RxLogService
    private lateinit var notificationService: NotificationService
    private lateinit var shadowManager: ShadowNotificationManager

    private lateinit var coordinator: SyncCoordinator
    private lateinit var dependencies: SyncDependencies

    private val radioID = UUID.randomUUID()
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        deviceStore = DeviceStore(database)
        contactStore = ContactStore(database)
        channelStore = ChannelStore(database)
        messageStore = MessageStore(database)
        rxLogStore = RxLogStore(database)

        contactSession = FakeContactSessionOps()
        channelSession = FakeChannelSessionOps()
        incomingSession = FakeIncomingSession()
        advertisementSession = FakeAdvertisementSession()
        rxLogSession = FakeRxLogSession()

        contactService = ContactService(contactSession, contactStore, deviceStore, messageStore)
        channelService = ChannelService(channelSession, channelStore, messageStore)
        incomingMessageService = IncomingMessageService(incomingSession, messageStore, contactStore, channelStore)
        // Short debounce/backoff — matches AdvertisementServiceTest's own reasoning: production
        // defaults (debounceMs = 5000) would make the discovery-notification test wait seconds
        // for no behavioral reason.
        val discoveredNodeStore = DiscoveredNodeStore(database)
        advertisementService = AdvertisementService(advertisementSession, contactStore, deviceStore, discoveredNodeStore, debounceMs = 5, minIntervalMs = 10, busyBackoffMs = 5)
        rxLogService = RxLogService(rxLogSession, rxLogStore, channelStore, contactStore, discoveredNodeStore)
        notificationService = NotificationService(context, preferencesProvider = { allEnabledPreferences() }).apply { setup() }
        shadowManager = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

        coordinator = SyncCoordinator()
        dependencies = SyncDependencies(
            deviceStore = deviceStore,
            contactStore = contactStore,
            channelStore = channelStore,
            contactService = contactService,
            channelService = channelService,
            incomingMessageService = incomingMessageService,
            notificationService = notificationService,
            advertisementService = advertisementService,
            rxLogService = rxLogService,
            startEventMonitoring = { _, _ -> },
            exportPrivateKey = { ByteArray(32) { 0x7A } },
        )
    }

    @After
    fun tearDown() = runTest {
        // Shut down before closing the database — a scheduled batch flush is a real-time timer
        // outside this test's coroutine scope, and one firing after close() can wedge Room's
        // process-wide lock for every other test's database. See RxLogStore.shutdown's doc.
        rxLogStore.shutdown()
        database.close()
    }

    private fun allEnabledPreferences() = NotificationPreferences(
        contactMessagesEnabled = true,
        channelMessagesEnabled = true,
        roomMessagesEnabled = true,
        newContactDiscoveredEnabled = true,
        discoveryContactEnabled = true,
        discoveryRepeaterEnabled = true,
        discoveryRoomEnabled = true,
        reactionNotificationsEnabled = true,
        soundEnabled = true,
        lowBatteryEnabled = true,
    )

    private suspend fun registerDevice(
        publicKey: ByteArray = ByteArray(32) { 0x50 },
        maxContacts: UShort = 100u,
        maxChannels: UByte = 8u,
    ) {
        deviceStore.saveDevice(
            DeviceDto(
                id = UUID.randomUUID(),
                radioID = radioID,
                publicKey = publicKey,
                nodeName = "Test Radio",
                firmwareVersion = 10u,
                firmwareVersionString = "v1.16.0",
                manufacturerName = "",
                buildDate = "",
                maxContacts = maxContacts,
                maxChannels = maxChannels,
                frequency = 915_000u,
                bandwidth = 250_000u,
                spreadingFactor = 10u,
                codingRate = 5u,
                txPower = 20,
                maxTxPower = 20,
                latitude = 0.0,
                longitude = 0.0,
                blePin = 0u,
                lastConnected = Instant.now(),
                lastContactSync = 0u,
                isActive = true,
                ocvPreset = null,
                customOCVArrayString = null,
            ),
        )
    }

    private fun meshContact(publicKey: ByteArray, name: String = "Alice", lastModified: Instant = Instant.ofEpochSecond(1000)) = MeshContact(
        id = publicKey.joinToString("") { "%02x".format(it) },
        publicKey = publicKey,
        type = ContactType.CHAT,
        flags = ContactFlags.NONE,
        outPathLength = 0xFFu,
        outPath = ByteArray(0),
        advertisedName = name,
        lastAdvertisement = Instant.ofEpochSecond(900),
        latitude = 0.0,
        longitude = 0.0,
        lastModified = lastModified,
    )

    // MARK: - Contact Watermark (pure logic)

    @Test
    fun `contactWatermarkUse is None for a null or zero watermark`() {
        assertEquals(SyncCoordinator.ContactWatermarkUse.None, SyncCoordinator.contactWatermarkUse(null))
        assertEquals(SyncCoordinator.ContactWatermarkUse.None, SyncCoordinator.contactWatermarkUse(0u))
    }

    @Test
    fun `contactWatermarkUse is Incremental for a plausible watermark`() {
        val now = Instant.ofEpochSecond(1_000_000)
        val watermark = 999_000u

        val use = SyncCoordinator.contactWatermarkUse(watermark, referenceNow = now)

        assertEquals(SyncCoordinator.ContactWatermarkUse.Incremental(watermark), use)
    }

    @Test
    fun `contactWatermarkUse is Invalid when the watermark leads the reference by more than 2 days`() {
        val now = Instant.ofEpochSecond(1_000_000)
        val farFuture = (now.epochSecond + 3 * 24 * 60 * 60).toUInt()

        val use = SyncCoordinator.contactWatermarkUse(farFuture, referenceNow = now)

        assertEquals(SyncCoordinator.ContactWatermarkUse.Invalid(farFuture), use)
    }

    @Test
    fun `incrementalSince rewinds the watermark by one second`() {
        val since = SyncCoordinator.incrementalSince(1000u)

        assertEquals(Instant.ofEpochSecond(999), since)
    }

    // MARK: - Data Events

    @Test
    fun `notifyContactsChanged increments contactsVersion and emits ContactsChanged`() = runTest {
        assertEquals(0, coordinator.contactsVersion.value)

        val collected = mutableListOf<SyncDataEvent>()
        // UNDISPATCHED: subscribed by the time launch returns, so the emit below cannot be missed.
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            .launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                coordinator.dataEvents().collect { collected.add(it) }
            }

        coordinator.notifyContactsChanged()

        val deadline = System.currentTimeMillis() + 10_000
        while (collected.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        job.cancel()
        assertEquals(1, coordinator.contactsVersion.value)
        assertTrue(collected.contains(SyncDataEvent.ContactsChanged))
    }

    @Test
    fun `notifyConversationsChanged increments conversationsVersion`() = runTest {
        coordinator.notifyConversationsChanged()
        coordinator.notifyConversationsChanged()

        assertEquals(2, coordinator.conversationsVersion.value)
    }

    // MARK: - onDisconnected

    @Test
    fun `onDisconnected resets state to Idle and clears notification suppression`() = runTest {
        notificationService.isSuppressingNotifications = true

        coordinator.onDisconnected(notificationService)

        assertEquals(SyncState.Idle, coordinator.state.value)
        assertTrue(!notificationService.isSuppressingNotifications)
    }

    // MARK: - Full Sync Orchestration

    @Test
    fun `onConnectionEstablished runs a full sync end to end`() = runTest {
        registerDevice()
        contactSession.contactsToReturn = ContactFetchResult(contacts = listOf(meshContact(ByteArray(32) { 0x01 })), reportedTotal = 1)
        channelSession.channels[0u] = ChannelInfo(0u, "General", ByteArray(16) { 0x02 })

        val result = coordinator.onConnectionEstablished(radioID, dependencies)

        assertEquals(SyncPhaseStatus.Clean, result.contacts)
        assertEquals(SyncPhaseStatus.Clean, result.channels)
        assertEquals(SyncPhaseStatus.Clean, result.messages)
        assertEquals(SyncState.Synced, coordinator.state.value)
        assertEquals(1, contactStore.fetchContacts(radioID).size)
        assertEquals(1, channelStore.fetchChannels(radioID).size)
        assertTrue(coordinator.lastSyncDate != null)
        assertTrue(!notificationService.isSuppressingNotifications)
    }

    @Test
    fun `a first sync with no watermark fetches contacts with since = null`() = runTest {
        registerDevice()
        contactSession.contactsToReturn = ContactFetchResult(contacts = emptyList(), reportedTotal = 0)

        coordinator.onConnectionEstablished(radioID, dependencies)

        assertEquals(listOf<Instant?>(null), contactSession.sinceCalls)
    }

    @Test
    fun `a second sync uses an incremental since based on the stored watermark`() = runTest {
        registerDevice()
        contactSession.contactsToReturn = ContactFetchResult(
            contacts = listOf(meshContact(ByteArray(32) { 0x03 }, name = "First", lastModified = Instant.ofEpochSecond(5000))),
            reportedTotal = 1,
        )

        coordinator.onConnectionEstablished(radioID, dependencies)
        coordinator.onConnectionEstablished(radioID, dependencies)

        assertEquals(2, contactSession.sinceCalls.size)
        assertEquals(null, contactSession.sinceCalls[0])
        assertEquals(Instant.ofEpochSecond(4999), contactSession.sinceCalls[1])
    }

    @Test
    fun `forceFullSync ignores an existing watermark`() = runTest {
        registerDevice()
        contactSession.contactsToReturn = ContactFetchResult(
            contacts = listOf(meshContact(ByteArray(32) { 0x05 }, lastModified = Instant.ofEpochSecond(5000))),
            reportedTotal = 1,
        )
        coordinator.onConnectionEstablished(radioID, dependencies)

        coordinator.onConnectionEstablished(radioID, dependencies, forceFullSync = true)

        assertEquals(listOf(null, null), contactSession.sinceCalls)
    }

    @Test
    fun `at-capacity local contact count forces a full pruning fetch`() = runTest {
        registerDevice(maxContacts = 1u)
        // A prior sync stamped a watermark, and the local table is already at the device's capacity.
        contactSession.contactsToReturn = ContactFetchResult(
            contacts = listOf(meshContact(ByteArray(32) { 0x04 }, lastModified = Instant.ofEpochSecond(5000))),
            reportedTotal = 1,
        )
        coordinator.onConnectionEstablished(radioID, dependencies)

        coordinator.onConnectionEstablished(radioID, dependencies)

        // Both rounds fetch full (since == null): the first because there's no watermark yet, the
        // second because local count (1) already meets maxContacts (1).
        assertEquals(listOf(null, null), contactSession.sinceCalls)
    }

    @Test
    fun `onConnectionEstablished skips channel sync when the app is backgrounded`() = runTest {
        registerDevice()
        contactSession.contactsToReturn = ContactFetchResult(contacts = emptyList(), reportedTotal = 0)
        val backgrounded = dependencies.copy(appStateProvider = { false })

        val result = coordinator.onConnectionEstablished(radioID, backgrounded)

        assertEquals(SyncPhaseStatus.Skipped, result.channels)
        assertTrue(channelSession.getChannelCallCount.isEmpty())
    }

    @Test
    fun `channel sync failure surfaces channels partial while contacts stays clean`() = runTest {
        registerDevice()
        contactSession.contactsToReturn = ContactFetchResult(contacts = emptyList(), reportedTotal = 0)
        channelSession.errorsByIndex[0u] = MeshCoreError.Timeout

        val result = coordinator.onConnectionEstablished(radioID, dependencies)

        assertEquals(SyncPhaseStatus.Clean, result.contacts)
        assertTrue(result.channels is SyncPhaseStatus.Partial)
    }

    @Test
    fun `onConnectionEstablished returns SKIPPED when a sync is already in progress`() = runTest {
        registerDevice()
        contactSession.holdGetContacts = true

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val firstCall = scope.launch { coordinator.onConnectionEstablished(radioID, dependencies) }
        contactSession.awaitGetContactsEntered()

        val second = coordinator.onConnectionEstablished(radioID, dependencies)

        assertEquals(FullSyncResult.SKIPPED, second)
        contactSession.releaseGetContacts()
        firstCall.join()
    }

    @Test
    fun `onConnectionEstablished exports the private key and forwards it to RxLogService`() = runTest {
        registerDevice()
        contactSession.contactsToReturn = ContactFetchResult(contacts = emptyList(), reportedTotal = 0)
        var exportCalls = 0
        val withExportTracking = dependencies.copy(exportPrivateKey = { exportCalls++; ByteArray(32) { 0x11 } })

        coordinator.onConnectionEstablished(radioID, withExportTracking)

        assertEquals(1, exportCalls)
    }

    @Test
    fun `onConnectionEstablished starts discovery monitoring that posts a notification for a newly discovered contact`() = runTest {
        registerDevice()
        contactSession.contactsToReturn = ContactFetchResult(contacts = emptyList(), reportedTotal = 0)
        val newPublicKey = ByteArray(32) { 0x91.toByte() }
        advertisementSession.contactToReturn = meshContact(newPublicKey, name = "Newly Discovered")
        advertisementService.setDeltaSyncHandler {
            contactStore.saveContact(radioID, meshContact(newPublicKey, name = "Newly Discovered"))
            com.meshcoretwo.services.advertisement.AdvertContactSyncOutcome.SYNCED
        }
        val withRealEventMonitoring = dependencies.copy(
            startEventMonitoring = { rid, _ -> advertisementService.startEventMonitoring(rid) },
        )

        coordinator.onConnectionEstablished(radioID, withRealEventMonitoring)
        val versionBefore = coordinator.contactsVersion.value
        advertisementSession.emit(MeshEvent.Advertisement(newPublicKey))

        awaitNotificationPosted()
        assertTrue(coordinator.contactsVersion.value > versionBefore)
        assertTrue(shadowManager.allNotifications.isNotEmpty())
    }

    private fun awaitNotificationPosted() {
        val deadline = System.currentTimeMillis() + 2000
        while (shadowManager.allNotifications.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
    }
}

/** Hand-written [ContactSessionOps] test double — see [SyncCoordinatorTest]'s class doc. */
private class FakeContactSessionOps : ContactSessionOps {
    var contactsToReturn = ContactFetchResult(contacts = emptyList(), reportedTotal = null)
    val sinceCalls = mutableListOf<Instant?>()

    var holdGetContacts = false
    private val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val entered = kotlinx.coroutines.CompletableDeferred<Unit>()

    suspend fun awaitGetContactsEntered() = entered.await()

    fun releaseGetContacts() {
        gate.complete(Unit)
    }

    override suspend fun getContacts(since: Instant?): List<MeshContact> = contactsToReturn.contacts

    override suspend fun getContactsReportingTotal(since: Instant?): ContactFetchResult {
        sinceCalls.add(since)
        if (holdGetContacts) {
            entered.complete(Unit)
            gate.await()
        }
        return contactsToReturn
    }

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = null

    override suspend fun addContact(contact: MeshContact) = error("not used by this vertical slice")

    override suspend fun removeContact(publicKey: ByteArray) = error("not used by this vertical slice")

    override suspend fun resetPath(publicKey: ByteArray) = error("not used by this vertical slice")

    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo = error("not used by this vertical slice")

    override suspend fun shareContact(publicKey: ByteArray) = error("not used by this vertical slice")

    override suspend fun exportContact(publicKey: ByteArray?): String = error("not used by this vertical slice")

    override suspend fun importContact(cardData: ByteArray) = error("not used by this vertical slice")

    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags) = error("not used by this vertical slice")
}

/** Hand-written [ChannelSessionOps] test double. */
private class FakeChannelSessionOps : ChannelSessionOps {
    val channels = mutableMapOf<UByte, ChannelInfo>()
    val errorsByIndex = mutableMapOf<UByte, MeshCoreError>()
    val getChannelCallCount = mutableMapOf<UByte, Int>()

    override suspend fun getChannel(index: UByte): ChannelInfo {
        getChannelCallCount[index] = (getChannelCallCount[index] ?: 0) + 1
        errorsByIndex[index]?.let { throw it }
        return channels[index] ?: ChannelInfo(index, "", ByteArray(16))
    }

    override suspend fun getChannels(indices: List<UByte>): ChannelsFetchResult = error("not used by this vertical slice")

    override suspend fun setChannel(index: UByte, name: String, secret: ByteArray) = error("not used by this vertical slice")
}

/** Hand-written [MeshCoreSessionProtocol] test double — only [getMessage] is exercised. */
private class FakeIncomingSession : MeshCoreSessionProtocol {
    override val currentSelfInfo: SelfInfo? get() = null

    override suspend fun sendMessage(destination: ByteArray, text: String, timestamp: Instant, attempt: UByte): MessageSentInfo =
        error("not used by this vertical slice")

    override suspend fun sendChannelMessage(channel: UByte, text: String, timestamp: Instant) = error("not used by this vertical slice")

    override val connectionState: Flow<ConnectionState> get() = error("not used by this vertical slice")

    // startAutoFetch (called at the end of a successful onConnectionEstablished) starts event
    // monitoring internally — an empty, never-emitting flow is the correct behavior here, not an
    // error: this fake just never delivers a live event.
    override suspend fun events(): Flow<MeshEvent> = MutableSharedFlow()

    override suspend fun events(filter: EventFilter): Flow<MeshEvent> = MutableSharedFlow()

    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? = error("not used by this vertical slice")

    override suspend fun getContacts(since: Instant?): List<MeshContact> = error("not used by this vertical slice")

    override suspend fun getContactsReportingTotal(since: Instant?) = error("not used by this vertical slice")

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = error("not used by this vertical slice")

    override suspend fun addContact(contact: MeshContact) = error("not used by this vertical slice")

    override suspend fun removeContact(publicKey: ByteArray) = error("not used by this vertical slice")

    override suspend fun resetPath(publicKey: ByteArray) = error("not used by this vertical slice")

    override suspend fun sendPathDiscovery(destination: ByteArray) = error("not used by this vertical slice")

    override suspend fun shareContact(publicKey: ByteArray) = error("not used by this vertical slice")

    override suspend fun exportContact(publicKey: ByteArray?) = error("not used by this vertical slice")

    override suspend fun importContact(cardData: ByteArray) = error("not used by this vertical slice")

    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags) = error("not used by this vertical slice")

    override suspend fun getChannel(index: UByte): ChannelInfo = error("not used by this vertical slice")

    override suspend fun getChannels(indices: List<UByte>): ChannelsFetchResult = error("not used by this vertical slice")

    override suspend fun setChannel(index: UByte, name: String, secret: ByteArray) = error("not used by this vertical slice")

    override suspend fun getMessage(timeout: Double?): MessageResult = MessageResult.NoMoreMessages

    override suspend fun startAutoMessageFetching() {}

    override fun stopAutoMessageFetching() {}
}

/** Hand-written [AdvertisementSessionOps] test double — see `AdvertisementServiceTest`'s equivalent for the same wait-for-subscriber reasoning on [emit]. */
private class FakeAdvertisementSession : AdvertisementSessionOps {
    var contactToReturn: MeshContact? = null

    private val eventsFlow = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 16)

    suspend fun emit(event: MeshEvent) {
        val deadline = System.currentTimeMillis() + 2000
        while (eventsFlow.subscriptionCount.value == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(1)
        }
        eventsFlow.emit(event)
    }

    override suspend fun sendAdvertisement(flood: Boolean) = error("not used by this vertical slice")

    override suspend fun setName(name: String) = error("not used by this vertical slice")

    override suspend fun setCoordinates(latitude: Double, longitude: Double) = error("not used by this vertical slice")

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = contactToReturn

    override val connectionState: Flow<ConnectionState> get() = error("not used by this vertical slice")

    override suspend fun events(): Flow<MeshEvent> = eventsFlow

    override suspend fun events(filter: EventFilter): Flow<MeshEvent> = eventsFlow.filter { filter.matches(it) }

    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? = error("not used by this vertical slice")
}

/** Hand-written [RxLogSessionOps] test double — none of its methods are exercised (RxLogService's own session is never touched by anything [SyncCoordinator] calls in this test). */
private class FakeRxLogSession : RxLogSessionOps {
    override suspend fun sendAppStart(): SelfInfo = error("not used")
    override suspend fun queryDevice(): DeviceCapabilities = error("not used")
    override suspend fun getBattery(): BatteryInfo = error("not used")
    override suspend fun getTime(): Instant = error("not used")
    override suspend fun setTime(date: Instant) = error("not used")
    override suspend fun setName(name: String) = error("not used")
    override suspend fun setCoordinates(latitude: Double, longitude: Double) = error("not used")
    override suspend fun setTxPower(power: Byte) = error("not used")
    override suspend fun setRadio(frequency: Double, bandwidth: Double, spreadingFactor: UByte, codingRate: UByte, clientRepeat: Boolean?) = error("not used")
    override suspend fun getRepeatFreq(): List<FrequencyRange> = error("not used")
    override suspend fun setOtherParams(manualAddContacts: Boolean, telemetryModeEnvironment: UByte, telemetryModeLocation: UByte, telemetryModeBase: UByte, advertisementLocationPolicy: UByte, multiAcks: UByte?) = error("not used")
    override suspend fun setDevicePin(pin: UInt) = error("not used")
    override suspend fun getAutoAddConfig(): AutoAddConfig = error("not used")
    override suspend fun setAutoAddConfig(config: AutoAddConfig) = error("not used")
    override suspend fun setPathHashMode(mode: UByte) = error("not used")
    override suspend fun setDefaultFloodScope(name: String, scope: FloodScope) = error("not used")
    override suspend fun getDefaultFloodScope(): DefaultFloodScope? = error("not used")
    override suspend fun reboot() = error("not used")
    override suspend fun factoryReset() = error("not used")
    override suspend fun getStatsCore(): CoreStats = error("not used")
    override suspend fun getStatsRadio(): RadioStats = error("not used")
    override suspend fun getStatsPackets(): PacketStats = error("not used")
    override suspend fun getCustomVars(): Map<String, String> = error("not used")
    override suspend fun setCustomVar(key: String, value: String) = error("not used")
    override suspend fun exportPrivateKey(): ByteArray = error("not used")
    override suspend fun importPrivateKey(key: ByteArray) = error("not used")
    override suspend fun sign(data: ByteArray, chunkSize: Int, timeout: Double?): ByteArray = error("not used")

    override val connectionState: Flow<ConnectionState> get() = error("not used")
    override suspend fun events(): Flow<MeshEvent> = MutableSharedFlow()
    override suspend fun events(filter: EventFilter): Flow<MeshEvent> = MutableSharedFlow()
    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? = error("not used")
}
