// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

// Ported from Parsers+Channels.swift.

/** Parser for the persisted default flood scope. Firmware v11+ (MeshCore v1.15.0+). */
object DefaultFloodScopeParser {
    /**
     * Parses the default flood scope response.
     *
     * ### Binary Format (offsets exclude the `0x1C` opcode byte, stripped by [PacketParser])
     * - Empty payload (0 bytes): No default scope configured, emits `defaultFloodScope(null)`.
     * - Populated (exactly 47 bytes): `[name:31 zero-padded UTF-8][key:16]`.
     *
     * Firmware emits exactly 0 or 47 bytes (`MyMesh.cpp:1915-1917`); other lengths indicate
     * protocol drift and fall through to a parse failure.
     */
    fun parse(data: ByteArray): MeshEvent {
        if (data.isEmpty()) {
            return MeshEvent.DefaultFloodScopeEvent(null)
        }
        if (data.size != PacketSize.DEFAULT_FLOOD_SCOPE_SET) {
            return MeshEvent.ParseFailure(
                data,
                "DefaultFloodScope response wrong size: ${data.size}, expected 0 or ${PacketSize.DEFAULT_FLOOD_SCOPE_SET}",
            )
        }

        var offset = 0
        val nameField = data.copyOfRange(offset, offset + PacketSize.DEFAULT_FLOOD_SCOPE_NAME_FIELD)
        offset += PacketSize.DEFAULT_FLOOD_SCOPE_NAME_FIELD
        val nullIdx = nameField.indexOf(0).let { if (it < 0) nameField.size else it }
        val nameBytes = nameField.copyOfRange(0, nullIdx)

        // Mirror ContactMessageParser/ChannelMessageParser lossy-UTF-8 handling so a corrupt
        // name doesn't silently misclassify a populated scope as null — the scope itself is
        // set, only the display name is garbled.
        val name = nameBytes.decodeUtf8Strict() ?: String(nameBytes, Charsets.UTF_8)

        val key = data.copyOfRange(offset, offset + PacketSize.DEFAULT_FLOOD_SCOPE_KEY_BYTES)

        return MeshEvent.DefaultFloodScopeEvent(DefaultFloodScope(name = name, scopeKey = key))
    }
}

/** Parser for channel configuration data. */
object ChannelInfoParser {
    /**
     * Parses channel index, name, and PSK secret.
     *
     * The channel name is a null-terminated C string in a 32-byte buffer. Bytes after the null
     * terminator may be uninitialized garbage from the firmware, so we must find the null and
     * decode only the bytes before it.
     */
    fun parse(data: ByteArray): MeshEvent {
        if (data.size < PacketSize.CHANNEL_INFO_MINIMUM) {
            return MeshEvent.ParseFailure(data, "ChannelInfo too short: ${data.size} < ${PacketSize.CHANNEL_INFO_MINIMUM}")
        }
        val index = data[0].toUByte()
        val nameData = data.copyOfRange(1, 33)

        // Find first null byte - firmware uses strcpy which leaves garbage after the null
        val nullIndex = nameData.indexOf(0).let { if (it < 0) nameData.size else it }
        val name = String(nameData, 0, nullIndex, Charsets.UTF_8)

        val secret = data.copyOfRange(33, 49)

        return MeshEvent.ChannelInfoEvent(ChannelInfo(index = index, name = name, secret = secret))
    }
}
