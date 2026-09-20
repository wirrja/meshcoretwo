// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import com.meshcoretwo.protocol.ParsedRxLogData
import com.meshcoretwo.protocol.PayloadType
import com.meshcoretwo.protocol.RouteType
import com.meshcoretwo.protocol.decodePathLen
import com.meshcoretwo.services.rendering.SNRQuality
import java.time.Instant
import java.util.UUID

/** An immutable snapshot of a logged RF packet — see [ContactDto]'s doc for why this crosses the DAO/store boundary. Ported from `RxLogEntryDTO`, trimmed per [RxLogEntity]'s class doc. */
data class RxLogDto(
    val id: UUID,
    val radioID: UUID,
    val receivedAt: Instant,
    val snr: Double?,
    val rssi: Int?,
    val routeType: RouteType,
    val payloadType: PayloadType,
    val payloadVersion: UByte,
    val pathLength: UByte,
    val pathNodes: ByteArray,
    val packetPayload: ByteArray,
    val rawPayload: ByteArray,
    val packetHash: String,
    val channelIndex: UByte?,
    val channelName: String?,
    val decryptStatus: DecryptStatus,
    val senderTimestamp: UInt?,
    /** See [RxLogEntity.regionScope]'s doc. */
    val regionScope: String? = null,
    /** See [RxLogEntity.regionScopeMatches]'s doc. */
    val regionScopeMatches: List<String> = emptyList(),
    /** See [RxLogEntity.payloadTypeBits]'s doc. */
    val payloadTypeBits: UByte = 0u,
    /** See [RxLogEntity.transportCode]'s doc. */
    val transportCode: ByteArray? = null,
    /**
     * The decrypted plaintext of a successfully-decoded group-text payload, kept only for the
     * duration of one [com.meshcoretwo.services.rxlog.RxLogService.process] call so
     * [com.meshcoretwo.services.rxlog.HeardRepeatProcessing] can match it against a sent message —
     * ported from `RxLogEntryDTO.decodedText`'s `@Transient` field. Deliberately absent from
     * [toEntity]/[RxLogEntity.toDto] — never written to or read from the database, preserving
     * this port's "never persist decrypted content" privacy property.
     */
    val decodedText: String? = null,
) {
    /** Whether this is a flood-type route. Ported from `RxLogEntryDTO.isFlood`. */
    val isFlood: Boolean get() = routeType.isFlood

    /** Route type display — "FLOOD" or "DIRECT" (simplified from TC variants). Ported from `RxLogEntryDTO.routeTypeSimple`. */
    val routeTypeSimple: String get() = if (isFlood) "FLOOD" else "DIRECT"

    /** Hash size per hop in bytes (1-3), derived from [pathLength]'s upper bits. */
    val pathHashSize: Int get() = decodePathLen(pathLength)?.hashSize ?: 1

    /** Hop count decoded from [pathLength]. */
    val hopCount: Int get() = decodePathLen(pathLength)?.hopCount ?: 0

    /** Classified signal quality based on SNR thresholds. */
    val snrQuality: SNRQuality get() = SNRQuality.of(snr)

    /** SNR mapped to 0-1 for a 4-bar signal icon. */
    val snrLevel: Double get() = snrQuality.barLevel

    /** Formatted SNR string (no label, always signed), or null when unmeasured. */
    val snrDisplayString: String? get() = snr?.let { "%+.1f dB".format(it) }

    /**
     * Target node hashes extracted from a TRACE payload. Layout: 4-byte tag, 4-byte auth, 1-byte
     * flags, then the hashes — hash size from the flags byte's lower 2 bits. Ported from
     * `RxLogEntryDTO.traceTargetHashes`.
     */
    val traceTargetHashes: List<ByteArray>?
        get() {
            if (payloadType != PayloadType.TRACE || packetPayload.size <= 9) return null
            val hashSize = 1 shl (packetPayload[8].toInt() and 0x03)
            val hashBytes = packetPayload.copyOfRange(9, packetPayload.size)
            if (hashBytes.isEmpty() || hashBytes.size % hashSize != 0) return null
            return hashBytes.toList().chunked(hashSize).map { it.toByteArray() }
        }

    /** Sender public key prefix for direct text messages. Ported from `RxLogEntryDTO.senderPrefix`. */
    val senderPrefix: ByteArray?
        get() {
            if (isFlood || payloadType != PayloadType.TEXT_MESSAGE || packetPayload.size < 2) return null
            return packetPayload.copyOfRange(1, 2)
        }

    /** Recipient public key prefix for direct text messages. Ported from `RxLogEntryDTO.recipientPrefix`. */
    val recipientPrefix: ByteArray?
        get() {
            if (isFlood || payloadType != PayloadType.TEXT_MESSAGE || packetPayload.size < 2) return null
            return packetPayload.copyOfRange(0, 1)
        }

    // ByteArray fields have reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RxLogDto) return false
        return id == other.id &&
            radioID == other.radioID &&
            receivedAt == other.receivedAt &&
            snr == other.snr &&
            rssi == other.rssi &&
            routeType == other.routeType &&
            payloadType == other.payloadType &&
            payloadVersion == other.payloadVersion &&
            pathLength == other.pathLength &&
            pathNodes.contentEquals(other.pathNodes) &&
            packetPayload.contentEquals(other.packetPayload) &&
            rawPayload.contentEquals(other.rawPayload) &&
            packetHash == other.packetHash &&
            channelIndex == other.channelIndex &&
            channelName == other.channelName &&
            decryptStatus == other.decryptStatus &&
            senderTimestamp == other.senderTimestamp &&
            regionScope == other.regionScope &&
            regionScopeMatches == other.regionScopeMatches &&
            payloadTypeBits == other.payloadTypeBits &&
            (transportCode?.contentEquals(other.transportCode ?: ByteArray(0)) ?: (other.transportCode == null)) &&
            decodedText == other.decodedText
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + radioID.hashCode()
        result = 31 * result + receivedAt.hashCode()
        result = 31 * result + (snr?.hashCode() ?: 0)
        result = 31 * result + (rssi ?: 0)
        result = 31 * result + routeType.hashCode()
        result = 31 * result + payloadType.hashCode()
        result = 31 * result + payloadVersion.hashCode()
        result = 31 * result + pathLength.hashCode()
        result = 31 * result + pathNodes.contentHashCode()
        result = 31 * result + packetPayload.contentHashCode()
        result = 31 * result + rawPayload.contentHashCode()
        result = 31 * result + packetHash.hashCode()
        result = 31 * result + (channelIndex?.hashCode() ?: 0)
        result = 31 * result + (channelName?.hashCode() ?: 0)
        result = 31 * result + decryptStatus.hashCode()
        result = 31 * result + (senderTimestamp?.hashCode() ?: 0)
        result = 31 * result + (regionScope?.hashCode() ?: 0)
        result = 31 * result + regionScopeMatches.hashCode()
        result = 31 * result + payloadTypeBits.hashCode()
        result = 31 * result + (transportCode?.contentHashCode() ?: 0)
        result = 31 * result + (decodedText?.hashCode() ?: 0)
        return result
    }
}

