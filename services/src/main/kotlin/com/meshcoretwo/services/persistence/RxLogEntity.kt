// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * A logged RF packet the radio heard, used to correlate `pathNodes`/`routeType` onto the
 * [MessageEntity] it decrypts to, and (via [rawPayload]) to back the RX Log viewer's raw-hex
 * detail row. Ported from `RxLogEntry.swift`'s `@Model` (SwiftData) to a Room `@Entity`, trimmed
 * of everything else Swift's model carries — see [com.meshcoretwo.services.rxlog.RxLogService]'s
 * class doc for what's still dropped and why (advert inbound-hop stamping, contact-name
 * attribution). [regionScope]/[regionScopeMatches]/[payloadTypeBits] *are* carried (added for the
 * "Incoming Region" chat-footer slice, PLAN.md Фаза 33.5's follow-up).
 *
 * [routeTypeRawValue]/[payloadTypeRawValue]/[payloadVersion]/[pathLength]/[decryptStatusRawValue]
 * (`UByte`/enum) and [channelIndex]/[rssi] (`Int`/`UByte`) and [senderTimestamp] (`UInt`?) are
 * stored as [Int]/[Long] — Room's KSP processor cannot handle Kotlin unsigned types as column
 * types (see [Converters]'s doc).
 */
@Entity(
    tableName = "rx_log_entries",
    indices = [
        Index(value = ["channelIndex", "senderTimestamp"]),
        Index(value = ["radioID", "receivedAt"]),
    ],
)
data class RxLogEntity(
    @PrimaryKey val id: UUID,
    /** The device this entry belongs to — partition key, not the volatile BLE address. */
    val radioID: UUID,
    val receivedAt: Instant,
    val snr: Double?,
    val rssi: Int?,
    val routeTypeRawValue: Int,
    val payloadTypeRawValue: Int,
    val payloadVersion: Int,
    val pathLength: Int,
    /** Raw hop-hash bytes, 1 byte per hop. Empty (not null) means zero hops — distinct from an uncorrelated [MessageEntity.pathNodes], which is `null`. */
    val pathNodes: ByteArray,
    val packetPayload: ByteArray,
    /** The full 0x88 `logData` push, undecoded. Only consumed by the RX Log viewer's raw-hex detail row. */
    val rawPayload: ByteArray,
    /** Correlation hash for a future "heard repeats" feature — not consumed by this slice, carried for fidelity. */
    val packetHash: String,
    /** Channel attribution once decrypted (`null` until then, or forever for a DM/other packet). */
    val channelIndex: Int?,
    val channelName: String?,
    val decryptStatusRawValue: Int,
    /** Sender's timestamp from the decrypted payload (Unix epoch seconds) — only set once decryption succeeds. */
    val senderTimestamp: Long?,
    /**
     * Confident single flood-region name for this packet, or `null` when unresolved or
     * ambiguous. Local-only — never sent over the mesh, purely a local resolution artifact. Read
     * via [com.meshcoretwo.services.rxlog.RegionScopeSemantics.coalesce] with [regionScopeMatches].
     */
    val regionScope: String? = null,
    /** Sorted multi-match set for this packet, comma-joined. Local-only, same convention as [regionScope]. */
    val regionScopeMatches: String = "",
    /**
     * Raw 4-bit payload-type nibble from the wire header (`ParsedRxLogData.payloadTypeBits`).
     * Persisted (unlike [payloadTypeRawValue], the mapped [com.meshcoretwo.protocol.PayloadType])
     * so a region reprocess pass can replay the exact firmware HMAC input — see
     * [com.meshcoretwo.protocol.ParsedRxLogData.payloadTypeBits]'s doc for why the mapped enum
     * can't stand in for it.
     */
    val payloadTypeBits: Int = 0,
    /**
     * Raw `transport_codes[0..1]` bytes when [routeTypeRawValue] carries one (`TC_FLOOD`/
     * `TC_DIRECT`), `null` otherwise. Persisted alongside [payloadTypeBits] so a region reprocess
     * pass can replay [com.meshcoretwo.protocol.TransportCodeRegionResolver.matchRegions] without
     * re-fetching the packet from the radio — [packetPayload] alone isn't enough, since
     * `RxLogParser` strips the transport code out before slicing `packetPayload`.
     */
    val transportCode: ByteArray? = null,
) {
    // ByteArray fields have reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RxLogEntity) return false
        return id == other.id &&
            radioID == other.radioID &&
            receivedAt == other.receivedAt &&
            snr == other.snr &&
            rssi == other.rssi &&
            routeTypeRawValue == other.routeTypeRawValue &&
            payloadTypeRawValue == other.payloadTypeRawValue &&
            payloadVersion == other.payloadVersion &&
            pathLength == other.pathLength &&
            pathNodes.contentEquals(other.pathNodes) &&
            packetPayload.contentEquals(other.packetPayload) &&
            rawPayload.contentEquals(other.rawPayload) &&
            packetHash == other.packetHash &&
            channelIndex == other.channelIndex &&
            channelName == other.channelName &&
            decryptStatusRawValue == other.decryptStatusRawValue &&
            senderTimestamp == other.senderTimestamp &&
            regionScope == other.regionScope &&
            regionScopeMatches == other.regionScopeMatches &&
            payloadTypeBits == other.payloadTypeBits &&
            (transportCode?.contentEquals(other.transportCode ?: ByteArray(0)) ?: (other.transportCode == null))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + radioID.hashCode()
        result = 31 * result + receivedAt.hashCode()
        result = 31 * result + (snr?.hashCode() ?: 0)
        result = 31 * result + (rssi ?: 0)
        result = 31 * result + routeTypeRawValue
        result = 31 * result + payloadTypeRawValue
        result = 31 * result + payloadVersion
        result = 31 * result + pathLength
        result = 31 * result + pathNodes.contentHashCode()
        result = 31 * result + packetPayload.contentHashCode()
        result = 31 * result + rawPayload.contentHashCode()
        result = 31 * result + packetHash.hashCode()
        result = 31 * result + (channelIndex ?: 0)
        result = 31 * result + (channelName?.hashCode() ?: 0)
        result = 31 * result + decryptStatusRawValue
        result = 31 * result + (senderTimestamp?.hashCode() ?: 0)
        result = 31 * result + (regionScope?.hashCode() ?: 0)
        result = 31 * result + regionScopeMatches.hashCode()
        result = 31 * result + payloadTypeBits
        result = 31 * result + (transportCode?.contentHashCode() ?: 0)
        return result
    }
}
