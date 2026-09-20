// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup.dto

import com.meshcoretwo.protocol.RouteType
import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.services.persistence.BlockedChannelSenderDto
import com.meshcoretwo.services.persistence.ChannelDto
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageRepeatDto
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.NeighborSnapshotEntry
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import com.meshcoretwo.services.persistence.NotificationLevel
import com.meshcoretwo.services.persistence.ReactionDto
import com.meshcoretwo.services.persistence.RemoteNodeRole
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RoomMessageDto
import com.meshcoretwo.services.persistence.RoomPermissionLevel
import com.meshcoretwo.services.persistence.TelemetrySnapshotEntry
import com.meshcoretwo.services.persistence.TracePathDto
import com.meshcoretwo.services.persistence.TracePathRunDto
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One round-trip (`toBackupJson()` then back) per backup model DTO, plus a couple of
 * null-handling checks on the two DTOs with the most optional fields (Device, Message). These are
 * pure field mappings — the interesting risk is a wrong unsigned-width conversion or a dropped
 * field, both of which a full round-trip equality check catches directly.
 *
 * Robolectric, not plain JUnit: `org.json.JSONObject` on the Android unit-test classpath is a
 * stub whose methods throw ("not mocked") unless Robolectric backs it with a real implementation
 * — the same reason [com.meshcoretwo.services.nodeconfig.NodeConfigServiceTest] (this module's
 * other `org.json`-based JSON layer) already runs under it.
 */
@RunWith(RobolectricTestRunner::class)
class BackupDtoJsonTest {
    private val radioID = UUID.randomUUID()

    /** Millisecond-precision now — [putInstant]/[putInstantOrNull] round-trip via epoch millis, so a raw `Instant.now()` (nanosecond precision) would spuriously fail equality here. */
    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    @Test
    fun `BlockedChannelSenderDto round-trips`() {
        val dto = BlockedChannelSenderDto(id = UUID.randomUUID(), name = "Alice", radioID = radioID, dateBlocked = now())
        assertEquals(dto, dto.toBackupJson().toBlockedChannelSenderDto())
    }

    @Test
    fun `ReactionDto round-trips, including a null channelIndex and contactID`() {
        val dto = ReactionDto(
            id = UUID.randomUUID(), messageID = UUID.randomUUID(), emoji = "👍", senderName = "Bob",
            messageHash = "abc123", rawText = "raw", receivedAt = now(),
            channelIndex = null, contactID = UUID.randomUUID(), radioID = radioID,
        )
        assertEquals(dto, dto.toBackupJson().toReactionDto())
    }

    @Test
    fun `MessageRepeatDto round-trips, including nulls`() {
        val dto = MessageRepeatDto(
            id = UUID.randomUUID(), messageID = UUID.randomUUID(), receivedAt = now(),
            pathNodes = byteArrayOf(1, 2, 3), pathLength = 3u, snr = null, rssi = null, rxLogEntryID = null,
        )
        assertEquals(dto, dto.toBackupJson().toMessageRepeatDto())

        val withValues = dto.copy(snr = -5.5, rssi = -80, rxLogEntryID = UUID.randomUUID())
        assertEquals(withValues, withValues.toBackupJson().toMessageRepeatDto())
    }

    @Test
    fun `RoomMessageDto round-trips`() {
        val dto = RoomMessageDto(
            sessionID = UUID.randomUUID(), authorKeyPrefix = byteArrayOf(9, 8, 7), authorName = "Carol",
            text = "hi room", timestamp = 123u, createdAt = now(), status = MessageStatus.SENT, ackCode = 42u,
        )
        assertEquals(dto, dto.toBackupJson().toRoomMessageDto())
    }

    @Test
    fun `ChannelDto round-trips, including a null regionScope`() {
        val dto = ChannelDto(
            id = UUID.randomUUID(), radioID = radioID, index = 1u, name = "General", secret = byteArrayOf(1, 2),
            isEnabled = true, lastMessageDate = now(), unreadCount = 2, unreadMentionCount = 1,
            notificationLevel = NotificationLevel.MENTIONS_ONLY, isFavorite = true,
            floodScopeModeRawValue = "specific", regionScope = "de-hh",
        )
        assertEquals(dto, dto.toBackupJson().toChannelDto())
    }

