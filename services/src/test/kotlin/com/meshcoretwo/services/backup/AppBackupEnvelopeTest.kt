// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

import com.meshcoretwo.services.persistence.BlockedChannelSenderDto
import com.meshcoretwo.services.persistence.ChannelDto
import com.meshcoretwo.services.persistence.NotificationLevel
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppBackupEnvelopeTest {
    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    @Test
    fun `create computes a manifest that matches the given lists`() {
        val radioID = UUID.randomUUID()
        val channel = ChannelDto(
            id = UUID.randomUUID(), radioID = radioID, index = 0u, name = "General", secret = ByteArray(16),
            isEnabled = true, lastMessageDate = null, unreadCount = 0, unreadMentionCount = 0,
            notificationLevel = NotificationLevel.ALL, isFavorite = false, floodScopeModeRawValue = "inherit", regionScope = null,
        )
        val blocked = BlockedChannelSenderDto(id = UUID.randomUUID(), name = "Spammer", radioID = radioID, dateBlocked = now())

        val envelope = AppBackupEnvelope.create(
            appVersion = "1.0", appBuild = "42",
            devices = emptyList(), contacts = emptyList(), channels = listOf(channel), messages = emptyList(),
            messageRepeats = emptyList(), reactions = emptyList(), roomMessages = emptyList(),
            remoteNodeSessions = emptyList(), savedTracePaths = emptyList(),
            blockedChannelSenders = listOf(blocked), nodeStatusSnapshots = emptyList(), discoveredNodes = emptyList(),
            exportDate = now(),
        )

        assertEquals(1, envelope.manifest.channelCount)
        assertEquals(1, envelope.manifest.blockedChannelSenderCount)
        assertEquals(0, envelope.manifest.contactCount)
        assertTrue(envelope.manifest.validate(envelope.actualCounts))
    }

    @Test
    fun `round-trips an envelope with populated lists through JSON`() {
        val radioID = UUID.randomUUID()
        val channel = ChannelDto(
            id = UUID.randomUUID(), radioID = radioID, index = 1u, name = "Test", secret = byteArrayOf(1, 2, 3),
            isEnabled = true, lastMessageDate = now(), unreadCount = 2, unreadMentionCount = 0,
            notificationLevel = NotificationLevel.MUTED, isFavorite = true, floodScopeModeRawValue = "allRegions", regionScope = null,
        )
        val envelope = AppBackupEnvelope.create(
            appVersion = "2.1", appBuild = "99",
            devices = emptyList(), contacts = emptyList(), channels = listOf(channel), messages = emptyList(),
            messageRepeats = emptyList(), reactions = emptyList(), roomMessages = emptyList(),
            remoteNodeSessions = emptyList(), savedTracePaths = emptyList(),
            blockedChannelSenders = emptyList(), nodeStatusSnapshots = emptyList(), discoveredNodes = emptyList(),
            exportDate = now(),
        )

        val decoded = envelope.toBackupJson().toAppBackupEnvelope()

        assertEquals(envelope, decoded)
        assertTrue(decoded.manifest.validate(decoded.actualCounts))
    }

    @Test
    fun `round-trips an entirely-empty envelope`() {
        val envelope = AppBackupEnvelope.create(
            appVersion = "1.0", appBuild = "1",
            devices = emptyList(), contacts = emptyList(), channels = emptyList(), messages = emptyList(),
            messageRepeats = emptyList(), reactions = emptyList(), roomMessages = emptyList(),
            remoteNodeSessions = emptyList(), savedTracePaths = emptyList(),
            blockedChannelSenders = emptyList(), nodeStatusSnapshots = emptyList(), discoveredNodes = emptyList(),
            exportDate = now(),
        )

        assertEquals(envelope, envelope.toBackupJson().toAppBackupEnvelope())
    }

    @Test
    fun `round-trips an envelope with a populated userDefaults through JSON`() {
        val envelope = AppBackupEnvelope.create(
            appVersion = "1.0", appBuild = "1",
            devices = emptyList(), contacts = emptyList(), channels = emptyList(), messages = emptyList(),
            messageRepeats = emptyList(), reactions = emptyList(), roomMessages = emptyList(),
            remoteNodeSessions = emptyList(), savedTracePaths = emptyList(),
            blockedChannelSenders = emptyList(), nodeStatusSnapshots = emptyList(), discoveredNodes = emptyList(),
            userDefaults = BackupUserDefaults(selectedThemeID = "midnight", notifyLowBattery = false, autoDeleteStaleNodesDays = 30),
            exportDate = now(),
        )

        val decoded = envelope.toBackupJson().toAppBackupEnvelope()

        assertEquals(envelope, decoded)
        assertEquals("midnight", decoded.userDefaults?.selectedThemeID)
    }

    @Test
    fun `a legacy envelope with no userDefaults key decodes it as null`() {
        val envelope = AppBackupEnvelope.create(
            appVersion = "1.0", appBuild = "1",
            devices = emptyList(), contacts = emptyList(), channels = emptyList(), messages = emptyList(),
            messageRepeats = emptyList(), reactions = emptyList(), roomMessages = emptyList(),
            remoteNodeSessions = emptyList(), savedTracePaths = emptyList(),
            blockedChannelSenders = emptyList(), nodeStatusSnapshots = emptyList(), discoveredNodes = emptyList(),
            exportDate = now(),
        )
        val legacyJson = envelope.toBackupJson().apply { remove("userDefaults") }

        assertEquals(null, legacyJson.toAppBackupEnvelope().userDefaults)
    }

    @Test
    fun `a manifest missing a key (legacy envelope) decodes that count as zero`() {
        val json = org.json.JSONObject().apply {
            put("deviceCount", 3)
            // every other key intentionally omitted, as a pre-discoveredNodes-field legacy backup would be
        }

        val manifest = json.toBackupManifest()

        assertEquals(3, manifest.deviceCount)
        assertEquals(0, manifest.discoveredNodeCount)
        assertEquals(0, manifest.contactCount)
    }
}
