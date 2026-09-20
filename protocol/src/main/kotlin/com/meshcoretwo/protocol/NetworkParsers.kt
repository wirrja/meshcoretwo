// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

// Ported from Parsers+Network.swift.

/**
 * Parser for advertisement path responses.
 *
 * ### Binary Format
 * - Offset 0-3 (4 bytes): Receive timestamp (UInt32 LE)
 * - Offset 4 (1 byte): Path length
 * - Offset 5+ (N bytes): Path data (length = pathLength)
 */
object AdvertPathResponseParser {
    /** Parses an advertisement path response. */
    fun parse(data: ByteArray): MeshEvent {
        if (data.size < 5) {
            return MeshEvent.ParseFailure(data, "AdvertPathResponse too short: ${data.size} bytes, need 5")
        }

        val timestamp = data.readUInt32LE(0)
        val pathLen = data[4].toUByte()
        val decoded = decodePathLen(pathLen) ?: return MeshEvent.ParseFailure(
            data,
            "AdvertPathResponse uses reserved path length encoding: 0x${"%02X".format(pathLen.toInt())}",
        )
        val byteLen = decoded.byteLength
        if (data.size < 5 + byteLen) {
            return MeshEvent.ParseFailure(data, "AdvertPathResponse path truncated: ${data.size} < ${5 + byteLen}")
        }
        val path = data.copyOfRange(5, 5 + byteLen)

        return MeshEvent.AdvertPathResponseEvent(
            AdvertPathResponse(recvTimestamp = timestamp, pathLength = pathLen, path = path),
        )
    }
}

/**
 * Parser for tuning parameters responses.
 *
 * ### Binary Format
 * - Offset 0-3 (4 bytes): RX delay base * 1000 (UInt32 LE)
 * - Offset 4-7 (4 bytes): Airtime factor * 1000 (UInt32 LE)
 */
object TuningParamsResponseParser {
    /** Parses a tuning parameters response. */
    fun parse(data: ByteArray): MeshEvent {
        if (data.size < 8) {
            return MeshEvent.ParseFailure(data, "TuningParamsResponse too short: ${data.size} bytes, need 8")
        }

        val rxDelayRaw = data.readUInt32LE(0)
        val airtimeRaw = data.readUInt32LE(4)

        return MeshEvent.TuningParamsResponseEvent(
            TuningParamsResponse(rxDelayBase = rxDelayRaw.toDouble() / 1000.0, airtimeFactor = airtimeRaw.toDouble() / 1000.0),
        )
    }
}

/**
 * Parser for allowed repeat frequency ranges (v9+).
 *
 * ### Binary Format
 * Sequence of 8-byte pairs:
 * - Offset N (4 bytes): Lower frequency in kHz (UInt32 LE)
 * - Offset N+4 (4 bytes): Upper frequency in kHz (UInt32 LE)
 */
object AllowedRepeatFreqParser {
    /** Parses allowed frequency ranges. */
    fun parse(data: ByteArray): MeshEvent {
        val ranges = mutableListOf<FrequencyRange>()
        var offset = 0
        while (offset + 8 <= data.size) {
            val lower = data.readUInt32LE(offset)
            val upper = data.readUInt32LE(offset + 4)
            ranges.add(FrequencyRange(lowerKHz = lower, upperKHz = upper))
            offset += 8
        }
        return MeshEvent.AllowedRepeatFreq(ranges)
    }
}
