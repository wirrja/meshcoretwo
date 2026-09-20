// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.meshcoretwo.protocol.MeshContact
import java.time.Instant
import java.util.UUID

/**
 * A contact discovered on the mesh network, persisted per-device and synced from the device's
 * contact table. Ported from `Contact.swift`'s `@Model` (SwiftData) to a Room `@Entity`.
 *
 * Drops Swift's `outPathLength: Int` widening ("so leftover Int8 flood sentinels (-1) survive
 * SwiftData fetch") — that accommodates rows written under an older on-disk schema this
 * from-scratch Android schema has no equivalent of.
 *
 * [typeRawValue]/[flags]/[outPathLength] are [Int] and [lastAdvertTimestamp]/[lastModified]/
 * [lastHeardTimestamp] are [Long] rather than the [UByte]/[UInt] the wire protocol and
 * [ContactDto] actually use — Room's KSP processor cannot handle Kotlin's unsigned (inline/value
 * class) types as column types (see [Converters]'s doc), so entities store the lossless signed
 * widening and convert at the [ContactDto] boundary instead.
 */
@Entity(
    tableName = "contacts",
    indices = [
        Index(value = ["radioID"]),
        Index(value = ["radioID", "publicKey"], unique = true),
    ],
)
data class ContactEntity(
    @PrimaryKey val id: UUID,
    /** The device this contact belongs to — partition key, not the volatile BLE address. */
    val radioID: UUID,
    val publicKey: ByteArray,
    val name: String,
    val typeRawValue: Int,
    val flags: Int,
    val outPathLength: Int,
    val outPath: ByteArray,
    val lastAdvertTimestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val lastModified: Long,
    /** Phone-clock epoch seconds of the last mesh liveness evidence heard for this contact; 0 = never. */
    val lastHeardTimestamp: Long,
    val nickname: String?,
    val isBlocked: Boolean,
    val isMuted: Boolean,
    val isFavorite: Boolean,
    val lastMessageDate: Instant?,
    val unreadCount: Int,
    val unreadMentionCount: Int,
    val ocvPreset: String?,
    val customOCVArrayString: String?,
    val avatarImageData: ByteArray?,
) {
    companion object {
        /**
         * Builds the row for a brand-new contact from a freshly-received wire record, matching
         * `Contact.init(radioID:from: ContactFrame)`. All app-only metadata (nickname, blocked,
         * muted, unread counts, avatar, OCV) starts at its default; [isFavorite] seeds from the
         * frame's flag bit 0 since this contact has no prior local state to preserve.
         */
        fun fromMeshContact(id: UUID, radioID: UUID, contact: MeshContact): ContactEntity =
            ContactEntity(
                id = id,
                radioID = radioID,
                publicKey = contact.publicKey,
                name = contact.advertisedName,
                typeRawValue = contact.typeRawValue.toInt(),
                flags = contact.flags.rawValue.toInt(),
                outPathLength = contact.outPathLength.toInt(),
                outPath = contact.outPath,
                lastAdvertTimestamp = contact.lastAdvertisement.epochSecond,
                latitude = contact.latitude,
                longitude = contact.longitude,
                lastModified = contact.lastModified.epochSecond,
                lastHeardTimestamp = 0L,
                nickname = null,
                isBlocked = false,
                isMuted = false,
                isFavorite = (contact.flags.rawValue.toInt() and 0x01) != 0,
                lastMessageDate = null,
                unreadCount = 0,
                unreadMentionCount = 0,
                ocvPreset = null,
                customOCVArrayString = null,
                avatarImageData = null,
            )
    }

    /**
     * Applies a freshly-received wire record onto this existing row, matching
     * `Contact.update(from: ContactFrame)`: only wire-level fields refresh. App-only metadata —
     * nickname, blocked/muted, favorite, unread counts, avatar, OCV — is untouched, so a device
     * sync can never clobber locally-set state. [isFavorite] itself is preserved as-is (only a
     * dedicated toggle changes it); its bit is merged back into the stored [flags] byte so a
     * later re-send to the device still carries this phone's favorite bit.
     */
    fun updatedFrom(contact: MeshContact): ContactEntity {
        val preservedFavoriteBit = flags and 0x01
        val incomingBitsWithoutFavorite = contact.flags.rawValue.toInt() and 0x01.inv()
        return copy(
            name = contact.advertisedName,
            typeRawValue = contact.typeRawValue.toInt(),
            flags = preservedFavoriteBit or incomingBitsWithoutFavorite,
            outPathLength = contact.outPathLength.toInt(),
            outPath = contact.outPath,
            lastAdvertTimestamp = contact.lastAdvertisement.epochSecond,
            latitude = contact.latitude,
            longitude = contact.longitude,
            lastModified = contact.lastModified.epochSecond,
        )
    }
}
