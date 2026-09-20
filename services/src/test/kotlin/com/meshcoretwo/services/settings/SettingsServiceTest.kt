// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.settings

import com.meshcoretwo.protocol.AutoAddConfig
import com.meshcoretwo.protocol.BatteryInfo
import com.meshcoretwo.protocol.ConfigurationSessionOps
import com.meshcoretwo.protocol.CoreStats
import com.meshcoretwo.protocol.DefaultFloodScope
import com.meshcoretwo.protocol.DeviceCapabilities
import com.meshcoretwo.protocol.FloodScope
import com.meshcoretwo.protocol.FrequencyRange
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.PacketStats
import com.meshcoretwo.protocol.RadioStats
import com.meshcoretwo.protocol.SelfInfo
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.region.RadioPresets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Exercises [SettingsService] against [FakeConfigurationSessionOps], a hand-written test double
 * for the [ConfigurationSessionOps] role interface — the same pattern
 * [com.meshcoretwo.services.contacts.ContactServiceTest] established. No persistence store is
 * involved (this service is a pure session wrapper), so unlike most service tests this one needs
 * neither Room nor Robolectric.
 *
 * Ported from `SettingsServiceClockTests`/`SettingsServiceDefaultFloodScopeTests`/
 * `SettingsServiceEventStreamTests`/`SettingsServiceLocationTests`, adapted from their
 * wire-level `MockTransport` assertions (this codebase tests at the [ConfigurationSessionOps]
 * boundary instead, matching every other service test here) plus new coverage for the plain
 * (non-verified) setters and the stats/custom-var/key-management passthroughs.
 */
class SettingsServiceTest {
    private lateinit var session: FakeConfigurationSessionOps
    private lateinit var service: SettingsService

    private fun selfInfo(
        name: String = "Test",
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        txPower: Byte = 22,
        radioFrequency: Double = 915.0,
        radioBandwidth: Double = 125.0,
        radioSpreadingFactor: UByte = 7u,
        radioCodingRate: UByte = 5u,
        manualAddContacts: Boolean = false,
    ) = SelfInfo(
        advertisementType = 1u,
        txPower = txPower,
        maxTxPower = 22,
        publicKey = ByteArray(32) { 0x01 },
        latitude = latitude,
        longitude = longitude,
        multiAcks = 0u,
        advertisementLocationPolicy = 0u,
        telemetryModeEnvironment = 0u,
        telemetryModeLocation = 0u,
        telemetryModeBase = 0u,
        manualAddContacts = manualAddContacts,
        radioFrequency = radioFrequency,
        radioBandwidth = radioBandwidth,
        radioSpreadingFactor = radioSpreadingFactor,
        radioCodingRate = radioCodingRate,
        name = name,
    )

    @Before
    fun setUp() {
        session = FakeConfigurationSessionOps()
        session.selfInfo = selfInfo()
        service = SettingsService(session)
    }

    // MARK: - Clock

    @Test
    fun `getTime reads back the device clock`() = runTest {
        val expected = Instant.ofEpochSecond(1_700_000_000)
        session.time = expected

        assertEquals(expected, service.getTime())
    }

    @Test
    fun `setTime writes the device clock`() = runTest {
        val target = Instant.ofEpochSecond(1_700_000_000)

        service.setTime(target)

        assertEquals(target, session.lastSetTime)
    }

    // MARK: - Plain setters wrap session errors

    @Test
    fun `setNodeName truncates to the usable name limit and wraps session errors`() = runTest {
        val overlong = "a".repeat(40)
        service.setNodeName(overlong)
        assertEquals("a".repeat(31), session.lastSetName)

        session.setNameError = MeshCoreError.Timeout
        try {
            service.setNodeName("x")
            fail("expected SettingsServiceError.SessionError")
        } catch (error: SettingsServiceError.SessionError) {
            assertTrue(error.error is MeshCoreError.Timeout)
        }
    }

