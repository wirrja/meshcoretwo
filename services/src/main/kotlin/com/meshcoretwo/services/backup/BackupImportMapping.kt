// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.persistence.ChannelDto
import com.meshcoretwo.services.persistence.ChannelFloodScope
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.NotificationLevel
import com.meshcoretwo.services.persistence.ReactionDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RoomMessageDto
import java.time.Instant
import java.util.UUID

/**
 * Pure (no Room) ID/index-remapping and metadata-merge helpers backup import needs before it can
 * touch the database. Ported from the "Import Mapping Helpers" and "Merge pre-existing rows from
 * backup metadata" sections of `PersistenceStore+BackupImport.swift`/`PersistenceStore+BackupHelpers.swift`.
 * The Room-dependent half of those files (`fetchExisting*`, `batchInsert*`, and the
 * `importBackupDatabase` orchestration) is a separate sub-slice — see PLAN.md's "Backup/restore —
 * план среза" — since it needs real DAO queries and a transaction, not just DTO lists.
 */

/** Slot 0 is reserved for the public channel, so relocation never assigns a non-public channel there. */
private const val FIRST_RELOCATABLE_CHANNEL_INDEX = 1

/** Result of matching backup devices to local devices by publicKey. */
data class RadioIdMapping(
    val mapping: Map<UUID, UUID>,
    val unmatchedDevices: List<DeviceDto>,
    val duplicateDeviceCount: Int,
)

/**
 * Matches each backup device to a local device by publicKey, producing a radioID remap for child
 * records. Duplicate publicKeys in the envelope (corruption) are counted so the caller can surface
 * the skip. `localDeviceRadioIdsByPublicKey` is keyed by [hexString] of the device's publicKey.
 * Ported from `buildRadioIDMapping`.
 */
fun buildRadioIdMapping(
    devices: List<DeviceDto>,
    localDeviceRadioIdsByPublicKey: Map<String, UUID>,
): RadioIdMapping {
    val mapping = mutableMapOf<UUID, UUID>()
    val unmatched = mutableListOf<DeviceDto>()
    val firstRadioIdByPublicKey = mutableMapOf<String, UUID>()
    var duplicates = 0
    for (backupDevice in devices) {
        val publicKeyHex = backupDevice.publicKey.hexString
        val firstRadioId = firstRadioIdByPublicKey[publicKeyHex]
        if (firstRadioId != null) {
            duplicates++
            // Point the duplicate's radioID at the first occurrence's local mapping so any
            // child records keyed off it resolve to a Device row that actually exists.
            if (backupDevice.radioID != firstRadioId && mapping[backupDevice.radioID] == null) {
                mapping[firstRadioId]?.let { winningLocal -> mapping[backupDevice.radioID] = winningLocal }
            }
            continue
        }
        firstRadioIdByPublicKey[publicKeyHex] = backupDevice.radioID
        val localRadioId = localDeviceRadioIdsByPublicKey[publicKeyHex]
        if (localRadioId != null) {
            mapping[backupDevice.radioID] = localRadioId
        } else {
            mapping[backupDevice.radioID] = backupDevice.radioID
            unmatched.add(backupDevice)
        }
    }
    return RadioIdMapping(mapping, unmatched, duplicates)
}

/**
 * Rewrites the `radioID` on every element of [items] through [mapping], leaving elements whose
 * current radioID isn't in the mapping untouched. Ported from `applyRadioIDMapping` — Kotlin has
 * no `WritableKeyPath`, so the caller supplies the field accessor/copier pair instead.
 */
fun <T> applyRadioIdMapping(mapping: Map<UUID, UUID>, items: List<T>, radioIdOf: (T) -> UUID, withRadioId: (T, UUID) -> T): List<T> {
    if (mapping.isEmpty()) return items
    return items.map { item ->
        val remapped = mapping[radioIdOf(item)]
        if (remapped != null) withRadioId(item, remapped) else item
    }
}

/**
 * Returns backup-contact-ID -> local-contact-ID entries for contacts that merged into an existing
 * local row (identity mappings are omitted). Ported from `buildContactIDMapping`.
 */
fun buildContactIdMapping(contacts: List<ContactDto>, contactIdsByKey: Map<String, UUID>): Map<UUID, UUID> {
    val mapping = mutableMapOf<UUID, UUID>()
    for (contact in contacts) {
        val key = BackupDedupKeys.contactKey(contact.radioID, contact.publicKey)
        val localId = contactIdsByKey[key]
        if (localId != null && localId != contact.id) {
            mapping[contact.id] = localId
        }
    }
    return mapping
}

