// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

import com.meshcoretwo.services.backup.dto.getBooleanOrNull
import com.meshcoretwo.services.backup.dto.getInstant
import com.meshcoretwo.services.backup.dto.getIntOrNull
import com.meshcoretwo.services.backup.dto.getObjectList
import com.meshcoretwo.services.backup.dto.getStringOrNull
import com.meshcoretwo.services.backup.dto.putBooleanOrNull
import com.meshcoretwo.services.backup.dto.putInstant
import com.meshcoretwo.services.backup.dto.putIntOrNull
import com.meshcoretwo.services.backup.dto.putObjectList
import com.meshcoretwo.services.backup.dto.putStringOrNull
import com.meshcoretwo.services.backup.dto.toBackupJson
import com.meshcoretwo.services.backup.dto.toBlockedChannelSenderDto
import com.meshcoretwo.services.backup.dto.toChannelDto
import com.meshcoretwo.services.backup.dto.toContactDto
import com.meshcoretwo.services.backup.dto.toDeviceDto
import com.meshcoretwo.services.backup.dto.toDiscoveredNodeDto
import com.meshcoretwo.services.backup.dto.toMessageDto
import com.meshcoretwo.services.backup.dto.toMessageRepeatDto
import com.meshcoretwo.services.backup.dto.toNodeStatusSnapshotDto
import com.meshcoretwo.services.backup.dto.toReactionDto
import com.meshcoretwo.services.backup.dto.toRemoteNodeSessionDto
import com.meshcoretwo.services.backup.dto.toRoomMessageDto
import com.meshcoretwo.services.backup.dto.toTracePathDto
import com.meshcoretwo.services.persistence.BlockedChannelSenderDto
import com.meshcoretwo.services.persistence.ChannelDto
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageRepeatDto
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import com.meshcoretwo.services.persistence.ReactionDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RoomMessageDto
import com.meshcoretwo.services.persistence.TracePathDto
import java.time.Instant
import org.json.JSONObject

/**
 * Top-level container for a full app backup export. Ported from `AppBackupEnvelope.swift`.
 * [userDefaults] is nullable to tolerate decoding a legacy envelope from before the
 * `BackupUserDefaults` sub-slice landed — [JSONObject.toAppBackupEnvelope] reads it with
 * `optJSONObject`, which returns `null` for an absent key exactly like Swift's own
 * `decodeIfPresent`.
 */
data class AppBackupEnvelope(
    val version: Int = CURRENT_VERSION,
    val exportDate: Instant,
    val appVersion: String,
    val appBuild: String,
    val manifest: BackupManifest,
    val devices: List<DeviceDto>,
    val contacts: List<ContactDto>,
    val channels: List<ChannelDto>,
    val messages: List<MessageDto>,
    val messageRepeats: List<MessageRepeatDto>,
    val reactions: List<ReactionDto>,
    val roomMessages: List<RoomMessageDto>,
    val remoteNodeSessions: List<RemoteNodeSessionDto>,
    val savedTracePaths: List<TracePathDto>,
    val blockedChannelSenders: List<BlockedChannelSenderDto>,
    val nodeStatusSnapshots: List<NodeStatusSnapshotDto>,
    val discoveredNodes: List<DiscoveredNodeDto>,
    val userDefaults: BackupUserDefaults? = null,
) {
    /** This envelope's actual per-kind counts, as [BackupManifest.validate]/`.from` expect them. */
    val actualCounts: Map<BackupModelKind, Int>
        get() = mapOf(
            BackupModelKind.DEVICES to devices.size,
            BackupModelKind.CONTACTS to contacts.size,
            BackupModelKind.CHANNELS to channels.size,
            BackupModelKind.MESSAGES to messages.size,
            BackupModelKind.MESSAGE_REPEATS to messageRepeats.size,
            BackupModelKind.REACTIONS to reactions.size,
            BackupModelKind.ROOM_MESSAGES to roomMessages.size,
            BackupModelKind.REMOTE_NODE_SESSIONS to remoteNodeSessions.size,
            BackupModelKind.SAVED_TRACE_PATHS to savedTracePaths.size,
            BackupModelKind.BLOCKED_CHANNEL_SENDERS to blockedChannelSenders.size,
            BackupModelKind.NODE_STATUS_SNAPSHOTS to nodeStatusSnapshots.size,
            BackupModelKind.DISCOVERED_NODES to discoveredNodes.size,
        )

    companion object {
        const val CURRENT_VERSION = 1

        /** Builds an envelope from freshly-fetched data, computing [manifest] from the arrays themselves so the two can never disagree. */
        fun create(
            appVersion: String,
            appBuild: String,
            devices: List<DeviceDto>,
            contacts: List<ContactDto>,
            channels: List<ChannelDto>,
            messages: List<MessageDto>,
            messageRepeats: List<MessageRepeatDto>,
            reactions: List<ReactionDto>,
            roomMessages: List<RoomMessageDto>,
            remoteNodeSessions: List<RemoteNodeSessionDto>,
            savedTracePaths: List<TracePathDto>,
            blockedChannelSenders: List<BlockedChannelSenderDto>,
            nodeStatusSnapshots: List<NodeStatusSnapshotDto>,
            discoveredNodes: List<DiscoveredNodeDto>,
            userDefaults: BackupUserDefaults? = null,
            exportDate: Instant = Instant.now(),
        ): AppBackupEnvelope {
            val envelope = AppBackupEnvelope(
                exportDate = exportDate,
                appVersion = appVersion,
                appBuild = appBuild,
                manifest = BackupManifest(),
                devices = devices,
                contacts = contacts,
                channels = channels,
                messages = messages,
                messageRepeats = messageRepeats,
                reactions = reactions,
                roomMessages = roomMessages,
                remoteNodeSessions = remoteNodeSessions,
                savedTracePaths = savedTracePaths,
                blockedChannelSenders = blockedChannelSenders,
                nodeStatusSnapshots = nodeStatusSnapshots,
                discoveredNodes = discoveredNodes,
                userDefaults = userDefaults,
            )
            return envelope.copy(manifest = BackupManifest.from(envelope.actualCounts))
        }
    }
}

