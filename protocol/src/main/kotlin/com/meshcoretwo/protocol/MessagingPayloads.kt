// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.time.Instant

/**
 * Information returned when a message is successfully queued for sending.
 *
 * Contains what's needed to wait for delivery acknowledgement.
 */
class MessageSentInfo(
    /** Route flag from the firmware: 1 = flood, 0 = direct. */
    val route: UByte,
    /** The expected acknowledgement data for correlation. */
    val expectedAck: ByteArray,
    /** The suggested timeout in milliseconds to wait for acknowledgement. */
    val suggestedTimeoutMs: UInt,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageSentInfo) return false
        return route == other.route &&
            expectedAck.contentEquals(other.expectedAck) &&
            suggestedTimeoutMs == other.suggestedTimeoutMs
    }

    override fun hashCode(): Int {
        var result = route.hashCode()
        result = 31 * result + expectedAck.contentHashCode()
        result = 31 * result + suggestedTimeoutMs.hashCode()
        return result
    }
}

/**
 * A message received from a mesh contact.
 *
 * Contact messages are private messages sent directly to your device from another node in the
 * mesh network.
 */
class ContactMessage(
    /** The public key prefix of the sender. */
    val senderPublicKeyPrefix: ByteArray,
    /** The length of the path the message travelled. */
    val pathLength: UByte,
    /** The type of text content. */
    val textType: UByte,
    /** The timestamp from the sender. */
    val senderTimestamp: Instant,
    /** The cryptographic signature of the message, if available. */
    val signature: ByteArray?,
    /** The actual text content of the message. */
    val text: String,
    /** The signal-to-noise ratio of the received packet. */
    val snr: Double?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactMessage) return false
        return senderPublicKeyPrefix.contentEquals(other.senderPublicKeyPrefix) &&
            pathLength == other.pathLength &&
            textType == other.textType &&
            senderTimestamp == other.senderTimestamp &&
            nullableBytesEqual(signature, other.signature) &&
            text == other.text &&
            snr == other.snr
    }

    override fun hashCode(): Int {
        var result = senderPublicKeyPrefix.contentHashCode()
        result = 31 * result + pathLength.hashCode()
        result = 31 * result + textType.hashCode()
        result = 31 * result + senderTimestamp.hashCode()
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        result = 31 * result + text.hashCode()
        result = 31 * result + (snr?.hashCode() ?: 0)
        return result
    }
}

/**
 * A message received on a broadcast channel.
 *
 * Channel messages are broadcast messages visible to all nodes subscribed to the same channel.
 */
class ChannelMessage(
    /** The index of the channel on which the message was received. */
    val channelIndex: UByte,
    /** The length of the path the message travelled. */
    val pathLength: UByte,
    /** The type of text content. */
    val textType: UByte,
    /** The timestamp from the sender. */
    val senderTimestamp: Instant,
    /** The actual text content of the message. */
    val text: String,
    /** The signal-to-noise ratio of the received packet. */
    val snr: Double?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChannelMessage) return false
        return channelIndex == other.channelIndex &&
            pathLength == other.pathLength &&
            textType == other.textType &&
            senderTimestamp == other.senderTimestamp &&
            text == other.text &&
            snr == other.snr
    }

    override fun hashCode(): Int {
        var result = channelIndex.hashCode()
        result = 31 * result + pathLength.hashCode()
        result = 31 * result + textType.hashCode()
        result = 31 * result + senderTimestamp.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + (snr?.hashCode() ?: 0)
        return result
    }
}

/**
 * A binary datagram received on a broadcast channel.
 *
 * Channel datagrams carry arbitrary application data (`data_type` namespaces the schema) rather
 * than plain text. Firmware v11+ (MeshCore v1.15.0+).
 */
class ChannelDatagram(
    /** The index of the channel on which the datagram was received. */
    val channelIndex: UByte,
    /**
     * Encoded path-length byte from the RF packet header.
     *
     * - `0xFF`: the datagram arrived via direct route; upstream path is unknown to firmware.
     * - Otherwise: flood-accumulated path encoding. Upper 2 bits = hash size (1, 2, or 3 bytes
     *   per hop); lower 6 bits = hop count. Decode with [decodePathLen] into a [PathLenDecoded]
     *   for inspecting hops.
     */
    val pathLength: UByte,
    /** Application data-type namespace (see firmware `number_allocations.md`). */
    val dataType: UShort,
    /** The raw binary payload (up to 163 bytes). */
    val data: ByteArray,
    /**
     * The signal-to-noise ratio of the received packet in dB.
     *
     * `RESP_CODE_CHANNEL_DATA_RECV` always carries SNR at offset 0, so this value is always
     * present (unlike [ChannelMessage.snr] which is optional because v1-era push codes omitted it).
     */
    val snr: Double,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChannelDatagram) return false
        return channelIndex == other.channelIndex &&
            pathLength == other.pathLength &&
            dataType == other.dataType &&
            data.contentEquals(other.data) &&
            snr == other.snr
    }

    override fun hashCode(): Int {
        var result = channelIndex.hashCode()
        result = 31 * result + pathLength.hashCode()
        result = 31 * result + dataType.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + snr.hashCode()
        return result
    }
}
