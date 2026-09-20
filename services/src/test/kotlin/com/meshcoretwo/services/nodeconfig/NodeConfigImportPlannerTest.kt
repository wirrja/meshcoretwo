// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.nodeconfig

import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.hexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/**
 * Pure-function coverage for [planConfigImport] and its helpers — ported from a representative
 * subset of `NodeConfigServiceTests.swift`'s planner-focused cases (validation errors, channel
 * dedup/overwrite semantics, contact capacity/dedup, out-path parsing). No session or Room needed:
 * everything here is synchronous and pure.
 */
class NodeConfigImportPlannerTest {
    private val allSections = ConfigSections(
        nodeIdentity = true, radioSettings = true, positionSettings = true,
        otherSettings = true, channels = true, contacts = true,
    )

    private fun plan(
        config: MeshCoreNodeConfig,
        sections: ConfigSections = allSections,
        maxChannels: UByte = 8u,
        maxContacts: Int = 100,
        maxTxPower: Byte = 22,
        existingChannels: List<DeviceChannelSlot> = emptyList(),
        existingContacts: Map<String, MeshContact> = emptyMap(),
    ) = planConfigImport(config, sections, maxChannels, maxContacts, maxTxPower, existingChannels, existingContacts)

    private fun hex(byte: Int, count: Int): String = ByteArray(count) { byte.toByte() }.hexString

    // MARK: - Private key

    @Test
    fun `valid private key is decoded into the plan`() {
        val keyHex = "ab".repeat(64)
        val result = plan(MeshCoreNodeConfig(privateKey = keyHex), sections = ConfigSections(nodeIdentity = true))
        assertEquals(64, result.importPrivateKey?.size)
    }

    @Test
    fun `absent private key leaves the plan field null`() {
        val result = plan(MeshCoreNodeConfig(name = "Node"), sections = ConfigSections(nodeIdentity = true))
        assertNull(result.importPrivateKey)
    }

    @Test
    fun `wrong-length private key hex throws InvalidPrivateKey`() {
        try {
            plan(MeshCoreNodeConfig(privateKey = "ab".repeat(10)), sections = ConfigSections(nodeIdentity = true))
            fail("expected InvalidPrivateKey")
        } catch (e: NodeConfigServiceError.InvalidPrivateKey) {
            assertEquals(20, e.hexLength)
        }
    }

    @Test
    fun `non-hex private key throws InvalidPrivateKey`() {
        try {
            plan(MeshCoreNodeConfig(privateKey = "zz".repeat(64)), sections = ConfigSections(nodeIdentity = true))
            fail("expected InvalidPrivateKey")
        } catch (e: NodeConfigServiceError.InvalidPrivateKey) {
            // expected
        }
    }

    // MARK: - Coordinates

    @Test
    fun `valid position is planned`() {
        val result = plan(
            MeshCoreNodeConfig(positionSettings = MeshCoreNodeConfig.PositionSettings("12.5", "-45.25")),
            sections = ConfigSections(positionSettings = true),
        )
        assertEquals(12.5, result.position?.latitude)
        assertEquals(-45.25, result.position?.longitude)
    }

    @Test
    fun `out-of-range latitude throws InvalidCoordinate`() {
        try {
            plan(
                MeshCoreNodeConfig(positionSettings = MeshCoreNodeConfig.PositionSettings("91.0", "0.0")),
                sections = ConfigSections(positionSettings = true),
            )
            fail("expected InvalidCoordinate")
        } catch (e: NodeConfigServiceError.InvalidCoordinate) {
            assertTrue(e.field is CoordinateField.PositionLatitude)
        }
    }

    @Test
    fun `non-numeric longitude throws InvalidCoordinate`() {
        try {
            plan(
                MeshCoreNodeConfig(positionSettings = MeshCoreNodeConfig.PositionSettings("0.0", "not-a-number")),
                sections = ConfigSections(positionSettings = true),
            )
            fail("expected InvalidCoordinate")
        } catch (e: NodeConfigServiceError.InvalidCoordinate) {
            assertTrue(e.field is CoordinateField.PositionLongitude)
        }
    }

    // MARK: - Radio

    private fun radio(
        frequency: UInt = 869_618u,
        bandwidth: UInt = 250_000u,
        spreadingFactor: UByte = 10u,
        codingRate: UByte = 5u,
        txPower: Byte = 20,
    ) = MeshCoreNodeConfig.RadioSettings(frequency, bandwidth, spreadingFactor, codingRate, txPower)

    @Test
    fun `valid radio settings pass through unchanged`() {
        val result = plan(MeshCoreNodeConfig(radioSettings = radio()), sections = ConfigSections(radioSettings = true))
        assertEquals(radio(), result.radioSettings)
    }

    @Test
    fun `frequency below range throws InvalidRadioSettings`() {
        try {
            plan(MeshCoreNodeConfig(radioSettings = radio(frequency = 1u)), sections = ConfigSections(radioSettings = true))
            fail("expected InvalidRadioSettings")
        } catch (e: NodeConfigServiceError.InvalidRadioSettings) {
            assertEquals(RadioField.FREQUENCY, e.field)
        }
    }

