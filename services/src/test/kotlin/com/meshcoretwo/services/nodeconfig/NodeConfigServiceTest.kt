// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.nodeconfig

import androidx.room.Room
import com.meshcoretwo.protocol.AutoAddConfig
import com.meshcoretwo.protocol.BatteryInfo
import com.meshcoretwo.protocol.ChannelInfo
import com.meshcoretwo.protocol.ChannelsFetchResult
import com.meshcoretwo.protocol.ConfigurationSessionOps
import com.meshcoretwo.protocol.ContactFetchResult
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.CoreStats
import com.meshcoretwo.protocol.DefaultFloodScope
import com.meshcoretwo.protocol.DeviceCapabilities
import com.meshcoretwo.protocol.FloodScope
import com.meshcoretwo.protocol.FrequencyRange
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.MessageSentInfo
import com.meshcoretwo.protocol.NodeConfigSessionOps
import com.meshcoretwo.protocol.PacketStats
import com.meshcoretwo.protocol.RadioStats
import com.meshcoretwo.protocol.SelfInfo
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.channels.ChannelService
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.settings.SettingsService
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.UUID

/**
 * Exercises [NodeConfigService] against a real [SettingsService]/[ChannelService] pair (backed by
 * [FakeNodeConfigTestSession] and, for [ChannelService], a real in-memory Room [ChannelStore] via
 * Robolectric) — the same "real service, fake wire" pattern established across this codebase's
 * other service tests. Covers export/import/preview at the service boundary; [planConfigImport]'s
 * own validation/dedup rules are covered separately in [NodeConfigImportPlannerTest], and the pure
 * ordering/diff-gate helpers (`executeConfigImport`, `resolveEffectiveRadioID`,
 * `nodeNameNeedsWrite`/etc., `NodeConfigService.stepCount`) need neither a session nor Room.
 */
