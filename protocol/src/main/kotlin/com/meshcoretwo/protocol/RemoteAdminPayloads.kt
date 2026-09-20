// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** Login success information. */
class LoginInfo(
    /** The permissions granted after successful login. */
    val permissions: UByte,
    /** Whether the user has administrator privileges. */
    val isAdmin: Boolean,
    /** The public key prefix of the node where the login occurred. */
    val publicKeyPrefix: ByteArray,
    /**
     * The remote node's RTC reading carried in the login response, when present.
     *
     * Comparing it against local time exposes clock drift that silently breaks the node's
     * timestamp-based replay protection.
     */
    val serverTime: Instant? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LoginInfo) return false
        return permissions == other.permissions &&
            isAdmin == other.isAdmin &&
            publicKeyPrefix.contentEquals(other.publicKeyPrefix) &&
            serverTime == other.serverTime
    }

    override fun hashCode(): Int {
        var result = permissions.hashCode()
        result = 31 * result + isAdmin.hashCode()
        result = 31 * result + publicKeyPrefix.contentHashCode()
        result = 31 * result + (serverTime?.hashCode() ?: 0)
        return result
    }
}

/** A telemetry response from a remote node. */
class TelemetryResponse(
    /** The public key prefix of the responding node. */
    val publicKeyPrefix: ByteArray,
    /** The optional tag for request correlation. */
    val tag: ByteArray?,
    /** The raw telemetry data payload. */
    val rawData: ByteArray,
) {
    /** The parsed LPP data points from [rawData]. */
    val dataPoints: List<LPPDataPoint>
        get() = LPPDecoder.decode(rawData)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TelemetryResponse) return false
        return publicKeyPrefix.contentEquals(other.publicKeyPrefix) &&
            nullableBytesEqual(tag, other.tag) &&
            rawData.contentEquals(other.rawData)
    }

    override fun hashCode(): Int {
        var result = publicKeyPrefix.contentHashCode()
        result = 31 * result + (tag?.contentHashCode() ?: 0)
        result = 31 * result + rawData.contentHashCode()
        return result
    }
}

/** A MMA (Min/Max/Average) response. */
class MMAResponse(
    /** The public key prefix of the responding node. */
    val publicKeyPrefix: ByteArray,
    /** The tag for request correlation. */
    val tag: ByteArray,
    /** The list of MMA entries. */
    val data: List<MMAEntry>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MMAResponse) return false
        return publicKeyPrefix.contentEquals(other.publicKeyPrefix) && tag.contentEquals(other.tag) && data == other.data
    }

    override fun hashCode(): Int {
        var result = publicKeyPrefix.contentHashCode()
        result = 31 * result + tag.contentHashCode()
        result = 31 * result + data.hashCode()
        return result
    }
}

/** An entry in MMA response data. */
data class MMAEntry(
    /** The sensor channel associated with this entry. */
    val channel: UByte,
    /** The type of data recorded. */
    val type: String,
    /** The minimum recorded value. */
    val min: Double,
    /** The maximum recorded value. */
    val max: Double,
    /** The average recorded value. */
    val avg: Double,
)

/** An ACL (Access Control List) response. */
class ACLResponse(
    /** The public key prefix of the responding node. */
    val publicKeyPrefix: ByteArray,
    /** The tag for request correlation. */
    val tag: ByteArray,
    /** The list of ACL entries. */
    val entries: List<ACLEntry>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ACLResponse) return false
        return publicKeyPrefix.contentEquals(other.publicKeyPrefix) &&
            tag.contentEquals(other.tag) &&
            entries == other.entries
    }

    override fun hashCode(): Int {
        var result = publicKeyPrefix.contentHashCode()
        result = 31 * result + tag.contentHashCode()
        result = 31 * result + entries.hashCode()
        return result
    }
}

/** An entry in ACL response data. */
class ACLEntry(
    /** The public key prefix affected by this ACL entry. */
    val keyPrefix: ByteArray,
    /** The permissions granted to the key prefix. */
    val permissions: UByte,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ACLEntry && keyPrefix.contentEquals(other.keyPrefix) && permissions == other.permissions)

    override fun hashCode(): Int = 31 * keyPrefix.contentHashCode() + permissions.hashCode()
}

