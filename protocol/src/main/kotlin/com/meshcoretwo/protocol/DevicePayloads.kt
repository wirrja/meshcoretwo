// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Response from a `REQ_TYPE_GET_OWNER_INFO` (0x07) binary request.
 *
 * The firmware responds with a UTF-8 string: `"<firmware_ver>\n<node_name>\n<owner_info>"`.
 */
data class OwnerInfoResponse(
    val firmwareVersion: String,
    val nodeName: String,
    val ownerInfo: String,
)

/**
 * A status response from a remote node.
 *
 * Note on offset logic (per Python parsing.py):
 * - Binary request responses: offset=0, fields start immediately after response code
 * - Push notification responses: offset=8, pubkey_prefix at bytes 2-8, fields follow
 *
 * The parser must handle both cases based on whether this is a solicited vs unsolicited response.
 */
class StatusResponse(
    /** Describes which firmware status layout was used to decode the payload. */
    val layout: Layout = Layout.REPEATER,
    /** The public key prefix of the responding node. */
    val publicKeyPrefix: ByteArray,
    /** The battery level in millivolts. */
    val battery: Int,
    /** The current length of the transmit queue. */
    val txQueueLength: Int,
    /** The noise floor in dBm. */
    val noiseFloor: Int,
    /** The last received signal strength indicator. */
    val lastRSSI: Int,
    /** Total packets received by the node. */
    val packetsReceived: UInt,
    /** Total packets sent by the node. */
    val packetsSent: UInt,
    /** Total transmit airtime in seconds. */
    val airtime: UInt,
    /** The node's uptime in seconds. */
    val uptime: UInt,
    /** Total flood packets sent. */
    val sentFlood: UInt,
    /** Total direct packets sent. */
    val sentDirect: UInt,
    /** Total flood packets received. */
    val receivedFlood: UInt,
    /** Total direct packets received. */
    val receivedDirect: UInt,
    /** Total full events recorded. */
    val fullEvents: Int,
    /** The last recorded signal-to-noise ratio. */
    val lastSNR: Double,
    /** Total direct duplicates received. */
    val directDuplicates: Int,
    /** Total flood duplicates received. */
    val floodDuplicates: Int,
    /** Total receive airtime in seconds. */
    val rxAirtime: UInt,
    /** Total receive errors (v1.12+, 0 for older firmware). */
    val receiveErrors: UInt = 0u,
    /** Total messages posted to the room server. */
    val roomServerPostedCount: UShort? = null,
    /** Total room-server post push attempts. */
    val roomServerPostPushCount: UShort? = null,
) {
    /** Which firmware status layout was used to decode the payload. */
    enum class Layout {
        /** Standard repeater / legacy status layout. */
        REPEATER,

        /** Room server layout used by room-server firmware. */
        ROOM_SERVER,
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StatusResponse) return false
        return layout == other.layout &&
            publicKeyPrefix.contentEquals(other.publicKeyPrefix) &&
            battery == other.battery &&
            txQueueLength == other.txQueueLength &&
            noiseFloor == other.noiseFloor &&
            lastRSSI == other.lastRSSI &&
            packetsReceived == other.packetsReceived &&
            packetsSent == other.packetsSent &&
            airtime == other.airtime &&
            uptime == other.uptime &&
            sentFlood == other.sentFlood &&
            sentDirect == other.sentDirect &&
            receivedFlood == other.receivedFlood &&
            receivedDirect == other.receivedDirect &&
            fullEvents == other.fullEvents &&
            lastSNR == other.lastSNR &&
            directDuplicates == other.directDuplicates &&
            floodDuplicates == other.floodDuplicates &&
            rxAirtime == other.rxAirtime &&
            receiveErrors == other.receiveErrors &&
            roomServerPostedCount == other.roomServerPostedCount &&
            roomServerPostPushCount == other.roomServerPostPushCount
    }

    override fun hashCode(): Int {
        var result = layout.hashCode()
        result = 31 * result + publicKeyPrefix.contentHashCode()
        result = 31 * result + battery
        result = 31 * result + txQueueLength
        result = 31 * result + noiseFloor
        result = 31 * result + lastRSSI
        result = 31 * result + packetsReceived.hashCode()
        result = 31 * result + packetsSent.hashCode()
        result = 31 * result + airtime.hashCode()
        result = 31 * result + uptime.hashCode()
        result = 31 * result + sentFlood.hashCode()
        result = 31 * result + sentDirect.hashCode()
        result = 31 * result + receivedFlood.hashCode()
        result = 31 * result + receivedDirect.hashCode()
        result = 31 * result + fullEvents
        result = 31 * result + lastSNR.hashCode()
        result = 31 * result + directDuplicates
        result = 31 * result + floodDuplicates
        result = 31 * result + rxAirtime.hashCode()
        result = 31 * result + receiveErrors.hashCode()
        result = 31 * result + (roomServerPostedCount?.hashCode() ?: 0)
        result = 31 * result + (roomServerPostPushCount?.hashCode() ?: 0)
        return result
    }
}

/** An allowed frequency range for client repeat mode. */
data class FrequencyRange(
    /** The lower bound of the frequency range in kHz. */
    val lowerKHz: UInt,
    /** The upper bound of the frequency range in kHz. */
    val upperKHz: UInt,
)

/**
 * A tuning parameters response.
 *
 * Contains radio tuning parameters used for adaptive timing calculations.
 */
data class TuningParamsResponse(
    /** The base delay for receive operations in milliseconds. */
    val rxDelayBase: Double,
    /** The airtime scaling factor. */
    val airtimeFactor: Double,
)