@RunWith(RobolectricTestRunner::class)
class NodeConfigServiceTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var session: FakeNodeConfigTestSession
    private lateinit var settingsService: SettingsService
    private lateinit var channelService: ChannelService
    private lateinit var service: NodeConfigService
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = FakeNodeConfigTestSession()
        settingsService = SettingsService(session)
        channelService = ChannelService(session, ChannelStore(database), MessageStore(database))
        service = NodeConfigService(session, settingsService, channelService)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // MARK: - Export

    @Test
    fun `exportConfig with identity section includes name public key and private key`() = runTest {
        val config = service.exportConfig(ConfigSections(nodeIdentity = true))
        assertEquals(session.selfInfo.name, config.name)
        assertEquals(session.selfInfo.publicKey.hexString, config.publicKey)
        assertEquals(session.exportPrivateKeyResult.hexString, config.privateKey)
    }

    @Test
    fun `exportConfig omits private key but keeps identity when export is firmware-disabled`() = runTest {
        session.exportPrivateKeyError = MeshCoreError.FeatureDisabled
        val config = service.exportConfig(ConfigSections(nodeIdentity = true))
        assertEquals(session.selfInfo.name, config.name)
        assertNull(config.privateKey)
    }

    @Test
    fun `exportConfig propagates a non-FeatureDisabled export failure`() = runTest {
        session.exportPrivateKeyError = MeshCoreError.Timeout
        try {
            service.exportConfig(ConfigSections(nodeIdentity = true))
            fail("expected the export to throw")
        } catch (e: com.meshcoretwo.services.settings.SettingsServiceError.SessionError) {
            assertEquals(MeshCoreError.Timeout, e.error)
        }
    }

    @Test
    fun `exportConfig builds radio settings scaled to the wire form`() = runTest {
        session.selfInfo = session.selfInfo.copy(radioFrequency = 869.618, radioBandwidth = 250.0, radioSpreadingFactor = 10u, radioCodingRate = 5u, txPower = 20)
        val config = service.exportConfig(ConfigSections(radioSettings = true))
        assertEquals(869_618u, config.radioSettings?.frequency)
        assertEquals(250_000u, config.radioSettings?.bandwidth)
        assertEquals(10u.toUByte(), config.radioSettings?.spreadingFactor)
        assertEquals(20.toByte(), config.radioSettings?.txPower)
    }

    @Test
    fun `exportConfig channels section only includes configured slots`() = runTest {
        session.deviceCapabilities = session.deviceCapabilities.copy(maxChannels = 2)
        session.channels[0u] = ChannelInfo(0u, "Public", ByteArray(16) { 0x01 })
        session.channels[1u] = ChannelInfo(1u, "", ByteArray(16))
        val config = service.exportConfig(ConfigSections(channels = true))
        assertEquals(1, config.channels?.size)
        assertEquals("Public", config.channels?.get(0)?.name)
    }

    @Test
    fun `exportConfig contacts section maps flood routing to a null out-path`() = runTest {
        session.contacts.add(
            MeshContact(
                id = "ab", publicKey = ByteArray(32) { 0x02 }, type = ContactType.CHAT, flags = ContactFlags(0u),
                outPathLength = 0xFFu, outPath = ByteArray(0), advertisedName = "Bob",
                lastAdvertisement = Instant.ofEpochSecond(100), latitude = 1.0, longitude = 2.0,
                lastModified = Instant.ofEpochSecond(200),
            ),
        )
        val config = service.exportConfig(ConfigSections(contacts = true))
        assertEquals(1, config.contacts?.size)
        assertNull(config.contacts?.get(0)?.outPath)
    }

    // MARK: - Import

    @Test
    fun `importConfig writes only the selected sections`() = runTest {
        val config = MeshCoreNodeConfig(name = "New Name", otherSettings = MeshCoreNodeConfig.OtherSettings(manualAddContacts = 1u))
        service.importConfig(config, ConfigSections(nodeIdentity = true), radioID)
        assertEquals(listOf("New Name"), session.setNameCalls)
        assertTrue(session.setOtherParamsCalls.isEmpty())
    }

    @Test
    fun `importConfig skips the node name write when it already matches the device`() = runTest {
        session.selfInfo = session.selfInfo.copy(name = "Same")
        service.importConfig(MeshCoreNodeConfig(name = "Same"), ConfigSections(nodeIdentity = true), radioID)
        assertTrue(session.setNameCalls.isEmpty())
    }

    @Test
    fun `importConfig reports progress once per completed step`() = runTest {
        val config = MeshCoreNodeConfig(name = "New Name")
        val progress = mutableListOf<ImportProgress>()
        service.importConfig(config, ConfigSections(nodeIdentity = true), radioID) { progress.add(it) }
        assertEquals(1, progress.size)
        assertEquals(1, progress[0].total)
    }

    @Test
    fun `importConfig writes channels through ChannelService and persists them locally`() = runTest {
        session.deviceCapabilities = session.deviceCapabilities.copy(maxChannels = 1)
        session.channels[0u] = ChannelInfo(0u, "", ByteArray(16))
        val config = MeshCoreNodeConfig(channels = listOf(MeshCoreNodeConfig.ChannelConfig("Test", "ab".repeat(16))))
        service.importConfig(config, ConfigSections(channels = true), radioID)
        assertEquals(1, session.setChannelCalls.size)
        assertEquals("Test", channelService.getChannel(radioID, 0u)?.name)
    }

    @Test
    fun `previewImport reports a channel overwrite without writing anything`() = runTest {
        session.deviceCapabilities = session.deviceCapabilities.copy(maxChannels = 1)
        session.channels[0u] = ChannelInfo(0u, "#topic", ByteArray(16) { 0x01 })
        val config = MeshCoreNodeConfig(channels = listOf(MeshCoreNodeConfig.ChannelConfig("#topic", "ff".repeat(16))))
        val preview = service.previewImport(config, ConfigSections(channels = true))
        assertTrue(preview.channelsOverwriteExisting)
        assertTrue(session.setChannelCalls.isEmpty())
    }

    // MARK: - importOtherParams merge

    @Test
    fun `importOtherParams is a no-op when the merged result matches the device`() = runTest {
        session.selfInfo = session.selfInfo.copy(manualAddContacts = false)
        service.importOtherParams(MeshCoreNodeConfig.OtherSettings(manualAddContacts = 0u, advertLocationPolicy = session.selfInfo.advertisementLocationPolicy))
        assertTrue(session.setOtherParamsCalls.isEmpty())
    }

    @Test
    fun `importOtherParams writes the merged values when they differ from the device`() = runTest {
        session.selfInfo = session.selfInfo.copy(manualAddContacts = false)
        service.importOtherParams(MeshCoreNodeConfig.OtherSettings(manualAddContacts = 1u))
        assertEquals(1, session.setOtherParamsCalls.size)
        assertTrue(session.setOtherParamsCalls[0].manualAddContacts)
    }

    // MARK: - resolveEffectiveRadioID

    @Test
    fun `resolveEffectiveRadioID keeps the original id when no private key was imported`() = runTest {
        val original = UUID.randomUUID()
        val resolved = resolveEffectiveRadioID(original, didImportPrivateKey = false) { UUID.randomUUID() }
        assertEquals(original, resolved)
    }

    @Test
    fun `resolveEffectiveRadioID uses the callback result when a private key was imported`() = runTest {
        val reconciled = UUID.randomUUID()
        val resolved = resolveEffectiveRadioID(UUID.randomUUID(), didImportPrivateKey = true) { reconciled }
        assertEquals(reconciled, resolved)
    }

    @Test
    fun `resolveEffectiveRadioID falls back to the original id when the callback returns null`() = runTest {
        val original = UUID.randomUUID()
        val resolved = resolveEffectiveRadioID(original, didImportPrivateKey = true) { null }
        assertEquals(original, resolved)
    }

    // MARK: - stepCount

    @Test
    fun `stepCount mirrors every conditional write in a plan`() {
        val plan = ConfigImportPlan(
            importPrivateKey = ByteArray(64), nodeName = "N", position = ConfigImportPlan.Coordinate(0.0, 0.0),
            otherSettings = MeshCoreNodeConfig.OtherSettings(), radioSettings = null,
            channelWrites = listOf(ConfigImportPlan.ChannelWrite(0u, "A", ByteArray(16))),
            channelsOverwriteExisting = false, contactRecords = emptyList(),
        )
        // privateKey + nodeName + position + otherSettings + 1 channel = 5 (no radio => no +2)
        assertEquals(5, NodeConfigService.stepCount(plan))
    }

    // MARK: - Diff gates

    @Test
    fun `nodeNameNeedsWrite compares against the truncated device name`() {
        val current = session.selfInfo.copy(name = "Same")
        assertFalse(nodeNameNeedsWrite("Same", current))
        assertTrue(nodeNameNeedsWrite("Different", current))
    }

    @Test
    fun `txPowerNeedsWrite only fires when the power actually differs`() {
        val current = session.selfInfo.copy(txPower = 20)
        val radio = MeshCoreNodeConfig.RadioSettings(869_618u, 250_000u, 10u, 5u, txPower = 20)
        assertFalse(txPowerNeedsWrite(radio, current))
        assertTrue(txPowerNeedsWrite(radio.copy(txPower = 10), current))
    }

    @Test
    fun `radioParamsNeedWrite ignores tx power but reacts to other radio fields`() {
        val current = session.selfInfo.copy(radioFrequency = 869.618, radioBandwidth = 250.0, radioSpreadingFactor = 10u, radioCodingRate = 5u, txPower = 20)
        val unchangedExceptPower = MeshCoreNodeConfig.RadioSettings(869_618u, 250_000u, 10u, 5u, txPower = 5)
        assertFalse(radioParamsNeedWrite(unchangedExceptPower, current))
        val changedSpreadingFactor = unchangedExceptPower.copy(spreadingFactor = 7u)
        assertTrue(radioParamsNeedWrite(changedSpreadingFactor, current))
    }

    // MARK: - executeConfigImport ordering

    @Test
    fun `executeConfigImport writes identity before channels and radio last`() = runTest {
        val order = mutableListOf<String>()
        val plan = ConfigImportPlan(
            importPrivateKey = ByteArray(64), nodeName = "N", position = null, otherSettings = null,
            // txPower deliberately differs from FakeNodeConfigTestSession's default SelfInfo.txPower
            // (20) so the diff gate does not skip the write this ordering test expects to see.
            radioSettings = MeshCoreNodeConfig.RadioSettings(869_618u, 250_000u, 10u, 5u, 15),
            channelWrites = listOf(ConfigImportPlan.ChannelWrite(0u, "A", ByteArray(16))),
            channelsOverwriteExisting = false,
            contactRecords = listOf(
                MeshContact(
                    id = "cc", publicKey = ByteArray(32), type = ContactType.CHAT, flags = ContactFlags(0u),
                    outPathLength = 0xFFu, outPath = ByteArray(0), advertisedName = "C",
                    lastAdvertisement = Instant.EPOCH, latitude = 0.0, longitude = 0.0, lastModified = Instant.EPOCH,
                ),
            ),
        )
        val writers = ConfigImportWriters(
            getSelfInfo = { session.selfInfo },
            importPrivateKey = { order.add("privateKey") },
            setNodeName = { order.add("nodeName") },
            setLocation = { _, _ -> order.add("location") },
            setOtherParams = { order.add("otherParams") },
            resolveEffectiveRadioID = { original, _ -> order.add("resolveIdentity"); original },
            setRadioParams = { order.add("radioParams") },
            setTxPower = { order.add("txPower") },
            setChannel = { _, _ -> order.add("channel") },
            addContact = { _, _ -> order.add("contact") },
        )
        executeConfigImport(plan, ConfigSections(nodeIdentity = true, radioSettings = true, channels = true, contacts = true), radioID, writers, onProgress = null)
        assertEquals(listOf("privateKey", "nodeName", "resolveIdentity", "channel", "contact", "radioParams", "txPower"), order)
    }
}

