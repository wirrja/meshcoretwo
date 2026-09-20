// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

import android.content.SharedPreferences
import androidx.room.withTransaction
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.DeviceStore
import com.meshcoretwo.services.persistence.DiscoveredNodeStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageRepeatStore
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.NodeStatusSnapshotStore
import com.meshcoretwo.services.persistence.ReactionStore
import com.meshcoretwo.services.persistence.RemoteNodeSessionStore
import com.meshcoretwo.services.persistence.RoomMessageDto
import com.meshcoretwo.services.persistence.RoomMessageStore
import com.meshcoretwo.services.persistence.TracePathStore
import com.meshcoretwo.services.persistence.redactedForBackup
import java.time.Instant
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

/**
 * Orchestrates a full app-data backup export/import over [database]. Ported from
 * `AppBackupService.swift` (export half) and `PersistenceStore+BackupImport.swift`'s
 * `importBackupDatabase` (the transaction this class's [importBackupFile] wraps).
 *
 * Takes [database] — not a [com.meshcoretwo.services.ServiceContainer] — as its only dependency,
 * constructing its own private `*Store` instances the same way [com.meshcoretwo.services.ServiceContainer]
 * does. This resolves the "where does a process-lifetime, cross-session store holder live" question
 * flagged when sub-slice 2b landed: [database] (`MeshCoreDatabase`) is already process-lifetime —
 * held by `AppContainer` in `app`, independent of [ConnectionManager][com.meshcoretwo.services.connection.ConnectionManager]'s
 * per-connection [com.meshcoretwo.services.ServiceContainer] — so no new container is needed; this
 * class just needs to be constructed once, alongside `connectionManager`, sharing the same database.
 * Backup export/import touches every radio's data regardless of which (if any) is currently
 * connected, which is exactly what makes [com.meshcoretwo.services.ServiceContainer] (built fresh
 * per connection, torn down on disconnect) the wrong home for this.
 *
 * [prefs] must be the `"app_storage"` file ([com.meshcoretwo.services.notifications.NotificationPreferences.PREFS_NAME]) —
 * the namespace [BackupUserDefaults] reads/writes.
 */
class AppBackupService(private val database: MeshCoreDatabase, private val prefs: SharedPreferences) {
    private val deviceStore = DeviceStore(database)
    private val contactStore = ContactStore(database)
    private val channelStore = ChannelStore(database)
    private val messageStore = MessageStore(database)
    private val messageRepeatStore = MessageRepeatStore(database)
    private val reactionStore = ReactionStore(database)
    private val roomMessageStore = RoomMessageStore(database)
    private val remoteNodeSessionStore = RemoteNodeSessionStore(database)
    private val tracePathStore = TracePathStore(database)
    private val nodeStatusSnapshotStore = NodeStatusSnapshotStore(database)
    private val discoveredNodeStore = DiscoveredNodeStore(database)

    /**
     * Builds a full-database envelope from the live tables. [com.meshcoretwo.services.persistence.DeviceDto.redactedForBackup]
     * strips privacy-sensitive device fields (BLE PIN, radio config, BLE MAC) before they ever
     * leave [database]. Ported from the `AppBackupEnvelope`-building half of `AppBackupService.createBackup`.
     */
    suspend fun exportEnvelope(appVersion: String, appBuild: String, exportDate: Instant = Instant.now()): AppBackupEnvelope {
        val remoteNodeSessions = remoteNodeSessionStore.fetchAllSessions()
        val messages = messageStore.fetchAllMessagesForBackup()
        return AppBackupEnvelope.create(
            appVersion = appVersion,
            appBuild = appBuild,
            devices = deviceStore.fetchDevices().map { it.redactedForBackup() },
            contacts = contactStore.fetchAllContacts(),
            channels = channelStore.fetchAllChannels(),
            messages = messages,
            messageRepeats = messageRepeatStore.fetchMessageRepeats(messages.map { it.id }),
            reactions = reactionStore.fetchAllReactions(),
            roomMessages = roomMessageStore.fetchMessages(remoteNodeSessions.map { it.id }),
            remoteNodeSessions = remoteNodeSessions,
            savedTracePaths = tracePathStore.fetchAllSavedTracePaths(),
            blockedChannelSenders = contactStore.fetchAllBlockedChannelSenders(),
            nodeStatusSnapshots = nodeStatusSnapshotStore.fetchAllNodeStatusSnapshots(),
            discoveredNodes = discoveredNodeStore.fetchAllDiscoveredNodes(),
            userDefaults = BackupUserDefaults.snapshot(prefs),
            exportDate = exportDate,
        )
    }