    @Test
    fun `setLocation forwards coordinates`() = runTest {
        service.setLocation(47.491031, -120.339279)
        assertEquals(47.491031 to -120.339279, session.lastSetCoordinates)
    }

    @Test
    fun `setBlePin forwards the pin`() = runTest {
        service.setBlePin(123456u)
        assertEquals(123456u, session.lastSetPin)
    }

    @Test
    fun `setTxPower forwards the power level`() = runTest {
        service.setTxPower(20)
        assertEquals(20.toByte(), session.lastSetTxPower)
    }

    @Test
    fun `setOtherParams maps autoAddContacts to the inverted manualAddContacts flag`() = runTest {
        service.setOtherParams(
            autoAddContacts = true,
            telemetryModes = TelemetryModes(base = 1u, location = 2u, environment = 3u),
            advertLocationPolicy = AdvertLocationPolicy.PREFS,
            multiAcks = 4u,
        )

        val sent = session.lastSetOtherParams!!
        assertFalse(sent.manualAddContacts)
        assertEquals(1u.toUByte(), sent.telemetryModeBase)
        assertEquals(2u.toUByte(), sent.telemetryModeLocation)
        assertEquals(3u.toUByte(), sent.telemetryModeEnvironment)
        assertEquals(AdvertLocationPolicy.PREFS.rawValue, sent.advertisementLocationPolicy)
        assertEquals(4u.toUByte(), sent.multiAcks)
    }

    @Test
    fun `setOtherParams with a raw policy byte forwards it verbatim`() = runTest {
        service.setOtherParams(
            autoAddContacts = false,
            telemetryModes = TelemetryModes(),
            advertLocationPolicyRaw = 99u,
            multiAcks = 0u,
        )

        assertEquals(99u.toUByte(), session.lastSetOtherParams!!.advertisementLocationPolicy)
    }

    @Test
    fun `factoryReset and reboot delegate to the session`() = runTest {
        service.factoryReset()
        assertTrue(session.factoryResetCalled)

        service.reboot()
        assertTrue(session.rebootCalled)
    }

    // MARK: - Device info / stats / custom vars / keys passthroughs

    @Test
    fun `getBattery queryDevice and getSelfInfo read through to the session`() = runTest {
        session.battery = BatteryInfo(level = 3700)
        session.capabilities = DeviceCapabilities(
            firmwareVersion = 10u,
            maxContacts = 100,
            maxChannels = 8,
            blePin = 0u,
            firmwareBuild = "build",
            model = "model",
            version = "1.0",
        )

        assertEquals(3700, service.getBattery().level)
        assertEquals(10u.toUByte(), service.queryDevice().firmwareVersion)
        assertEquals("Test", service.getSelfInfo().name)
    }

    @Test
    fun `stats passthroughs read through to the session`() = runTest {
        session.coreStats = CoreStats(batteryMV = 3700u, uptimeSeconds = 60u, errors = 0u, queueLength = 1u)
        session.radioStats = RadioStats(noiseFloor = -100, lastRSSI = -80, lastSNR = 5.0, txAirtimeSeconds = 1u, rxAirtimeSeconds = 2u)
        session.packetStats = PacketStats(received = 1u, sent = 2u, floodTx = 3u, directTx = 4u, floodRx = 5u, directRx = 6u)

        assertEquals(3700u.toUShort(), service.getStatsCore().batteryMV)
        assertEquals((-80).toByte(), service.getStatsRadio().lastRSSI)
        assertEquals(2u.toUInt(), service.getStatsPackets().sent)
    }

    @Test
    fun `custom var passthroughs read through to the session`() = runTest {
        session.customVars = mapOf("foo" to "bar")
        assertEquals(mapOf("foo" to "bar"), service.getCustomVars())

        service.setCustomVar("foo", "baz")
        assertEquals("foo" to "baz", session.lastSetCustomVar)
    }