// MARK: - Fakes

/** [SelfInfo] is a plain class (custom `equals`/`hashCode`, no `copy()`) — this test-only extension fills that gap. */
private fun SelfInfo.copy(
    advertisementType: UByte = this.advertisementType,
    txPower: Byte = this.txPower,
    maxTxPower: Byte = this.maxTxPower,
    publicKey: ByteArray = this.publicKey,
    latitude: Double = this.latitude,
    longitude: Double = this.longitude,
    multiAcks: UByte = this.multiAcks,
    advertisementLocationPolicy: UByte = this.advertisementLocationPolicy,
    telemetryModeEnvironment: UByte = this.telemetryModeEnvironment,
    telemetryModeLocation: UByte = this.telemetryModeLocation,
    telemetryModeBase: UByte = this.telemetryModeBase,
    manualAddContacts: Boolean = this.manualAddContacts,
    radioFrequency: Double = this.radioFrequency,
    radioBandwidth: Double = this.radioBandwidth,
    radioSpreadingFactor: UByte = this.radioSpreadingFactor,
    radioCodingRate: UByte = this.radioCodingRate,
    name: String = this.name,
) = SelfInfo(
    advertisementType, txPower, maxTxPower, publicKey, latitude, longitude, multiAcks, advertisementLocationPolicy,
    telemetryModeEnvironment, telemetryModeLocation, telemetryModeBase, manualAddContacts, radioFrequency,
    radioBandwidth, radioSpreadingFactor, radioCodingRate, name,
)

