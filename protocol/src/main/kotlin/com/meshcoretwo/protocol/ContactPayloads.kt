// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/** A node discovery response. */
class DiscoverResponse(
    /** The type of the discovered node. */
    val nodeType: UByte,
    /** The inbound signal-to-noise ratio. */
    val snrIn: Double,
    /** The signal-to-noise ratio. */
    val snr: Double,
    /** The received signal strength indicator in dBm. */
    val rssi: Int,
    /** The path length to the discovered node. */
    val pathLength: UByte,
    /** The tag for request correlation. */
    val tag: ByteArray,
    /** The full public key of the discovered node. */
    val publicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiscoverResponse) return false
        return nodeType == other.nodeType &&
            snrIn == other.snrIn &&
            snr == other.snr &&
            rssi == other.rssi &&
            pathLength == other.pathLength &&
            tag.contentEquals(other.tag) &&
            publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int {
        var result = nodeType.hashCode()
        result = 31 * result + snrIn.hashCode()
        result = 31 * result + snr.hashCode()
        result = 31 * result + rssi.hashCode()
        result = 31 * result + pathLength.hashCode()
        result = 31 * result + tag.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}

/**
 * An advertisement path response.
 *
 * Contains the path data received in response to an advertisement path query.
 */
class AdvertPathResponse(
    /** The timestamp when the advertisement was received. */
    val recvTimestamp: UInt,
    /** The length of the path in bytes. */
    val pathLength: UByte,
    /** The raw path data. */
    val path: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdvertPathResponse) return false
        return recvTimestamp == other.recvTimestamp && pathLength == other.pathLength && path.contentEquals(other.path)
    }

    override fun hashCode(): Int {
        var result = recvTimestamp.hashCode()
        result = 31 * result + pathLength.hashCode()
        result = 31 * result + path.contentHashCode()
        return result
    }
}
