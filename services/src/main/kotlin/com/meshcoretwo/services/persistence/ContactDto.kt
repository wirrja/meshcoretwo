// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.protocol.decodePathLen
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.RepeaterResolvable
import com.meshcoretwo.services.remotenode.OCVPreset
import java.time.Instant
import java.util.UUID

/**
 * An immutable, [ContactEntity]-independent snapshot of a contact — the shape services and UI
 * consume. Ported from `ContactDTO` (`Contact.swift`). Room entities stay behind the DAO/store
 * boundary; this is what crosses it, matching PLAN.md's Phase 4 guidance ("Room-сущность живёт в
 * DAO/репозитории, наружу отдаём неизменяемые data class").
 *
 * Conforms to [RepeaterResolvable] ([recencyValue] = [lastModified], [resolvableName] =
 * [displayName]) so `app`'s `RepeaterResolver`/Trace Path hop picker can resolve a hash prefix to
 * a contact without a separate adapter type.
 */
data class ContactDto(
    val id: UUID,
    val radioID: UUID,
    override val publicKey: ByteArray,
    val name: String,
    val typeRawValue: UByte,
    val flags: UByte,
    val outPathLength: UByte,
    val outPath: ByteArray,
    override val lastAdvertTimestamp: UInt,
    override val latitude: Double,
    override val longitude: Double,
    val lastModified: UInt,
    val lastHeardTimestamp: UInt,
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
) : RepeaterResolvable {
    val type: ContactType get() = ContactType.fromValue(typeRawValue) ?: ContactType.CHAT

    val displayName: String get() = nickname ?: name

    override val resolvableName: String get() = displayName

    override val recencyValue: UInt get() = lastModified

    /**
     * Max of [lastModified] and [lastHeardTimestamp] — the effective "last seen" instant used for
     * stale-node pruning (unlike [recencyValue]/`RepeaterResolvable`, which stays [lastModified]-only
     * for hop-name resolution). Ported from `Contact.recencyTimestamp`.
     */
    val recencyTimestamp: UInt get() = maxOf(lastModified, lastHeardTimestamp)

    /**
     * Whether this contact matches [com.meshcoretwo.services.connection.removeStaleNodes]'s cutoff
     * (epoch seconds) — favorites never match. Ported from `Contact.matchesStaleNodePrune`.
     */
    fun matchesStaleNodePrune(cutoffEpochSeconds: UInt): Boolean = !isFavorite && recencyTimestamp < cutoffEpochSeconds

    val publicKeyPrefix: ByteArray get() = publicKey.copyOfRange(0, minOf(6, publicKey.size))

    val isFloodRouted: Boolean get() = outPathLength == PacketBuilder.FLOOD_PATH_SENTINEL

    val pathHashSize: Int get() = decodePathLen(outPathLength)?.hashSize ?: 1

    val pathHopCount: Int get() = decodePathLen(outPathLength)?.hopCount ?: 0

    val pathByteLength: Int get() = decodePathLen(outPathLength)?.byteLength ?: 0

    /**
     * Each hop as its raw hash bytes plus uppercase hex, e.g. `[(0xA3, "A3"), (0x7F, "7F")]`. Ported
     * from `Contact.pathHops` (`Data.pathHops(hashSize:)`). The raw bytes are needed to match a hop
     * against a repeater's public-key prefix ([com.meshcoretwo.services.rendering], via
     * `NeighborNameResolver.resolvePath` in the `app` module).
     */
    val pathHops: List<ContactPathHop>
        get() {
            if (pathHashSize <= 0) return emptyList()
            val bytes = outPath.copyOfRange(0, minOf(pathByteLength, outPath.size))
            return bytes.toList().chunked(pathHashSize).map { chunk ->
                val chunkBytes = chunk.toByteArray()
                ContactPathHop(data = chunkBytes, hex = chunkBytes.hexString.uppercase())
            }
        }

    /** Each hop's hash as a hex string, e.g. `["A3", "7F", "42"]`. */
    val pathNodesHex: List<String> get() = pathHops.map { it.hex }

    /** Human-readable path string with arrow separators, e.g. `"A3 → 7F → 42"`. */
    val pathString: String get() = pathNodesHex.joinToString(" → ")

    override val hasLocation: Boolean
        get() {
            if (latitude == 0.0 && longitude == 0.0) return false
            return latitude in -90.0..90.0 && longitude in -180.0..180.0
        }

    /**
     * The active OCV array for this contact (preset or custom). Ported from
     * `Contact.activeOCVArray` (`Contact.swift`).
     */
    val activeOCVArray: List<Int>
        get() {
            if (ocvPreset == OCVPreset.CUSTOM.rawValue && customOCVArrayString != null) {
                val parsed = customOCVArrayString.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (parsed.size == 11) return parsed
            }

            ocvPreset?.let { OCVPreset.fromRawValue(it)?.let { preset -> return preset.ocvArray } }

            return OCVPreset.LI_ION.ocvArray
        }

    // ByteArray has reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactDto) return false
        return id == other.id &&
            radioID == other.radioID &&
            publicKey.contentEquals(other.publicKey) &&
            name == other.name &&
            typeRawValue == other.typeRawValue &&
            flags == other.flags &&
            outPathLength == other.outPathLength &&
            outPath.contentEquals(other.outPath) &&
            lastAdvertTimestamp == other.lastAdvertTimestamp &&
            latitude == other.latitude &&
            longitude == other.longitude &&
            lastModified == other.lastModified &&
            lastHeardTimestamp == other.lastHeardTimestamp &&
            nickname == other.nickname &&
            isBlocked == other.isBlocked &&
            isMuted == other.isMuted &&
            isFavorite == other.isFavorite &&
            lastMessageDate == other.lastMessageDate &&
            unreadCount == other.unreadCount &&
            unreadMentionCount == other.unreadMentionCount &&
            ocvPreset == other.ocvPreset &&
            customOCVArrayString == other.customOCVArrayString &&
            (avatarImageData?.contentEquals(other.avatarImageData ?: ByteArray(0)) ?: (other.avatarImageData == null))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + radioID.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + typeRawValue.hashCode()
        result = 31 * result + flags.hashCode()
        result = 31 * result + outPathLength.hashCode()
        result = 31 * result + outPath.contentHashCode()
        result = 31 * result + lastAdvertTimestamp.hashCode()
        result = 31 * result + latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        result = 31 * result + lastModified.hashCode()
        result = 31 * result + lastHeardTimestamp.hashCode()
        result = 31 * result + (nickname?.hashCode() ?: 0)
        result = 31 * result + isBlocked.hashCode()
        result = 31 * result + isMuted.hashCode()
        result = 31 * result + isFavorite.hashCode()
        result = 31 * result + (lastMessageDate?.hashCode() ?: 0)
        result = 31 * result + unreadCount
        result = 31 * result + unreadMentionCount
        result = 31 * result + (ocvPreset?.hashCode() ?: 0)
        result = 31 * result + (customOCVArrayString?.hashCode() ?: 0)
        result = 31 * result + (avatarImageData?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * A single path hop's raw hash bytes plus its uppercase hex rendering. Ported from the tuple
 * element type of Swift's `Contact.pathHops: [(data: Data, hex: String)]` — Kotlin has no named-tuple
 * equivalent, so this is a small standalone type instead.
 */
data class ContactPathHop(val data: ByteArray, val hex: String) {
    // ByteArray has reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactPathHop) return false
        return data.contentEquals(other.data) && hex == other.hex
    }

    override fun hashCode(): Int = 31 * data.contentHashCode() + hex.hashCode()
}

/**
 * Maps a persisted row to the immutable snapshot services consume, narrowing the entity's
 * lossless-signed-widened columns back to the wire protocol's unsigned types (see
 * [ContactEntity]'s class doc for why the entity itself can't store these directly).
 */
fun ContactEntity.toDto(): ContactDto = ContactDto(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    name = name,
    typeRawValue = typeRawValue.toUByte(),
    flags = flags.toUByte(),
    outPathLength = outPathLength.toUByte(),
    outPath = outPath,
    lastAdvertTimestamp = lastAdvertTimestamp.toUInt(),
    latitude = latitude,
    longitude = longitude,
    lastModified = lastModified.toUInt(),
    lastHeardTimestamp = lastHeardTimestamp.toUInt(),
    nickname = nickname,
    isBlocked = isBlocked,
    isMuted = isMuted,
    isFavorite = isFavorite,
    lastMessageDate = lastMessageDate,
    unreadCount = unreadCount,
    unreadMentionCount = unreadMentionCount,
    ocvPreset = ocvPreset,
    customOCVArrayString = customOCVArrayString,
    avatarImageData = avatarImageData,
)

/**
 * A [MeshContact] snapshot of this contact's stored fields, path preserved verbatim (unlike
 * [toFloodedMeshContact], which resets it). Used to refresh a Discover row from a Contact row
 * without a radio round-trip, matching `reconcile()`'s `ContactFrame` reconstruction
 * (`AdvertisementService+DeltaSync.swift`).
 */
fun ContactDto.toMeshContact(): MeshContact = MeshContact(
    id = publicKey.hexString,
    publicKey = publicKey,
    type = type,
    typeRawValue = typeRawValue,
    flags = ContactFlags(flags),
    outPathLength = outPathLength,
    outPath = outPath,
    advertisedName = name,
    lastAdvertisement = Instant.ofEpochSecond(lastAdvertTimestamp.toLong()),
    latitude = latitude,
    longitude = longitude,
    lastModified = Instant.ofEpochSecond(lastModified.toLong()),
)

/**
 * A [MeshContact] for this contact with the path reset to flood routing. Used to push a contact
 * back to a radio that reports it missing (`RemoteNodeService`'s login auto-heal): a contact the
 * radio doesn't know has no valid stored path, so flooding is the only route that can reach it,
 * and the firmware rediscovers the direct path from the first response. Also used to mirror a
 * radio-side path reset locally (`ContactService.resetPath`). Ported from
 * `Contact.floodedContactFrame(asOf:)` + `ContactFrame.toMeshContact()` (`Contact.swift`/
 * `ContactService.swift`) collapsed into one step — Kotlin has no need for Swift's intermediate
 * `ContactFrame` wire-shape struct between a persisted contact and the session-facing type.
 */
fun ContactDto.toFloodedMeshContact(asOf: Instant): MeshContact = MeshContact(
    id = publicKey.hexString,
    publicKey = publicKey,
    type = type,
    typeRawValue = typeRawValue,
    flags = ContactFlags(flags),
    outPathLength = PacketBuilder.FLOOD_PATH_SENTINEL,
    outPath = ByteArray(0),
    advertisedName = name,
    lastAdvertisement = Instant.ofEpochSecond(lastAdvertTimestamp.toLong()),
    latitude = latitude,
    longitude = longitude,
    lastModified = asOf,
)

/** The reverse of [ContactEntity.toDto] — backup import's batch-insert needs to persist an arbitrary [ContactDto], not just one built from a [MeshContact]. */
fun ContactDto.toEntity(): ContactEntity = ContactEntity(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    name = name,
    typeRawValue = typeRawValue.toInt(),
    flags = flags.toInt(),
    outPathLength = outPathLength.toInt(),
    outPath = outPath,
    lastAdvertTimestamp = lastAdvertTimestamp.toLong(),
    latitude = latitude,
    longitude = longitude,
    lastModified = lastModified.toLong(),
    lastHeardTimestamp = lastHeardTimestamp.toLong(),
    nickname = nickname,
    isBlocked = isBlocked,
    isMuted = isMuted,
    isFavorite = isFavorite,
    lastMessageDate = lastMessageDate,
    unreadCount = unreadCount,
    unreadMentionCount = unreadMentionCount,
    ocvPreset = ocvPreset,
    customOCVArrayString = customOCVArrayString,
    avatarImageData = avatarImageData,
)
