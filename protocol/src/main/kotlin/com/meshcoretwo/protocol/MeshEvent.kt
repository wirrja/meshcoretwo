// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.time.Instant

/**
 * Events emitted by a MeshCore device during communication.
 *
 * Encapsulates all possible events that can be received from a MeshCore mesh networking
 * device, delivered through the session's event stream.
 *
 * Seven case names collide with an existing payload type of the same name once PascalCased
 * (e.g. the `selfInfo(SelfInfo)` case would become a nested `SelfInfo` class shadowing the
 * top-level [SelfInfo] payload) — those are suffixed `...Event` here: [SelfInfoEvent],
 * [StatusResponseEvent], [TelemetryResponseEvent], [NeighboursResponseEvent],
 * [DiscoverResponseEvent], [AdvertPathResponseEvent], [TuningParamsResponseEvent],
 * [ChannelInfoEvent], [AutoAddConfigEvent], [DefaultFloodScopeEvent].
 *
 * Unlike [MeshContact], this type does not declare `Equatable` in Swift, so several cases below
 * carry a `ByteArray` payload with the default (reference-comparing) `data class` equals() —
 * matching Swift's behavior of simply not supporting `==` on these cases at all.
 */
sealed class MeshEvent {
    // MARK: - Connection Lifecycle

    /** The connection state has changed. */
    data class ConnectionStateChanged(val state: ConnectionState) : MeshEvent()

    // MARK: - Command Responses

    /** A command completed successfully, with an optional success value. */
    data class Ok(val value: UInt?) : MeshEvent()

    /** A command failed with an error; `code` is a device-specific error code, if available. */
    data class Error(val code: UByte?) : MeshEvent()

    // MARK: - Device Information

    /** Device self-information, emitted after starting the session. */
    data class SelfInfoEvent(val info: SelfInfo) : MeshEvent()

    /** Device capabilities, emitted in response to a device query. */
    data class DeviceInfo(val capabilities: DeviceCapabilities) : MeshEvent()

    /** Battery status. */
    data class Battery(val info: BatteryInfo) : MeshEvent()

    /** The current device time. */
    data class CurrentTime(val time: Instant) : MeshEvent()

    /** Custom variables. */
    data class CustomVars(val vars: Map<String, String>) : MeshEvent()

    /** Channel configuration. */
    data class ChannelInfoEvent(val info: ChannelInfo) : MeshEvent()

    /** Core statistics. */
    data class StatsCore(val stats: CoreStats) : MeshEvent()

    /** Radio statistics. */
    data class StatsRadio(val stats: RadioStats) : MeshEvent()

    /** Packet statistics. */
    data class StatsPackets(val stats: PacketStats) : MeshEvent()

    /** Auto-add configuration. */
    data class AutoAddConfigEvent(val config: AutoAddConfig) : MeshEvent()

    /**
     * The persisted default flood scope.
     *
     * Firmware v11+ (MeshCore v1.15.0+). `null` means no default scope is persisted.
     */
    data class DefaultFloodScopeEvent(val scope: DefaultFloodScope?) : MeshEvent()

    /** Allowed repeat frequency ranges (v9+ firmware). */
    data class AllowedRepeatFreq(val ranges: List<FrequencyRange>) : MeshEvent()

    // MARK: - Contact Management

    /** A contact list transfer has started, with the total count. */
    data class ContactsStart(val count: Int) : MeshEvent()

    /** A contact was received during a contact list transfer. */
    data class Contact(val contact: MeshContact) : MeshEvent()

    /** A contact list transfer has completed, with the timestamp of the most recently modified contact. */
    data class ContactsEnd(val lastModified: Instant) : MeshEvent()

    /** A new contact was discovered and added to the device's contact list. */
    data class NewContact(val contact: MeshContact) : MeshEvent()

    /** A contact was automatically deleted by the device (auto-add overwrote it due to storage limits). */
    data class ContactDeleted(val publicKey: ByteArray) : MeshEvent()

    /** The device's contact storage is full. */
    data object ContactsFull : MeshEvent()

    /** A shareable contact URI. */
    data class ContactURI(val uri: String) : MeshEvent()

    // MARK: - Messaging

    /** A message was queued for sending; wait for an [Acknowledgement] event to confirm delivery. */
    data class MessageSent(val info: MessageSentInfo) : MeshEvent()

    /** A direct message was received from a contact. */
    data class ContactMessageReceived(val message: ContactMessage) : MeshEvent()

    /** A channel broadcast message was received. */
    data class ChannelMessageReceived(val message: ChannelMessage) : MeshEvent()

    /**
     * A binary datagram was received on a channel.
     *
     * Firmware v11+ (MeshCore v1.15.0+). Emitted for a `PAYLOAD_TYPE_GRP_DATA` packet. The
     * payload is the raw (encrypted) ciphertext bytes — higher layers decrypt with the
     * channel's shared key.
     */
    data class ChannelDataReceived(val datagram: ChannelDatagram) : MeshEvent()

    /** No more messages are waiting (emitted by getMessage when the queue is empty). */
    data object NoMoreMessages : MeshEvent()

    /** Messages are waiting to be fetched. */
    data object MessagesWaiting : MeshEvent()

    // MARK: - Network Events

    /** An advertisement was received from a node. */
    data class Advertisement(val publicKey: ByteArray) : MeshEvent()

    /** A routing path to a node was updated. */
    data class PathUpdate(val publicKey: ByteArray) : MeshEvent()

    /**
     * A message delivery acknowledgement. Match [code] against [MessageSentInfo.expectedAck] to
     * correlate with sent messages.
     *
     * @param tripTime Firmware-measured radio round-trip time in milliseconds, if available.
     */
    data class Acknowledgement(val code: ByteArray, val tripTime: UInt? = null) : MeshEvent()

