// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

// Ported from Parsers+Status.swift.

/** Parser for remote node status reports. */
object StatusResponseParser {
    /**
     * Parses remote node status (58 bytes).
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Reserved (skipped)
     * - Offset 1 (6 bytes): Public Key Prefix
     * - Offset 7 (2 bytes): Battery level in mV (UInt16 LE)
     * - Offset 9 (2 bytes): Tx queue length (UInt16 LE)
     * - Offset 11 (2 bytes): Noise floor (Int16 LE)
     * - Offset 13 (2 bytes): Last RSSI (Int16 LE)
     * - Offset 15 (8 bytes): Total packets recv/sent (UInt32 LE)
     * - Offset 23 (8 bytes): Airtime/Uptime in seconds (UInt32 LE)
     * - Offset 31 (16 bytes): Stats for flood/direct comms (UInt32 LE)
     * - Offset 47 (2 bytes): Full events counter
     * - Offset 49 (2 bytes): Last SNR scaled by 4 (Int16 LE)
     * - Offset 51 (4 bytes): Duplicate counters
     * - Offset 55 (4 bytes): Repeater: Rx airtime (UInt32 LE); Room server: posted count (UInt16 LE) + post-push count (UInt16 LE)
     * - Offset 59 (4 bytes): Repeater only: Receive errors (UInt32 LE, optional)
     */
    fun parse(data: ByteArray, layout: StatusResponse.Layout = StatusResponse.Layout.REPEATER): MeshEvent {
        if (data.size < PacketSize.STATUS_RESPONSE_MINIMUM) {
            return MeshEvent.ParseFailure(data, "StatusResponse too short: ${data.size} < ${PacketSize.STATUS_RESPONSE_MINIMUM}")
        }

        var offset = 0
        offset += 1 // Skip reserved byte (per firmware and Python parsing.py)
        val pubkeyPrefix = data.copyOfRange(offset, offset + 6); offset += 6
        val battery = data.readUInt16LE(offset).toInt(); offset += 2
        val txQueueLen = data.readUInt16LE(offset).toInt(); offset += 2
        val noiseFloor = data.readInt16LE(offset).toInt(); offset += 2
        val lastRSSI = data.readInt16LE(offset).toInt(); offset += 2
        val packetsRecv = data.readUInt32LE(offset); offset += 4
        val packetsSent = data.readUInt32LE(offset); offset += 4
        val airtime = data.readUInt32LE(offset); offset += 4
        val uptime = data.readUInt32LE(offset); offset += 4
        val sentFlood = data.readUInt32LE(offset); offset += 4
        val sentDirect = data.readUInt32LE(offset); offset += 4
        val recvFlood = data.readUInt32LE(offset); offset += 4
        val recvDirect = data.readUInt32LE(offset); offset += 4
        val fullEvents = data.readUInt16LE(offset).toInt(); offset += 2
        val lastSNR = data.readInt16LE(offset) / 4.0; offset += 2
        val directDups = data.readUInt16LE(offset).toInt(); offset += 2
        val floodDups = data.readUInt16LE(offset).toInt(); offset += 2

        val response = when (layout) {
            StatusResponse.Layout.REPEATER -> {
                val rxAirtime = data.readUInt32LE(offset); offset += 4
                val receiveErrors = if (data.size >= offset + 4) data.readUInt32LE(offset) else 0u

                StatusResponse(
                    layout = StatusResponse.Layout.REPEATER,
                    publicKeyPrefix = pubkeyPrefix,
                    battery = battery,
                    txQueueLength = txQueueLen,
                    noiseFloor = noiseFloor,
                    lastRSSI = lastRSSI,
                    packetsReceived = packetsRecv,
                    packetsSent = packetsSent,
                    airtime = airtime,
                    uptime = uptime,
                    sentFlood = sentFlood,
                    sentDirect = sentDirect,
                    receivedFlood = recvFlood,
                    receivedDirect = recvDirect,
                    fullEvents = fullEvents,
                    lastSNR = lastSNR,
                    directDuplicates = directDups,
                    floodDuplicates = floodDups,
                    rxAirtime = rxAirtime,
                    receiveErrors = receiveErrors,
                )
            }

            StatusResponse.Layout.ROOM_SERVER -> {
                val postedCount = if (data.size >= offset + 4) data.readUInt16LE(offset) else null
                val postPushCount = if (data.size >= offset + 4) data.readUInt16LE(offset + 2) else null

                StatusResponse(
                    layout = StatusResponse.Layout.ROOM_SERVER,
                    publicKeyPrefix = pubkeyPrefix,
                    battery = battery,
                    txQueueLength = txQueueLen,
                    noiseFloor = noiseFloor,
                    lastRSSI = lastRSSI,
                    packetsReceived = packetsRecv,
                    packetsSent = packetsSent,
                    airtime = airtime,
                    uptime = uptime,
                    sentFlood = sentFlood,
                    sentDirect = sentDirect,
                    receivedFlood = recvFlood,
                    receivedDirect = recvDirect,
                    fullEvents = fullEvents,
                    lastSNR = lastSNR,
                    directDuplicates = directDups,
                    floodDuplicates = floodDups,
                    rxAirtime = 0u,
                    receiveErrors = 0u,
                    roomServerPostedCount = postedCount,
                    roomServerPostPushCount = postPushCount,
                )
            }
        }

        return MeshEvent.StatusResponseEvent(response)
    }