    /** [exportEnvelope] as compressed `.mc1backup` file bytes: JSON, UTF-8, then [BackupZlibCodec.compress]. */
    suspend fun exportBackupFile(appVersion: String, appBuild: String, exportDate: Instant = Instant.now()): ByteArray {
        val json = exportEnvelope(appVersion, appBuild, exportDate).toBackupJson().toString()
        return BackupZlibCodec.compress(json.toByteArray(Charsets.UTF_8))
    }

    /**
     * Validates and decodes a `.mc1backup` file's bytes into an [AppBackupEnvelope], without
     * touching [database] — the read-only half of the import pipeline, split out from
     * [importEnvelope] so a caller (the Settings UI) can show the user what a file contains before
     * committing it. Ported from `parseBackup(data:)`.
     *
     * @throws AppBackupError.FileTooLarge if [data] exceeds [BackupZlibCodec.MAX_BACKUP_COMPRESSED_BYTES].
     * @throws AppBackupError.InvalidFile if [data] isn't valid zlib, or the decompressed payload isn't valid JSON.
     * @throws AppBackupError.UnsupportedVersion if the envelope declares a newer format version than this app supports.
     * @throws AppBackupError.CorruptedManifest if the declared manifest counts don't match the envelope's actual arrays.
     */
    fun parseBackupFile(data: ByteArray): AppBackupEnvelope {
        if (data.size.toLong() > BackupZlibCodec.MAX_BACKUP_COMPRESSED_BYTES) {
            throw AppBackupError.FileTooLarge(data.size.toLong(), BackupZlibCodec.MAX_BACKUP_COMPRESSED_BYTES)
        }
        val decompressed = BackupZlibCodec.decompress(data)
        val envelope = try {
            JSONObject(String(decompressed, Charsets.UTF_8)).toAppBackupEnvelope()
        } catch (e: JSONException) {
            throw AppBackupError.InvalidFile
        }
        if (envelope.version > AppBackupEnvelope.CURRENT_VERSION) {
            throw AppBackupError.UnsupportedVersion(envelope.version, AppBackupEnvelope.CURRENT_VERSION)
        }
        if (!envelope.manifest.validate(envelope.actualCounts)) {
            throw AppBackupError.CorruptedManifest
        }
        return envelope
    }

    /**
     * Imports an already-[parseBackupFile]d envelope in one Room transaction — either every row
     * lands or none does — then restores [AppBackupEnvelope.userDefaults] into [prefs]. Ported from
     * `importBackupDatabase`'s call site and the `userDefaults` restore in `AppBackupService.importBackup`.
     *
     * The preferences restore intentionally happens after the transaction commits, matching Swift's
     * own ordering (see that function's comment on why cancellation past that point is ignored):
     * [BackupUserDefaults.restore] is a plain, non-transactional `SharedPreferences` write with no
     * relationship to Room's transaction, and re-running it on a retry is a no-op by construction
     * (write-if-missing), so there is nothing to roll back either way.
     */
    suspend fun importEnvelope(envelope: AppBackupEnvelope): ImportResult {
        val result = database.withTransaction { importBackupDatabase(envelope) }
        val addedAny = envelope.userDefaults?.restore(prefs) ?: false
        result.userDefaultsRestored = addedAny
        return result
    }

