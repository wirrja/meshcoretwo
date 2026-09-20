// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/** Configuration settings for a [MeshCoreSession]. */
data class SessionConfiguration(
    /** The default timeout for device operations, in seconds. */
    val defaultTimeout: Double = 5.0,

    /** The client identifier sent to the device during session startup. */
    val clientIdentifier: String = "MeshCore-Kotlin",

    /**
     * Wall-clock budget for one binary exchange (status, telemetry, and peers). In-exchange
     * retransmits share this window until a reply or timeout.
     */
    val binaryRequestOverallTimeout: Double = 40.0,

    /**
     * Minimum spacing between in-exchange retransmits of a binary request. `null` disables
     * retransmits. Live cadence is `max(this, suggestedTimeoutMs * binaryRetransmitRTTHeadroom)`.
     */
    val binaryRequestRetransmitInterval: Double? = 1.0,

    /** Maximum idle gap allowed between contact stream events. */
    val contactStreamInactivityTimeout: Double = 15.0,

    /** Maximum total duration allowed for a contact stream. */
    val contactStreamHardTimeout: Double = 180.0,

    /**
     * Maximum number of `CMD_GET_CHANNEL` Write Commands the pipeline keeps outstanding. Bounded
     * below the nRF52 firmware's 12-deep receive queue to leave drop headroom.
     */
    val channelPipelineWindow: Int = 8,

    /**
     * Maximum idle gap allowed between pipelined channel responses before the remaining requests
     * are presumed dropped and surfaced as `missing` for reconciliation.
     */
    val channelPipelineIdleTimeout: Double = 1.5,

    /** Maximum total duration for a pipelined channel read before it returns what it has. */
    val channelPipelineHardTimeout: Double = 30.0,

    /**
     * Time to keep draining after the last requested channel arrives, absorbing duplicate or
     * straggler frames before releasing the serializer so they cannot leak to the next command.
     */
    val channelPipelinePostDrainGrace: Double = 0.05,
) {
    companion object {
        /** The default configuration instance. */
        val default = SessionConfiguration()

        /** Multiplier on firmware `suggestedTimeoutMs` for retransmit spacing. */
        const val BINARY_RETRANSMIT_RTT_HEADROOM: Double = 2.0

        /** Conversion factor between `MessageSentInfo.suggestedTimeoutMs` and seconds. */
        const val MILLISECONDS_PER_SECOND: Double = 1000.0

        /**
         * Headroom multiplier applied to the firmware-suggested round-trip time when waiting for
         * a delivery acknowledgement during retry sends.
         */
        const val RETRY_ACK_TIMEOUT_MULTIPLIER: Double = 1.2
    }
}

/** Errors that can occur during mesh core operations. */
sealed class MeshCoreError(message: String) : Exception(message) {
    /** The operation timed out. */
    object Timeout : MeshCoreError("timeout")

    /** The device returned an error code. */
    data class DeviceError(val code: UByte) : MeshCoreError("device error: $code") {
        /** The typed firmware sub-code, or `null` for a raw code outside the known [ErrorCode] range. */
        val deviceErrorCode: ErrorCode? get() = ErrorCode.fromValue(code)
    }

    /** Failed to parse data from the device. */
    data class ParseError(val reason: String) : MeshCoreError("parse error: $reason")

    /** The transport is not connected. */
    object NotConnected : MeshCoreError("not connected")

    /** A command failed on the device. */
    data class CommandFailed(val command: CommandCode, val reason: String) :
        MeshCoreError("command $command failed: $reason")

    /** Received an unexpected response from the device. */
    data class InvalidResponse(val expected: String, val got: String) :
        MeshCoreError("expected $expected, got $got")

    /** Could not find the specified contact. */
    data class ContactNotFound(val publicKeyPrefix: ByteArray) : MeshCoreError("contact not found")

    /** The data exceeds the device's maximum allowed size. */
    data class DataTooLarge(val maxSize: Int, val actualSize: Int) :
        MeshCoreError("data too large: $actualSize > $maxSize")

    /** Cryptographic signing failed. */
    data class SigningFailed(val reason: String) : MeshCoreError("signing failed: $reason")

    /** Provided input is invalid. */
    data class InvalidInput(val reason: String) : MeshCoreError(reason)

    /** An unknown error occurred. */
    data class Unknown(val reason: String) : MeshCoreError(reason)

    /** Bluetooth is unavailable on this device. */
    object BluetoothUnavailable : MeshCoreError("bluetooth unavailable")

    /** App is not authorized to use Bluetooth. */
    object BluetoothUnauthorized : MeshCoreError("bluetooth unauthorized")

    /** Bluetooth is powered off. */
    object BluetoothPoweredOff : MeshCoreError("bluetooth powered off")

    /** The connection was lost. */
    data class ConnectionLost(val underlying: Throwable?) : MeshCoreError("connection lost")

    /** The session has not been started. */
    object SessionNotStarted : MeshCoreError("session not started")

    /** The requested feature is disabled on the device. */
    object FeatureDisabled : MeshCoreError("feature disabled")
}

/** The result of a message fetch operation. */
sealed class MessageResult {
    /** A direct message from a contact. */
    data class ContactMessageResult(val message: ContactMessage) : MessageResult()

    /** A message from a channel. */
    data class ChannelMessageResult(val message: ChannelMessage) : MessageResult()

    /** A binary datagram received on a channel (firmware v11+). */
    data class ChannelDatagramResult(val datagram: ChannelDatagram) : MessageResult()

    /** No more messages are available in the device queue. */
    object NoMoreMessages : MessageResult()
}
