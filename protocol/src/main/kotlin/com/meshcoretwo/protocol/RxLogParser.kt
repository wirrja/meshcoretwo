// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Parser for raw RF packets from rxLogData events.
 *
 * Ported ahead of the rest of RxLogParser.swift's siblings (NeighboursParser, MMAParser, ...)
 * because [LogDataParser] (Parsers+Diagnostics.swift) calls it directly.
 */
object RxLogParser {
    /** Parses raw payload bytes into structured [ParsedRxLogData], or `null` if malformed. */
    fun parse(snr: Double?, rssi: Int?, payload: ByteArray): ParsedRxLogData? {
        if (payload.isEmpty()) return null

        var offset = 0

        // Parse header byte
        val header = payload[offset].toUByte()
        offset += 1

        val routeTypeBits = header and 0x03u
        val payloadTypeBits = (header.toInt() shr 2).toUByte() and 0x0Fu
        val payloadVersion = (header.toInt() shr 6).toUByte() and 0x03u

        val routeType = RouteType.fromValue(routeTypeBits) ?: return null
        val payloadType = PayloadType.fromBits(payloadTypeBits)

        // Parse transport code if present
        var transportCode: ByteArray? = null
        if (routeType.hasTransportCode) {
            if (payload.size < offset + 4) return null
            transportCode = payload.copyOfRange(offset, offset + 4)
            offset += 4
        }

        // Parse path length (multibyte encoded)
        if (payload.size <= offset) return null
        val pathLength = payload[offset].toUByte()
        offset += 1

        // Decode actual byte length from multibyte encoding.
        // Mode 3 is reserved and decodePathLen returns null; the firmware rejects the whole
        // packet in that case, so fail the parse rather than mis-slicing the remainder as payload.
        val pathByteLen = decodePathLen(pathLength)?.byteLength ?: return null

        // Parse path nodes
        var pathNodes: List<UByte> = emptyList()
        if (pathByteLen > 0) {
            if (payload.size < offset + pathByteLen) return null
            pathNodes = payload.copyOfRange(offset, offset + pathByteLen).map { it.toUByte() }
            offset += pathByteLen
        }

        // Remaining bytes are packet payload
        val packetPayload = if (payload.size > offset) payload.copyOfRange(offset, payload.size) else ByteArray(0)

        // Extract dest and src hashes for text messages. The firmware prefixes every text
        // message payload with these two hashes regardless of route type, so flood and direct
        // packets share the same offsets; extracting for both lets the consumer resolve the
        // sender name for flood-routed DMs too.
        // DM payload hashes are always 1 byte per MeshCore spec (PATH_HASH_SIZE = 1)
        var senderPubkeyPrefix: ByteArray? = null
        var recipientPubkeyPrefix: ByteArray? = null
        val payloadHashSize = 1
        if (payloadType == PayloadType.TEXT_MESSAGE && packetPayload.size >= payloadHashSize * 2) {
            recipientPubkeyPrefix = packetPayload.copyOfRange(0, payloadHashSize)
            senderPubkeyPrefix = packetPayload.copyOfRange(payloadHashSize, payloadHashSize * 2)
        }

        return ParsedRxLogData(
            snr = snr,
            rssi = rssi,
            rawPayload = payload,
            routeType = routeType,
            payloadType = payloadType,
            payloadVersion = payloadVersion,
            payloadTypeBits = payloadTypeBits,
            transportCode = transportCode,
            pathLength = pathLength,
            pathNodes = pathNodes,
            packetPayload = packetPayload,
            senderPubkeyPrefix = senderPubkeyPrefix,
            recipientPubkeyPrefix = recipientPubkeyPrefix,
        )
    }
}