    /** [parseBackupFile] then [importEnvelope] in one call, for callers that don't need a preview step in between. */
    suspend fun importBackupFile(data: ByteArray): ImportResult = importEnvelope(parseBackupFile(data))

    /**
     * The actual row-by-row reconciliation, run inside the caller's transaction. Ported from
     * `importBackupDatabase` — see that Swift function's doc for why each step happens in this
     * exact order (later steps depend on ID/index remaps earlier steps produce).
     */
    private suspend fun importBackupDatabase(envelope: AppBackupEnvelope): ImportResult {
        val result = ImportResult()

        val localDeviceRadioIdsByPublicKey = deviceStore.existingDeviceRadioIdsByPublicKey()
        val radioMap = buildRadioIdMapping(envelope.devices, localDeviceRadioIdsByPublicKey)
        if (radioMap.duplicateDeviceCount > 0) {
            result.record(BackupModelKind.DEVICES, skipped = radioMap.duplicateDeviceCount)
        }

        var contacts = envelope.contacts
        var channels = envelope.channels
        var messages = envelope.messages
        var reactions = envelope.reactions
        var sessions = envelope.remoteNodeSessions
        var tracePaths = envelope.savedTracePaths
        var blockedSenders = envelope.blockedChannelSenders
        var messageRepeats = envelope.messageRepeats
        var roomMessages = envelope.roomMessages
        var discoveredNodes = envelope.discoveredNodes

        // Skip the rewrite when every mapping entry is identity (same-device restore).
        if (radioMap.mapping.any { it.key != it.value }) {
            contacts = applyRadioIdMapping(radioMap.mapping, contacts, { it.radioID }, { c, r -> c.copy(radioID = r) })
            channels = applyRadioIdMapping(radioMap.mapping, channels, { it.radioID }, { c, r -> c.copy(radioID = r) })
            messages = applyRadioIdMapping(radioMap.mapping, messages, { it.radioID }, { m, r -> m.copy(radioID = r) })
            reactions = applyRadioIdMapping(radioMap.mapping, reactions, { it.radioID }, { r, id -> r.copy(radioID = id) })
            sessions = applyRadioIdMapping(radioMap.mapping, sessions, { it.radioID }, { s, r -> s.copy(radioID = r) })
            tracePaths = applyRadioIdMapping(radioMap.mapping, tracePaths, { it.radioID }, { t, r -> t.copy(radioID = r) })
            blockedSenders = applyRadioIdMapping(radioMap.mapping, blockedSenders, { it.radioID }, { b, r -> b.copy(radioID = r) })
            discoveredNodes = applyRadioIdMapping(radioMap.mapping, discoveredNodes, { it.radioID }, { d, r -> d.copy(radioID = r) })
        }

        val deviceCounts = deviceStore.batchInsertDevices(radioMap.unmatchedDevices, localDeviceRadioIdsByPublicKey.keys)
        result.record(BackupModelKind.DEVICES, inserted = deviceCounts.inserted, skipped = deviceCounts.skipped)

        val allRadioIds = mutableSetOf<UUID>().apply {
            addAll(radioMap.mapping.values)
            addAll(contacts.map { it.radioID })
            addAll(channels.map { it.radioID })
            addAll(messages.map { it.radioID })
            addAll(reactions.map { it.radioID })
            addAll(sessions.map { it.radioID })
            addAll(tracePaths.map { it.radioID })
            addAll(blockedSenders.map { it.radioID })
            addAll(discoveredNodes.map { it.radioID })
        }

        val contactResult = contactStore.batchInsertContacts(contacts, allRadioIds)
        result.record(BackupModelKind.CONTACTS, inserted = contactResult.counts.inserted, merged = contactResult.counts.merged, skipped = contactResult.counts.skipped)

        val contactIdMapping = buildContactIdMapping(contacts, contactResult.contactIdsByKey)
        val (messagesAfterContactRemap, reactionsAfterContactRemap) = applyContactIdMapping(contactIdMapping, messages, reactions)
        messages = messagesAfterContactRemap
        reactions = reactionsAfterContactRemap

        val maxChannelsByRadioId = maxChannelsByLocalRadioId(envelope.devices, radioMap.mapping)
        val channelResult = channelStore.batchInsertChannels(channels, allRadioIds, maxChannelsByRadioId)
        result.record(
            BackupModelKind.CHANNELS,
            inserted = channelResult.counts.inserted,
            merged = channelResult.counts.merged,
            skipped = channelResult.counts.skipped,
            dropped = channelResult.counts.dropped,
        )
        for ((radioID, indices) in channelResult.insertedLocalIndices) {
            result.channelSlotsAffectedByImport.getOrPut(radioID) { mutableSetOf() }.addAll(indices)
        }
        for ((radioID, dropped) in channelResult.droppedChannelIndices) {
            result.channelSlotsAffectedByImport.getOrPut(radioID) { mutableSetOf() }.addAll(dropped)
        }

        val channelIndexResult = applyChannelIndexMapping(channelResult.channelIndexRemap, channelResult.droppedChannelIndices, messages, reactions)
        messages = channelIndexResult.messages
        reactions = channelIndexResult.reactions

        val sessionResult = remoteNodeSessionStore.batchInsertRemoteNodeSessions(sessions, allRadioIds)
        result.record(BackupModelKind.REMOTE_NODE_SESSIONS, inserted = sessionResult.counts.inserted, merged = sessionResult.counts.merged, skipped = sessionResult.counts.skipped)

        val (existingMessageKeys, existingMessageIdsByKey) = messageStore.existingMessageLookups(allRadioIds)
        val messageResult = messageStore.batchInsertMessages(messages, existingMessageKeys, existingMessageIdsByKey)
        result.record(BackupModelKind.MESSAGES, inserted = messageResult.counts.inserted, skipped = messageResult.counts.skipped, dropped = channelIndexResult.droppedMessageCount)

        val messageIdMapping = messageResult.messageIdByBackupId
        val allMessageIds = messages.mapTo(mutableSetOf()) { messageIdMapping[it.id] ?: it.id }
        messageRepeats = applyMessageIdMapping(messageIdMapping, messageRepeats, { it.messageID }, { r, id -> r.copy(messageID = id) })
        reactions = applyMessageIdMapping(messageIdMapping, reactions, { it.messageID }, { r, id -> r.copy(messageID = id) })

        val existingRepeatIds = messageRepeatStore.existingRepeatIds(allMessageIds)
        val repeatResult = messageRepeatStore.batchInsertMessageRepeats(messageRepeats, existingRepeatIds, allMessageIds)
        result.record(BackupModelKind.MESSAGE_REPEATS, inserted = repeatResult.counts.inserted, skipped = repeatResult.counts.skipped)

        val existingReactionKeys = reactionStore.existingReactionKeys(allMessageIds)
        val reactionResult = reactionStore.batchInsertReactions(reactions, existingReactionKeys, allMessageIds)
        result.record(BackupModelKind.REACTIONS, inserted = reactionResult.counts.inserted, skipped = reactionResult.counts.skipped, dropped = channelIndexResult.droppedReactionCount)

        val affectedMessageIds = repeatResult.affectedParentIds + reactionResult.affectedParentIds
        messageStore.recomputeMessageCaches(affectedMessageIds)

        val sessionIdMapping = buildSessionIdMapping(sessions, sessionResult.sessionIdsByKey)
        val allSessionIds = sessions.mapTo(mutableSetOf()) { sessionIdMapping[it.id] ?: it.id }
        roomMessages = applySessionIdMapping(sessionIdMapping, roomMessages)

        val existingRoomMessageKeys = roomMessageStore.existingRoomMessageKeys(allSessionIds)
        val roomMessageResult = roomMessageStore.batchInsertRoomMessages(roomMessages, existingRoomMessageKeys, allSessionIds)
        result.record(BackupModelKind.ROOM_MESSAGES, inserted = roomMessageResult.counts.inserted, skipped = roomMessageResult.counts.skipped)

        val traceCounts = tracePathStore.batchInsertSavedTracePaths(tracePaths)
        result.record(BackupModelKind.SAVED_TRACE_PATHS, inserted = traceCounts.inserted, merged = traceCounts.merged, skipped = traceCounts.skipped)

        val existingBlockedKeys = contactStore.existingBlockedSenderKeys(allRadioIds)
        val blockedCounts = contactStore.batchInsertBlockedChannelSenders(blockedSenders, existingBlockedKeys)
        result.record(BackupModelKind.BLOCKED_CHANNEL_SENDERS, inserted = blockedCounts.inserted, skipped = blockedCounts.skipped)

        val existingSnapshotKeys = nodeStatusSnapshotStore.existingSnapshotKeys()
        val snapshotCounts = nodeStatusSnapshotStore.batchInsertNodeStatusSnapshots(envelope.nodeStatusSnapshots, existingSnapshotKeys)
        result.record(BackupModelKind.NODE_STATUS_SNAPSHOTS, inserted = snapshotCounts.inserted, skipped = snapshotCounts.skipped)

        val existingDiscoveredKeys = discoveredNodeStore.existingDiscoveredNodeKeys(allRadioIds)
        val discoveredCounts = discoveredNodeStore.batchInsertDiscoveredNodes(discoveredNodes, existingDiscoveredKeys)
        result.record(BackupModelKind.DISCOVERED_NODES, inserted = discoveredCounts.inserted, skipped = discoveredCounts.skipped, dropped = discoveredCounts.dropped)

        reconcileLastMessageDates(messages, roomMessages, roomMessageResult.affectedParentIds)

        return result
    }

