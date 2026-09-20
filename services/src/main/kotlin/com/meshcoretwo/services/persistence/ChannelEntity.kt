// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.meshcoretwo.protocol.ChannelInfo
import java.time.Instant
import java.util.UUID

/**
 * A broadcast-messaging channel (group). Ported from `Channel.swift`'s `@Model` (SwiftData) to a
 * Room `@Entity`. Drops `legacyIsMuted` — a Swift V1-schema migration field with nothing to
 * migrate from on a from-scratch Android schema.
 *
 * [index] is [Int] rather than the wire's [UByte] and [notificationLevelRawValue] is the same
 * lossless-signed-widening pattern used throughout this module's entities (see
 * [ContactEntity]'s class doc) — Room's KSP processor cannot handle Kotlin unsigned types as
 * column types. [ChannelFloodScope] is the one sum type on this model, and it is already
 * decomposed into two plain scalar columns ([floodScopeModeRawValue]/[regionScope]) by
 * [ChannelFloodScopeStorage], so it needs no `TypeConverter` of its own.
 */
@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["radioID"]),
        Index(value = ["radioID", "index"], unique = true),
    ],
)
data class ChannelEntity(
    @PrimaryKey val id: UUID,
    /** The device this channel belongs to — partition key, not the volatile BLE address. */
    val radioID: UUID,
    val index: Int,
    val name: String,
    val secret: ByteArray,
    val isEnabled: Boolean,
    val lastMessageDate: Instant?,
    val unreadCount: Int,
    val unreadMentionCount: Int,
    val notificationLevelRawValue: Int,
    val isFavorite: Boolean,
    val floodScopeModeRawValue: String,
    val regionScope: String?,
) {
    companion object {
        /**
         * Builds the row for a brand-new channel from a freshly-read wire record, matching
         * `Channel.init(radioID:from: ChannelInfo)`. App-only metadata (unread counts,
         * notification level, favorite, flood scope) starts at its default.
         */
        fun fromChannelInfo(id: UUID, radioID: UUID, info: ChannelInfo): ChannelEntity = ChannelEntity(
            id = id,
            radioID = radioID,
            index = info.index.toInt(),
            name = info.name,
            secret = info.secret,
            isEnabled = true,
            lastMessageDate = null,
            unreadCount = 0,
            unreadMentionCount = 0,
            notificationLevelRawValue = NotificationLevel.ALL.rawValue,
            isFavorite = false,
            floodScopeModeRawValue = ChannelFloodScopeStorage.Mode.INHERIT.rawValue,
            regionScope = null,
        )
    }

    /**
     * Applies a freshly-read wire record onto this existing row, matching
     * `Channel.update(from: ChannelInfo)`: only [name]/[secret] refresh. App-only metadata is
     * untouched, so a device sync can never clobber locally-set state.
     */
    fun updatedFrom(info: ChannelInfo): ChannelEntity = copy(name = info.name, secret = info.secret)
}
