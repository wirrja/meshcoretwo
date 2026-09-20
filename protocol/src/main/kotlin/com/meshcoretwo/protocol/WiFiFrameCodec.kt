// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Codec for WiFi/TCP frame encoding and decoding.
 *
 * Ported from `WiFiFrameCodec.swift`. MeshCore WiFi protocol uses length-prefixed framing:
 * - Outbound (app to device): `<` (0x3C) + 2-byte length (LE) + payload
 * - Inbound (device to app): `>` (0x3E) + 2-byte length (LE) + payload
 */
object WiFiFrameCodec {
    /** Outbound frame delimiter (app to device). */
    const val OUTBOUND_DELIMITER: Byte = 0x3C // '<'

    /** Inbound frame delimiter (device to app). */
    const val INBOUND_DELIMITER: Byte = 0x3E // '>'

    /** Header size: delimiter (1) + length (2). */
    const val HEADER_SIZE = 3

    /** Encodes a payload for transmission to the device: `<` + 2-byte length (little-endian) + payload. */
    fun encode(payload: ByteArray): ByteArray {
        val length = payload.size
        val frame = ByteArray(HEADER_SIZE + length)
        frame[0] = OUTBOUND_DELIMITER
        frame[1] = (length and 0xFF).toByte()
        frame[2] = ((length shr 8) and 0xFF).toByte()
        payload.copyInto(frame, destinationOffset = HEADER_SIZE)
        return frame
    }
}

/**
 * Stateful decoder for incoming WiFi frames. Buffers partial data and extracts complete frames.
 *
 * Ported from `WiFiFrameDecoder.swift`. Not thread-safe, same as the Swift `struct` it mirrors —
 * callers (here, [WiFiTransport]'s single receive loop) are responsible for serializing access.
 */
class WiFiFrameDecoder {
    private var buffer = ByteArray(0)

    /** Decodes incoming data, returning any complete frames. Partial frames are buffered for subsequent calls. */
    fun decode(data: ByteArray): List<ByteArray> {
        buffer += data
        val frames = mutableListOf<ByteArray>()
        while (true) {
            frames.add(extractFrame() ?: break)
        }
        return frames
    }

    /** Extracts a single complete frame from the buffer, if available. */
    private fun extractFrame(): ByteArray? {
        var start = 0
        while (start < buffer.size && buffer[start] != WiFiFrameCodec.INBOUND_DELIMITER) {
            start++
        }
        if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)

        if (buffer.size < WiFiFrameCodec.HEADER_SIZE) return null

        val length = (buffer[1].toInt() and 0xFF) or ((buffer[2].toInt() and 0xFF) shl 8)
        val totalFrameSize = WiFiFrameCodec.HEADER_SIZE + length
        if (buffer.size < totalFrameSize) return null

        val payload = buffer.copyOfRange(WiFiFrameCodec.HEADER_SIZE, totalFrameSize)
        buffer = buffer.copyOfRange(totalFrameSize, buffer.size)
        return payload
    }

    /** Clears the internal buffer. */
    fun reset() {
        buffer = ByteArray(0)
    }
}