    /**
     * Parses status data from a BINARY_RESPONSE (0x8C) payload.
     *
     * ### Binary Format (Format 2 - no pubkey header)
     * Fields start at offset 0:
     * - Offset 0 (2 bytes): Battery level in mV (UInt16 LE)
     * - Offset 2 (2 bytes): Tx queue length (UInt16 LE)
     * - Offset 4 (2 bytes): Noise floor (Int16 LE)
     * - Offset 6 (2 bytes): Last RSSI (Int16 LE)
     * - Offset 8 (4 bytes): Packets received (UInt32 LE)
     * - Offset 12 (4 bytes): Packets sent (UInt32 LE)
     * - Offset 16 (4 bytes): Airtime in seconds (UInt32 LE)
     * - Offset 20 (4 bytes): Uptime in seconds (UInt32 LE)
     * - Offset 24 (4 bytes): Sent flood (UInt32 LE)
     * - Offset 28 (4 bytes): Sent direct (UInt32 LE)
     * - Offset 32 (4 bytes): Received flood (UInt32 LE)
     * - Offset 36 (4 bytes): Received direct (UInt32 LE)
     * - Offset 40 (2 bytes): Full events counter (UInt16 LE)
     * - Offset 42 (2 bytes): Last SNR scaled by 4 (Int16 LE)
     * - Offset 44 (2 bytes): Direct duplicates (UInt16 LE)
     * - Offset 46 (2 bytes): Flood duplicates (UInt16 LE)
     * - Offset 48 (4 bytes): Rx airtime (UInt32 LE, optional)
     * - Offset 52 (4 bytes): Receive errors (UInt32 LE, optional, v1.12+)
     *
     * @param data Raw binary response payload (without the 4-byte tag).
     * @param publicKeyPrefix The 6-byte public key prefix from the pending request context.
     * @return A [StatusResponse] if parsing succeeds, `null` otherwise.
     */
    fun parseFromBinaryResponse(
        data: ByteArray,
        publicKeyPrefix: ByteArray,
        layout: StatusResponse.Layout = StatusResponse.Layout.REPEATER,
    ): StatusResponse? {
        // Base frame is required; optional trailing fields are read only when the full field is
        // present. A partial trailer is ignored rather than rejected.
        if (data.size < PacketSize.BINARY_RESPONSE_STATUS_BASE) return null

        var offset = 0
        val battery = data.readUInt16LE(offset).toInt(); offset += 2
        val txQueueLen = data.readUInt16LE(offset).toInt(); offset += 2
        val noiseFloor = data.readInt16LE(offset).toInt(); offset += 2
        val lastRSSI = data.readInt16LE(offset).toInt(); offset += 2
        val packetsRecv = data.readUInt32LE(offset); offset += 4
        val packetsSent = data.readUInt32LE(offset); offset += 4
        val airtime = data.readUInt32LE(offset); offset += 4
        val uptime = data.readUInt32LE(offset); offset += 4
        val sentFlood = data.readUInt32LE(offset); offset += 4
        val sentDirect = data.readUInt32LE(offset); offset += 4
        val recvFlood = data.readUInt32LE(offset); offset += 4
        val recvDirect = data.readUInt32LE(offset); offset += 4
        val fullEvents = data.readUInt16LE(offset).toInt(); offset += 2
        val lastSNR = data.readInt16LE(offset) / 4.0; offset += 2
        val directDups = data.readUInt16LE(offset).toInt(); offset += 2
        val floodDups = data.readUInt16LE(offset).toInt(); offset += 2

        return when (layout) {
            StatusResponse.Layout.REPEATER -> {
                val rxAirtime = if (data.size >= PacketSize.BINARY_RESPONSE_STATUS_WITH_RX_AIRTIME) data.readUInt32LE(offset) else 0u
                offset += 4
                val receiveErrors =
                    if (data.size >= PacketSize.BINARY_RESPONSE_STATUS_WITH_RECEIVE_ERRORS) data.readUInt32LE(offset) else 0u

                StatusResponse(
                    layout = StatusResponse.Layout.REPEATER,
                    publicKeyPrefix = publicKeyPrefix,
                    battery = battery,
                    txQueueLength = txQueueLen,
                    noiseFloor = noiseFloor,
                    lastRSSI = lastRSSI,
                    packetsReceived = packetsRecv,
                    packetsSent = packetsSent,
                    airtime = airtime,
                    uptime = uptime,
                    sentFlood = sentFlood,
                    sentDirect = sentDirect,
                    receivedFlood = recvFlood,
                    receivedDirect = recvDirect,
                    fullEvents = fullEvents,
                    lastSNR = lastSNR,
                    directDuplicates = directDups,
                    floodDuplicates = floodDups,
                    rxAirtime = rxAirtime,
                    receiveErrors = receiveErrors,
                )
            }

            StatusResponse.Layout.ROOM_SERVER -> {
                val postedCount =
                    if (data.size >= PacketSize.BINARY_RESPONSE_STATUS_WITH_RX_AIRTIME) data.readUInt16LE(offset) else null
                val postPushCount =
                    if (data.size >= PacketSize.BINARY_RESPONSE_STATUS_WITH_RX_AIRTIME) data.readUInt16LE(offset + 2) else null

                StatusResponse(
                    layout = StatusResponse.Layout.ROOM_SERVER,
                    publicKeyPrefix = publicKeyPrefix,
                    battery = battery,
                    txQueueLength = txQueueLen,
                    noiseFloor = noiseFloor,
                    lastRSSI = lastRSSI,
                    packetsReceived = packetsRecv,
                    packetsSent = packetsSent,
                    airtime = airtime,
                    uptime = uptime,
                    sentFlood = sentFlood,
                    sentDirect = sentDirect,
                    receivedFlood = recvFlood,
                    receivedDirect = recvDirect,
                    fullEvents = fullEvents,
                    lastSNR = lastSNR,
                    directDuplicates = directDups,
                    floodDuplicates = floodDups,
                    rxAirtime = 0u,
                    receiveErrors = 0u,
                    roomServerPostedCount = postedCount,
                    roomServerPostPushCount = postPushCount,
                )
            }
        }
    }
}

