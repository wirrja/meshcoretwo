// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services

import android.content.Context
import androidx.room.Room
import com.meshcoretwo.protocol.ACLResponse
import com.meshcoretwo.protocol.AutoAddConfig
import com.meshcoretwo.protocol.BatteryInfo
import com.meshcoretwo.protocol.ChannelInfo
import com.meshcoretwo.protocol.ChannelsFetchResult
import com.meshcoretwo.protocol.ConnectionState
import com.meshcoretwo.protocol.ContactFetchResult
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.CoreStats
import com.meshcoretwo.protocol.DefaultFloodScope
import com.meshcoretwo.protocol.DeviceCapabilities
import com.meshcoretwo.protocol.EventFilter
import com.meshcoretwo.protocol.FloodScope
import com.meshcoretwo.protocol.FrequencyRange
import com.meshcoretwo.protocol.FullMeshCoreSessionOps
import com.meshcoretwo.protocol.MMAResponse
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.protocol.MessageResult
import com.meshcoretwo.protocol.MessageSentInfo
import com.meshcoretwo.protocol.NeighboursResponse
import com.meshcoretwo.protocol.OwnerInfoResponse
import com.meshcoretwo.protocol.PacketStats
import com.meshcoretwo.protocol.RadioStats
import com.meshcoretwo.protocol.SelfInfo
import com.meshcoretwo.protocol.StatusResponse
import com.meshcoretwo.protocol.TelemetryResponse
import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.security.KeychainService
import com.meshcoretwo.services.sync.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.UUID

/**
 * Exercises [ServiceContainer] against a real in-memory Room database (Robolectric) with every
 * real service it builds, faking only the session boundary — matching this codebase's "real
 * service, fake wire" pattern. Focuses on what is genuinely container-specific: the object graph
 * shares one database, [ServiceContainer]'s own [NotificationActionHandler]-to-[NotificationService]
 * callback wiring (the gap both classes' docs flagged as "composition root's job"), the
 * `channelSecretsSink` closure, and the [ServiceContainer.startEventMonitoring]/
 * [ServiceContainer.stopEventMonitoring]/[ServiceContainer.tearDown] lifecycle — not the
 * already-tested internals of any individual service.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceContainerTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var context: Context
    private lateinit var contactStore: ContactStore
    private lateinit var messageStore: MessageStore
    private lateinit var container: ServiceContainer
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Test-local stores over the *same* database instance the container is given, so
        // assertions below observe container-internal writes without the container needing to
        // expose its private stores.
        contactStore = ContactStore(database)
        messageStore = MessageStore(database)

        container = ServiceContainer(
            context = context,
            session = FakeContainerSession(),
            database = database,
            radioID = radioID,
            keychainService = KeychainService(context.getSharedPreferences("container-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun saveContact(publicKey: ByteArray, name: String = "Alice"): UUID {
        val meshContact = MeshContact(
            id = publicKey.joinToString("") { "%02x".format(it) }, publicKey = publicKey, type = ContactType.CHAT,
            flags = ContactFlags.NONE, outPathLength = 0xFFu, outPath = ByteArray(0), advertisedName = name,
            lastAdvertisement = Instant.ofEpochSecond(900), latitude = 0.0, longitude = 0.0, lastModified = Instant.ofEpochSecond(1000),
        )
        contactStore.saveContact(radioID, meshContact)
        return contactStore.fetchContact(radioID, publicKey)!!.id
    }

    private fun directMessage(contactID: UUID) = MessageDto(
        id = UUID.randomUUID(), radioID = radioID, contactID = contactID, channelIndex = null, text = "hi",
        timestamp = 0u, createdAt = Instant.now(), sortDate = Instant.now(), direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED, textType = TextType.PLAIN_TEXT, ackCode = null,
        pathLength = 0u, snr = null, pathNodes = null, senderKeyPrefix = null, senderNodeName = null,
        isRead = false, replyToID = null, roundTripTime = null, sendCount = 1, retryAttempt = 0, maxRetryAttempts = 0,
        deduplicationKey = null, reactionSummary = null, senderTimestamp = null, routeType = null, heardRepeats = 0,
    )

    @Test
    fun `container wires every service onto the same database`() = runTest {
        val contactID = saveContact(ByteArray(32) { 1 })
        val message = directMessage(contactID)
        messageStore.saveMessage(message)

        // handleMarkAsRead is a NotificationActionHandler transaction built from the container's
        // *internal* messageStore/contactStore — if the container had wired those to a different
        // database (or a different instance), this would not observe the row saved above.
        container.notificationActionHandler.handleMarkAsRead(contactID, message.id)

        assertTrue(messageStore.fetchMessage(message.id)!!.isRead)
    }

    @Test
    fun `notificationService quick-reply callback delegates to notificationActionHandler`() = runTest {
        val contactID = saveContact(ByteArray(32) { 2 })

        assertNotNull(container.notificationService.onQuickReply)
        container.notificationService.onQuickReply!!.invoke(contactID, "hello")

        // NotificationActionHandler.configure() is deliberately not called by the container (an
        // app-layer job — see ServiceContainer's class doc), so isConnectionReady defaults to
        // false and handleQuickReply takes the draft-saving fallback path.
        assertEquals("hello", container.notificationService.consumeDraft(contactID))
    }

    @Test
    fun `tearDown clears every notification action callback`() = runTest {
        assertNotNull(container.notificationService.onQuickReply)
        assertNotNull(container.notificationService.onChannelQuickReply)
        assertNotNull(container.notificationService.onMarkAsRead)
        assertNotNull(container.notificationService.onChannelMarkAsRead)
        assertNotNull(container.notificationService.onRoomMarkAsRead)

        container.tearDown()

        assertNull(container.notificationService.onQuickReply)
        assertNull(container.notificationService.onChannelQuickReply)
        assertNull(container.notificationService.onMarkAsRead)
        assertNull(container.notificationService.onChannelMarkAsRead)
        assertNull(container.notificationService.onRoomMarkAsRead)
    }

    @Test
    fun `startEventMonitoring enables auto-fetch, stopEventMonitoring disables it`() = runTest {
        assertFalse(container.incomingMessageService.isAutoFetching)

        container.startEventMonitoring()
        assertTrue(container.incomingMessageService.isAutoFetching)

        container.stopEventMonitoring()
        assertFalse(container.incomingMessageService.isAutoFetching)
    }

    @Test
    fun `channelSecretsSink wiring does not throw when a channel is set`() = runTest {
        container.channelService.setChannelWithSecret(radioID, 0u, "General", ByteArray(16) { 1 })

        assertEquals("General", container.channelService.getChannel(radioID, 0u)?.name)
    }

    @Test
    fun `onDisconnected stops monitoring and resets sync state without throwing`() = runTest {
        container.startEventMonitoring()

        container.onDisconnected()

        assertFalse(container.incomingMessageService.isAutoFetching)
        assertEquals(SyncState.Idle, container.syncCoordinator.state.value)
    }
}

/**
 * Stubs every session role [ServiceContainer] wires up. Only the handful of members the
 * container's construction/lifecycle path actually calls ([events], [startAutoMessageFetching],
 * [stopAutoMessageFetching], [setChannel]) have real bodies; the rest follow this codebase's
 * `error("not used by ...")` convention for a fake's untouched surface.
 */