/** Rewrites `contactID` (including the DM dedup key) on messages, and `contactID` on reactions. Ported from `applyContactIDMapping`. */
fun applyContactIdMapping(mapping: Map<UUID, UUID>, messages: List<MessageDto>, reactions: List<ReactionDto>): Pair<List<MessageDto>, List<ReactionDto>> {
    if (mapping.isEmpty()) return messages to reactions
    val remappedMessages = messages.map { message ->
        val localId = message.contactID?.let { mapping[it] } ?: return@map message
        val dedupKey = message.deduplicationKey
        message.copy(
            contactID = localId,
            deduplicationKey = if (dedupKey != null) BackupDedupKeys.rewriteDMDeduplicationKey(dedupKey, message.contactID, localId) else dedupKey,
        )
    }
    val remappedReactions = reactions.map { reaction ->
        val localId = reaction.contactID?.let { mapping[it] } ?: return@map reaction
        reaction.copy(contactID = localId)
    }
    return remappedMessages to remappedReactions
}

/**
 * Resolves each device's channel capacity to the local radioID it maps to, so the channel
 * reconciler can bound free-slot search by `maxChannels`. Ported from `maxChannelsByLocalRadioID`.
 */
fun maxChannelsByLocalRadioId(devices: List<DeviceDto>, radioMap: Map<UUID, UUID>): Map<UUID, UByte> {
    val result = mutableMapOf<UUID, UByte>()
    for (device in devices) {
        val localRadioId = radioMap[device.radioID] ?: device.radioID
        // First occurrence wins, consistent with duplicate-device handling upstream.
        result.putIfAbsent(localRadioId, device.maxChannels)
    }
    return result
}

/**
 * A channel secret carries stable cryptographic identity only when it is non-empty. An all-zero
 * secret marks the public channel or an unconfigured slot, keyed by slot index instead. Ported
 * from `channelHasStableSecret`.
 */
fun channelHasStableSecret(secret: ByteArray): Boolean = secret.isNotEmpty() && secret.any { it != 0.toByte() }

/**
 * Lowest free slot for a relocating channel: prefer the backup's own slot when free, otherwise the
 * lowest unoccupied index within `[FIRST_RELOCATABLE_CHANNEL_INDEX, maxChannels)` so slot 0 stays
 * reserved for the public channel. Returns `null` when no slot is free so the caller can drop the
 * channel rather than mis-associate it. When `maxChannels` is unavailable, the search is bounded by
 * the highest occupied slot plus one so it still terminates without inventing a capacity. Ported
 * from `resolveChannelPlacementIndex`.
 */
fun resolveChannelPlacementIndex(backupIndex: UByte, occupiedIndices: Set<UByte>, maxChannels: UByte?): UByte? {
    if (backupIndex !in occupiedIndices) return backupIndex
    val upperBound = maxChannels?.toInt() ?: ((occupiedIndices.maxOrNull()?.toInt() ?: 0) + 1)
    if (upperBound <= FIRST_RELOCATABLE_CHANNEL_INDEX) return null
    for (candidate in FIRST_RELOCATABLE_CHANNEL_INDEX until upperBound) {
        val candidateByte = candidate.toUByte()
        if (candidateByte !in occupiedIndices) return candidateByte
    }
    return null
}

/** Outcome of rewriting channel-message/reaction slots after channel reconciliation. */
data class ChannelIndexMappingResult(
    val messages: List<MessageDto>,
    val reactions: List<ReactionDto>,
    val droppedMessageCount: Int,
    val droppedReactionCount: Int,
)

/**
 * Rewrites `channelIndex` on channel messages and reactions to the slot their channel was placed
 * at locally, rewriting the content-based channel dedup key in lockstep so a second import of the
 * same backup still deduplicates. Messages/reactions belonging to a channel that had no free local
 * slot are dropped, since no placement exists to attach them to. Ported from `applyChannelIndexMapping`.
 */
fun applyChannelIndexMapping(
    remap: Map<UUID, Map<UByte, UByte>>,
    droppedChannelIndices: Map<UUID, Set<UByte>>,
    messages: List<MessageDto>,
    reactions: List<ReactionDto>,
): ChannelIndexMappingResult {
    if (remap.isEmpty() && droppedChannelIndices.isEmpty()) {
        return ChannelIndexMappingResult(messages, reactions, 0, 0)
    }

    var workingMessages = messages
    var workingReactions = reactions
    var droppedMessages = 0
    var droppedReactions = 0

    if (droppedChannelIndices.isNotEmpty()) {
        val beforeMessages = workingMessages.size
        workingMessages = workingMessages.filterNot { dto ->
            val index = dto.channelIndex ?: return@filterNot false
            droppedChannelIndices[dto.radioID]?.contains(index) ?: false
        }
        droppedMessages = beforeMessages - workingMessages.size

        val beforeReactions = workingReactions.size
        workingReactions = workingReactions.filterNot { dto ->
            val index = dto.channelIndex ?: return@filterNot false
            droppedChannelIndices[dto.radioID]?.contains(index) ?: false
        }
        droppedReactions = beforeReactions - workingReactions.size
    }

    if (remap.isNotEmpty()) {
        workingMessages = workingMessages.map { message ->
            val backupIndex = message.channelIndex ?: return@map message
            val localIndex = remap[message.radioID]?.get(backupIndex) ?: return@map message
            val dedupKey = message.deduplicationKey
            message.copy(
                channelIndex = localIndex,
                deduplicationKey = if (dedupKey != null) BackupDedupKeys.rewriteChannelDeduplicationKey(dedupKey, backupIndex, localIndex) else dedupKey,
            )
        }
        workingReactions = workingReactions.map { reaction ->
            val backupIndex = reaction.channelIndex ?: return@map reaction
            val localIndex = remap[reaction.radioID]?.get(backupIndex) ?: return@map reaction
            reaction.copy(channelIndex = localIndex)
        }
    }

    return ChannelIndexMappingResult(workingMessages, workingReactions, droppedMessages, droppedReactions)
}