    @Test
    fun `RemoteNodeSessionDto round-trips`() {
        val dto = RemoteNodeSessionDto(
            id = UUID.randomUUID(), radioID = radioID, publicKey = byteArrayOf(5, 5, 5), name = "Repeater1",
            role = RemoteNodeRole.REPEATER, latitude = 1.5, longitude = -2.5, isConnected = true,
            permissionLevel = RoomPermissionLevel.ADMIN, lastConnectedDate = now(),
            lastBatteryMillivolts = 3700, lastUptimeSeconds = 12345L, lastNoiseFloor = -100,
            unreadCount = 0, notificationLevel = NotificationLevel.ALL, lastRxAirtimeSeconds = 99L,
            neighborCount = 3, lastSyncTimestamp = 1_700_000_000L, lastMessageDate = now(),
            isFavorite = true,
        )
        assertEquals(dto, dto.toBackupJson().toRemoteNodeSessionDto())
    }

    @Test
    fun `TracePathDto round-trips with its nested runs`() {
        val dto = TracePathDto(
            id = UUID.randomUUID(), radioID = radioID, name = "Home route", pathBytes = byteArrayOf(1, 2, 3, 4),
            hashSize = 2, createdDate = now(),
            runs = listOf(
                TracePathRunDto(id = UUID.randomUUID(), date = now(), success = true, roundTripMs = 250, hopsSNR = listOf(1.5, 2.5)),
                TracePathRunDto(id = UUID.randomUUID(), date = now(), success = false, roundTripMs = 0, hopsSNR = emptyList()),
            ),
        )
        assertEquals(dto, dto.toBackupJson().toTracePathDto())
    }

    @Test
    fun `DiscoveredNodeDto round-trips, including null inbound-hop fields`() {
        val dto = DiscoveredNodeDto(
            id = UUID.randomUUID(), radioID = radioID, publicKey = byteArrayOf(1), name = "Node", typeRawValue = 2u,
            lastHeard = now(), lastAdvertTimestamp = 100u, latitude = 0.0, longitude = 0.0,
            outPathLength = 0u, outPath = ByteArray(0), inboundHopCount = null, inboundHopAdvertTimestamp = null,
        )
        assertEquals(dto, dto.toBackupJson().toDiscoveredNodeDto())
    }

    @Test
    fun `ContactDto round-trips, including nulls and a non-empty avatar`() {
        val dto = ContactDto(
            id = UUID.randomUUID(), radioID = radioID, publicKey = byteArrayOf(1, 2, 3), name = "Dave",
            typeRawValue = 1u, flags = 0u, outPathLength = 0u, outPath = ByteArray(0),
            lastAdvertTimestamp = 100u, latitude = 12.3, longitude = -45.6, lastModified = 200u,
            lastHeardTimestamp = 300u, nickname = null, isBlocked = false, isMuted = true, isFavorite = false,
            lastMessageDate = now(), unreadCount = 5, unreadMentionCount = 1, ocvPreset = null,
            customOCVArrayString = null, avatarImageData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
        )
        assertEquals(dto, dto.toBackupJson().toContactDto())
    }

    @Test
    fun `NodeStatusSnapshotDto round-trips with nested neighbor and telemetry lists`() {
        val dto = NodeStatusSnapshotDto(
            id = UUID.randomUUID(), timestamp = now(), nodePublicKey = byteArrayOf(1, 2),
            batteryMillivolts = 3700u, lastSNR = 5.5, lastRSSI = (-90).toShort(), noiseFloor = (-110).toShort(),
            uptimeSeconds = 1000u, rxAirtimeSeconds = 50u, packetsSent = 10u, packetsReceived = 20u,
            receiveErrors = 0u, sentDirect = 1u, sentFlood = 2u, receivedDirect = 3u, receivedFlood = 4u,
            directDuplicates = 0u, floodDuplicates = 0u, postedCount = 1u, postPushCount = 2u,
            neighborSnapshots = listOf(NeighborSnapshotEntry(publicKeyPrefix = byteArrayOf(1, 2, 3), snr = 4.5, secondsAgo = 60)),
            telemetryEntries = listOf(TelemetrySnapshotEntry(channel = 1, type = "temperature", value = 21.5)),
            latitude = 1.0, longitude = 2.0, altitude = 3.0,
        )
        assertEquals(dto, dto.toBackupJson().toNodeStatusSnapshotDto())
    }

    @Test
    fun `NodeStatusSnapshotDto round-trips with all-null optional fields`() {
        val dto = NodeStatusSnapshotDto(
            id = UUID.randomUUID(), timestamp = now(), nodePublicKey = byteArrayOf(1),
            batteryMillivolts = null, lastSNR = null, lastRSSI = null, noiseFloor = null, uptimeSeconds = null,
            rxAirtimeSeconds = null, packetsSent = null, packetsReceived = null, receiveErrors = null,
            sentDirect = null, sentFlood = null, receivedDirect = null, receivedFlood = null,
            directDuplicates = null, floodDuplicates = null, postedCount = null, postPushCount = null,
            neighborSnapshots = null, telemetryEntries = null, latitude = null, longitude = null, altitude = null,
        )
        assertEquals(dto, dto.toBackupJson().toNodeStatusSnapshotDto())
    }

