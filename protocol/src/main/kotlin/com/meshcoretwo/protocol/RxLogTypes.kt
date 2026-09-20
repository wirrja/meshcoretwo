// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.security.MessageDigest

/**
 * Route type extracted from header byte bits 0-1.
 * All 4 possible 2-bit values are valid (cannot be unknown).
 */
enum class RouteType(val value: UByte) {
    TC_FLOOD(0u),
    FLOOD(1u),
    DIRECT(2u),
    TC_DIRECT(3u);

    /** Whether this route type includes a 4-byte transport code. */
    val hasTransportCode: Boolean
        get() = this == TC_FLOOD || this == TC_DIRECT

    /**
     * Whether the packet was flood-routed, accumulating each relay's hash into its path.
     * Direct-routed packets carry the remaining route instead, so their path length is not a
     * count of hops traversed.
     */
    val isFlood: Boolean
        get() = this == FLOOD || this == TC_FLOOD

    /** Human-readable display name. */
    val displayName: String
        get() = when (this) {
            TC_FLOOD -> "TC_FLOOD"
            FLOOD -> "FLOOD"
            DIRECT -> "DIRECT"
            TC_DIRECT -> "TC_DIRECT"
        }

    companion object {
        private val byValue = entries.associateBy { it.value }
        fun fromValue(value: UByte): RouteType? = byValue[value]
    }
}

/**
 * Payload type extracted from header byte bits 2-5.
 * Values 0-11 are defined; 12-15 map to [UNKNOWN].
 */
enum class PayloadType(val value: UByte) {
    REQUEST(0u),
    RESPONSE(1u),
    TEXT_MESSAGE(2u),
    ACK(3u),
    ADVERT(4u),
    GROUP_TEXT(5u),
    GROUP_DATA(6u),
    ANON_REQUEST(7u),
    PATH(8u),
    TRACE(9u),
    MULTIPART(10u),
    CONTROL(11u),
    RAW_CUSTOM(15u),
    UNKNOWN(255u);

    /** Human-readable display name. */
    val displayName: String
        get() = when (this) {
            REQUEST -> "REQUEST"
            RESPONSE -> "RESPONSE"
            TEXT_MESSAGE -> "TEXT_MSG"
            ACK -> "ACK"
            ADVERT -> "ADVERT"
            GROUP_TEXT -> "GROUP_TEXT"
            GROUP_DATA -> "GROUP_DATA"
            ANON_REQUEST -> "ANON_REQ"
            PATH -> "PATH"
            TRACE -> "TRACE"
            MULTIPART -> "MULTIPART"
            CONTROL -> "CONTROL"
            RAW_CUSTOM -> "RAW_CUSTOM"
            UNKNOWN -> "UNKNOWN"
        }

    companion object {
        private val byValue = entries.associateBy { it.value }

        /** Raw-value lookup; `null` for values with no defined case (e.g. the reserved 12-14). */
        fun fromValue(value: UByte): PayloadType? = byValue[value]

        /** Initialize from raw 4-bit value (0-15). Values 12-14 (reserved) return [UNKNOWN]; 15 returns [RAW_CUSTOM]. */
        fun fromBits(bits: UByte): PayloadType = byValue[bits] ?: UNKNOWN
    }
}

/** Parsed RF packet data from rxLogData events. */
class ParsedRxLogData(
    // Always available (raw)
    val snr: Double?,
    val rssi: Int?,
    val rawPayload: ByteArray,

    // Always available (parsed)
    val routeType: RouteType,
    val payloadType: PayloadType,
    val payloadVersion: UByte,

    /**
     * Raw 4-bit payload-type bits from the header, before mapping to [PayloadType].
     *
     * Required by `TransportCodeRegionResolver`, which must hash the wire-format nibble
     * (firmware bits 12-14 currently map to [PayloadType.UNKNOWN] = 255, which would corrupt
     * the HMAC input).
     */
    val payloadTypeBits: UByte,

    /** Conditional on route type. */
    val transportCode: ByteArray?,

    // Path information (empty if malformed)
    val pathLength: UByte,
    val pathNodes: List<UByte>,

    /** Message payload (after header/path extraction). */
    val packetPayload: ByteArray,

    /** 1-byte sender pubkey hash for direct messages (`null` for channel/other types). */
    val senderPubkeyPrefix: ByteArray? = null,

    /** 1-byte recipient pubkey hash for direct messages (`null` for channel/other types). */
    val recipientPubkeyPrefix: ByteArray? = null,
) {
    /** Correlation hash for "heard repeats" detection. */
    val packetHash: String = computePacketHash(packetPayload)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedRxLogData) return false
        return snr == other.snr &&
            rssi == other.rssi &&
            rawPayload.contentEquals(other.rawPayload) &&
            routeType == other.routeType &&
            payloadType == other.payloadType &&
            payloadVersion == other.payloadVersion &&
            payloadTypeBits == other.payloadTypeBits &&
            nullableBytesEqual(transportCode, other.transportCode) &&
            pathLength == other.pathLength &&
            pathNodes == other.pathNodes &&
            packetPayload.contentEquals(other.packetPayload) &&
            nullableBytesEqual(senderPubkeyPrefix, other.senderPubkeyPrefix) &&
            nullableBytesEqual(recipientPubkeyPrefix, other.recipientPubkeyPrefix)
    }

    override fun hashCode(): Int {
        var result = snr?.hashCode() ?: 0
        result = 31 * result + (rssi ?: 0)
        result = 31 * result + rawPayload.contentHashCode()
        result = 31 * result + routeType.hashCode()
        result = 31 * result + payloadType.hashCode()
        result = 31 * result + payloadVersion.hashCode()
        result = 31 * result + payloadTypeBits.hashCode()
        result = 31 * result + (transportCode?.contentHashCode() ?: 0)
        result = 31 * result + pathLength.hashCode()
        result = 31 * result + pathNodes.hashCode()
        result = 31 * result + packetPayload.contentHashCode()
        result = 31 * result + (senderPubkeyPrefix?.contentHashCode() ?: 0)
        result = 31 * result + (recipientPubkeyPrefix?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        /** Computes the SHA-256 hash of [packetPayload], returning the first 8 bytes as hex. */
        fun computePacketHash(packetPayload: ByteArray): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(packetPayload)
            return hash.copyOf(8).hexString
        }
    }
}