    @Test
    fun `tx power above device max throws InvalidRadioSettings`() {
        try {
            plan(MeshCoreNodeConfig(radioSettings = radio(txPower = 30)), sections = ConfigSections(radioSettings = true), maxTxPower = 22)
            fail("expected InvalidRadioSettings")
        } catch (e: NodeConfigServiceError.InvalidRadioSettings) {
            assertEquals(RadioField.TX_POWER, e.field)
        }
    }

    @Test
    fun `tx power below firmware floor throws InvalidRadioSettings`() {
        try {
            plan(MeshCoreNodeConfig(radioSettings = radio(txPower = -20)), sections = ConfigSections(radioSettings = true))
            fail("expected InvalidRadioSettings")
        } catch (e: NodeConfigServiceError.InvalidRadioSettings) {
            assertEquals(RadioField.TX_POWER, e.field)
        }
    }

    // MARK: - Channels

    @Test
    fun `channel import fills an empty slot`() {
        val existing = listOf(DeviceChannelSlot(0u, "", ByteArray(16), isConfigured = false))
        val result = plan(
            MeshCoreNodeConfig(channels = listOf(MeshCoreNodeConfig.ChannelConfig("Test", "ab".repeat(16)))),
            sections = ConfigSections(channels = true),
            existingChannels = existing,
        )
        assertEquals(1, result.channelWrites.size)
        assertEquals(0u.toUByte(), result.channelWrites[0].index)
        assertFalse(result.channelsOverwriteExisting)
    }

    @Test
    fun `re-importing an unchanged channel is a no-op write`() {
        val secret = ByteArray(16) { 0xAB.toByte() }
        val existing = listOf(DeviceChannelSlot(0u, "Test", secret, isConfigured = true))
        val result = plan(
            MeshCoreNodeConfig(channels = listOf(MeshCoreNodeConfig.ChannelConfig("Test", secret.hexString))),
            sections = ConfigSections(channels = true),
            existingChannels = existing,
        )
        assertTrue(result.channelWrites.isEmpty())
        assertFalse(result.channelsOverwriteExisting)
    }

    @Test
    fun `overwriting a configured hashtag slot with a different secret sets the overwrite flag`() {
        // A plain (non-hashtag) name is only ever matched by secret, never by name (see
        // planChannelWrites' doc), so the only way an import lands on an already-configured slot
        // under a different secret is via a hashtag-name match.
        val existing = listOf(DeviceChannelSlot(0u, "#topic", ByteArray(16) { 0x01 }, isConfigured = true))
        val result = plan(
            MeshCoreNodeConfig(channels = listOf(MeshCoreNodeConfig.ChannelConfig("#topic", "ff".repeat(16)))),
            sections = ConfigSections(channels = true),
            existingChannels = existing,
        )
        assertEquals(1, result.channelWrites.size)
        assertTrue(result.channelsOverwriteExisting)
    }

    @Test
    fun `channel import with no free slot throws NoAvailableChannelSlot`() {
        val existing = listOf(DeviceChannelSlot(0u, "Full", ByteArray(16) { 0x01 }, isConfigured = true))
        try {
            plan(
                MeshCoreNodeConfig(channels = listOf(MeshCoreNodeConfig.ChannelConfig("New", "ff".repeat(16)))),
                sections = ConfigSections(channels = true),
                maxChannels = 1u,
                existingChannels = existing,
            )
            fail("expected NoAvailableChannelSlot")
        } catch (e: NodeConfigServiceError.NoAvailableChannelSlot) {
            assertEquals("New", e.name)
        }
    }

    @Test
    fun `hashtag channel name matches its existing slot even with a new secret`() {
        val existing = listOf(DeviceChannelSlot(0u, "#topic", ByteArray(16) { 0x01 }, isConfigured = true))
        val result = plan(
            MeshCoreNodeConfig(channels = listOf(MeshCoreNodeConfig.ChannelConfig("#topic", "ff".repeat(16)))),
            sections = ConfigSections(channels = true),
            maxChannels = 1u,
            existingChannels = existing,
        )
        assertEquals(1, result.channelWrites.size)
        assertEquals(0u.toUByte(), result.channelWrites[0].index)
    }

    @Test
    fun `invalid channel secret hex throws InvalidChannelSecret`() {
        try {
            plan(
                MeshCoreNodeConfig(channels = listOf(MeshCoreNodeConfig.ChannelConfig("Test", "zz"))),
                sections = ConfigSections(channels = true),
                existingChannels = listOf(DeviceChannelSlot(0u, "", ByteArray(16), isConfigured = false)),
            )
            fail("expected InvalidChannelSecret")
        } catch (e: NodeConfigServiceError.InvalidChannelSecret) {
            assertEquals(0, e.index)
        }
    }

    // MARK: - Contacts