/** Maps a persisted row to the immutable snapshot services consume, narrowing a corrupt/out-of-range raw value with the same fallback Swift's `RxLogEntryDTO.init(from:)` uses. */
fun RxLogEntity.toDto(): RxLogDto = RxLogDto(
    id = id,
    radioID = radioID,
    receivedAt = receivedAt,
    snr = snr,
    rssi = rssi,
    routeType = RouteType.fromValue(routeTypeRawValue.toUByte()) ?: RouteType.FLOOD,
    payloadType = PayloadType.fromValue(payloadTypeRawValue.toUByte()) ?: PayloadType.UNKNOWN,
    payloadVersion = payloadVersion.toUByte(),
    pathLength = pathLength.toUByte(),
    pathNodes = pathNodes,
    packetPayload = packetPayload,
    rawPayload = rawPayload,
    packetHash = packetHash,
    channelIndex = channelIndex?.toUByte(),
    channelName = channelName,
    decryptStatus = DecryptStatus.fromRawValue(decryptStatusRawValue) ?: DecryptStatus.NOT_APPLICABLE,
    senderTimestamp = senderTimestamp?.toUInt(),
    regionScope = regionScope,
    regionScopeMatches = if (regionScopeMatches.isEmpty()) emptyList() else regionScopeMatches.split(","),
    payloadTypeBits = payloadTypeBits.toUByte(),
    transportCode = transportCode,
)

/** Maps a domain snapshot to the Room row shape (the lossless signed widening — see [RxLogEntity]'s class doc). */
fun RxLogDto.toEntity(): RxLogEntity = RxLogEntity(
    id = id,
    radioID = radioID,
    receivedAt = receivedAt,
    snr = snr,
    rssi = rssi,
    routeTypeRawValue = routeType.value.toInt(),
    payloadTypeRawValue = payloadType.value.toInt(),
    payloadVersion = payloadVersion.toInt(),
    pathLength = pathLength.toInt(),
    pathNodes = pathNodes,
    packetPayload = packetPayload,
    rawPayload = rawPayload,
    packetHash = packetHash,
    channelIndex = channelIndex?.toInt(),
    channelName = channelName,
    decryptStatusRawValue = decryptStatus.rawValue,
    senderTimestamp = senderTimestamp?.toLong(),
    regionScope = regionScope,
    regionScopeMatches = regionScopeMatches.joinToString(","),
    payloadTypeBits = payloadTypeBits.toInt(),
    transportCode = transportCode,
)

/** Builds a fresh entry DTO from a just-parsed RX-log event. Ported from `RxLogEntryDTO.init(from parsed:)`. */
fun ParsedRxLogData.toRxLogDto(
    radioID: UUID,
    id: UUID = UUID.randomUUID(),
    receivedAt: Instant = Instant.now(),
    channelIndex: UByte? = null,
    channelName: String? = null,
    decryptStatus: DecryptStatus = DecryptStatus.NOT_APPLICABLE,
    senderTimestamp: UInt? = null,
    regionScope: String? = null,
    regionScopeMatches: List<String> = emptyList(),
    decodedText: String? = null,
): RxLogDto = RxLogDto(
    id = id,
    radioID = radioID,
    receivedAt = receivedAt,
    snr = snr,
    rssi = rssi,
    routeType = routeType,
    payloadType = payloadType,
    payloadVersion = payloadVersion,
    pathLength = pathLength,
    pathNodes = ByteArray(pathNodes.size) { pathNodes[it].toByte() },
    packetPayload = packetPayload,
    rawPayload = rawPayload,
    packetHash = packetHash,
    channelIndex = channelIndex,
    channelName = channelName,
    decryptStatus = decryptStatus,
    senderTimestamp = senderTimestamp,
    regionScope = regionScope,
    regionScopeMatches = regionScopeMatches,
    payloadTypeBits = payloadTypeBits,
    transportCode = transportCode,
    decodedText = decodedText,
)