/**
 * A neighbours response from a remote node.
 *
 * Note: parser context must include `pubkey_prefix_length` for proper neighbour parsing
 * (typically 6 bytes, but configurable in some firmware versions).
 */
class NeighboursResponse(
    /** The public key prefix of the responding node. */
    val publicKeyPrefix: ByteArray,
    /** The tag for request correlation. */
    val tag: ByteArray,
    /** The total number of neighbours known to the node. */
    val totalCount: Int,
    /** The list of neighbours returned in this response. */
    val neighbours: List<Neighbour>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NeighboursResponse) return false
        return publicKeyPrefix.contentEquals(other.publicKeyPrefix) &&
            tag.contentEquals(other.tag) &&
            totalCount == other.totalCount &&
            neighbours == other.neighbours
    }

    override fun hashCode(): Int {
        var result = publicKeyPrefix.contentHashCode()
        result = 31 * result + tag.contentHashCode()
        result = 31 * result + totalCount
        result = 31 * result + neighbours.hashCode()
        return result
    }

    companion object {
        /**
         * Absolute backstop on pagination round-trips. The empty-page guard already guarantees
         * termination for well-behaved firmware; this bounds a pathological node that keeps
         * reporting fresh rows without ever reaching its advertised total.
         */
        const val MAX_PAGINATION_PAGES = 512

        /**
         * Pause a caller applies before each page after the first: back-to-back remote
         * round-trips can be dropped while the radio is still transmitting the prior reply or
         * settling a newly-discovered path. The pause lives at the call site so this aggregator
         * stays clock-free.
         */
        val INTER_PAGE_DELAY = 1.seconds

        /**
         * Aggregates every neighbour page from a node into a single response.
         *
         * A node returns at most one radio frame of neighbours per request (roughly a dozen
         * entries), so the complete table is retrieved by repeating the request at advancing
         * offsets until the accumulated count reaches the node-reported total.
         *
         * Termination is guaranteed two ways: pagination stops as soon as a page returns no
         * rows (a stalled or out-of-range response cannot advance the offset), and by an
         * absolute page cap. The returned response carries the node's reported [totalCount], so
         * a caller can compare it against `neighbours.size` to detect a truncated table.
         *
         * @param fetchPage Requests one page of neighbours starting at the given offset.
         * @return A response whose [neighbours] concatenates every page in request order.
         */
        suspend fun collectingAllPages(fetchPage: suspend (offset: UShort) -> NeighboursResponse): NeighboursResponse {
            val aggregated = mutableListOf<Neighbour>()
            var firstPage: NeighboursResponse? = null
            var totalCount = 0

            for (page in 0 until MAX_PAGINATION_PAGES) {
                val offset = minOf(aggregated.size, UShort.MAX_VALUE.toInt()).toUShort()
                val response = fetchPage(offset)
                if (firstPage == null) firstPage = response
                totalCount = response.totalCount

                if (response.neighbours.isEmpty()) break
                aggregated.addAll(response.neighbours)

                if (aggregated.size >= totalCount) break
            }

            return NeighboursResponse(
                publicKeyPrefix = firstPage?.publicKeyPrefix ?: ByteArray(0),
                tag = firstPage?.tag ?: ByteArray(0),
                totalCount = totalCount,
                neighbours = aggregated,
            )
        }
    }
}

/** A neighbour node. */
class Neighbour(
    /** The public key prefix of the neighbour node. */
    val publicKeyPrefix: ByteArray,
    /** How many seconds ago the neighbour was last seen. */
    val secondsAgo: Int,
    /** The signal-to-noise ratio of the last communication with this neighbour. */
    val snr: Double,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is Neighbour && publicKeyPrefix.contentEquals(other.publicKeyPrefix) &&
                    secondsAgo == other.secondsAgo && snr == other.snr
                )

    override fun hashCode(): Int {
        var result = publicKeyPrefix.contentHashCode()
        result = 31 * result + secondsAgo
        result = 31 * result + snr.hashCode()
        return result
    }
}