    @Test
    fun `MessageDto round-trips a fully-populated outgoing message`() {
        val dto = MessageDto(
            id = UUID.randomUUID(), radioID = radioID, contactID = UUID.randomUUID(), channelIndex = null,
            text = "hello", timestamp = 111u, createdAt = now(), sortDate = now(),
            direction = MessageDirection.OUTGOING, status = MessageStatus.DELIVERED, textType = TextType.PLAIN_TEXT,
            ackCode = 7u, pathLength = 2u, snr = 3.5, pathNodes = byteArrayOf(1, 2), senderKeyPrefix = byteArrayOf(3, 4),
            senderNodeName = "Eve", isRead = true, replyToID = UUID.randomUUID(), roundTripTime = 500u,
            sendCount = 1, retryAttempt = 0, maxRetryAttempts = 3, deduplicationKey = "dm-x-1-AA",
            reactionSummary = "👍1", senderTimestamp = 222u, routeType = RouteType.DIRECT, heardRepeats = 2,
            containsSelfMention = true, mentionSeen = false,
        )
        assertEquals(dto, dto.toBackupJson().toMessageDto())
    }

    @Test
    fun `MessageDto round-trips with every optional field null`() {
        val dto = MessageDto(
            id = UUID.randomUUID(), radioID = radioID, contactID = null, channelIndex = 0u,
            text = "hi", timestamp = 1u, createdAt = now(), sortDate = now(),
            direction = MessageDirection.INCOMING, status = MessageStatus.PENDING, textType = TextType.PLAIN_TEXT,
            ackCode = null, pathLength = 0u, snr = null, pathNodes = null, senderKeyPrefix = null,
            senderNodeName = null, isRead = false, replyToID = null, roundTripTime = null,
            sendCount = 0, retryAttempt = 0, maxRetryAttempts = 0, deduplicationKey = null,
            reactionSummary = null, senderTimestamp = null, routeType = null, heardRepeats = 0,
        )
        assertEquals(dto, dto.toBackupJson().toMessageDto())
    }

    @Test
    fun `DeviceDto round-trips a fully-populated device`() {
        val dto = DeviceDto(
            id = UUID.randomUUID(), radioID = radioID, publicKey = byteArrayOf(1, 2, 3), nodeName = "MyNode",
            firmwareVersion = 12u, firmwareVersionString = "v1.14.0", manufacturerName = "Acme",
            buildDate = "2026-01-01", maxContacts = 200u, maxChannels = 20u, frequency = 910_525u,
            bandwidth = 62_500u, spreadingFactor = 9u, codingRate = 5u, txPower = 22, maxTxPower = 22,
            latitude = 1.1, longitude = 2.2, blePin = 123456u, lastConnected = now(),
            lastContactSync = 999u, isActive = true, ocvPreset = "18650", customOCVArrayString = "3.0,3.5,4.0",
            pathHashMode = 2u, bleAddress = "AA:BB:CC:DD:EE:FF", wifiHost = "192.168.1.1", wifiPort = 5000,
            manualAddContacts = true, multiAcks = 1u, telemetryModeBase = 1u, telemetryModeLocation = 1u,
            telemetryModeEnvironment = 1u, advertLocationPolicy = 1u, autoAddConfig = 3u, autoAddMaxHops = 4u,
            clientRepeat = true, preRepeatFrequency = 900_000u, preRepeatBandwidth = 125_000u,
            preRepeatSpreadingFactor = 7u, preRepeatCodingRate = 5u, defaultFloodScopeName = "region-a",
            knownRegions = listOf("region-a", "region-b"),
        )
        assertEquals(dto, dto.toBackupJson().toDeviceDto())
    }

    @Test
    fun `DeviceDto round-trips with every optional field at its default`() {
        val dto = DeviceDto(
            id = UUID.randomUUID(), radioID = radioID, publicKey = byteArrayOf(1), nodeName = "N",
            firmwareVersion = 1u, firmwareVersionString = "v1.0.0", manufacturerName = "M", buildDate = "d",
            maxContacts = 1u, maxChannels = 1u, frequency = 1u, bandwidth = 1u, spreadingFactor = 1u,
            codingRate = 1u, txPower = 0, maxTxPower = 0, latitude = 0.0, longitude = 0.0, blePin = 0u,
            lastConnected = now(), lastContactSync = 0u, isActive = false, ocvPreset = null,
            customOCVArrayString = null,
        )
        assertEquals(dto, dto.toBackupJson().toDeviceDto())
    }
}
