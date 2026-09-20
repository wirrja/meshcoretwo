// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

// Ported from Parsers+Stats.swift.

/** Parser for core system statistics. */
object CoreStatsParser {
    /** Parses battery, uptime, errors, and queue length. */
    fun parse(data: ByteArray): MeshEvent {
        if (data.size < PacketSize.CORE_STATS_MINIMUM) {
            return MeshEvent.ParseFailure(data, "CoreStats too short: ${data.size} < ${PacketSize.CORE_STATS_MINIMUM}")
        }
        val batteryMV = data.readUInt16LE(0)
        val uptime = data.readUInt32LE(2)
        val errors = data.readUInt16LE(6)
        val queueLen = data[8].toUByte()

        return MeshEvent.StatsCore(
            CoreStats(batteryMV = batteryMV, uptimeSeconds = uptime, errors = errors, queueLength = queueLen),
        )
    }
}

/** Parser for radio performance statistics. */
object RadioStatsParser {
    /** Parses noise floor, SNR, and radio airtime. */
    fun parse(data: ByteArray): MeshEvent {
        if (data.size < PacketSize.RADIO_STATS_MINIMUM) {
            return MeshEvent.ParseFailure(data, "RadioStats too short: ${data.size} < ${PacketSize.RADIO_STATS_MINIMUM}")
        }
        val noiseFloor = data.readInt16LE(0)
        val lastRSSI = data[2]
        val lastSNR = data[3].snrValue
        val txAir = data.readUInt32LE(4)
        val rxAir = data.readUInt32LE(8)

        return MeshEvent.StatsRadio(
            RadioStats(
                noiseFloor = noiseFloor,
                lastRSSI = lastRSSI,
                lastSNR = lastSNR,
                txAirtimeSeconds = txAir,
                rxAirtimeSeconds = rxAir,
            ),
        )
    }
}

/** Parser for packet counters. */
object PacketStatsParser {
    /** Parses total sent/received, flood/direct packet counts, and receive errors. */
    fun parse(data: ByteArray): MeshEvent {
        if (data.size < PacketSize.PACKET_STATS_MINIMUM) {
            return MeshEvent.ParseFailure(data, "PacketStats too short: ${data.size} < ${PacketSize.PACKET_STATS_MINIMUM}")
        }
        val receiveErrors = if (data.size >= PacketSize.PACKET_STATS_WITH_RECEIVE_ERRORS) data.readUInt32LE(24) else 0u
        return MeshEvent.StatsPackets(
            PacketStats(
                received = data.readUInt32LE(0),
                sent = data.readUInt32LE(4),
                floodTx = data.readUInt32LE(8),
                directTx = data.readUInt32LE(12),
                floodRx = data.readUInt32LE(16),
                directRx = data.readUInt32LE(20),
                receiveErrors = receiveErrors,
            ),
        )
    }
}