    @Test
    fun `key management passthroughs read through to the session`() = runTest {
        session.privateKey = byteArrayOf(1, 2, 3)
        assertTrue(service.exportPrivateKey().contentEquals(byteArrayOf(1, 2, 3)))

        service.importPrivateKey(byteArrayOf(4, 5, 6))
        assertTrue(session.lastImportedKey!!.contentEquals(byteArrayOf(4, 5, 6)))

        session.signature = byteArrayOf(9, 9)
        assertTrue(service.sign(byteArrayOf(1)).contentEquals(byteArrayOf(9, 9)))
    }

    // MARK: - Auto-add config

    @Test
    fun `setAutoAddConfigVerified succeeds when the read-back matches and emits an event`() = runTest {
        val config = AutoAddConfig(bitmask = 0x02u, maxHops = 3u)
        session.autoAddConfig = config

        val result = service.setAutoAddConfigVerified(config)

        assertEquals(config.bitmask, result.bitmask)
        assertEquals(config.maxHops, result.maxHops)
        assertEquals(config, session.lastSetAutoAddConfig)
    }

    @Test
    fun `setAutoAddConfigVerified throws when the device reports a different config`() = runTest {
        session.autoAddConfig = AutoAddConfig(bitmask = 0x00u, maxHops = 0u)

        try {
            service.setAutoAddConfigVerified(AutoAddConfig(bitmask = 0x02u, maxHops = 3u))
            fail("expected VerificationFailed")
        } catch (error: SettingsServiceError.VerificationFailed) {
            // expected
        }
    }

    // MARK: - Path hash mode

    @Test
    fun `setPathHashModeVerified succeeds and emits an event when queryDevice agrees`() = runTest {
        session.capabilities = deviceCapabilities(pathHashMode = 2u)

        val mode = service.setPathHashModeVerified(2u)

        assertEquals(2u.toUByte(), mode)
        assertEquals(2u.toUByte(), session.lastSetPathHashMode)
    }

    @Test
    fun `setPathHashModeVerified throws when queryDevice disagrees`() = runTest {
        session.capabilities = deviceCapabilities(pathHashMode = 0u)

        try {
            service.setPathHashModeVerified(2u)
            fail("expected VerificationFailed")
        } catch (error: SettingsServiceError.VerificationFailed) {
            // expected
        }
    }

    // MARK: - Default flood scope

    @Test
    fun `setDefaultFloodScopeVerified truncates overlong names before send and verify`() = runTest {
        val overlong = "a".repeat(35)
        val truncated = "a".repeat(30)
        session.onSetDefaultFloodScope = { name, scope ->
            assertEquals(truncated, name)
            assertEquals(FloodScope.Region(truncated), scope)
            session.defaultFloodScope = DefaultFloodScope(truncated, ByteArray(16))
        }

        val result = service.setDefaultFloodScopeVerified(overlong)

        assertEquals(truncated, result)
    }

    @Test
    fun `setDefaultFloodScopeVerified forwards names at or below the cap unchanged`() = runTest {
        val name = "Germany"
        session.onSetDefaultFloodScope = { sentName, scope ->
            assertEquals(name, sentName)
            assertEquals(FloodScope.Region(name), scope)
            session.defaultFloodScope = DefaultFloodScope(name, ByteArray(16))
        }

        assertEquals(name, service.setDefaultFloodScopeVerified(name))
    }

    @Test
    fun `setDefaultFloodScopeVerified clears the scope when name is null`() = runTest {
        session.defaultFloodScope = DefaultFloodScope("stale", ByteArray(16))
        session.onSetDefaultFloodScope = { sentName, scope ->
            assertEquals("", sentName)
            assertEquals(FloodScope.Disabled, scope)
            session.defaultFloodScope = null
        }

        assertNull(service.setDefaultFloodScopeVerified(null))
    }

    // MARK: - Event stream

