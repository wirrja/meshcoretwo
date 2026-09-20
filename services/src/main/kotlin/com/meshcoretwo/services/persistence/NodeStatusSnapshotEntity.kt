// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Room [TypeConverter]s for [NodeStatusSnapshotEntity]'s two sparse list columns, encoded as JSON
 * text — `org.json` (no new Gradle dependency), matching [InlineImageDimensionsStore]'s precedent
 * as the only other JSON-shaped persistence in the codebase. Scoped to this entity alone via
 * [TypeConverters] on the class rather than added to the database-wide [Converters], since no
 * other entity needs a `List<NeighborSnapshotEntry>`/`List<TelemetrySnapshotEntry>` column.
 */
internal class NodeSnapshotListConverters {
    @TypeConverter
    fun neighborsToJson(value: List<NeighborSnapshotEntry>?): String? {
        if (value == null) return null
        val array = JSONArray()
        for (entry in value) {
            array.put(
                JSONObject().apply {
                    put("publicKeyPrefix", bytesToHex(entry.publicKeyPrefix))
                    put("snr", entry.snr)
                    put("secondsAgo", entry.secondsAgo)
                },
            )
        }
        return array.toString()
    }

    @TypeConverter
    fun jsonToNeighbors(value: String?): List<NeighborSnapshotEntry>? {
        if (value == null) return null
        val array = JSONArray(value)
        return List(array.length()) { index ->
            val obj = array.getJSONObject(index)
            NeighborSnapshotEntry(
                publicKeyPrefix = hexToBytes(obj.getString("publicKeyPrefix")),
                snr = obj.getDouble("snr"),
                secondsAgo = obj.getInt("secondsAgo"),
            )
        }
    }

    @TypeConverter
    fun telemetryToJson(value: List<TelemetrySnapshotEntry>?): String? {
        if (value == null) return null
        val array = JSONArray()
        for (entry in value) {
            array.put(
                JSONObject().apply {
                    put("channel", entry.channel)
                    put("type", entry.type)
                    put("value", entry.value)
                },
            )
        }
        return array.toString()
    }

