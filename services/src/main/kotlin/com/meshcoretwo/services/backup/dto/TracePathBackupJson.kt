// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup.dto

import com.meshcoretwo.services.persistence.TracePathDto
import com.meshcoretwo.services.persistence.TracePathRunDto
import org.json.JSONObject

fun TracePathDto.toBackupJson(): JSONObject = JSONObject().apply {
    putUuid("id", id)
    putUuid("radioID", radioID)
    put("name", name)
    putHex("pathBytes", pathBytes)
    put("hashSize", hashSize)
    putInstant("createdDate", createdDate)
    putObjectList("runs", runs) { it.toBackupJson() }
}

fun JSONObject.toTracePathDto(): TracePathDto = TracePathDto(
    id = getUuid("id"),
    radioID = getUuid("radioID"),
    name = getString("name"),
    pathBytes = getHex("pathBytes"),
    hashSize = getInt("hashSize"),
    createdDate = getInstant("createdDate"),
    runs = getObjectList("runs") { it.toTracePathRunDto() },
)

fun TracePathRunDto.toBackupJson(): JSONObject = JSONObject().apply {
    putUuid("id", id)
    putInstant("date", date)
    put("success", success)
    put("roundTripMs", roundTripMs)
    putDoubleList("hopsSNR", hopsSNR)
}

fun JSONObject.toTracePathRunDto(): TracePathRunDto = TracePathRunDto(
    id = getUuid("id"),
    date = getInstant("date"),
    success = getBoolean("success"),
    roundTripMs = getInt("roundTripMs"),
    hopsSNR = getDoubleList("hopsSNR"),
)
