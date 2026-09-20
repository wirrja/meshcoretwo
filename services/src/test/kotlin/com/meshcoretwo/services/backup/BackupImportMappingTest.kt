// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.persistence.ChannelDto
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.NotificationLevel
import com.meshcoretwo.services.persistence.ReactionDto
import com.meshcoretwo.services.persistence.RemoteNodeRole
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RoomMessageDto
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupImportMappingTest {
    private val radioID = UUID.randomUUID()

    // MARK: - buildRadioIdMapping / applyRadioIdMapping

    @Test
    fun `buildRadioIdMapping maps a backup device to the local device sharing its publicKey`() {
        val localRadioID = UUID.randomUUID()
        val device = deviceDto(publicKey = byteArrayOf(1, 2, 3))

        val result = buildRadioIdMapping(listOf(device), mapOf(device.publicKey.hexString to localRadioID))

        assertEquals(localRadioID, result.mapping[device.radioID])
        assertTrue(result.unmatchedDevices.isEmpty())
        assertEquals(0, result.duplicateDeviceCount)
    }

    @Test
    fun `buildRadioIdMapping leaves an unmatched device on its own radioID and reports it`() {
        val device = deviceDto(publicKey = byteArrayOf(9, 9, 9))

        val result = buildRadioIdMapping(listOf(device), emptyMap())

        assertEquals(device.radioID, result.mapping[device.radioID])
        assertEquals(listOf(device), result.unmatchedDevices)
    }

    @Test
    fun `buildRadioIdMapping counts a duplicate publicKey and points it at the first occurrence's mapping`() {
        val localRadioID = UUID.randomUUID()
        val first = deviceDto(radioID = UUID.randomUUID(), publicKey = byteArrayOf(1, 1, 1))
        val duplicate = deviceDto(radioID = UUID.randomUUID(), publicKey = byteArrayOf(1, 1, 1))
        val publicKeyHex = first.publicKey.hexString

        val result = buildRadioIdMapping(listOf(first, duplicate), mapOf(publicKeyHex to localRadioID))

        assertEquals(1, result.duplicateDeviceCount)
        assertEquals(localRadioID, result.mapping[first.radioID])
        assertEquals(localRadioID, result.mapping[duplicate.radioID])
    }

    @Test
    fun `applyRadioIdMapping rewrites mapped radioIDs and leaves unmapped ones untouched`() {
        val mappedFrom = UUID.randomUUID()
        val mappedTo = UUID.randomUUID()
        val unmapped = UUID.randomUUID()
        val contacts = listOf(contactDto(radioID = mappedFrom), contactDto(radioID = unmapped))

        val result = applyRadioIdMapping(mapOf(mappedFrom to mappedTo), contacts, { it.radioID }, { c, r -> c.copy(radioID = r) })

        assertEquals(mappedTo, result[0].radioID)
        assertEquals(unmapped, result[1].radioID)
    }

    @Test
    fun `applyRadioIdMapping is a no-op passthrough for an empty mapping`() {
        val contacts = listOf(contactDto())
        assertEquals(contacts, applyRadioIdMapping(emptyMap(), contacts, { it.radioID }, { c, r -> c.copy(radioID = r) }))
    }

    // MARK: - contact ID mapping

    @Test
    fun `buildContactIdMapping omits identity mappings and includes only merged contacts`() {
        val mergedLocalID = UUID.randomUUID()
        val contact = contactDto(publicKey = byteArrayOf(5, 5, 5))
        val identityContact = contactDto(publicKey = byteArrayOf(6, 6, 6))
        val byKey = mapOf(
            BackupDedupKeys.contactKey(contact.radioID, contact.publicKey) to mergedLocalID,
            BackupDedupKeys.contactKey(identityContact.radioID, identityContact.publicKey) to identityContact.id,
        )

        val mapping = buildContactIdMapping(listOf(contact, identityContact), byKey)

        assertEquals(mergedLocalID, mapping[contact.id])
        assertNull(mapping[identityContact.id])
    }

    @Test
    fun `applyContactIdMapping rewrites contactID on messages and reactions and the DM dedup key`() {
        val backupContactID = UUID.randomUUID()
        val localContactID = UUID.randomUUID()
        val message = messageDto(contactID = backupContactID, deduplicationKey = "dm-$backupContactID-1234-ABCDEF01")
        val reaction = reactionDto(contactID = backupContactID)

        val (messages, reactions) = applyContactIdMapping(mapOf(backupContactID to localContactID), listOf(message), listOf(reaction))

        assertEquals(localContactID, messages[0].contactID)
        assertEquals("dm-$localContactID-1234-ABCDEF01", messages[0].deduplicationKey)
        assertEquals(localContactID, reactions[0].contactID)
    }

    // MARK: - channel placement

    @Test
    fun `resolveChannelPlacementIndex keeps the backup's own slot when free`() {
        assertEquals(3u.toUByte(), resolveChannelPlacementIndex(3u, emptySet(), maxChannels = 8u))
    }

    @Test
    fun `resolveChannelPlacementIndex relocates to the lowest free slot within capacity, never slot 0`() {
        val occupied = setOf(0u.toUByte(), 1u.toUByte(), 2u.toUByte())
        assertEquals(3u.toUByte(), resolveChannelPlacementIndex(1u, occupied, maxChannels = 8u))
    }

    @Test
    fun `resolveChannelPlacementIndex returns null when no slot is free within capacity`() {
        val occupied = setOf(1u.toUByte(), 2u.toUByte(), 3u.toUByte())
        assertNull(resolveChannelPlacementIndex(1u, occupied, maxChannels = 4u))
    }

    @Test
    fun `channelHasStableSecret is false for an all-zero or empty secret`() {
        assertTrue(!channelHasStableSecret(ByteArray(16)))
        assertTrue(!channelHasStableSecret(ByteArray(0)))
        assertTrue(channelHasStableSecret(byteArrayOf(0, 0, 1)))
    }

    @Test
    fun `applyChannelIndexMapping rewrites channelIndex and dedup key, and drops messages for a dropped channel`() {
        val radioID = UUID.randomUUID()
        val kept = messageDto(radioID = radioID, channelIndex = 1u, deduplicationKey = "ch-1-1234-Alice-ABCDEF01", contactID = null)
        val dropped = messageDto(radioID = radioID, channelIndex = 5u, deduplicationKey = null, contactID = null)

        val result = applyChannelIndexMapping(
            remap = mapOf(radioID to mapOf(1u.toUByte() to 3u.toUByte())),
            droppedChannelIndices = mapOf(radioID to setOf(5u.toUByte())),
            messages = listOf(kept, dropped),
            reactions = emptyList(),
        )

        assertEquals(1, result.messages.size)
        assertEquals(3u.toUByte(), result.messages[0].channelIndex)
        assertEquals("ch-3-1234-Alice-ABCDEF01", result.messages[0].deduplicationKey)
        assertEquals(1, result.droppedMessageCount)
    }

    @Test
    fun `applyChannelIndexMapping is a no-op passthrough when there is nothing to remap or drop`() {
        val messages = listOf(messageDto(contactID = null, deduplicationKey = null))
        val result = applyChannelIndexMapping(emptyMap(), emptyMap(), messages, emptyList())
        assertEquals(messages, result.messages)
        assertEquals(0, result.droppedMessageCount)
    }

    // MARK: - message ID mapping

    @Test
    fun `applyMessageIdMapping rewrites the messageID field through the given accessor pair`() {
        data class Repeat(val messageID: UUID)

        val backupID = UUID.randomUUID()
        val localID = UUID.randomUUID()
        val result = applyMessageIdMapping(mapOf(backupID to localID), listOf(Repeat(backupID)), { it.messageID }, { r, id -> r.copy(messageID = id) })

        assertEquals(localID, result[0].messageID)
    }

    // MARK: - session ID mapping

    @Test
    fun `buildSessionIdMapping and applySessionIdMapping rewrite room message sessionID on merge`() {
        val session = remoteNodeSessionDto(publicKey = byteArrayOf(7, 7, 7))
        val mergedLocalID = UUID.randomUUID()
        val byKey = mapOf(BackupDedupKeys.remoteNodeSessionKey(session.radioID, session.publicKey) to mergedLocalID)

        val mapping = buildSessionIdMapping(listOf(session), byKey)
        val roomMessage = RoomMessageDto(sessionID = session.id, authorKeyPrefix = byteArrayOf(1), text = "hi", timestamp = 1u)

        val rewritten = applySessionIdMapping(mapping, listOf(roomMessage))

        assertEquals(mergedLocalID, rewritten[0].sessionID)
    }

    // MARK: - metadata merge

    @Test
    fun `mergeContactBackupMetadata never un-blocks and adopts a null-local nickname`() {
        val existing = contactDto(nickname = null, isBlocked = false)
        val backup = contactDto(nickname = "Bob", isBlocked = true)

        val (merged, changed) = mergeContactBackupMetadata(existing, backup)

        assertTrue(changed)
        assertEquals("Bob", merged.nickname)
        assertTrue(merged.isBlocked)
    }

    @Test
    fun `mergeContactBackupMetadata takes the max of unread counts and later lastMessageDate`() {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val existing = contactDto(unreadCount = 2, lastMessageDate = now)
        val backup = contactDto(unreadCount = 5, lastMessageDate = now.plusSeconds(60))

        val (merged, changed) = mergeContactBackupMetadata(existing, backup)

        assertTrue(changed)
        assertEquals(5, merged.unreadCount)
        assertEquals(now.plusSeconds(60), merged.lastMessageDate)
    }

    @Test
    fun `mergeContactBackupMetadata reports no change when the backup adds nothing new`() {
        val existing = contactDto(nickname = "Bob", isBlocked = true, unreadCount = 5)
        val backup = contactDto(nickname = "Alice", isBlocked = false, unreadCount = 1)

        val (_, changed) = mergeContactBackupMetadata(existing, backup)

        assertTrue(!changed)
    }

    @Test
    fun `clampedPhoneClockTimestamp clamps a far-future stamp to now plus tolerance`() {
        val now = Instant.ofEpochSecond(1_700_000_000)
        val farFuture = (now.epochSecond + 10_000_000).toUInt()

        val clamped = clampedPhoneClockTimestamp(farFuture, now)

        assertEquals((now.epochSecond.toUInt() + 300u), clamped)
    }

    @Test
    fun `clampedPhoneClockTimestamp leaves a plausible stamp untouched`() {
        val now = Instant.ofEpochSecond(1_700_000_000)
        val recent = (now.epochSecond - 10).toUInt()

        assertEquals(recent, clampedPhoneClockTimestamp(recent, now))
    }

    @Test
    fun `mergeChannelBackupMetadata only adopts notification level and flood scope while local is default`() {
        val existing = channelDto(notificationLevel = NotificationLevel.ALL, floodScopeModeRawValue = "inherit")
        val backup = channelDto(notificationLevel = NotificationLevel.MUTED, floodScopeModeRawValue = "allRegions")

        val (merged, changed) = mergeChannelBackupMetadata(existing, backup)

        assertTrue(changed)
        assertEquals(NotificationLevel.MUTED, merged.notificationLevel)
        assertEquals("allRegions", merged.floodScopeModeRawValue)
    }

    @Test
    fun `mergeChannelBackupMetadata does not override an already-customized notification level`() {
        val existing = channelDto(notificationLevel = NotificationLevel.MENTIONS_ONLY)
        val backup = channelDto(notificationLevel = NotificationLevel.MUTED)

        val (merged, _) = mergeChannelBackupMetadata(existing, backup)

        assertEquals(NotificationLevel.MENTIONS_ONLY, merged.notificationLevel)
    }

    @Test
    fun `mergeRemoteNodeSessionBackupMetadata takes the max of unreadCount and lastSyncTimestamp`() {
        val existing = remoteNodeSessionDto(unreadCount = 1, lastSyncTimestamp = 100)
        val backup = remoteNodeSessionDto(unreadCount = 9, lastSyncTimestamp = 50)

        val (merged, changed) = mergeRemoteNodeSessionBackupMetadata(existing, backup)

        assertTrue(changed)
        assertEquals(9, merged.unreadCount)
        assertEquals(100L, merged.lastSyncTimestamp)
    }

    @Test
    fun `mergeRemoteNodeSessionBackupMetadata carries a favorited flag over from backup`() {
        val existing = remoteNodeSessionDto(isFavorite = false)
        val backup = remoteNodeSessionDto(isFavorite = true)

        val (merged, changed) = mergeRemoteNodeSessionBackupMetadata(existing, backup)

        assertTrue(changed)
        assertTrue(merged.isFavorite)
    }

    // MARK: - fixtures

    private fun deviceDto(radioID: UUID = this.radioID, publicKey: ByteArray) = DeviceDto(
        id = UUID.randomUUID(),
        radioID = radioID,
        publicKey = publicKey,
        nodeName = "Node",
        firmwareVersion = 1u,
        firmwareVersionString = "1.0",
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
        lastConnected = Instant.now().truncatedTo(ChronoUnit.MILLIS),
        lastContactSync = 0u,
        isActive = true,
        ocvPreset = null,
        customOCVArrayString = null,
    )

    private fun contactDto(
        radioID: UUID = this.radioID,
        publicKey: ByteArray = byteArrayOf(1, 2, 3),
        nickname: String? = null,
        isBlocked: Boolean = false,
        unreadCount: Int = 0,
        lastMessageDate: Instant? = null,
    ) = ContactDto(
        id = UUID.randomUUID(),
        radioID = radioID,
        publicKey = publicKey,
        name = "Node",
        typeRawValue = 1u,
        flags = 0u,
        outPathLength = 0u,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0u,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0u,
        lastHeardTimestamp = 0u,
        nickname = nickname,
        isBlocked = isBlocked,
        isMuted = false,
        isFavorite = false,
        lastMessageDate = lastMessageDate,
        unreadCount = unreadCount,
        unreadMentionCount = 0,
        ocvPreset = null,
        customOCVArrayString = null,
        avatarImageData = null,
    )

    private fun channelDto(
        radioID: UUID = this.radioID,
        index: UByte = 1u,
        notificationLevel: NotificationLevel = NotificationLevel.ALL,
        floodScopeModeRawValue: String = "inherit",
    ) = ChannelDto(
        id = UUID.randomUUID(),
        radioID = radioID,
        index = index,
        name = "General",
        secret = ByteArray(16),
        isEnabled = true,
        lastMessageDate = null,
        unreadCount = 0,
        unreadMentionCount = 0,
        notificationLevel = notificationLevel,
        isFavorite = false,
        floodScopeModeRawValue = floodScopeModeRawValue,
        regionScope = null,
    )

    private fun remoteNodeSessionDto(
        radioID: UUID = this.radioID,
        publicKey: ByteArray = byteArrayOf(1, 2, 3),
        unreadCount: Int = 0,
        lastSyncTimestamp: Long = 0,
        isFavorite: Boolean = false,
    ) = RemoteNodeSessionDto(
        id = UUID.randomUUID(),
        radioID = radioID,
        publicKey = publicKey,
        name = "Room",
        role = RemoteNodeRole.ROOM_SERVER,
        unreadCount = unreadCount,
        lastSyncTimestamp = lastSyncTimestamp,
        isFavorite = isFavorite,
    )

    private fun messageDto(
        radioID: UUID = this.radioID,
        contactID: UUID?,
        channelIndex: UByte? = null,
        deduplicationKey: String?,
    ) = MessageDto(
        id = UUID.randomUUID(),
        radioID = radioID,
        contactID = contactID,
        channelIndex = channelIndex,
        text = "hi",
        timestamp = 1234u,
        createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS),
        sortDate = Instant.now().truncatedTo(ChronoUnit.MILLIS),
        direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED,
        textType = TextType.PLAIN_TEXT,
        ackCode = null,
        pathLength = 0u,
        snr = null,
        pathNodes = null,
        senderKeyPrefix = null,
        senderNodeName = null,
        isRead = true,
        replyToID = null,
        roundTripTime = null,
        sendCount = 1,
        retryAttempt = 0,
        maxRetryAttempts = 0,
        deduplicationKey = deduplicationKey,
        reactionSummary = null,
        senderTimestamp = null,
        routeType = null,
        heardRepeats = 0,
    )

    private fun reactionDto(contactID: UUID?) = ReactionDto(
        id = UUID.randomUUID(),
        messageID = UUID.randomUUID(),
        emoji = "👍",
        senderName = "Alice",
        messageHash = "abc",
        rawText = ":+1:",
        receivedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS),
        channelIndex = null,
        contactID = contactID,
        radioID = radioID,
    )
}