    @TypeConverter
    fun jsonToTelemetry(value: String?): List<TelemetrySnapshotEntry>? {
        if (value == null) return null
        val array = JSONArray(value)
        return List(array.length()) { index ->
            val obj = array.getJSONObject(index)
            TelemetrySnapshotEntry(
                channel = obj.getInt("channel"),
                type = obj.getString("type"),
                value = obj.getDouble("value"),
            )
        }
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

/**
 * Point-in-time snapshot of a remote node's status, captured when the user views it. Ported from
 * `NodeStatusSnapshot.swift`'s `@Model` (SwiftData) to a Room `@Entity`.
 *
 * Unlike most entities in this module, rows are keyed by [nodePublicKey] alone, mesh-wide — not
 * scoped to a [DeviceEntity]/`radioID` partition, matching `NodeSnapshotPersisting`'s protocol
 * signature (no `radioID` parameter anywhere) and [RemoteNodeSessionEntity]'s same mesh-wide
 * `publicKey` identity.
 *
 * `batteryMillivolts`/`postedCount`/`postPushCount` (`UInt16`), `uptimeSeconds`/`rxAirtimeSeconds`/
 * `packetsSent`/`packetsReceived`/`receiveErrors`/`sentDirect`/`sentFlood`/`receivedDirect`/
 * `receivedFlood`/`directDuplicates`/`floodDuplicates` (`UInt32`) are stored as [Int]/[Long] — Room's
 * KSP processor cannot handle Kotlin unsigned types as column types (see [Converters]'s doc).
 * `lastRSSI`/`noiseFloor` (`Int16` in Swift) are already signed, so they widen losslessly to [Int]
 * with no encoding scheme needed, same as [RemoteNodeSessionEntity.lastNoiseFloor].
 */
@Entity(
    tableName = "node_status_snapshots",
    indices = [Index(value = ["nodePublicKey", "timestamp"])],
)
@TypeConverters(NodeSnapshotListConverters::class)
data class NodeStatusSnapshotEntity(
    @PrimaryKey val id: UUID,
    val timestamp: Instant,
    /** The node's full public key (32 bytes) — links to [RemoteNodeSessionEntity.publicKey]. */
    val nodePublicKey: ByteArray,
    val batteryMillivolts: Int?,
    val lastSNR: Double?,
    val lastRSSI: Int?,
    val noiseFloor: Int?,
    val uptimeSeconds: Long?,
    val rxAirtimeSeconds: Long?,
    val packetsSent: Long?,
    val packetsReceived: Long?,
    val receiveErrors: Long?,
    val sentDirect: Long?,
    val sentFlood: Long?,
    val receivedDirect: Long?,
    val receivedFlood: Long?,
    val directDuplicates: Long?,
    val floodDuplicates: Long?,
    val postedCount: Int?,
    val postPushCount: Int?,
    /** Neighbor data, only populated if the user expanded the neighbors section. */
    val neighborSnapshots: List<NeighborSnapshotEntry>?,
    /** Telemetry data, only populated if the user expanded the telemetry section. */
    val telemetryEntries: List<TelemetrySnapshotEntry>?,
    /**
     * Primary GPS fix at capture time. Both set or both null; dropped when the node had no lock.
     * Written first-wins during in-window enrichment so a later no-fix response can't erase a
     * good fix.
     */
    val latitude: Double?,
    val longitude: Double?,
    /** Altitude in meters for the fix above, or null when the node reported none. */
    val altitude: Double?,
) {
    // ByteArray fields have reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeStatusSnapshotEntity) return false
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
        result = 31 * result + (batteryMillivolts ?: 0)
        result = 31 * result + (lastSNR?.hashCode() ?: 0)
        result = 31 * result + (lastRSSI ?: 0)
        result = 31 * result + (noiseFloor ?: 0)
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
        result = 31 * result + (postedCount ?: 0)
        result = 31 * result + (postPushCount ?: 0)
        result = 31 * result + (neighborSnapshots?.hashCode() ?: 0)
        result = 31 * result + (telemetryEntries?.hashCode() ?: 0)
        result = 31 * result + (latitude?.hashCode() ?: 0)
        result = 31 * result + (longitude?.hashCode() ?: 0)
        result = 31 * result + (altitude?.hashCode() ?: 0)
        return result
    }
}

fun NodeStatusSnapshotEntity.toDto(): NodeStatusSnapshotDto = NodeStatusSnapshotDto(
    id = id,
    timestamp = timestamp,
    nodePublicKey = nodePublicKey,
    batteryMillivolts = batteryMillivolts?.toUShort(),
    lastSNR = lastSNR,
    lastRSSI = lastRSSI?.toShort(),
    noiseFloor = noiseFloor?.toShort(),
    uptimeSeconds = uptimeSeconds?.toUInt(),
    rxAirtimeSeconds = rxAirtimeSeconds?.toUInt(),
    packetsSent = packetsSent?.toUInt(),
    packetsReceived = packetsReceived?.toUInt(),
    receiveErrors = receiveErrors?.toUInt(),
    sentDirect = sentDirect?.toUInt(),
    sentFlood = sentFlood?.toUInt(),
    receivedDirect = receivedDirect?.toUInt(),
    receivedFlood = receivedFlood?.toUInt(),
    directDuplicates = directDuplicates?.toUInt(),
    floodDuplicates = floodDuplicates?.toUInt(),
    postedCount = postedCount?.toUShort(),
    postPushCount = postPushCount?.toUShort(),
    neighborSnapshots = neighborSnapshots,
    telemetryEntries = telemetryEntries,
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
)

fun NodeStatusSnapshotDto.toEntity(): NodeStatusSnapshotEntity = NodeStatusSnapshotEntity(
    id = id,
    timestamp = timestamp,
    nodePublicKey = nodePublicKey,
    batteryMillivolts = batteryMillivolts?.toInt(),
    lastSNR = lastSNR,
    lastRSSI = lastRSSI?.toInt(),
    noiseFloor = noiseFloor?.toInt(),
    uptimeSeconds = uptimeSeconds?.toLong(),
    rxAirtimeSeconds = rxAirtimeSeconds?.toLong(),
    packetsSent = packetsSent?.toLong(),
    packetsReceived = packetsReceived?.toLong(),
    receiveErrors = receiveErrors?.toLong(),
    sentDirect = sentDirect?.toLong(),
    sentFlood = sentFlood?.toLong(),
    receivedDirect = receivedDirect?.toLong(),
    receivedFlood = receivedFlood?.toLong(),
    directDuplicates = directDuplicates?.toLong(),
    floodDuplicates = floodDuplicates?.toLong(),
    postedCount = postedCount?.toInt(),
    postPushCount = postPushCount?.toInt(),
    neighborSnapshots = neighborSnapshots,
    telemetryEntries = telemetryEntries,
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
)