    /**
     * Advances `Contact`/`Channel`/`RemoteNodeSession.lastMessageDate` toward the max timestamp
     * among just-imported rows attached to each. Ported from `reconcileLastMessageDates`.
     */
    private suspend fun reconcileLastMessageDates(
        messages: List<MessageDto>,
        roomMessages: List<RoomMessageDto>,
        affectedRoomSessionIds: Set<UUID>,
    ) {
        val contactMaxDates = mutableMapOf<UUID, Instant>()
        val channelMaxDates = mutableMapOf<UUID, MutableMap<UByte, Instant>>()
        for (message in messages) {
            message.contactID?.let { contactId ->
                val existing = contactMaxDates[contactId]
                if (existing == null || existing < message.createdAt) contactMaxDates[contactId] = message.createdAt
            }
            message.channelIndex?.let { index ->
                val perIndex = channelMaxDates.getOrPut(message.radioID) { mutableMapOf() }
                val existing = perIndex[index]
                if (existing == null || existing < message.createdAt) perIndex[index] = message.createdAt
            }
        }
        if (contactMaxDates.isNotEmpty()) contactStore.applyLastMessageDatesToContacts(contactMaxDates)
        if (channelMaxDates.isNotEmpty()) channelStore.applyLastMessageDatesToChannels(channelMaxDates)

        if (affectedRoomSessionIds.isEmpty()) return
        val sessionMaxDates = mutableMapOf<UUID, Instant>()
        for (roomMessage in roomMessages) {
            if (roomMessage.sessionID !in affectedRoomSessionIds) continue
            val existing = sessionMaxDates[roomMessage.sessionID]
            if (existing == null || existing < roomMessage.createdAt) sessionMaxDates[roomMessage.sessionID] = roomMessage.createdAt
        }
        if (sessionMaxDates.isNotEmpty()) remoteNodeSessionStore.applyLastMessageDatesToRemoteNodeSessions(sessionMaxDates)
    }
}