/** Rewrites `messageID` on both message repeats and reactions so they reference the local (post-merge) parent. Ported from `applyMessageIDMapping`. */
fun <T> applyMessageIdMapping(mapping: Map<UUID, UUID>, items: List<T>, messageIdOf: (T) -> UUID, withMessageId: (T, UUID) -> T): List<T> {
    if (mapping.isEmpty()) return items
    return items.map { item ->
        val localId = mapping[messageIdOf(item)]
        if (localId != null) withMessageId(item, localId) else item
    }
}

/** Returns backup-session-ID -> local-session-ID entries for sessions that merged into an existing local row. Ported from `buildSessionIDMapping`. */
fun buildSessionIdMapping(sessions: List<RemoteNodeSessionDto>, sessionIdsByKey: Map<String, UUID>): Map<UUID, UUID> {
    val mapping = mutableMapOf<UUID, UUID>()
    for (session in sessions) {
        val key = BackupDedupKeys.remoteNodeSessionKey(session.radioID, session.publicKey)
        val localId = sessionIdsByKey[key]
        if (localId != null && localId != session.id) {
            mapping[session.id] = localId
        }
    }
    return mapping
}

/** Rewrites `sessionID` on room messages so they attach to the local (post-merge) session row. Ported from `applySessionIDMapping`. */
fun applySessionIdMapping(mapping: Map<UUID, UUID>, roomMessages: List<RoomMessageDto>): List<RoomMessageDto> {
    if (mapping.isEmpty()) return roomMessages
    return roomMessages.map { roomMessage ->
        val localId = mapping[roomMessage.sessionID]
        if (localId != null) roomMessage.copy(sessionID = localId) else roomMessage
    }
}

/**
 * Merges backup metadata into an already-existing local contact (matched by `(radioID, publicKey)`).
 * Never un-blocks, un-mutes, or un-favorites; dates/counts take the max; `null` local fields adopt
 * the backup value. Returns the merged row and whether anything changed. Ported from
 * `mergeBackupMetadata(into:from:)` for `Contact`.
 */
fun mergeContactBackupMetadata(existing: ContactDto, from: ContactDto, now: Instant = Instant.now()): Pair<ContactDto, Boolean> {
    var merged = existing
    var changed = false
    if (merged.nickname == null && from.nickname != null) {
        merged = merged.copy(nickname = from.nickname)
        changed = true
    }
    if (from.isBlocked && !merged.isBlocked) {
        merged = merged.copy(isBlocked = true)
        changed = true
    }
    if (from.isMuted && !merged.isMuted) {
        merged = merged.copy(isMuted = true)
        changed = true
    }
    if (from.isFavorite && !merged.isFavorite) {
        merged = merged.copy(isFavorite = true)
        changed = true
    }
    from.lastMessageDate?.let { backupDate ->
        if (merged.lastMessageDate == null || merged.lastMessageDate!! < backupDate) {
            merged = merged.copy(lastMessageDate = backupDate)
            changed = true
        }
    }
    val mergedUnread = maxOf(merged.unreadCount, from.unreadCount)
    if (merged.unreadCount != mergedUnread) {
        merged = merged.copy(unreadCount = mergedUnread)
        changed = true
    }
    val mergedMention = maxOf(merged.unreadMentionCount, from.unreadMentionCount)
    if (merged.unreadMentionCount != mergedMention) {
        merged = merged.copy(unreadMentionCount = mergedMention)
        changed = true
    }
    if (merged.ocvPreset == null && from.ocvPreset != null) {
        merged = merged.copy(ocvPreset = from.ocvPreset)
        changed = true
    }
    if (merged.customOCVArrayString == null && from.customOCVArrayString != null) {
        merged = merged.copy(customOCVArrayString = from.customOCVArrayString)
        changed = true
    }
    if (merged.avatarImageData == null && from.avatarImageData != null) {
        merged = merged.copy(avatarImageData = from.avatarImageData)
        changed = true
    }
    val clampedHeard = clampedPhoneClockTimestamp(from.lastHeardTimestamp, now)
    if (clampedHeard > merged.lastHeardTimestamp) {
        merged = merged.copy(lastHeardTimestamp = clampedHeard)
        changed = true
    }
    return merged to changed
}