    @Test
    fun `refreshDeviceInfo emits a deviceUpdated event`() = runTest {
        val newInfo = selfInfo(name = "Updated")
        session.selfInfo = newInfo

        val collected = mutableListOf<SettingsEvent>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { service.events().collect { collected.add(it) } }
        service.refreshDeviceInfo()
        job.cancel()

        val event = collected.single() as SettingsEvent.DeviceUpdated
        assertEquals("Updated", event.selfInfo.name)
    }

    // MARK: - Device GPS

    @Test
    fun `getDeviceGPSState returns unsupported when gps custom var is missing`() = runTest {
        session.customVars = emptyMap()
        assertEquals(DeviceGPSState(isSupported = false, isEnabled = false), service.getDeviceGPSState())
    }

    @Test
    fun `getDeviceGPSState returns enabled when gps custom var is on`() = runTest {
        session.customVars = mapOf("gps" to "1", "foo" to "bar")
        assertEquals(DeviceGPSState(isSupported = true, isEnabled = true), service.getDeviceGPSState())
    }

    @Test
    fun `setDeviceGPSEnabledVerified writes verifies and refreshes device info`() = runTest {
        session.selfInfo = selfInfo(latitude = 47.491031, longitude = -120.339279)
        session.onSetCustomVar = { key, value ->
            assertEquals("gps", key)
            assertEquals("0", value)
            session.customVars = mapOf("gps" to "0")
        }

        val state = service.setDeviceGPSEnabledVerified(false)

        assertEquals(DeviceGPSState(isSupported = true, isEnabled = false), state)
    }

    @Test
    fun `setDeviceGPSEnabledVerified throws when the device state still disagrees`() = runTest {
        session.customVars = mapOf("gps" to "1")

        try {
            service.setDeviceGPSEnabledVerified(false)
            fail("expected DeviceGPSVerificationFailed")
        } catch (error: SettingsServiceError.DeviceGPSVerificationFailed) {
            assertFalse(error.expectedEnabled)
            assertTrue(error.actualEnabled)
        }
    }

    // MARK: - Verified location

    @Test
    fun `setLocationVerified succeeds when the read-back matches within tolerance`() = runTest {
        session.selfInfo = selfInfo(latitude = 47.491031, longitude = -120.339279)

        val result = service.setLocationVerified(47.491031, -120.339279)

        assertEquals(47.491031, result.latitude, 0.0)
    }

    @Test
    fun `setLocationVerified throws when the read-back diverges beyond tolerance`() = runTest {
        session.selfInfo = selfInfo(latitude = 10.0, longitude = 10.0)

        try {
            service.setLocationVerified(47.491031, -120.339279)
            fail("expected VerificationFailed")
        } catch (error: SettingsServiceError.VerificationFailed) {
            // expected
        }
    }

    @Test
    fun `setManualLocationVerified disables device GPS before writing location`() = runTest {
        session.customVars = mapOf("gps" to "1")
        session.onSetCustomVar = { _, value -> session.customVars = mapOf("gps" to value) }
        session.selfInfo = selfInfo(latitude = 0.0, longitude = 0.0)

        val result = service.setManualLocationVerified(0.0, 0.0)

        assertEquals("0", session.customVars["gps"])
        assertEquals(0.0, result.latitude, 0.0)
    }

    @Test
    fun `setManualLocationVerified aborts when device GPS stays on`() = runTest {
        session.customVars = mapOf("gps" to "1")
        // onSetCustomVar left unset: the write is a no-op, so the read-back still reports "1".

        try {
            service.setManualLocationVerified(0.0, 0.0)
            fail("expected a SettingsServiceError")
        } catch (error: SettingsServiceError) {
            // expected
        }
    }

    // MARK: - Verified radio params / TX power / other params

    @Test
    fun `setRadioParamsVerified succeeds when the read-back matches`() = runTest {
        session.selfInfo = selfInfo(radioFrequency = 869.618, radioBandwidth = 62.5, radioSpreadingFactor = 8u, radioCodingRate = 6u)
        session.capabilities = deviceCapabilities(clientRepeat = true)

        val result = service.setRadioParamsVerified(
            frequencyKHz = 869_618u,
            bandwidthKHz = 62_500u,
            spreadingFactor = 8u,
            codingRate = 6u,
            clientRepeat = true,
        )

        assertEquals(869.618, result.radioFrequency, 0.0001)
    }

