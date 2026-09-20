// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup.dto

import com.meshcoretwo.services.persistence.MessageRepeatDto
import org.json.JSONObject

fun MessageRepeatDto.toBackupJson(): JSONObject = JSONObject().apply {
    putUuid("id", id)
    putUuid("messageID", messageID)
    putInstant("receivedAt", receivedAt)
    putHex("pathNodes", pathNodes)
    put("pathLength", pathLength.toInt())
    putDoubleOrNull("snr", snr)
    putIntOrNull("rssi", rssi)
    putUuidOrNull("rxLogEntryID", rxLogEntryID)
}

fun JSONObject.toMessageRepeatDto(): MessageRepeatDto = MessageRepeatDto(
    id = getUuid("id"),
    messageID = getUuid("messageID"),
    receivedAt = getInstant("receivedAt"),
    pathNodes = getHex("pathNodes"),
    pathLength = getInt("pathLength").toUByte(),
    snr = getDoubleOrNull("snr"),
    rssi = getIntOrNull("rssi"),
    rxLogEntryID = getUuidOrNull("rxLogEntryID"),
)