/**
 * Upper-bounds a phone-clock second stamp to `now + TIMESTAMP_TOLERANCE_FUTURE_SECONDS`, so a
 * future exporting clock can't pin sort/prune under max-wins. Ported from
 * `clampedPhoneClockTimestamp`/`clampedBackupLastHeardTimestamp` (the Kotlin `ContactDto.lastHeardTimestamp`
 * field is non-optional, so the Swift `nil -> 0` branch doesn't apply here).
 */
fun clampedPhoneClockTimestamp(stamp: UInt, now: Instant = Instant.now()): UInt {
    val nowSeconds = now.epochSecond.toUInt()
    val tolerance = TIMESTAMP_TOLERANCE_FUTURE_SECONDS
    val upperBound = if (nowSeconds > UInt.MAX_VALUE - tolerance) UInt.MAX_VALUE else nowSeconds + tolerance
    return minOf(stamp, upperBound)
}

private val TIMESTAMP_TOLERANCE_FUTURE_SECONDS = (5u * 60u)

/**
 * Merges backup metadata into an already-existing local channel (matched by stable secret or, for
 * secret-less slots, by index). Adopts the backup notification level/flood scope only while the
 * local row is still at its default. Ported from `mergeBackupMetadata(into:from:)` for `Channel`.
 */
fun mergeChannelBackupMetadata(existing: ChannelDto, from: ChannelDto): Pair<ChannelDto, Boolean> {
    var merged = existing
    var changed = false
    from.lastMessageDate?.let { backupDate ->
        if (merged.lastMessageDate == null || merged.lastMessageDate!! < backupDate) {
            merged = merged.copy(lastMessageDate = backupDate)
            changed = true
        }
    }
    val mergedUnread = maxOf(merged.unreadCount, from.unreadCount)
    if (merged.unreadCount != mergedUnread) {
        merged = merged.copy(unreadCount = mergedUnread)
        changed = true
    }
    val mergedMention = maxOf(merged.unreadMentionCount, from.unreadMentionCount)
    if (merged.unreadMentionCount != mergedMention) {
        merged = merged.copy(unreadMentionCount = mergedMention)
        changed = true
    }
    if (merged.notificationLevel == NotificationLevel.ALL && from.notificationLevel != NotificationLevel.ALL) {
        merged = merged.copy(notificationLevel = from.notificationLevel)
        changed = true
    }
    if (from.isFavorite && !merged.isFavorite) {
        merged = merged.copy(isFavorite = true)
        changed = true
    }
    if (merged.floodScope is ChannelFloodScope.Inherit && from.floodScope !is ChannelFloodScope.Inherit) {
        merged = merged.copy(floodScopeModeRawValue = from.floodScopeModeRawValue, regionScope = from.regionScope)
        changed = true
    }
    return merged to changed
}

/**
 * Merges backup metadata into an already-existing local remote-node session (matched by
 * `(radioID, publicKey)`). Ported from `mergeBackupMetadata(into:from:)` for `RemoteNodeSession`.
 */
fun mergeRemoteNodeSessionBackupMetadata(existing: RemoteNodeSessionDto, from: RemoteNodeSessionDto): Pair<RemoteNodeSessionDto, Boolean> {
    var merged = existing
    var changed = false
    val mergedUnread = maxOf(merged.unreadCount, from.unreadCount)
    if (merged.unreadCount != mergedUnread) {
        merged = merged.copy(unreadCount = mergedUnread)
        changed = true
    }
    if (merged.notificationLevel == NotificationLevel.ALL && from.notificationLevel != NotificationLevel.ALL) {
        merged = merged.copy(notificationLevel = from.notificationLevel)
        changed = true
    }
    if (from.isFavorite && !merged.isFavorite) {
        merged = merged.copy(isFavorite = true)
        changed = true
    }
    val mergedSync = maxOf(merged.lastSyncTimestamp, from.lastSyncTimestamp)
    if (merged.lastSyncTimestamp != mergedSync) {
        merged = merged.copy(lastSyncTimestamp = mergedSync)
        changed = true
    }
    from.lastMessageDate?.let { backupDate ->
        if (merged.lastMessageDate == null || merged.lastMessageDate!! < backupDate) {
            merged = merged.copy(lastMessageDate = backupDate)
            changed = true
        }
    }
    return merged to changed
}