    @Test
    fun `setRadioParamsVerified throws when the read-back diverges`() = runTest {
        session.selfInfo = selfInfo(radioFrequency = 915.0, radioBandwidth = 125.0)

        try {
            service.setRadioParamsVerified(frequencyKHz = 869_618u, bandwidthKHz = 62_500u, spreadingFactor = 8u, codingRate = 6u)
            fail("expected VerificationFailed")
        } catch (error: SettingsServiceError.VerificationFailed) {
            // expected
        }
    }

    @Test
    fun `applyRadioPreset forwards the preset's radio parameters`() = runTest {
        val preset = RadioPresets.all.first { it.id == "us-ca" }
        service.applyRadioPreset(preset)

        assertEquals(preset.frequencyKHz.toDouble() / 1000.0, session.lastSetRadio?.frequency ?: -1.0, 0.0001)
        assertEquals(preset.bandwidthHz.toDouble() / 1000.0, session.lastSetRadio?.bandwidth ?: -1.0, 0.0001)
        assertEquals(preset.spreadingFactor, session.lastSetRadio?.spreadingFactor)
        assertEquals(preset.codingRate, session.lastSetRadio?.codingRate)
    }

    @Test
    fun `applyRadioPresetVerified succeeds when the read-back matches`() = runTest {
        val preset = RadioPresets.all.first { it.id == "us-ca" }
        session.selfInfo = selfInfo(
            radioFrequency = preset.frequencyMHz,
            radioBandwidth = preset.bandwidthKHz,
            radioSpreadingFactor = preset.spreadingFactor,
            radioCodingRate = preset.codingRate,
        )

        val result = service.applyRadioPresetVerified(preset)

        assertEquals(preset.frequencyMHz, result.radioFrequency, 0.0001)
    }

    @Test
    fun `applyRadioPresetVerified throws when the read-back diverges`() = runTest {
        val preset = RadioPresets.all.first { it.id == "us-ca" }
        session.selfInfo = selfInfo(radioFrequency = 433.0, radioBandwidth = 250.0)

        try {
            service.applyRadioPresetVerified(preset)
            fail("expected VerificationFailed")
        } catch (error: SettingsServiceError.VerificationFailed) {
            // expected
        }
    }

    private fun matchingSelfInfo(preset: RadioPresets.RadioPreset) = selfInfo(
        radioFrequency = preset.frequencyMHz,
        radioBandwidth = preset.bandwidthKHz,
        radioSpreadingFactor = preset.spreadingFactor,
        radioCodingRate = preset.codingRate,
    )

    @Test
    fun `applyRadioPresetVerified writes the preset's path hash after RF`() = runTest {
        val preset = RadioPresets.all.first { it.id == "hu" } // pathHashSize 2 -> mode 1
        session.selfInfo = matchingSelfInfo(preset)
        session.capabilities = deviceCapabilities(pathHashMode = 1u)

        service.applyRadioPresetVerified(preset)

        assertEquals(1u.toUByte(), session.lastSetPathHashMode)
    }

    @Test
    fun `applyRadioPresetVerified skips the path hash on firmware below v10`() = runTest {
        val preset = RadioPresets.all.first { it.id == "hu" }
        session.selfInfo = matchingSelfInfo(preset)
        session.capabilities = deviceCapabilities(firmwareVersion = 9u)

        service.applyRadioPresetVerified(preset)

        assertNull(session.lastSetPathHashMode)
    }

    @Test
    fun `applyRadioPresetVerified leaves the path hash alone for presets without a size`() = runTest {
        val preset = RadioPresets.all.first { it.id == "us-ca" }
        session.selfInfo = matchingSelfInfo(preset)
        session.capabilities = deviceCapabilities()

        service.applyRadioPresetVerified(preset)

        assertNull(session.lastSetPathHashMode)
    }