private fun defaultSelfInfo() = SelfInfo(
    advertisementType = 1u, txPower = 20, maxTxPower = 22, publicKey = ByteArray(32) { 0x03 },
    latitude = 12.5, longitude = -45.25, multiAcks = 0u, advertisementLocationPolicy = 0u,
    telemetryModeEnvironment = 0u, telemetryModeLocation = 0u, telemetryModeBase = 0u,
    manualAddContacts = false, radioFrequency = 915.0, radioBandwidth = 125.0,
    radioSpreadingFactor = 7u, radioCodingRate = 5u, name = "Device",
)

/**
 * Hand-written test double implementing every session role [NodeConfigService] and its
 * collaborators ([SettingsService], [ChannelService]) need — the same single-fake-per-test-class
 * pattern [com.meshcoretwo.services.ServiceContainerTest]'s `FakeContainerSession` established for
 * multi-service tests. Methods this slice's tests never call are stubbed with `error(...)`.
 */
private class FakeNodeConfigTestSession : ConfigurationSessionOps, NodeConfigSessionOps {
    var selfInfo: SelfInfo = defaultSelfInfo()
    var deviceCapabilities = DeviceCapabilities(
        firmwareVersion = 1u, maxContacts = 100, maxChannels = 8, blePin = 0u,
        firmwareBuild = "test", model = "test", version = "1.0",
    )
    val channels = mutableMapOf<UByte, ChannelInfo>()
    val contacts = mutableListOf<MeshContact>()