fun AppBackupEnvelope.toBackupJson(): JSONObject = JSONObject().apply {
    put("version", version)
    putInstant("exportDate", exportDate)
    put("appVersion", appVersion)
    put("appBuild", appBuild)
    put("manifest", manifest.toBackupJson())
    putObjectList("devices", devices) { it.toBackupJson() }
    putObjectList("contacts", contacts) { it.toBackupJson() }
    putObjectList("channels", channels) { it.toBackupJson() }
    putObjectList("messages", messages) { it.toBackupJson() }
    putObjectList("messageRepeats", messageRepeats) { it.toBackupJson() }
    putObjectList("reactions", reactions) { it.toBackupJson() }
    putObjectList("roomMessages", roomMessages) { it.toBackupJson() }
    putObjectList("remoteNodeSessions", remoteNodeSessions) { it.toBackupJson() }
    putObjectList("savedTracePaths", savedTracePaths) { it.toBackupJson() }
    putObjectList("blockedChannelSenders", blockedChannelSenders) { it.toBackupJson() }
    putObjectList("nodeStatusSnapshots", nodeStatusSnapshots) { it.toBackupJson() }
    putObjectList("discoveredNodes", discoveredNodes) { it.toBackupJson() }
    userDefaults?.let { put("userDefaults", it.toBackupJson()) }
}

/** Throws [org.json.JSONException] on a structurally invalid envelope — callers doing untrusted-file import should validate via [BackupManifest.validate] afterward, not rely on this to reject a corrupted-but-well-formed file. */
fun JSONObject.toAppBackupEnvelope(): AppBackupEnvelope = AppBackupEnvelope(
    version = getInt("version"),
    exportDate = getInstant("exportDate"),
    appVersion = getString("appVersion"),
    appBuild = getString("appBuild"),
    manifest = getJSONObject("manifest").toBackupManifest(),
    devices = getObjectList("devices") { it.toDeviceDto() },
    contacts = getObjectList("contacts") { it.toContactDto() },
    channels = getObjectList("channels") { it.toChannelDto() },
    messages = getObjectList("messages") { it.toMessageDto() },
    messageRepeats = getObjectList("messageRepeats") { it.toMessageRepeatDto() },
    reactions = getObjectList("reactions") { it.toReactionDto() },
    roomMessages = getObjectList("roomMessages") { it.toRoomMessageDto() },
    remoteNodeSessions = getObjectList("remoteNodeSessions") { it.toRemoteNodeSessionDto() },
    savedTracePaths = getObjectList("savedTracePaths") { it.toTracePathDto() },
    blockedChannelSenders = getObjectList("blockedChannelSenders") { it.toBlockedChannelSenderDto() },
    nodeStatusSnapshots = getObjectList("nodeStatusSnapshots") { it.toNodeStatusSnapshotDto() },
    discoveredNodes = getObjectList("discoveredNodes") { it.toDiscoveredNodeDto() },
    userDefaults = optJSONObject("userDefaults")?.toBackupUserDefaults(),
)