    @Test
    fun `applyRadioPresetVerified fails when the path hash read-back disagrees`() = runTest {
        val preset = RadioPresets.all.first { it.id == "hu" }
        session.selfInfo = matchingSelfInfo(preset)
        session.capabilities = deviceCapabilities(pathHashMode = 0u)

        try {
            service.applyRadioPresetVerified(preset)
            fail("expected VerificationFailed")
        } catch (error: SettingsServiceError.VerificationFailed) {
            // expected: the caller must not stamp the catalog id
        }
    }

    @Test
    fun `setTxPowerVerified succeeds when the read-back matches`() = runTest {
        session.selfInfo = selfInfo(txPower = 20)
        val result = service.setTxPowerVerified(20)
        assertEquals(20.toByte(), result.txPower)
    }

    @Test
    fun `setTxPowerVerified throws when the read-back diverges`() = runTest {
        session.selfInfo = selfInfo(txPower = 5)
        try {
            service.setTxPowerVerified(20)
            fail("expected VerificationFailed")
        } catch (error: SettingsServiceError.VerificationFailed) {
            // expected
        }
    }

    @Test
    fun `setOtherParamsVerified succeeds when manualAddContacts flips as expected`() = runTest {
        session.selfInfo = selfInfo(manualAddContacts = false)

        val result = service.setOtherParamsVerified(
            autoAddContacts = true,
            telemetryModes = TelemetryModes(),
            advertLocationPolicy = AdvertLocationPolicy.NONE,
            multiAcks = 0u,
        )

        assertFalse(result.manualAddContacts)
    }

    @Test
    fun `setOtherParamsVerified throws when manualAddContacts didn't flip`() = runTest {
        session.selfInfo = selfInfo(manualAddContacts = true)

        try {
            service.setOtherParamsVerified(
                autoAddContacts = true,
                telemetryModes = TelemetryModes(),
                advertLocationPolicy = AdvertLocationPolicy.NONE,
                multiAcks = 0u,
            )
            fail("expected VerificationFailed")
        } catch (error: SettingsServiceError.VerificationFailed) {
            // expected
        }
    }

    @Test
    fun `setOtherParamsVerified(device) defaults unsupplied params from the device snapshot`() = runTest {
        session.selfInfo = selfInfo(manualAddContacts = false)
        val device = testDevice(
            manualAddContacts = false,
            multiAcks = 3u,
            telemetryModeBase = 1u,
            telemetryModeLocation = 2u,
            telemetryModeEnvironment = 3u,
            advertLocationPolicy = AdvertLocationPolicy.SHARE.rawValue,
        )

        service.setOtherParamsVerified(device = device)

        val sent = session.lastSetOtherParams!!
        // autoAddContacts defaults to !device.manualAddContacts, then setOtherParams inverts it back.
        assertFalse(sent.manualAddContacts)
        assertEquals(1u.toUByte(), sent.telemetryModeBase)
        assertEquals(2u.toUByte(), sent.telemetryModeLocation)
        assertEquals(3u.toUByte(), sent.telemetryModeEnvironment)
        assertEquals(AdvertLocationPolicy.SHARE.rawValue, sent.advertisementLocationPolicy)
        assertEquals(3u.toUByte(), sent.multiAcks)
    }

    @Test
    fun `setOtherParamsVerified(device) lets a supplied param override the device snapshot`() = runTest {
        session.selfInfo = selfInfo(manualAddContacts = true)
        val device = testDevice(manualAddContacts = true, multiAcks = 3u)

        service.setOtherParamsVerified(device = device, multiAcks = 9u)

        assertEquals(9u.toUByte(), session.lastSetOtherParams!!.multiAcks)
    }