    var exportPrivateKeyResult: ByteArray = ByteArray(32) { 0x09 }
    var exportPrivateKeyError: MeshCoreError? = null

    val setNameCalls = mutableListOf<String>()
    val setCoordinatesCalls = mutableListOf<Pair<Double, Double>>()
    val setTxPowerCalls = mutableListOf<Byte>()
    val setRadioCalls = mutableListOf<SelfInfo>()
    data class OtherParamsCall(val manualAddContacts: Boolean, val advertLocationPolicy: UByte, val multiAcks: UByte?)
    val setOtherParamsCalls = mutableListOf<OtherParamsCall>()
    val importPrivateKeyCalls = mutableListOf<ByteArray>()
    val addContactCalls = mutableListOf<MeshContact>()
    val setChannelCalls = mutableListOf<Triple<UByte, String, ByteArray>>()

    // MARK: ConfigurationSessionOps

    override suspend fun sendAppStart(): SelfInfo = selfInfo
    override suspend fun queryDevice(): DeviceCapabilities = deviceCapabilities
    override suspend fun getBattery(): BatteryInfo = error("not used by this vertical slice")
    override suspend fun getTime(): Instant = error("not used by this vertical slice")
    override suspend fun setTime(date: Instant) = error("not used by this vertical slice")

    override suspend fun setName(name: String) {
        setNameCalls.add(name)
        selfInfo = selfInfo.copy(name = name)
    }

    override suspend fun setCoordinates(latitude: Double, longitude: Double) {
        setCoordinatesCalls.add(latitude to longitude)
        selfInfo = selfInfo.copy(latitude = latitude, longitude = longitude)
    }

    override suspend fun setTxPower(power: Byte) {
        setTxPowerCalls.add(power)
        selfInfo = selfInfo.copy(txPower = power)
    }

    override suspend fun setRadio(frequency: Double, bandwidth: Double, spreadingFactor: UByte, codingRate: UByte, clientRepeat: Boolean?) {
        selfInfo = selfInfo.copy(radioFrequency = frequency, radioBandwidth = bandwidth, radioSpreadingFactor = spreadingFactor, radioCodingRate = codingRate)
        setRadioCalls.add(selfInfo)
    }

    override suspend fun getRepeatFreq(): List<FrequencyRange> = error("not used by this vertical slice")

