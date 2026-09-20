// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup.dto

import com.meshcoretwo.services.persistence.DeviceDto
import org.json.JSONObject

fun DeviceDto.toBackupJson(): JSONObject = JSONObject().apply {
    putUuid("id", id)
    putUuid("radioID", radioID)
    putHex("publicKey", publicKey)
    put("nodeName", nodeName)
    put("firmwareVersion", firmwareVersion.toInt())
    put("firmwareVersionString", firmwareVersionString)
    put("manufacturerName", manufacturerName)
    put("buildDate", buildDate)
    put("maxContacts", maxContacts.toInt())
    put("maxChannels", maxChannels.toInt())
    put("frequency", frequency.toLong())
    put("bandwidth", bandwidth.toLong())
    put("spreadingFactor", spreadingFactor.toInt())
    put("codingRate", codingRate.toInt())
    put("txPower", txPower.toInt())
    put("maxTxPower", maxTxPower.toInt())
    put("latitude", latitude)
    put("longitude", longitude)
    put("blePin", blePin.toLong())
    putInstant("lastConnected", lastConnected)
    put("lastContactSync", lastContactSync.toLong())
    put("isActive", isActive)
    putStringOrNull("ocvPreset", ocvPreset)
    putStringOrNull("appliedRadioPresetID", appliedRadioPresetID)
    putStringOrNull("customOCVArrayString", customOCVArrayString)
    put("pathHashMode", pathHashMode.toInt())
    putStringOrNull("bleAddress", bleAddress)
    putStringOrNull("wifiHost", wifiHost)
    putIntOrNull("wifiPort", wifiPort)
    put("manualAddContacts", manualAddContacts)
    put("multiAcks", multiAcks.toInt())
    put("telemetryModeBase", telemetryModeBase.toInt())
    put("telemetryModeLocation", telemetryModeLocation.toInt())
    put("telemetryModeEnvironment", telemetryModeEnvironment.toInt())
    put("advertLocationPolicy", advertLocationPolicy.toInt())
    put("autoAddConfig", autoAddConfig.toInt())
    put("autoAddMaxHops", autoAddMaxHops.toInt())
    put("clientRepeat", clientRepeat)
    putUIntOrNull("preRepeatFrequency", preRepeatFrequency)
    putUIntOrNull("preRepeatBandwidth", preRepeatBandwidth)
    putUByteOrNull("preRepeatSpreadingFactor", preRepeatSpreadingFactor)
    putUByteOrNull("preRepeatCodingRate", preRepeatCodingRate)
    putStringOrNull("defaultFloodScopeName", defaultFloodScopeName)
    putStringList("knownRegions", knownRegions)
}

fun JSONObject.toDeviceDto(): DeviceDto = DeviceDto(
    id = getUuid("id"),
    radioID = getUuid("radioID"),
    publicKey = getHex("publicKey"),
    nodeName = getString("nodeName"),
    firmwareVersion = getInt("firmwareVersion").toUByte(),
    firmwareVersionString = getString("firmwareVersionString"),
    manufacturerName = getString("manufacturerName"),
    buildDate = getString("buildDate"),
    maxContacts = getInt("maxContacts").toUShort(),
    maxChannels = getInt("maxChannels").toUByte(),
    frequency = getLong("frequency").toUInt(),
    bandwidth = getLong("bandwidth").toUInt(),
    spreadingFactor = getInt("spreadingFactor").toUByte(),
    codingRate = getInt("codingRate").toUByte(),
    txPower = getInt("txPower").toByte(),
    maxTxPower = getInt("maxTxPower").toByte(),
    latitude = getDouble("latitude"),
    longitude = getDouble("longitude"),
    blePin = getLong("blePin").toUInt(),
    lastConnected = getInstant("lastConnected"),
    lastContactSync = getLong("lastContactSync").toUInt(),
    isActive = getBoolean("isActive"),
    ocvPreset = getStringOrNull("ocvPreset"),
    appliedRadioPresetID = getStringOrNull("appliedRadioPresetID"),
    customOCVArrayString = getStringOrNull("customOCVArrayString"),
    pathHashMode = getInt("pathHashMode").toUByte(),
    bleAddress = getStringOrNull("bleAddress"),
    wifiHost = getStringOrNull("wifiHost"),
    wifiPort = getIntOrNull("wifiPort"),
    manualAddContacts = getBoolean("manualAddContacts"),
    multiAcks = getInt("multiAcks").toUByte(),
    telemetryModeBase = getInt("telemetryModeBase").toUByte(),
    telemetryModeLocation = getInt("telemetryModeLocation").toUByte(),
    telemetryModeEnvironment = getInt("telemetryModeEnvironment").toUByte(),
    advertLocationPolicy = getInt("advertLocationPolicy").toUByte(),
    autoAddConfig = getInt("autoAddConfig").toUByte(),
    autoAddMaxHops = getInt("autoAddMaxHops").toUByte(),
    clientRepeat = getBoolean("clientRepeat"),
    preRepeatFrequency = getUIntOrNull("preRepeatFrequency"),
    preRepeatBandwidth = getUIntOrNull("preRepeatBandwidth"),
    preRepeatSpreadingFactor = getUByteOrNull("preRepeatSpreadingFactor"),
    preRepeatCodingRate = getUByteOrNull("preRepeatCodingRate"),
    defaultFloodScopeName = getStringOrNull("defaultFloodScopeName"),
    knownRegions = getStringList("knownRegions"),
)
