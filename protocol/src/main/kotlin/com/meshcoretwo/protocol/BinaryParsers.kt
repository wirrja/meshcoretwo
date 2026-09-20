// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

// Ported from Parsers+Binary.swift.

/**
 * Parser for generic binary protocol responses.
 *
 * Note: this returns a generic [MeshEvent.BinaryResponse] event. The caller should use
 * specialized parsers (like [ACLParser] or [MMAParser]) to decode `data` based on the request
 * context.
 */
object BinaryResponseParser {
    /** Parses a generic binary response. */
    fun parse(data: ByteArray): MeshEvent {
        // Binary response format:
        // - Byte 0: Request type (unused, skip)
        // - Bytes 1-4: Tag (matches expectedAck from messageSent)
        // - Bytes 5+: Response data
        if (data.size < 5) {
            return MeshEvent.ParseFailure(data, "BinaryResponse too short: ${data.size} < 5")
        }
        val tag = data.copyOfRange(1, 5)
        val responseData = data.copyOfRange(5, data.size)
        return MeshEvent.BinaryResponse(tag = tag, data = responseData)
    }
}

/** Parser for path discovery results. */
object PathDiscoveryResponseParser {
    /**
     * Parses a path discovery response.
     *
     * ### Binary Format
     * (Per firmware MyMesh.cpp push_path_discovery_response)
     * - Offset 0 (1 byte): Reserved
     * - Offset 1 (6 bytes): Public key prefix
     * - Offset 7 (1 byte): Outbound path length
     * - Offset 8 (N bytes): Outbound path data
     * - Offset 8+N (1 byte): Inbound path length
     * - Offset 9+N (M bytes): Inbound path data
     */
    fun parse(data: ByteArray): MeshEvent {
        // Minimum: reserved(1) + pubkey(6) + out_path_len(1) + in_path_len(1) = 9 bytes
        if (data.size < PacketSize.PATH_DISCOVERY_MINIMUM) {
            return MeshEvent.ParseFailure(
                data,
                "PathDiscoveryResponse too short: ${data.size} bytes, need ${PacketSize.PATH_DISCOVERY_MINIMUM}",
            )
        }

        // Skip reserved byte at offset 0
        val pubkeyPrefix = data.copyOfRange(1, 7)
        var offset = 7

        var outPathLength: UByte = 0u
        var outPath = ByteArray(0)
        var inPathLength: UByte = 0u
        var inPath = ByteArray(0)

        // Parse outbound path (multibyte encoded). Preserve the raw length byte so the UI can
        // display an accurate hop count without needing the device's cached hashSize. A
        // truncated payload where the declared byte length runs past the end of data is
        // surfaced as a parse failure so PathInfo's size invariant holds.
        if (data.size > offset) {
            outPathLength = data[offset].toUByte()
            offset += 1
            val decoded = decodePathLen(outPathLength)
            if (decoded != null && decoded.byteLength > 0) {
                if (data.size < offset + decoded.byteLength) {
                    return MeshEvent.ParseFailure(
                        data,
                        "PathDiscoveryResponse truncated outbound path: need ${decoded.byteLength} bytes, have ${data.size - offset}",
                    )
                }
                outPath = data.copyOfRange(offset, offset + decoded.byteLength)
                offset += decoded.byteLength
            }
        }

        // Parse inbound path (multibyte encoded)
        if (data.size > offset) {
            inPathLength = data[offset].toUByte()
            offset += 1
            val decoded = decodePathLen(inPathLength)
            if (decoded != null && decoded.byteLength > 0) {
                if (data.size < offset + decoded.byteLength) {
                    return MeshEvent.ParseFailure(
                        data,
                        "PathDiscoveryResponse truncated inbound path: need ${decoded.byteLength} bytes, have ${data.size - offset}",
                    )
                }
                inPath = data.copyOfRange(offset, offset + decoded.byteLength)
            }
        }

        return MeshEvent.PathResponse(
            PathInfo(
                publicKeyPrefix = pubkeyPrefix,
                outPathLength = outPathLength,
                outPath = outPath,
                inPathLength = inPathLength,
                inPath = inPath,
            ),
        )
    }
}

/** Parser for low-level protocol control data. */
object ControlDataParser {
    /**
     * Parses SNR, RSSI, and payload from a control packet.
     *
     * This parser automatically detects DISCOVER_RESP payloads (upper nibble 0x9) and returns a
     * structured [MeshEvent.DiscoverResponseEvent] instead of raw [MeshEvent.ControlData].
     *
     * ### Binary Format
     * - Offset 0 (1 byte): SNR scaled by 4 (Int8)
     * - Offset 1 (1 byte): RSSI (Int8)
     * - Offset 2 (1 byte): Path length
     * - Offset 3 (1 byte): Payload type (upper nibble 0x9 = DISCOVER_RESP)
     * - Offset 4+ (N bytes): Payload data
     *
     * ### DISCOVER_RESP Inner Payload Format
     * - Offset 0 (1 byte): SNR in scaled by 4 (Int8)
     * - Offset 1-4 (4 bytes): Tag (UInt32 LE)
     * - Offset 5+ (8 or 32 bytes): Public key (prefix or full)
     */
    fun parse(data: ByteArray): MeshEvent {
        if (data.size < PacketSize.CONTROL_DATA_MINIMUM) {
            return MeshEvent.ParseFailure(data, "ControlData too short: ${data.size} < ${PacketSize.CONTROL_DATA_MINIMUM}")
        }
        val snr = data[0].snrValue
        val rssi = data[1].toInt()
        val pathLen = data[2].toUByte()
        val payloadType = data[3].toUByte()
        val payload = data.copyOfRange(4, data.size)

        // Check for DISCOVER_RESP (upper nibble 0x9)
        // Minimum inner payload: snr_in(1) + tag(4) = 5 bytes
        if ((payloadType and 0xF0u) == 0x90u.toUByte() && payload.size >= 5) {
            val nodeType = payloadType and 0x0Fu
            val snrIn = payload[0].snrValue
            val tag = payload.copyOfRange(1, 5)

            // Pubkey: 32 bytes if available, otherwise 8-byte prefix
            val pubkey = when {
                payload.size >= 37 -> payload.copyOfRange(5, 37)
                payload.size >= 13 -> payload.copyOfRange(5, 13)
                else -> payload.copyOfRange(5, payload.size)
            }

            return MeshEvent.DiscoverResponseEvent(
                DiscoverResponse(
                    nodeType = nodeType,
                    snrIn = snrIn,
                    snr = snr,
                    rssi = rssi,
                    pathLength = pathLen,
                    tag = tag,
                    publicKey = pubkey,
                ),
            )
        }

        return MeshEvent.ControlData(
            ControlDataInfo(snr = snr, rssi = rssi, pathLength = pathLen, payloadType = payloadType, payload = payload),
        )
    }
}

/** Parser for cryptographic signature responses. */
object SignatureParser {
    /** Wraps the signature data in a [MeshEvent.Signature] event. */
    fun parse(data: ByteArray): MeshEvent = MeshEvent.Signature(data)
}
