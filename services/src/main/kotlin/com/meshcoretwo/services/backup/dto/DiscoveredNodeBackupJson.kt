// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup.dto

import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import org.json.JSONObject

fun DiscoveredNodeDto.toBackupJson(): JSONObject = JSONObject().apply {
    putUuid("id", id)
    putUuid("radioID", radioID)
    putHex("publicKey", publicKey)
    put("name", name)
    put("typeRawValue", typeRawValue.toInt())
    putInstant("lastHeard", lastHeard)
    put("lastAdvertTimestamp", lastAdvertTimestamp.toLong())
    put("latitude", latitude)
    put("longitude", longitude)
    put("outPathLength", outPathLength.toInt())
    putHex("outPath", outPath)
    putIntOrNull("inboundHopCount", inboundHopCount)
    putUIntOrNull("inboundHopAdvertTimestamp", inboundHopAdvertTimestamp)
}

fun JSONObject.toDiscoveredNodeDto(): DiscoveredNodeDto = DiscoveredNodeDto(
    id = getUuid("id"),
    radioID = getUuid("radioID"),
    publicKey = getHex("publicKey"),
    name = getString("name"),
    typeRawValue = getInt("typeRawValue").toUByte(),
    lastHeard = getInstant("lastHeard"),
    lastAdvertTimestamp = getLong("lastAdvertTimestamp").toUInt(),
    latitude = getDouble("latitude"),
    longitude = getDouble("longitude"),
    outPathLength = getInt("outPathLength").toUByte(),
    outPath = getHex("outPath"),
    inboundHopCount = getIntOrNull("inboundHopCount"),
    inboundHopAdvertTimestamp = getUIntOrNull("inboundHopAdvertTimestamp"),
)
