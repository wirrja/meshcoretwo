// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/** Trace route information. */
data class TraceInfo(
    /** The tag for request correlation. */
    val tag: UInt,
    /** The authentication code for the trace request. */
    val authCode: UInt,
    /** Configuration flags for the trace. */
    val flags: UByte,
    /** The length of the recorded path. */
    val pathLength: UByte,
    /** The list of nodes in the trace path. */
    val path: List<TraceNode>,
)

/** A node in a trace path. */
class TraceNode(
    /**
     * The hash bytes of the node's public key, if available.
     *
     * Size depends on the path_sz flag: 1, 2, 4, or 8 bytes. `null` for the destination node or
     * if the hash is `0xFF` (single-byte mode).
     */
    val hashBytes: ByteArray?,
    /** The signal-to-noise ratio at this hop. */
    val snr: Double,
) {
    /** Legacy accessor: first byte of hash, or `null` if no hash. Use [hashBytes] for multi-byte hashes. */
    val hash: UByte?
        get() = hashBytes?.takeIf { it.isNotEmpty() }?.get(0)?.toUByte()

    /** Legacy constructor for single-byte hashes. */
    constructor(hash: UByte?, snr: Double) : this(hash?.let { byteArrayOf(it.toByte()) }, snr)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TraceNode) return false
        val hashesEqual = when {
            hashBytes == null || other.hashBytes == null -> hashBytes == null && other.hashBytes == null
            else -> hashBytes.contentEquals(other.hashBytes)
        }
        return hashesEqual && snr == other.snr
    }

    override fun hashCode(): Int = 31 * (hashBytes?.contentHashCode() ?: 0) + snr.hashCode()
}

/** Path discovery information. */
class PathInfo(
    /** The public key prefix of the node for which the path was discovered. */
    val publicKeyPrefix: ByteArray,
    /** Raw outbound `path_len` byte as received on the wire. Upper 2 bits = hash mode, lower 6 bits = hop count. */
    val outPathLength: UByte,
    /** The outbound path data. */
    val outPath: ByteArray,
    /** Raw inbound `path_len` byte as received on the wire. */
    val inPathLength: UByte,
    /** The inbound path data. */
    val inPath: ByteArray,
) {
    init {
        val outByteLength = decodePathLen(outPathLength)?.byteLength ?: 0
        val inByteLength = decodePathLen(inPathLength)?.byteLength ?: 0
        require(outPath.size == outByteLength) {
            "PathInfo.outPath size ${outPath.size} does not match outPathLength byte length $outByteLength"
        }
        require(inPath.size == inByteLength) {
            "PathInfo.inPath size ${inPath.size} does not match inPathLength byte length $inByteLength"
        }
    }

    /**
     * Hop count decoded from [outPathLength]. Returns `null` when the byte uses the reserved
     * hash-size mode (upper 2 bits == `11`) so callers can handle unknown encodings explicitly
     * instead of defaulting to "direct".
     */
    val outHopCount: Int?
        get() = decodePathLen(outPathLength)?.hopCount

    /** Hop count decoded from [inPathLength]. */
    val inHopCount: Int?
        get() = decodePathLen(inPathLength)?.hopCount

    /**
     * Whether this response refers to the node with [publicKey]. The wire carries only a key
     * prefix; an empty prefix never matches rather than matching every key.
     */
    fun matches(publicKey: ByteArray): Boolean =
        publicKeyPrefix.isNotEmpty() && publicKey.size >= publicKeyPrefix.size &&
            publicKey.copyOfRange(0, publicKeyPrefix.size).contentEquals(publicKeyPrefix)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PathInfo) return false
        return publicKeyPrefix.contentEquals(other.publicKeyPrefix) &&
            outPathLength == other.outPathLength &&
            outPath.contentEquals(other.outPath) &&
            inPathLength == other.inPathLength &&
            inPath.contentEquals(other.inPath)
    }

    override fun hashCode(): Int {
        var result = publicKeyPrefix.contentHashCode()
        result = 31 * result + outPathLength.hashCode()
        result = 31 * result + outPath.contentHashCode()
        result = 31 * result + inPathLength.hashCode()
        result = 31 * result + inPath.contentHashCode()
        return result
    }
}

/** Raw data received from the device. */
class RawDataInfo(
    /** The signal-to-noise ratio of the received packet. */
    val snr: Double,
    /** The received signal strength indicator in dBm. */
    val rssi: Int,
    /** The raw payload data. */
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is RawDataInfo && snr == other.snr && rssi == other.rssi && payload.contentEquals(other.payload))

    override fun hashCode(): Int {
        var result = snr.hashCode()
        result = 31 * result + rssi.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/** Log data received from the device. */
class LogDataInfo(
    /** The optional signal-to-noise ratio associated with the log entry. */
    val snr: Double?,
    /** The optional received signal strength indicator associated with the log entry. */
    val rssi: Int?,
    /** The raw log payload data. */
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is LogDataInfo && snr == other.snr && rssi == other.rssi && payload.contentEquals(other.payload))

    override fun hashCode(): Int {
        var result = snr.hashCode()
        result = 31 * result + rssi.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/** Control protocol data received from the device. */
class ControlDataInfo(
    /** The signal-to-noise ratio of the received packet. */
    val snr: Double,
    /** The received signal strength indicator in dBm. */
    val rssi: Int,
    /** The path length the control packet travelled. */
    val pathLength: UByte,
    /** The type of control protocol payload. */
    val payloadType: UByte,
    /** The raw payload data. */
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ControlDataInfo) return false
        return snr == other.snr && rssi == other.rssi && pathLength == other.pathLength &&
            payloadType == other.payloadType && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = snr.hashCode()
        result = 31 * result + rssi.hashCode()
        result = 31 * result + pathLength.hashCode()
        result = 31 * result + payloadType.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