/** Parser for remote sensor telemetry. */
object TelemetryResponseParser {
    /**
     * Parses a telemetry push notification.
     *
     * ### Binary Format
     * (Per firmware MyMesh.cpp push_telemetry_response)
     * - Offset 0 (1 byte): Reserved
     * - Offset 1 (6 bytes): Public key prefix
     * - Offset 7 (N bytes): Raw LPP telemetry data
     */
    fun parse(data: ByteArray): MeshEvent {
        // Minimum: reserved(1) + pubkey(6) = 7 bytes
        if (data.size < 7) {
            return MeshEvent.ParseFailure(data, "TelemetryResponse too short: ${data.size} bytes, need 7")
        }

        // Skip reserved byte at offset 0
        val pubkeyPrefix = data.copyOfRange(1, 7)
        // LPP data starts at byte 7, no tag in push frames
        val rawData = data.copyOfRange(7, data.size)

        return MeshEvent.TelemetryResponseEvent(
            TelemetryResponse(publicKeyPrefix = pubkeyPrefix, tag = null, rawData = rawData),
        )
    }

    /**
     * Parses telemetry data from a BINARY_RESPONSE (0x8C) payload.
     *
     * ### Binary Format (Format 2 - no pubkey header)
     * Raw LPP data starts at offset 0.
     *
     * @param data Raw binary response payload (without the 4-byte tag).
     * @param publicKeyPrefix The 6-byte public key prefix from the pending request context.
     * @return A [TelemetryResponse] with the raw data for LPP decoding.
     */
    fun parseFromBinaryResponse(data: ByteArray, publicKeyPrefix: ByteArray): TelemetryResponse =
        TelemetryResponse(publicKeyPrefix = publicKeyPrefix, tag = null, rawData = data)
}
