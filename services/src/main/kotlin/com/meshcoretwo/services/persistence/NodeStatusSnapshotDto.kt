// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import java.time.Instant
import java.util.UUID

/**
 * Capture-rate policy for node status snapshots. Ported from `NodeSnapshotPolicy`.
 */
internal object NodeSnapshotPolicy {
    /**
     * Minimum interval between persisted snapshots for the same node. A status, telemetry, or
     * neighbor capture arriving within this window of the latest snapshot enriches that row
     * rather than inserting a new one.
     */
    const val MINIMUM_INTERVAL_SECONDS = 15 * 60L
}

/**
 * The radio/room status fields captured in a snapshot. Bundling them into one value keeps the
 * save and backfill paths in lockstep. Ported from `NodeStatusMetrics`, trimmed of the
 * `RemoteNodeStatus`-consuming convenience initializer — that belongs to the not-yet-ported
 * RemoteNodes UI layer that builds a capture from a live status response (see
 * [com.meshcoretwo.services.nodesnapshot.NodeSnapshotService]'s class doc).
 */
data class NodeStatusMetrics(
    val batteryMillivolts: UShort?,
    val lastSNR: Double?,
    val lastRSSI: Short?,
    val noiseFloor: Short?,
    val uptimeSeconds: UInt?,
    val rxAirtimeSeconds: UInt?,
    val packetsSent: UInt?,
    val packetsReceived: UInt?,
    val receiveErrors: UInt?,
    /** Per-type packet breakdown: direct vs flood packets sent/received and duplicates. */
    val sentDirect: UInt?,
    val sentFlood: UInt?,
    val receivedDirect: UInt?,
    val receivedFlood: UInt?,
    val directDuplicates: UInt?,
    val floodDuplicates: UInt?,
    val postedCount: UShort? = null,
    val postPushCount: UShort? = null,
)

/** A single neighbor's state at snapshot time. Ported from `NeighborSnapshotEntry`. */
data class NeighborSnapshotEntry(
    val publicKeyPrefix: ByteArray,
    val snr: Double,
    val secondsAgo: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NeighborSnapshotEntry) return false
        return publicKeyPrefix.contentEquals(other.publicKeyPrefix) && snr == other.snr && secondsAgo == other.secondsAgo
    }

    override fun hashCode(): Int {
        var result = publicKeyPrefix.contentHashCode()
        result = 31 * result + snr.hashCode()
        result = 31 * result + secondsAgo
        return result
    }
}

/** A single telemetry reading at snapshot time. Ported from `TelemetrySnapshotEntry`. */
data class TelemetrySnapshotEntry(
    val channel: Int,
    val type: String,
    val value: Double,
)

/**
 * A GPS fix persisted with a snapshot. Latitude and longitude travel together so a snapshot can
 * never hold half a fix. Ported from `NodeLocationFix`; its `primaryFix(from:)` factory lives in
 * `remotenode/TelemetrySnapshotMapping.kt` as `primaryLocationFix`, keeping this model free of LPP
 * types.
 */
data class NodeLocationFix(
    val latitude: Double,
    val longitude: Double,
    /** Altitude in meters, or `null` when the fix carried none or an implausible one. */
    val altitude: Double? = null,
)

/**
 * An immutable snapshot of a [NodeStatusSnapshotEntity] — see [ContactDto]'s doc for why this
 * crosses the DAO/store boundary. Ported from `NodeStatusSnapshotDTO`, minus `validCoordinate`
 * (a SwiftUI/MapKit-facing computed property; callers derive that themselves once the
 * RemoteNodes/map UI consuming this exists).
 */
data class NodeStatusSnapshotDto(
    val id: UUID,
    val timestamp: Instant,
    val nodePublicKey: ByteArray,
    val batteryMillivolts: UShort?,
    val lastSNR: Double?,
    val lastRSSI: Short?,
    val noiseFloor: Short?,
    val uptimeSeconds: UInt?,
    val rxAirtimeSeconds: UInt?,
    val packetsSent: UInt?,
    val packetsReceived: UInt?,
    val receiveErrors: UInt?,
    val sentDirect: UInt?,
    val sentFlood: UInt?,
    val receivedDirect: UInt?,
    val receivedFlood: UInt?,
    val directDuplicates: UInt?,
    val floodDuplicates: UInt?,
    val postedCount: UShort?,
    val postPushCount: UShort?,
    val neighborSnapshots: List<NeighborSnapshotEntry>?,
    val telemetryEntries: List<TelemetrySnapshotEntry>?,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeStatusSnapshotDto) return false
        return id == other.id &&
            timestamp == other.timestamp &&
            nodePublicKey.contentEquals(other.nodePublicKey) &&
            batteryMillivolts == other.batteryMillivolts &&
            lastSNR == other.lastSNR &&
            lastRSSI == other.lastRSSI &&
            noiseFloor == other.noiseFloor &&
            uptimeSeconds == other.uptimeSeconds &&
            rxAirtimeSeconds == other.rxAirtimeSeconds &&
            packetsSent == other.packetsSent &&
            packetsReceived == other.packetsReceived &&
            receiveErrors == other.receiveErrors &&
            sentDirect == other.sentDirect &&
            sentFlood == other.sentFlood &&
            receivedDirect == other.receivedDirect &&
            receivedFlood == other.receivedFlood &&
            directDuplicates == other.directDuplicates &&
            floodDuplicates == other.floodDuplicates &&
            postedCount == other.postedCount &&
            postPushCount == other.postPushCount &&
            neighborSnapshots == other.neighborSnapshots &&
            telemetryEntries == other.telemetryEntries &&
            latitude == other.latitude &&
            longitude == other.longitude &&
            altitude == other.altitude
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + nodePublicKey.contentHashCode()
        result = 31 * result + (batteryMillivolts?.hashCode() ?: 0)
        result = 31 * result + (lastSNR?.hashCode() ?: 0)
        result = 31 * result + (lastRSSI?.hashCode() ?: 0)
        result = 31 * result + (noiseFloor?.hashCode() ?: 0)
        result = 31 * result + (uptimeSeconds?.hashCode() ?: 0)
        result = 31 * result + (rxAirtimeSeconds?.hashCode() ?: 0)
        result = 31 * result + (packetsSent?.hashCode() ?: 0)
        result = 31 * result + (packetsReceived?.hashCode() ?: 0)
        result = 31 * result + (receiveErrors?.hashCode() ?: 0)
        result = 31 * result + (sentDirect?.hashCode() ?: 0)
        result = 31 * result + (sentFlood?.hashCode() ?: 0)
        result = 31 * result + (receivedDirect?.hashCode() ?: 0)
        result = 31 * result + (receivedFlood?.hashCode() ?: 0)
        result = 31 * result + (directDuplicates?.hashCode() ?: 0)
        result = 31 * result + (floodDuplicates?.hashCode() ?: 0)
        result = 31 * result + (postedCount?.hashCode() ?: 0)
        result = 31 * result + (postPushCount?.hashCode() ?: 0)
        result = 31 * result + (neighborSnapshots?.hashCode() ?: 0)
        result = 31 * result + (telemetryEntries?.hashCode() ?: 0)
        result = 31 * result + (latitude?.hashCode() ?: 0)
        result = 31 * result + (longitude?.hashCode() ?: 0)
        result = 31 * result + (altitude?.hashCode() ?: 0)
        return result
    }
}