    private fun testDevice(
        manualAddContacts: Boolean = false,
        multiAcks: UByte = 2u,
        telemetryModeBase: UByte = 2u,
        telemetryModeLocation: UByte = 0u,
        telemetryModeEnvironment: UByte = 0u,
        advertLocationPolicy: UByte = 0u,
    ) = DeviceDto(
        id = UUID.randomUUID(),
        radioID = UUID.randomUUID(),
        publicKey = ByteArray(32),
        nodeName = "Radio",
        firmwareVersion = 10u,
        firmwareVersionString = "v1.16.0",
        manufacturerName = "Acme",
        buildDate = "2026-01-01",
        maxContacts = 100u,
        maxChannels = 8u,
        frequency = 915_000u,
        bandwidth = 250_000u,
        spreadingFactor = 10u,
        codingRate = 5u,
        txPower = 20,
        maxTxPower = 20,
        latitude = 0.0,
        longitude = 0.0,
        blePin = 0u,
        lastConnected = Instant.EPOCH,
        lastContactSync = 0u,
        isActive = true,
        ocvPreset = null,
        customOCVArrayString = null,
        manualAddContacts = manualAddContacts,
        multiAcks = multiAcks,
        telemetryModeBase = telemetryModeBase,
        telemetryModeLocation = telemetryModeLocation,
        telemetryModeEnvironment = telemetryModeEnvironment,
        advertLocationPolicy = advertLocationPolicy,
    )

    private fun deviceCapabilities(pathHashMode: UByte = 0u, clientRepeat: Boolean = false, firmwareVersion: UByte = 10u) = DeviceCapabilities(
        firmwareVersion = firmwareVersion,
        maxContacts = 100,
        maxChannels = 8,
        blePin = 0u,
        firmwareBuild = "build",
        model = "model",
        version = "1.0",
        clientRepeat = clientRepeat,
        pathHashMode = pathHashMode,
    )
}

/** Snapshot of the arguments a [FakeConfigurationSessionOps.setOtherParams] call was made with. */
private data class SentOtherParams(
    val manualAddContacts: Boolean,
    val telemetryModeEnvironment: UByte,
    val telemetryModeLocation: UByte,
    val telemetryModeBase: UByte,
    val advertisementLocationPolicy: UByte,
    val multiAcks: UByte?,
)

/** Snapshot of the arguments a [FakeConfigurationSessionOps.setRadio] call was made with. */
private data class SetRadioCall(
    val frequency: Double,
    val bandwidth: Double,
    val spreadingFactor: UByte,
    val codingRate: UByte,
    val clientRepeat: Boolean?,
)

private class FakeConfigurationSessionOps : ConfigurationSessionOps {
    lateinit var selfInfo: SelfInfo
    var time: Instant = Instant.EPOCH
    var battery: BatteryInfo = BatteryInfo(level = 0)
    var capabilities: DeviceCapabilities = DeviceCapabilities(
        firmwareVersion = 0u,
        maxContacts = 0,
        maxChannels = 0,
        blePin = 0u,
        firmwareBuild = "",
        model = "",
        version = "",
    )
    var repeatFreqRanges: List<FrequencyRange> = emptyList()
    var autoAddConfig: AutoAddConfig = AutoAddConfig(bitmask = 0u)
    var defaultFloodScope: DefaultFloodScope? = null
    var coreStats: CoreStats = CoreStats(batteryMV = 0u, uptimeSeconds = 0u, errors = 0u, queueLength = 0u)
    var radioStats: RadioStats = RadioStats(noiseFloor = 0, lastRSSI = 0, lastSNR = 0.0, txAirtimeSeconds = 0u, rxAirtimeSeconds = 0u)
    var packetStats: PacketStats = PacketStats(received = 0u, sent = 0u, floodTx = 0u, directTx = 0u, floodRx = 0u, directRx = 0u)
    var customVars: Map<String, String> = emptyMap()
    var privateKey: ByteArray = ByteArray(0)
    var signature: ByteArray = ByteArray(0)