    private fun contactConfig(
        name: String = "Alice",
        publicKey: String = hex(0x01, 32),
        lastModified: UInt = 1000u,
        outPath: String? = null,
    ) = MeshCoreNodeConfig.ContactConfig(
        type = 1u, name = name, publicKey = publicKey, flags = 0u,
        latitude = "0.0", longitude = "0.0", lastAdvert = 500u, lastModified = lastModified, outPath = outPath,
    )

    @Test
    fun `contact import produces one record per unique public key`() {
        val result = plan(MeshCoreNodeConfig(contacts = listOf(contactConfig())), sections = ConfigSections(contacts = true))
        assertEquals(1, result.contactRecords.size)
        assertEquals("Alice", result.contactRecords[0].advertisedName)
    }

    @Test
    fun `duplicate public keys dedup to the newest lastModified`() {
        val result = plan(
            MeshCoreNodeConfig(
                contacts = listOf(
                    contactConfig(name = "Old", lastModified = 100u),
                    contactConfig(name = "New", lastModified = 200u),
                ),
            ),
            sections = ConfigSections(contacts = true),
        )
        assertEquals(1, result.contactRecords.size)
        assertEquals("New", result.contactRecords[0].advertisedName)
    }

    @Test
    fun `contact import over capacity throws ContactCapacityExceeded`() {
        try {
            plan(
                MeshCoreNodeConfig(contacts = listOf(contactConfig(publicKey = hex(0x01, 32)), contactConfig(name = "Bob", publicKey = hex(0x02, 32)))),
                sections = ConfigSections(contacts = true),
                maxContacts = 1,
            )
            fail("expected ContactCapacityExceeded")
        } catch (e: NodeConfigServiceError.ContactCapacityExceeded) {
            assertEquals(2, e.needed)
            assertEquals(1, e.available)
        }
    }

    @Test
    fun `contact already stored byte-for-byte is dropped from the plan`() {
        val publicKey = ByteArray(32) { 0x01 }
        val existing = MeshContact(
            id = publicKey.hexString,
            publicKey = publicKey, type = ContactType.CHAT, flags = ContactFlags(0u),
            outPathLength = 0xFFu, outPath = ByteArray(0), advertisedName = "Alice",
            lastAdvertisement = Instant.ofEpochSecond(500), latitude = 0.0, longitude = 0.0,
            lastModified = Instant.ofEpochSecond(1000),
        )
        val result = plan(
            MeshCoreNodeConfig(contacts = listOf(contactConfig(lastModified = 1000u))),
            sections = ConfigSections(contacts = true),
            existingContacts = mapOf(existing.id to existing),
        )
        assertTrue(result.contactRecords.isEmpty())
    }

    @Test
    fun `invalid contact public key hex throws InvalidContactPublicKey`() {
        try {
            plan(MeshCoreNodeConfig(contacts = listOf(contactConfig(publicKey = "zz"))), sections = ConfigSections(contacts = true))
            fail("expected InvalidContactPublicKey")
        } catch (e: NodeConfigServiceError.InvalidContactPublicKey) {
            assertEquals("Alice", e.name)
        }
    }

    // MARK: - Out-path

    @Test
    fun `absent out-path resolves to flood routing`() {
        val result = plan(MeshCoreNodeConfig(contacts = listOf(contactConfig(outPath = null))), sections = ConfigSections(contacts = true))
        assertTrue(result.contactRecords[0].isFloodPath)
    }

    @Test
    fun `empty out-path resolves to direct routing`() {
        val result = plan(MeshCoreNodeConfig(contacts = listOf(contactConfig(outPath = ""))), sections = ConfigSections(contacts = true))
        assertFalse(result.contactRecords[0].isFloodPath)
        assertEquals(0, result.contactRecords[0].pathByteLength)
    }

    @Test
    fun `non-hex out-path throws InvalidOutPath`() {
        try {
            plan(MeshCoreNodeConfig(contacts = listOf(contactConfig(outPath = "zzzz"))), sections = ConfigSections(contacts = true))
            fail("expected InvalidOutPath")
        } catch (e: NodeConfigServiceError.InvalidOutPath) {
            assertEquals("Alice", e.name)
        }
    }

    @Test
    fun `odd-length out-path throws InvalidOutPath`() {
        try {
            plan(MeshCoreNodeConfig(contacts = listOf(contactConfig(outPath = "abc"))), sections = ConfigSections(contacts = true))
            fail("expected InvalidOutPath")
        } catch (e: NodeConfigServiceError.InvalidOutPath) {
            // expected
        }
    }

    // MARK: - Section gating

    @Test
    fun `sections not selected are left out of the plan even when the config carries them`() {
        val config = MeshCoreNodeConfig(
            name = "Node", privateKey = "ab".repeat(64),
            radioSettings = radio(), otherSettings = MeshCoreNodeConfig.OtherSettings(manualAddContacts = 1u),
        )
        val result = plan(config, sections = ConfigSections(nodeIdentity = true))
        assertNull(result.radioSettings)
        assertNull(result.otherSettings)
        assertEquals("Node", result.nodeName)
    }
}
