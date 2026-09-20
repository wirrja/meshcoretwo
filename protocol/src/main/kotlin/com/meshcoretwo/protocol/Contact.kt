// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.time.Instant

/**
 * Represents a contact stored on the MeshCore device.
 *
 * `MeshContact` defines a node in the mesh network that your device has discovered or stored.
 * Contacts are typically discovered through advertisements and are used as message destinations.
 *
 * ## Identity
 * Each contact has a unique 32-byte public key. [id] is the hex string representation.
 *
 * ## Routing
 * [outPath] and [outPathLength] describe the routing path to reach this contact. A path
 * length of `0xFF` indicates flood routing (broadcast to all).
 *
 * The [outPathLength] byte uses bit packing to encode both the hash size and hop count:
 * - Upper 2 bits (6-7): hash size mode (0=1-byte, 1=2-byte, 2=3-byte, 3=reserved)
 * - Lower 6 bits (0-5): hop count (0-63)
 *
 * Use [pathHashSize], [pathHopCount], and [pathByteLength] to decode these fields.
 *
 * ## Location
 * If the contact shares its location, [latitude] and [longitude] contain GPS coordinates.
 */
class MeshContact(
    /** The unique identifier for the contact, represented as a hex string of the public key. */
    val id: String,
    /** The contact's 32-byte public key. */
    val publicKey: ByteArray,
    /** The type identifier for the contact. */
    val type: ContactType,
    /**
     * The raw 1-byte type value as it appears on the wire.
     *
     * Normally equal to `type.value`. Preserved separately so a contact carrying a type byte
     * not yet modeled by [ContactType] (e.g. from newer firmware or an imported config)
     * survives instead of being coerced to [ContactType.CHAT]. The byte round-trips through
     * OTA decode, the local cache, config export, and the config-import / contact-add device
     * write ([PacketBuilder.updateContact]).
     */
    val typeRawValue: UByte = type.value,
    /** The operational flags for the contact. */
    val flags: ContactFlags,
    /**
     * The encoded outbound path length byte.
     *
     * Uses bit packing: upper 2 bits = hash size mode, lower 6 bits = hop count.
     * A value of `0xFF` indicates flood routing (unknown path).
     */
    val outPathLength: UByte,
    /** The outbound routing path data. */
    val outPath: ByteArray,
    /** The name this contact advertises on the network. */
    val advertisedName: String,
    /** The date and time when this contact last sent an advertisement. */
    val lastAdvertisement: Instant,
    /** The latitude coordinate of the contact, if location sharing is enabled. */
    val latitude: Double,
    /** The longitude coordinate of the contact, if location sharing is enabled. */
    val longitude: Double,
    /** The date and time when this contact record was last modified. */
    val lastModified: Instant,
) {
    /** The first 6 bytes of the public key as a hex string. */
    val publicKeyPrefix: String
        get() = publicKey.copyOfRange(0, minOf(6, publicKey.size)).hexString

    /** Whether this contact uses flood (broadcast) routing, represented by `0xFF` on the wire. */
    val isFloodPath: Boolean
        get() = outPathLength == 0xFFu.toUByte()

    /** The hash size per hop in bytes (1, 2, or 3). Only meaningful when [isFloodPath] is `false`. */
    val pathHashSize: Int
        get() = decodePathLen(outPathLength)?.hashSize ?: 1

    /** The number of hops in the path. Only meaningful when [isFloodPath] is `false`. */
    val pathHopCount: Int
        get() = decodePathLen(outPathLength)?.hopCount ?: 0

    /** The total byte length of the path data. Only meaningful when [isFloodPath] is `false`. */
    val pathByteLength: Int
        get() = decodePathLen(outPathLength)?.byteLength ?: 0

    // ByteArray has reference equality under ==, so publicKey/outPath need contentEquals here.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshContact) return false
        return id == other.id &&
            publicKey.contentEquals(other.publicKey) &&
            type == other.type &&
            typeRawValue == other.typeRawValue &&
            flags == other.flags &&
            outPathLength == other.outPathLength &&
            outPath.contentEquals(other.outPath) &&
            advertisedName == other.advertisedName &&
            lastAdvertisement == other.lastAdvertisement &&
            latitude == other.latitude &&
            longitude == other.longitude &&
            lastModified == other.lastModified
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + typeRawValue.hashCode()
        result = 31 * result + flags.hashCode()
        result = 31 * result + outPathLength.hashCode()
        result = 31 * result + outPath.contentHashCode()
        result = 31 * result + advertisedName.hashCode()
        result = 31 * result + lastAdvertisement.hashCode()
        result = 31 * result + latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        result = 31 * result + lastModified.hashCode()
        return result
    }
}
