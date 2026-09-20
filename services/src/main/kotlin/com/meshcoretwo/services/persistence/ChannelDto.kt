// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import java.time.Instant
import java.util.UUID

/**
 * An immutable snapshot of a channel — see [ContactDto]'s doc for why this crosses the DAO/store
 * boundary instead of the Room entity. Ported from `ChannelDTO` (`Channel.swift`), trimmed to
 * the fields [ChannelEntity] carries; the six backup-import `with(...)` copy helpers on the
 * Swift DTO aren't ported here — [com.meshcoretwo.services.backup.dto.toBackupJson]/
 * `toChannelDto` cover export's JSON round-trip, but the *merge* helpers (applying a backup row's
 * metadata onto an existing local row without clobbering it) belong with the backup-import
 * sub-slice, see PLAN.md's "Backup/restore — план среза".
 */
data class ChannelDto(
    val id: UUID,
    val radioID: UUID,
    val index: UByte,
    val name: String,
    val secret: ByteArray,
    val isEnabled: Boolean,
    val lastMessageDate: Instant?,
    val unreadCount: Int,
    val unreadMentionCount: Int,
    val notificationLevel: NotificationLevel,
    val isFavorite: Boolean,
    val floodScopeModeRawValue: String,
    val regionScope: String?,
) {
    /** Type-safe accessor for the per-channel flood scope preference. */
    val floodScope: ChannelFloodScope get() = ChannelFloodScopeStorage.recompose(floodScopeModeRawValue, regionScope)

    /** Whether this is the public channel (slot 0). */
    val isPublicChannel: Boolean get() = index == 0.toUByte()

    /** Whether this channel has a non-empty secret. */
    val hasSecret: Boolean get() = secret.any { it != 0.toByte() }

    /**
     * Whether this channel uses meaningful encryption (private channels only). Public channels
     * (index 0) and hashtag channels use publicly-derivable keys.
     */
    val isEncryptedChannel: Boolean get() = !isPublicChannel && !name.startsWith("#")

    // ByteArray has reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChannelDto) return false
        return id == other.id &&
            radioID == other.radioID &&
            index == other.index &&
            name == other.name &&
            secret.contentEquals(other.secret) &&
            isEnabled == other.isEnabled &&
            lastMessageDate == other.lastMessageDate &&
            unreadCount == other.unreadCount &&
            unreadMentionCount == other.unreadMentionCount &&
            notificationLevel == other.notificationLevel &&
            isFavorite == other.isFavorite &&
            floodScopeModeRawValue == other.floodScopeModeRawValue &&
            regionScope == other.regionScope
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + radioID.hashCode()
        result = 31 * result + index.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + secret.contentHashCode()
        result = 31 * result + isEnabled.hashCode()
        result = 31 * result + (lastMessageDate?.hashCode() ?: 0)
        result = 31 * result + unreadCount
        result = 31 * result + unreadMentionCount
        result = 31 * result + notificationLevel.hashCode()
        result = 31 * result + isFavorite.hashCode()
        result = 31 * result + floodScopeModeRawValue.hashCode()
        result = 31 * result + (regionScope?.hashCode() ?: 0)
        return result
    }
}

/** Maps a persisted row to the immutable snapshot services consume. */
fun ChannelEntity.toDto(): ChannelDto = ChannelDto(
    id = id,
    radioID = radioID,
    index = index.toUByte(),
    name = name,
    secret = secret,
    isEnabled = isEnabled,
    lastMessageDate = lastMessageDate,
    unreadCount = unreadCount,
    unreadMentionCount = unreadMentionCount,
    notificationLevel = NotificationLevel.fromRawValue(notificationLevelRawValue) ?: NotificationLevel.ALL,
    isFavorite = isFavorite,
    floodScopeModeRawValue = floodScopeModeRawValue,
    regionScope = regionScope,
)

/** The reverse of [ChannelEntity.toDto] — backup import's batch-insert needs to persist an arbitrary [ChannelDto], not just one built from a [ChannelInfo]. */
fun ChannelDto.toEntity(): ChannelEntity = ChannelEntity(
    id = id,
    radioID = radioID,
    index = index.toInt(),
    name = name,
    secret = secret,
    isEnabled = isEnabled,
    lastMessageDate = lastMessageDate,
    unreadCount = unreadCount,
    unreadMentionCount = unreadMentionCount,
    notificationLevelRawValue = notificationLevel.rawValue,
    isFavorite = isFavorite,
    floodScopeModeRawValue = floodScopeModeRawValue,
    regionScope = regionScope,
)
