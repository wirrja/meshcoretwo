// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup.dto

import com.meshcoretwo.services.persistence.BlockedChannelSenderDto
import org.json.JSONObject

fun BlockedChannelSenderDto.toBackupJson(): JSONObject = JSONObject().apply {
    putUuid("id", id)
    put("name", name)
    putUuid("radioID", radioID)
    putInstant("dateBlocked", dateBlocked)
}

fun JSONObject.toBlockedChannelSenderDto(): BlockedChannelSenderDto = BlockedChannelSenderDto(
    id = getUuid("id"),
    name = getString("name"),
    radioID = getUuid("radioID"),
    dateBlocked = getInstant("dateBlocked"),
)