fun BackupManifest.toBackupJson(): JSONObject = JSONObject().apply {
    put("deviceCount", deviceCount)
    put("contactCount", contactCount)
    put("channelCount", channelCount)
    put("messageCount", messageCount)
    put("messageRepeatCount", messageRepeatCount)
    put("reactionCount", reactionCount)
    put("roomMessageCount", roomMessageCount)
    put("remoteNodeSessionCount", remoteNodeSessionCount)
    put("savedTracePathCount", savedTracePathCount)
    put("blockedChannelSenderCount", blockedChannelSenderCount)
    put("nodeStatusSnapshotCount", nodeStatusSnapshotCount)
    put("discoveredNodeCount", discoveredNodeCount)
}

/** Legacy-envelope-tolerant: a manifest key missing from an older backup file decodes as 0, matching Swift's `decodeIfPresent ?? 0` for `discoveredNodeCount`. */
fun JSONObject.toBackupManifest(): BackupManifest = BackupManifest(
    deviceCount = optInt("deviceCount"),
    contactCount = optInt("contactCount"),
    channelCount = optInt("channelCount"),
    messageCount = optInt("messageCount"),
    messageRepeatCount = optInt("messageRepeatCount"),
    reactionCount = optInt("reactionCount"),
    roomMessageCount = optInt("roomMessageCount"),
    remoteNodeSessionCount = optInt("remoteNodeSessionCount"),
    savedTracePathCount = optInt("savedTracePathCount"),
    blockedChannelSenderCount = optInt("blockedChannelSenderCount"),
    nodeStatusSnapshotCount = optInt("nodeStatusSnapshotCount"),
    discoveredNodeCount = optInt("discoveredNodeCount"),
)

fun BackupUserDefaults.toBackupJson(): JSONObject = JSONObject().apply {
    putStringOrNull("selectedThemeID", selectedThemeID)
    putStringOrNull("appColorSchemePreference", appColorSchemePreference)
    putBooleanOrNull("notifyContactMessages", notifyContactMessages)
    putBooleanOrNull("notifyChannelMessages", notifyChannelMessages)
    putBooleanOrNull("notifyRoomMessages", notifyRoomMessages)
    putBooleanOrNull("notifyNewContacts", notifyNewContacts)
    putBooleanOrNull("notifyNewContactsContact", notifyNewContactsContact)
    putBooleanOrNull("notifyNewContactsRepeater", notifyNewContactsRepeater)
    putBooleanOrNull("notifyNewContactsRoom", notifyNewContactsRoom)
    putBooleanOrNull("notifyReactions", notifyReactions)
    putBooleanOrNull("notificationSoundEnabled", notificationSoundEnabled)
    putBooleanOrNull("notifyLowBattery", notifyLowBattery)
    putIntOrNull("autoDeleteStaleNodesDays", autoDeleteStaleNodesDays)
}

fun JSONObject.toBackupUserDefaults(): BackupUserDefaults = BackupUserDefaults(
    selectedThemeID = getStringOrNull("selectedThemeID"),
    appColorSchemePreference = getStringOrNull("appColorSchemePreference"),
    notifyContactMessages = getBooleanOrNull("notifyContactMessages"),
    notifyChannelMessages = getBooleanOrNull("notifyChannelMessages"),
    notifyRoomMessages = getBooleanOrNull("notifyRoomMessages"),
    notifyNewContacts = getBooleanOrNull("notifyNewContacts"),
    notifyNewContactsContact = getBooleanOrNull("notifyNewContactsContact"),
    notifyNewContactsRepeater = getBooleanOrNull("notifyNewContactsRepeater"),
    notifyNewContactsRoom = getBooleanOrNull("notifyNewContactsRoom"),
    notifyReactions = getBooleanOrNull("notifyReactions"),
    notificationSoundEnabled = getBooleanOrNull("notificationSoundEnabled"),
    notifyLowBattery = getBooleanOrNull("notifyLowBattery"),
    autoDeleteStaleNodesDays = getIntOrNull("autoDeleteStaleNodesDays"),
)
