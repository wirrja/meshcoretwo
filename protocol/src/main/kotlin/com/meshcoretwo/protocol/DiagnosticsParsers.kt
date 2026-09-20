// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

// Ported from Parsers+Diagnostics.swift.

/** Parser for full trace route results. */
object TraceDataParser {
    /**
     * Parses trace route data.
     *
     * ### Binary Format
     * (Per firmware MyMesh.cpp onTraceRecv, v1.11+)
     * - Offset 0 (1 byte): Reserved
     * - Offset 1 (1 byte): Path length (total hash bytes, not hop count)
     * - Offset 2 (1 byte): Flags (bits 0-1: path_sz, determines hash size)
     * - Offset 3 (4 bytes): Tag (UInt32 LE)
     * - Offset 7 (4 bytes): Auth code (UInt32 LE)
     * - Offset 11 (pathLen bytes): Hash bytes
     * - Offset 11+pathLen (hopCount bytes): SNR bytes (one per hop)
     * - Offset 11+pathLen+hopCount (1 byte): Final SNR at destination
     *
     * path_sz encoding:
     * - 0: 1-byte hashes (pathLen = hopCount)
     * - 1: 2-byte hashes (hopCount = pathLen / 2)
     * - 2: 4-byte hashes (hopCount = pathLen / 4)
     * - 3: 8-byte hashes (hopCount = pathLen / 8)
     */
    fun parse(data: ByteArray): MeshEvent {
        // Minimum: reserved(1) + pathLen(1) + flags(1) + tag(4) + authCode(4) = 11 bytes
        if (data.size < PacketSize.TRACE_DATA_MINIMUM) {
            return MeshEvent.ParseFailure(data, "TraceData too short: ${data.size} bytes, need ${PacketSize.TRACE_DATA_MINIMUM}")
        }

        val pathLength = data[1].toInt() and 0xFF
        val flags = data[2].toUByte()
        val pathSz = flags.toInt() and 0x03
        val hashSize = 1 shl pathSz // 1, 2, 4, or 8 bytes per hop
        val hopCount = if (pathLength > 0) pathLength / hashSize else 0

        val tag = data.readUInt32LE(3)
        val authCode = data.readUInt32LE(7)

        val hashesStart = 11
        val snrsStart = hashesStart + pathLength
        val finalSnrOffset = snrsStart + hopCount

        // Validate we have enough data
        if (data.size < finalSnrOffset + 1) {
            return MeshEvent.ParseFailure(
                data,
                "TraceData too short for path: need ${finalSnrOffset + 1}, have ${data.size}",
            )
        }

        val path = mutableListOf<TraceNode>()

        // Parse each hop
        for (i in 0 until hopCount) {
            val hashOffset = hashesStart + (i * hashSize)
            val hashBytes = data.copyOfRange(hashOffset, hashOffset + hashSize)
            val snrOffset = snrsStart + i
            val snr = data[snrOffset].snrValue

            // Check if all hash bytes are 0xFF (destination marker)
            val isDestination = hashBytes.all { it == 0xFF.toByte() }
            path.add(TraceNode(hashBytes = if (isDestination) null else hashBytes, snr = snr))
        }

        // Final SNR at destination
        val finalSnr = data[finalSnrOffset].snrValue
        path.add(TraceNode(hashBytes = null, snr = finalSnr))

        return MeshEvent.TraceData(
            TraceInfo(tag = tag, authCode = authCode, flags = flags, pathLength = pathLength.toUByte(), path = path),
        )
    }
}

/** Parser for generic raw packet notifications. */
object RawDataParser {
    /**
     * Parses raw radio data.
     *
     * ### Binary Format
     * (Per firmware MyMesh.cpp push_raw_data)
     * - Offset 0 (1 byte): SNR scaled by 4 (Int8)
     * - Offset 1 (1 byte): RSSI (Int8)
     * - Offset 2 (1 byte): Reserved (0xFF)
     * - Offset 3 (N bytes): Payload data
     */
    fun parse(data: ByteArray): MeshEvent {
        // Minimum: snr(1) + rssi(1) + reserved(1) = 3 bytes
        if (data.size < PacketSize.RAW_DATA_MINIMUM) {
            return MeshEvent.ParseFailure(data, "RawData too short: ${data.size} bytes, need ${PacketSize.RAW_DATA_MINIMUM}")
        }

        val snr = data[0].snrValue
        val rssi = data[1].toInt()
        // Skip reserved byte at offset 2
        val payload = data.copyOfRange(3, data.size)

        return MeshEvent.RawData(RawDataInfo(snr = snr, rssi = rssi, payload = payload))
    }
}

/** Parser for remote debug log entries. */
object LogDataParser {
    /**
     * Parses log messages with optional signal metadata.
     *
     * Returns [MeshEvent.RxLogData] with a parsed RF packet if parsing succeeds, otherwise
     * [MeshEvent.LogData] with the raw payload.
     */
    fun parse(data: ByteArray): MeshEvent {
        if (data.size >= 2) {
            val snr = data[0].snrValue
            val rssi = data[1].toInt()
            val payload = data.copyOfRange(2, data.size)
            val parsed = RxLogParser.parse(snr = snr, rssi = rssi, payload = payload)
            if (parsed != null) return MeshEvent.RxLogData(parsed)
            return MeshEvent.LogData(LogDataInfo(snr = snr, rssi = rssi, payload = payload))
        }
        val parsed = RxLogParser.parse(snr = null, rssi = null, payload = data)
        if (parsed != null) return MeshEvent.RxLogData(parsed)
        return MeshEvent.LogData(LogDataInfo(snr = null, rssi = null, payload = data))
    }
}
