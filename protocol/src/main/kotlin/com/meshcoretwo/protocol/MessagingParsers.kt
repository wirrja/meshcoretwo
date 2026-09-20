// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.time.Instant

// Ported from Parsers+Messaging.swift.

/** Parser for incoming direct messages. */
object ContactMessageParser {
    /** Supported protocol versions for message parsing. */
    enum class Version { V1, V3 }

    /**
     * Parses a contact message.
     *
     * ### Binary Format (v3)
     * - Offset 0 (1 byte): SNR scaled by 4 (Int8)
     * - Offset 1 (2 bytes): Reserved
     * - Offset 3 (6 bytes): Sender Public Key Prefix
     * - Offset 9 (1 byte): Path Length
     * - Offset 10 (1 byte): Text Type
     * - Offset 11 (4 bytes): Sender Timestamp (UInt32 LE)
     * - Offset 15+ (N bytes): Message payload (UTF-8)
     */
    fun parse(data: ByteArray, version: Version): MeshEvent {
        var offset = 0
        var snr: Double? = null

        val minSize = if (version == Version.V3) PacketSize.CONTACT_MESSAGE_V3_MINIMUM else PacketSize.CONTACT_MESSAGE_V1_MINIMUM
        if (data.size < minSize) {
            return MeshEvent.ParseFailure(data, "ContactMessage response too short: ${data.size} < $minSize")
        }

        if (version == Version.V3) {
            snr = data[offset].snrValue
            offset += 1
            offset += 2 // reserved
        }

        val pubkeyPrefix = data.copyOfRange(offset, offset + 6); offset += 6
        val pathLen = data[offset].toUByte(); offset += 1
        val txtType = data[offset].toUByte(); offset += 1
        val timestamp = Instant.ofEpochSecond(data.readUInt32LE(offset).toLong()); offset += 4

        var signature: ByteArray? = null
        if (txtType == 2u.toUByte()) {
            if (data.size < offset + 4) {
                return MeshEvent.ParseFailure(data, "ContactMessage signature truncated: ${data.size} < ${offset + 4}")
            }
            signature = data.copyOfRange(offset, offset + 4); offset += 4
        }

        // Handle UTF-8 decoding with explicit fallback (lossy) if strict decode fails.
        val textData = data.copyOfRange(offset, data.size)
        val text = textData.decodeUtf8Strict() ?: String(textData, Charsets.UTF_8)

        return MeshEvent.ContactMessageReceived(
            ContactMessage(
                senderPublicKeyPrefix = pubkeyPrefix,
                pathLength = pathLen,
                textType = txtType,
                senderTimestamp = timestamp,
                signature = signature,
                text = text,
                snr = snr,
            ),
        )
    }
}

/** Parser for incoming channel (broadcast) messages. */
object ChannelMessageParser {
    /** Supported protocol versions for message parsing. */
    enum class Version { V1, V3 }

    /**
     * Parses a channel message.
     *
     * ### Binary Format (v3)
     * - Offset 0 (1 byte): SNR scaled by 4 (Int8)
     * - Offset 1 (2 bytes): Reserved
     * - Offset 3 (1 byte): Channel Index
     * - Offset 4 (1 byte): Path Length
     * - Offset 5 (1 byte): Text Type
     * - Offset 6 (4 bytes): Sender Timestamp (UInt32 LE)
     * - Offset 10+ (N bytes): Message payload (UTF-8)
     */
    fun parse(data: ByteArray, version: Version): MeshEvent {
        var offset = 0
        var snr: Double? = null

        val minSize = if (version == Version.V3) PacketSize.CHANNEL_MESSAGE_V3_MINIMUM else PacketSize.CHANNEL_MESSAGE_V1_MINIMUM
        if (data.size < minSize) {
            return MeshEvent.ParseFailure(data, "ChannelMessage response too short: ${data.size} < $minSize")
        }

        if (version == Version.V3) {
            snr = data[offset].snrValue
            offset += 1
            offset += 2 // reserved
        }

        val channelIndex = data[offset].toUByte(); offset += 1
        val pathLen = data[offset].toUByte(); offset += 1
        val txtType = data[offset].toUByte(); offset += 1
        val timestamp = Instant.ofEpochSecond(data.readUInt32LE(offset).toLong()); offset += 4

        val textData = data.copyOfRange(offset, data.size)
        val text = textData.decodeUtf8Strict() ?: String(textData, Charsets.UTF_8)

        return MeshEvent.ChannelMessageReceived(
            ChannelMessage(
                channelIndex = channelIndex,
                pathLength = pathLen,
                textType = txtType,
                senderTimestamp = timestamp,
                text = text,
                snr = snr,
            ),
        )
    }
}

/** Parser for incoming channel binary datagrams. Firmware v11+ (MeshCore v1.15.0+). */
object ChannelDatagramParser {
    /**
     * Parses a channel datagram.
     *
     * ### Binary Format (offsets exclude the `0x1B` opcode byte, stripped by [PacketParser])
     * - Offset 0 (1 byte): SNR scaled by 4 (Int8)
     * - Offset 1 (2 bytes): Reserved
     * - Offset 3 (1 byte): Channel Index
     * - Offset 4 (1 byte): Path Length -- `0xFF` = direct route; otherwise flood-accumulated path encoding
     * - Offset 5 (2 bytes): Data Type (UInt16 LE)
     * - Offset 7 (1 byte): Data Length
     * - Offset 8+: Binary payload (length = data_len, clamped to remaining bytes)
     */
    fun parse(data: ByteArray): MeshEvent {
        if (data.size < PacketSize.CHANNEL_DATAGRAM_MINIMUM) {
            return MeshEvent.ParseFailure(
                data,
                "ChannelDatagram response too short: ${data.size} < ${PacketSize.CHANNEL_DATAGRAM_MINIMUM}",
            )
        }

        var offset = 0
        val snr = data[offset].snrValue
        offset += 1
        offset += 2 // reserved
        val channelIndex = data[offset].toUByte(); offset += 1
        val pathLen = data[offset].toUByte(); offset += 1
        val dataType = data.readUInt16LE(offset); offset += 2
        val declared = data[offset].toInt() and 0xFF; offset += 1

        val remaining = data.size - offset
        val length = minOf(declared, remaining)
        val payload = data.copyOfRange(offset, offset + length)

        return MeshEvent.ChannelDataReceived(
            ChannelDatagram(channelIndex = channelIndex, pathLength = pathLen, dataType = dataType, data = payload, snr = snr),
        )
    }
}