private class FakeContainerSession : FullMeshCoreSessionOps {
    private val eventsFlow = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 8)
    private var autoFetching = false

    override val connectionState: Flow<ConnectionState> get() = error("not used by ServiceContainerTest")

    override suspend fun events(): Flow<MeshEvent> = eventsFlow

    override suspend fun events(filter: EventFilter): Flow<MeshEvent> = eventsFlow

    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? = error("not used by ServiceContainerTest")

    override val currentSelfInfo: SelfInfo? get() = null

    override suspend fun sendMessage(destination: ByteArray, text: String, timestamp: Instant, attempt: UByte): MessageSentInfo =
        error("not used by ServiceContainerTest")

    override suspend fun sendChannelMessage(channel: UByte, text: String, timestamp: Instant): Unit =
        error("not used by ServiceContainerTest")

    override suspend fun getContacts(since: Instant?): List<MeshContact> = error("not used by ServiceContainerTest")

    override suspend fun getContactsReportingTotal(since: Instant?): ContactFetchResult = error("not used by ServiceContainerTest")

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = error("not used by ServiceContainerTest")

    override suspend fun addContact(contact: MeshContact): Unit = error("not used by ServiceContainerTest")

    override suspend fun removeContact(publicKey: ByteArray): Unit = error("not used by ServiceContainerTest")

    override suspend fun resetPath(publicKey: ByteArray): Unit = error("not used by ServiceContainerTest")

    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo = error("not used by ServiceContainerTest")

    override suspend fun shareContact(publicKey: ByteArray): Unit = error("not used by ServiceContainerTest")

    override suspend fun exportContact(publicKey: ByteArray?): String = error("not used by ServiceContainerTest")

    override suspend fun importContact(cardData: ByteArray): Unit = error("not used by ServiceContainerTest")

    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags): Unit = error("not used by ServiceContainerTest")

    override suspend fun getChannel(index: UByte): ChannelInfo = error("not used by ServiceContainerTest")

    override suspend fun getChannels(indices: List<UByte>): ChannelsFetchResult = error("not used by ServiceContainerTest")

    override suspend fun setChannel(index: UByte, name: String, secret: ByteArray) {
        // No-op: ChannelService persists locally after this call succeeds.
    }

    override suspend fun getMessage(timeout: Double?): MessageResult = error("not used by ServiceContainerTest")

    override suspend fun startAutoMessageFetching() {
        autoFetching = true
    }

    override fun stopAutoMessageFetching() {
        autoFetching = false
    }

    override suspend fun sendAppStart(): SelfInfo = error("not used by ServiceContainerTest")

    override suspend fun queryDevice(): DeviceCapabilities = error("not used by ServiceContainerTest")

    override suspend fun getBattery(): BatteryInfo = error("not used by ServiceContainerTest")

    override suspend fun getTime(): Instant = error("not used by ServiceContainerTest")

    override suspend fun setTime(date: Instant): Unit = error("not used by ServiceContainerTest")

    override suspend fun setName(name: String): Unit = error("not used by ServiceContainerTest")

    override suspend fun setCoordinates(latitude: Double, longitude: Double): Unit = error("not used by ServiceContainerTest")

    override suspend fun setTxPower(power: Byte): Unit = error("not used by ServiceContainerTest")

    override suspend fun setRadio(frequency: Double, bandwidth: Double, spreadingFactor: UByte, codingRate: UByte, clientRepeat: Boolean?): Unit =
        error("not used by ServiceContainerTest")

    override suspend fun getRepeatFreq(): List<FrequencyRange> = error("not used by ServiceContainerTest")

    override suspend fun setOtherParams(
        manualAddContacts: Boolean,
        telemetryModeEnvironment: UByte,
        telemetryModeLocation: UByte,
        telemetryModeBase: UByte,
        advertisementLocationPolicy: UByte,
        multiAcks: UByte?,
    ): Unit = error("not used by ServiceContainerTest")

    override suspend fun setDevicePin(pin: UInt): Unit = error("not used by ServiceContainerTest")

    override suspend fun getAutoAddConfig(): AutoAddConfig = error("not used by ServiceContainerTest")

    override suspend fun setAutoAddConfig(config: AutoAddConfig): Unit = error("not used by ServiceContainerTest")

    override suspend fun setPathHashMode(mode: UByte): Unit = error("not used by ServiceContainerTest")

    override suspend fun setDefaultFloodScope(name: String, scope: FloodScope): Unit = error("not used by ServiceContainerTest")

    override suspend fun getDefaultFloodScope(): DefaultFloodScope? = error("not used by ServiceContainerTest")

    override suspend fun reboot(): Unit = error("not used by ServiceContainerTest")

    override suspend fun factoryReset(): Unit = error("not used by ServiceContainerTest")

    override suspend fun getStatsCore(): CoreStats = error("not used by ServiceContainerTest")

    override suspend fun getStatsRadio(): RadioStats = error("not used by ServiceContainerTest")

    override suspend fun getStatsPackets(): PacketStats = error("not used by ServiceContainerTest")

    override suspend fun getCustomVars(): Map<String, String> = error("not used by ServiceContainerTest")

    override suspend fun setCustomVar(key: String, value: String): Unit = error("not used by ServiceContainerTest")

    override suspend fun exportPrivateKey(): ByteArray = error("not used by ServiceContainerTest")

    override suspend fun importPrivateKey(key: ByteArray): Unit = error("not used by ServiceContainerTest")

    override suspend fun sign(data: ByteArray, chunkSize: Int, timeout: Double?): ByteArray = error("not used by ServiceContainerTest")

    override suspend fun sendAdvertisement(flood: Boolean): Unit = error("not used by ServiceContainerTest")

    override suspend fun requestStatus(publicKey: ByteArray): StatusResponse = error("not used by ServiceContainerTest")

    override suspend fun requestStatus(publicKey: ByteArray, type: ContactType): StatusResponse = error("not used by ServiceContainerTest")

    override suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse = error("not used by ServiceContainerTest")

    override suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: UByte,
        offset: UShort,
        orderBy: UByte,
        pubkeyPrefixLength: UByte,
    ): NeighboursResponse = error("not used by ServiceContainerTest")

    override suspend fun fetchAllNeighbours(publicKey: ByteArray, orderBy: UByte, pubkeyPrefixLength: UByte): NeighboursResponse =
        error("not used by ServiceContainerTest")

    override suspend fun requestMMA(publicKey: ByteArray, start: Instant, end: Instant): MMAResponse = error("not used by ServiceContainerTest")

    override suspend fun requestACL(publicKey: ByteArray): ACLResponse = error("not used by ServiceContainerTest")

    override suspend fun getSelfTelemetry(): TelemetryResponse = error("not used by ServiceContainerTest")

    override suspend fun sendTrace(tag: UInt?, authCode: UInt?, flags: UByte, path: ByteArray?): MessageSentInfo =
        error("not used by ServiceContainerTest")

    override suspend fun sendLogin(destination: ByteArray, password: String): MessageSentInfo = error("not used by ServiceContainerTest")

    override suspend fun sendLogout(destination: ByteArray): Unit = error("not used by ServiceContainerTest")

    override suspend fun sendCommand(destination: ByteArray, command: String, timestamp: Instant): MessageSentInfo =
        error("not used by ServiceContainerTest")

    override suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: UInt): MessageSentInfo = error("not used by ServiceContainerTest")

    override suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse = error("not used by ServiceContainerTest")

    override suspend fun sendMessageWithRetry(
        destination: ByteArray,
        text: String,
        timestamp: Instant,
        maxAttempts: Int,
        floodAfter: Int,
        maxFloodAttempts: Int,
        timeout: Double?,
    ): MessageSentInfo? = error("not used by ServiceContainerTest")
}
