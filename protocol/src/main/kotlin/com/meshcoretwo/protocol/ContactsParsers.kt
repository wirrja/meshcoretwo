// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.time.Instant

// Ported from Parsers+Contacts.swift.

/**
 * Parses a 147-byte contact structure into a [MeshContact].
 *
 * ### Binary Format
 * (Per Python reader.py)
 * - Offset 0 (32 bytes): Public Key
 * - Offset 32 (1 byte): Contact Type
 * - Offset 33 (1 byte): Flags
 * - Offset 34 (1 byte): Path Length (encoded: upper 2 bits = hash mode, lower 6 bits = hop count; 0xFF = flood)
 * - Offset 35 (64 bytes): Routing Path
 * - Offset 99 (32 bytes): Advertised Name (UTF-8, padded)
 * - Offset 131 (4 bytes): Last Advertisement Time (UInt32 LE)
 * - Offset 135 (4 bytes): Latitude scaled by 1e6 (Int32 LE)
 * - Offset 139 (4 bytes): Longitude scaled by 1e6 (Int32 LE)
 * - Offset 143 (4 bytes): Last Modified Time (UInt32 LE)
 */
internal fun parseContactData(data: ByteArray): MeshContact? {
    if (data.size < PacketSize.CONTACT) return null

    var offset = 0
    val publicKey = data.copyOfRange(offset, offset + 32); offset += 32
    val typeByte = data[offset].toUByte()
    val type = ContactType.fromValue(typeByte) ?: ContactType.CHAT; offset += 1
    val flags = ContactFlags(data[offset].toUByte()); offset += 1
    val pathLen = data[offset].toUByte(); offset += 1
    if (pathLen != 0xFFu.toUByte() && decodePathLen(pathLen) == null) return null
    val actualPathLen = if (pathLen == 0xFFu.toUByte()) 0 else (decodePathLen(pathLen)?.byteLength ?: 0)
    // Read full 64-byte path field, but only use first actualPathLen bytes
    val pathBytes = data.copyOfRange(offset, offset + 64)
    val path = if (actualPathLen > 0) pathBytes.prefixBytes(actualPathLen) else ByteArray(0)
    offset += 64
    val nameField = data.copyOfRange(offset, offset + 32)
    // Null-terminated C-string in a fixed 32-byte field; firmware truncates byte-wise, so trim
    // at the first null and decode the longest UTF-8-valid prefix.
    val nullIndex = nameField.indexOf(0).let { if (it < 0) nameField.size else it }
    val name = nameField.copyOfRange(0, nullIndex).decodingLongestValidUtf8Prefix().trimControlCharacters()
    offset += 32
    val lastAdvert = Instant.ofEpochSecond(data.readUInt32LE(offset).toLong()); offset += 4
    val lat = data.readInt32LE(offset) / 1_000_000.0; offset += 4
    val lon = data.readInt32LE(offset) / 1_000_000.0; offset += 4
    val lastMod = Instant.ofEpochSecond(data.readUInt32LE(offset).toLong())

    return MeshContact(
        id = publicKey.hexString,
        publicKey = publicKey,
        type = type,
        typeRawValue = typeByte,
        flags = flags,
        outPathLength = pathLen,
        outPath = path,
        advertisedName = name,
        lastAdvertisement = lastAdvert,
        latitude = lat,
        longitude = lon,
        lastModified = lastMod,
    )
}

/** Parser for mesh contact structures. */
object ContactParser {
    /** Parses a 147-byte contact structure. */
    fun parse(data: ByteArray): MeshEvent {
        if (data.size >= PacketSize.CONTACT) {
            val pathLen = data[34].toUByte()
            if (pathLen != 0xFFu.toUByte() && decodePathLen(pathLen) == null) {
                return MeshEvent.ParseFailure(
                    data,
                    "Contact response uses reserved path length encoding: 0x${"%02X".format(pathLen.toInt())}",
                )
            }
        }
        val contact = parseContactData(data)
            ?: return MeshEvent.ParseFailure(data, "Contact response too short: ${data.size} < ${PacketSize.CONTACT}")
        return MeshEvent.Contact(contact)
    }
}

/** Parser for node advertisement (beacon) data. */
object AdvertisementParser {
    /** Parses a 32-byte public key advertisement. */
    fun parse(data: ByteArray): MeshEvent {
        if (data.size < PacketBuilder.PUBLIC_KEY_SIZE) {
            return MeshEvent.ParseFailure(data, "Advertisement too short: ${data.size} < ${PacketBuilder.PUBLIC_KEY_SIZE}")
        }
        val publicKey = data.prefixBytes(PacketBuilder.PUBLIC_KEY_SIZE)
        return MeshEvent.Advertisement(publicKey)
    }
}

/** Parser for advertisements from previously unknown nodes (manual-add mode). */
object NewAdvertisementParser {
    /**
     * Parses a new node advertisement and returns [MeshEvent.NewContact].
     *
     * This is sent by the device when `manualAddContacts` is enabled and a new advertisement is
     * received. Unlike [MeshEvent.Advertisement] (which only contains a public key prefix),
     * this contains full contact data.
     */
    fun parse(data: ByteArray): MeshEvent {
        val contact = parseContactData(data)
        if (contact != null) return MeshEvent.NewContact(contact)
        if (data.size >= PacketBuilder.PUBLIC_KEY_SIZE) {
            // Fallback: insufficient data for full contact, but we have public key
            return MeshEvent.ParseFailure(
                data,
                "NewAdvertisement has public key but insufficient contact data: ${data.size} < ${PacketSize.CONTACT}",
            )
        }
        return MeshEvent.ParseFailure(data, "NewAdvertisement too short: ${data.size}")
    }
}

/** Parser for routing path update notifications. */
object PathUpdateParser {
    /** Parses a 32-byte public key path update. */
    fun parse(data: ByteArray): MeshEvent {
        if (data.size < PacketBuilder.PUBLIC_KEY_SIZE) {
            return MeshEvent.ParseFailure(data, "PathUpdate too short: ${data.size} < ${PacketBuilder.PUBLIC_KEY_SIZE}")
        }
        val publicKey = data.prefixBytes(PacketBuilder.PUBLIC_KEY_SIZE)
        return MeshEvent.PathUpdate(publicKey)
    }
}

/** Parser for contact deletion notifications. */
object ContactDeletedParser {
    /** Parses a contact deletion notification containing the 32-byte public key. */
    fun parse(data: ByteArray): MeshEvent {
        if (data.size < PacketSize.CONTACT_DELETED_PUBLIC_KEY) {
            return MeshEvent.ParseFailure(
                data,
                "ContactDeleted too short: ${data.size} < ${PacketSize.CONTACT_DELETED_PUBLIC_KEY}",
            )
        }
        val publicKey = data.prefixBytes(PacketSize.CONTACT_DELETED_PUBLIC_KEY)
        return MeshEvent.ContactDeleted(publicKey)
    }
}

/** Parser for contacts full notifications. */
object ContactsFullParser {
    /** Parses a contacts full notification (no payload required). */
    fun parse(data: ByteArray): MeshEvent = MeshEvent.ContactsFull
}