    var setNameError: MeshCoreError? = null

    var lastSetTime: Instant? = null
    var lastSetName: String? = null
    var lastSetCoordinates: Pair<Double, Double>? = null
    var lastSetPin: UInt? = null
    var lastSetTxPower: Byte? = null
    var lastSetRadio: SetRadioCall? = null
    var lastSetOtherParams: SentOtherParams? = null
    var lastSetAutoAddConfig: AutoAddConfig? = null
    var lastSetPathHashMode: UByte? = null
    var lastSetCustomVar: Pair<String, String>? = null
    var lastImportedKey: ByteArray? = null
    var factoryResetCalled = false
    var rebootCalled = false

    var onSetDefaultFloodScope: ((String, FloodScope) -> Unit)? = null
    var onSetCustomVar: ((String, String) -> Unit)? = null

    override suspend fun sendAppStart(): SelfInfo = selfInfo

    override suspend fun queryDevice(): DeviceCapabilities = capabilities

    override suspend fun getBattery(): BatteryInfo = battery

    override suspend fun getTime(): Instant = time

    override suspend fun setTime(date: Instant) {
        lastSetTime = date
    }

    override suspend fun setName(name: String) {
        setNameError?.let { throw it }
        lastSetName = name
    }

    override suspend fun setCoordinates(latitude: Double, longitude: Double) {
        lastSetCoordinates = latitude to longitude
    }

    override suspend fun setTxPower(power: Byte) {
        lastSetTxPower = power
    }

    override suspend fun setRadio(
        frequency: Double,
        bandwidth: Double,
        spreadingFactor: UByte,
        codingRate: UByte,
        clientRepeat: Boolean?,
    ) {
        lastSetRadio = SetRadioCall(frequency, bandwidth, spreadingFactor, codingRate, clientRepeat)
    }

    override suspend fun getRepeatFreq(): List<FrequencyRange> = repeatFreqRanges

    override suspend fun setOtherParams(
        manualAddContacts: Boolean,
        telemetryModeEnvironment: UByte,
        telemetryModeLocation: UByte,
        telemetryModeBase: UByte,
        advertisementLocationPolicy: UByte,
        multiAcks: UByte?,
    ) {
        lastSetOtherParams = SentOtherParams(
            manualAddContacts,
            telemetryModeEnvironment,
            telemetryModeLocation,
            telemetryModeBase,
            advertisementLocationPolicy,
            multiAcks,
        )
    }

    override suspend fun setDevicePin(pin: UInt) {
        lastSetPin = pin
    }

    override suspend fun getAutoAddConfig(): AutoAddConfig = autoAddConfig

    override suspend fun setAutoAddConfig(config: AutoAddConfig) {
        lastSetAutoAddConfig = config
    }

    override suspend fun setPathHashMode(mode: UByte) {
        lastSetPathHashMode = mode
    }

    override suspend fun setDefaultFloodScope(name: String, scope: FloodScope) {
        onSetDefaultFloodScope?.invoke(name, scope)
    }

    override suspend fun getDefaultFloodScope(): DefaultFloodScope? = defaultFloodScope

    override suspend fun reboot() {
        rebootCalled = true
    }

    override suspend fun factoryReset() {
        factoryResetCalled = true
    }

    override suspend fun getStatsCore(): CoreStats = coreStats

    override suspend fun getStatsRadio(): RadioStats = radioStats

    override suspend fun getStatsPackets(): PacketStats = packetStats

    override suspend fun getCustomVars(): Map<String, String> = customVars

    override suspend fun setCustomVar(key: String, value: String) {
        lastSetCustomVar = key to value
        onSetCustomVar?.invoke(key, value)
    }

    override suspend fun exportPrivateKey(): ByteArray = privateKey

    override suspend fun importPrivateKey(key: ByteArray) {
        lastImportedKey = key
    }

    override suspend fun sign(data: ByteArray, chunkSize: Int, timeout: Double?): ByteArray = signature
}