    override suspend fun setOtherParams(
        manualAddContacts: Boolean,
        telemetryModeEnvironment: UByte,
        telemetryModeLocation: UByte,
        telemetryModeBase: UByte,
        advertisementLocationPolicy: UByte,
        multiAcks: UByte?,
    ) {
        setOtherParamsCalls.add(OtherParamsCall(manualAddContacts, advertisementLocationPolicy, multiAcks))
        selfInfo = selfInfo.copy(
            manualAddContacts = manualAddContacts, advertisementLocationPolicy = advertisementLocationPolicy,
            telemetryModeEnvironment = telemetryModeEnvironment, telemetryModeLocation = telemetryModeLocation,
            telemetryModeBase = telemetryModeBase, multiAcks = multiAcks ?: selfInfo.multiAcks,
        )
    }

    override suspend fun setDevicePin(pin: UInt) = error("not used by this vertical slice")
    override suspend fun getAutoAddConfig(): AutoAddConfig = error("not used by this vertical slice")
    override suspend fun setAutoAddConfig(config: AutoAddConfig) = error("not used by this vertical slice")
    override suspend fun setPathHashMode(mode: UByte) = error("not used by this vertical slice")
    override suspend fun setDefaultFloodScope(name: String, scope: FloodScope) = error("not used by this vertical slice")
    override suspend fun getDefaultFloodScope(): DefaultFloodScope? = error("not used by this vertical slice")
    override suspend fun reboot() = error("not used by this vertical slice")
    override suspend fun factoryReset() = error("not used by this vertical slice")
    override suspend fun getStatsCore(): CoreStats = error("not used by this vertical slice")
    override suspend fun getStatsRadio(): RadioStats = error("not used by this vertical slice")
    override suspend fun getStatsPackets(): PacketStats = error("not used by this vertical slice")
    override suspend fun getCustomVars(): Map<String, String> = error("not used by this vertical slice")
    override suspend fun setCustomVar(key: String, value: String) = error("not used by this vertical slice")

    override suspend fun exportPrivateKey(): ByteArray {
        exportPrivateKeyError?.let { throw it }
        return exportPrivateKeyResult
    }

    override suspend fun importPrivateKey(key: ByteArray) {
        importPrivateKeyCalls.add(key)
    }

    override suspend fun sign(data: ByteArray, chunkSize: Int, timeout: Double?): ByteArray = error("not used by this vertical slice")

    // MARK: ChannelSessionOps

    override suspend fun getChannel(index: UByte): ChannelInfo = channels[index] ?: ChannelInfo(index, "", ByteArray(16))

    override suspend fun getChannels(indices: List<UByte>): ChannelsFetchResult = error("not used by this vertical slice")

    override suspend fun setChannel(index: UByte, name: String, secret: ByteArray) {
        setChannelCalls.add(Triple(index, name, secret))
        channels[index] = ChannelInfo(index, name, secret)
    }

    // MARK: ContactSessionOps

    override suspend fun getContacts(since: Instant?): List<MeshContact> = contacts

    override suspend fun getContactsReportingTotal(since: Instant?): ContactFetchResult = error("not used by this vertical slice")

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = contacts.find { it.publicKey.contentEquals(publicKey) }

    override suspend fun addContact(contact: MeshContact) {
        addContactCalls.add(contact)
        contacts.add(contact)
    }

    override suspend fun removeContact(publicKey: ByteArray) = error("not used by this vertical slice")
    override suspend fun resetPath(publicKey: ByteArray) = error("not used by this vertical slice")
    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo = error("not used by this vertical slice")
    override suspend fun shareContact(publicKey: ByteArray) = error("not used by this vertical slice")
    override suspend fun exportContact(publicKey: ByteArray?): String = error("not used by this vertical slice")
    override suspend fun importContact(cardData: ByteArray) = error("not used by this vertical slice")
    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags) = error("not used by this vertical slice")
}
