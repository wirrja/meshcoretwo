// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup.dto

import com.meshcoretwo.services.persistence.NeighborSnapshotEntry
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import com.meshcoretwo.services.persistence.TelemetrySnapshotEntry
import org.json.JSONObject

fun NodeStatusSnapshotDto.toBackupJson(): JSONObject = JSONObject().apply {
    putUuid("id", id)
    putInstant("timestamp", timestamp)
    putHex("nodePublicKey", nodePublicKey)
    putUShortOrNull("batteryMillivolts", batteryMillivolts)
    putDoubleOrNull("lastSNR", lastSNR)
    putShortOrNull("lastRSSI", lastRSSI)
    putShortOrNull("noiseFloor", noiseFloor)
    putUIntOrNull("uptimeSeconds", uptimeSeconds)
    putUIntOrNull("rxAirtimeSeconds", rxAirtimeSeconds)
    putUIntOrNull("packetsSent", packetsSent)
    putUIntOrNull("packetsReceived", packetsReceived)
    putUIntOrNull("receiveErrors", receiveErrors)
    putUIntOrNull("sentDirect", sentDirect)
    putUIntOrNull("sentFlood", sentFlood)
    putUIntOrNull("receivedDirect", receivedDirect)
    putUIntOrNull("receivedFlood", receivedFlood)
    putUIntOrNull("directDuplicates", directDuplicates)
    putUIntOrNull("floodDuplicates", floodDuplicates)
    putUShortOrNull("postedCount", postedCount)
    putUShortOrNull("postPushCount", postPushCount)
    putObjectListOrNull("neighborSnapshots", neighborSnapshots) { it.toBackupJson() }
    putObjectListOrNull("telemetryEntries", telemetryEntries) { it.toBackupJson() }
    putDoubleOrNull("latitude", latitude)
    putDoubleOrNull("longitude", longitude)
    putDoubleOrNull("altitude", altitude)
}

fun JSONObject.toNodeStatusSnapshotDto(): NodeStatusSnapshotDto = NodeStatusSnapshotDto(
    id = getUuid("id"),
    timestamp = getInstant("timestamp"),
    nodePublicKey = getHex("nodePublicKey"),
    batteryMillivolts = getUShortOrNull("batteryMillivolts"),
    lastSNR = getDoubleOrNull("lastSNR"),
    lastRSSI = getShortOrNull("lastRSSI"),
    noiseFloor = getShortOrNull("noiseFloor"),
    uptimeSeconds = getUIntOrNull("uptimeSeconds"),
    rxAirtimeSeconds = getUIntOrNull("rxAirtimeSeconds"),
    packetsSent = getUIntOrNull("packetsSent"),
    packetsReceived = getUIntOrNull("packetsReceived"),
    receiveErrors = getUIntOrNull("receiveErrors"),
    sentDirect = getUIntOrNull("sentDirect"),
    sentFlood = getUIntOrNull("sentFlood"),
    receivedDirect = getUIntOrNull("receivedDirect"),
    receivedFlood = getUIntOrNull("receivedFlood"),
    directDuplicates = getUIntOrNull("directDuplicates"),
    floodDuplicates = getUIntOrNull("floodDuplicates"),
    postedCount = getUShortOrNull("postedCount"),
    postPushCount = getUShortOrNull("postPushCount"),
    neighborSnapshots = getObjectListOrNull("neighborSnapshots") { it.toNeighborSnapshotEntry() },
    telemetryEntries = getObjectListOrNull("telemetryEntries") { it.toTelemetrySnapshotEntry() },
    latitude = getDoubleOrNull("latitude"),
    longitude = getDoubleOrNull("longitude"),
    altitude = getDoubleOrNull("altitude"),
)

fun NeighborSnapshotEntry.toBackupJson(): JSONObject = JSONObject().apply {
    putHex("publicKeyPrefix", publicKeyPrefix)
    put("snr", snr)
    put("secondsAgo", secondsAgo)
}

fun JSONObject.toNeighborSnapshotEntry(): NeighborSnapshotEntry = NeighborSnapshotEntry(
    publicKeyPrefix = getHex("publicKeyPrefix"),
    snr = getDouble("snr"),
    secondsAgo = getInt("secondsAgo"),
)

fun TelemetrySnapshotEntry.toBackupJson(): JSONObject = JSONObject().apply {
    put("channel", channel)
    put("type", type)
    put("value", value)
}

fun JSONObject.toTelemetrySnapshotEntry(): TelemetrySnapshotEntry = TelemetrySnapshotEntry(
    channel = getInt("channel"),
    type = getString("type"),
    value = getDouble("value"),
)