    /** Trace route data. */
    data class TraceData(val info: TraceInfo) : MeshEvent()

    /** A path discovery response. */
    data class PathResponse(val info: PathInfo) : MeshEvent()

    // MARK: - Authentication

    /** Login to a remote node succeeded. */
    data class LoginSuccess(val info: LoginInfo) : MeshEvent()

    /** Login to a remote node failed. */
    data class LoginFailed(val publicKeyPrefix: ByteArray?) : MeshEvent()

    // MARK: - Binary Protocol Responses

    /** A status response from a remote node. */
    data class StatusResponseEvent(val response: StatusResponse) : MeshEvent()

    /** A telemetry response from a remote node. */
    data class TelemetryResponseEvent(val response: TelemetryResponse) : MeshEvent()

    /** A generic binary protocol response without a specific event type. */
    data class BinaryResponse(val tag: ByteArray, val data: ByteArray) : MeshEvent()

    /** A Min/Max/Average telemetry response. */
    data class MmaResponse(val response: MMAResponse) : MeshEvent()

    /** An access control list response. */
    data class AclResponse(val response: ACLResponse) : MeshEvent()

    /** A neighbours list response. */
    data class NeighboursResponseEvent(val response: NeighboursResponse) : MeshEvent()

    // MARK: - Cryptographic Signing

    /** A signing session has started, with the maximum number of bytes that can be signed. */
    data class SignStart(val maxLength: Int) : MeshEvent()

    /** A cryptographic signature was generated. */
    data class Signature(val data: ByteArray) : MeshEvent()

    /** A requested feature is disabled on the device. */
    data class Disabled(val reason: String) : MeshEvent()

    // MARK: - Raw Data and Logging

    /** Raw radio data forwarded by the device. */
    data class RawData(val info: RawDataInfo) : MeshEvent()

    /** Diagnostic log data from the device. */
    data class LogData(val info: LogDataInfo) : MeshEvent()

    /** Parsed low-level radio log data. */
    data class RxLogData(val data: ParsedRxLogData) : MeshEvent()

    /** Control protocol data. */
    data class ControlData(val info: ControlDataInfo) : MeshEvent()

    /** A node discovery response. */
    data class DiscoverResponseEvent(val response: DiscoverResponse) : MeshEvent()

    /** An advertisement path response (0x16). */
    data class AdvertPathResponseEvent(val response: AdvertPathResponse) : MeshEvent()

    /** A tuning parameters response (0x17). */
    data class TuningParamsResponseEvent(val response: TuningParamsResponse) : MeshEvent()

    // MARK: - Key Management

    /** The device's exported private key. */
    data class PrivateKey(val key: ByteArray) : MeshEvent()

    // MARK: - Debug and Diagnostics

    /**
     * Packet parsing failed; a diagnostic event for debugging protocol issues.
     *
     * @param data The raw data that failed to parse.
     * @param reason A human-readable reason for the parse failure.
     */
    data class ParseFailure(val data: ByteArray, val reason: String) : MeshEvent()
}

// MARK: - Event Attributes for Filtering

/**
 * Attributes for event filtering: a map of key-value pairs usable for type-safe filtering
 * without runtime type checking. Events without filterable properties return an empty map.
 */
val MeshEvent.attributes: Map<String, Any>
    get() = when (this) {
        is MeshEvent.ContactMessageReceived -> mapOf(
            "publicKeyPrefix" to message.senderPublicKeyPrefix,
            "textType" to message.textType,
        )

        is MeshEvent.ChannelMessageReceived -> mapOf(
            "channelIndex" to message.channelIndex,
            "textType" to message.textType,
        )

        is MeshEvent.Acknowledgement -> buildMap {
            put("code", code)
            tripTime?.let { put("tripTime", it) }
        }

        is MeshEvent.MessageSent -> mapOf(
            "route" to info.route,
            "expectedAck" to info.expectedAck,
        )

        is MeshEvent.StatusResponseEvent -> mapOf("publicKeyPrefix" to response.publicKeyPrefix)
        is MeshEvent.TelemetryResponseEvent -> mapOf("publicKeyPrefix" to response.publicKeyPrefix)
        is MeshEvent.Advertisement -> mapOf("publicKeyPrefix" to publicKey.prefixBytes(6))
        is MeshEvent.PathUpdate -> mapOf("publicKeyPrefix" to publicKey.prefixBytes(6))
        is MeshEvent.NewContact -> mapOf("publicKey" to contact.publicKey)
        is MeshEvent.Contact -> mapOf("publicKey" to contact.publicKey)
        is MeshEvent.Error -> code?.let { mapOf("code" to it) } ?: emptyMap()
        is MeshEvent.Ok -> value?.let { mapOf("value" to it) } ?: emptyMap()
        else -> emptyMap()
    }

/**
 * Stable, low-cardinality identifier for the case, e.g. `"Ok"` or `"ContactsFull"`. Used for
 * observability output where dumping the full payload would balloon log storage.
 *
 * Unlike the Swift original (which parses `String(describing: self)` up to the first `(`),
 * this reads the Kotlin class name directly — type-safe, and equally immune to falling through
 * to "unknown" as [MeshEvent] grows new cases.
 */
val MeshEvent.caseName: String
    get() = this::class.simpleName ?: "Unknown"

/**
 * The typed device error sub-code for an [MeshEvent.Error] event.
 *
 * `null` for non-error events, when the firmware omitted the sub-code byte, or when the raw
 * byte falls outside the known [ErrorCode] range. The raw byte remains available on
 * [MeshEvent.Error.code] for forward compatibility.
 */
val MeshEvent.errorCode: ErrorCode?
    get() = (this as? MeshEvent.Error)?.code?.let { ErrorCode.fromValue(it) }
