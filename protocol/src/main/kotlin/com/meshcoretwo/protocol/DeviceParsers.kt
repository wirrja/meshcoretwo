// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

// Ported from Parsers+Device.swift.

/** Parser for local device configuration info. */
object SelfInfoParser {
    /**
     * Parses self info response (57+ bytes).
     *
     * ### Binary Format
     * - Offset 0-2 (3 bytes): Adv type, Tx power, Max Tx power
     * - Offset 3 (32 bytes): Public Key
     * - Offset 35 (8 bytes): Lat/Lon scaled by 1e6 (Int32 LE)
     * - Offset 43-45 (3 bytes): Multi-ACKs, Adv policy, Telemetry mode
     * - Offset 46 (1 byte): Manual add contacts flag
     * - Offset 47 (8 bytes): Radio Freq/BW scaled by 1000 (UInt32 LE)
     * - Offset 55-56 (2 bytes): Spreading factor, Coding rate
     * - Offset 57+ (N bytes): Local name (UTF-8)
     */
    fun parse(data: ByteArray): MeshEvent {
        if (data.size < PacketSize.SELF_INFO_MINIMUM) {
            return MeshEvent.ParseFailure(data, "SelfInfo response too short: ${data.size} < ${PacketSize.SELF_INFO_MINIMUM}")
        }

        var offset = 0
        val advType = data[offset].toUByte(); offset += 1
        val txPower = data[offset]; offset += 1
        val maxTxPower = data[offset]; offset += 1
        val publicKey = data.copyOfRange(offset, offset + 32); offset += 32
        val lat = data.readInt32LE(offset) / 1_000_000.0; offset += 4
        val lon = data.readInt32LE(offset) / 1_000_000.0; offset += 4
        val multiAcks = data[offset].toUByte(); offset += 1
        val advLocPolicy = data[offset].toUByte(); offset += 1
        val telemetryMode = data[offset].toUByte(); offset += 1
        val manualAdd = (data[offset].toInt() and 0xFF) > 0; offset += 1
        val radioFreq = data.readUInt32LE(offset).toDouble() / 1000.0; offset += 4
        val radioBW = data.readUInt32LE(offset).toDouble() / 1000.0; offset += 4
        val radioSF = data[offset].toUByte(); offset += 1
        val radioCR = data[offset].toUByte(); offset += 1
        val name = data.copyOfRange(offset, data.size).decodeUtf8Strict()?.trimControlCharacters() ?: ""

        val info = SelfInfo(
            advertisementType = advType,
            txPower = txPower,
            maxTxPower = maxTxPower,
            publicKey = publicKey,
            latitude = lat,
            longitude = lon,
            multiAcks = multiAcks,
            advertisementLocationPolicy = advLocPolicy,
            telemetryModeEnvironment = ((telemetryMode.toInt() shr 4) and 0b11).toUByte(),
            telemetryModeLocation = ((telemetryMode.toInt() shr 2) and 0b11).toUByte(),
            telemetryModeBase = (telemetryMode.toInt() and 0b11).toUByte(),
            manualAddContacts = manualAdd,
            radioFrequency = radioFreq,
            radioBandwidth = radioBW,
            radioSpreadingFactor = radioSF,
            radioCodingRate = radioCR,
            name = name,
        )
        return MeshEvent.SelfInfoEvent(info)
    }
}

/** Parser for device capabilities and versioning. */
object DeviceInfoParser {
    /**
     * Parses device info with version-specific handling.
     *
     * ### Binary Format (v3+)
     * - Offset 0 (1 byte): Firmware version
     * - Offset 1 (1 byte): Max contacts (stored as count/2)
     * - Offset 2 (1 byte): Max channels
     * - Offset 3 (4 bytes): BLE PIN (UInt32 LE)
     * - Offset 7 (12 bytes): Firmware build string (UTF-8)
     * - Offset 19 (40 bytes): Model string (UTF-8)
     * - Offset 59 (20 bytes): Hardware version string (UTF-8)
     */
    fun parse(data: ByteArray): MeshEvent {
        if (data.isEmpty()) {
            return MeshEvent.ParseFailure(data, "DeviceInfo response empty")
        }

        val fwVer = data[0].toUByte()
        var offset = 1
        var maxContacts: Int? = null
        var maxChannels: Int? = null
        var blePin: UInt? = null
        var fwBuild: String? = null
        var model: String? = null
        var version: String? = null

        if (fwVer >= 3u.toUByte() && data.size < PacketSize.DEVICE_INFO_V3_FULL) {
            return MeshEvent.ParseFailure(
                data,
                "DeviceInfo v$fwVer response too short: ${data.size} < ${PacketSize.DEVICE_INFO_V3_FULL}",
            )
        }

        // v3+ format: fwBuild=12, model=40, version=20 bytes
        if (fwVer >= 3u.toUByte() && data.size >= PacketSize.DEVICE_INFO_V3_FULL) {
            maxContacts = (data[offset].toInt() and 0xFF) * 2 // Stored as count/2 in firmware.
            offset += 1
            maxChannels = data[offset].toInt() and 0xFF
            offset += 1
            blePin = data.readUInt32LE(offset)
            offset += 4
            fwBuild = data.copyOfRange(offset, offset + 12).decodeUtf8Strict()?.trimControlCharacters()
            offset += 12
            model = data.copyOfRange(offset, offset + 40).decodeUtf8Strict()?.trimControlCharacters()
            offset += 40
            version = data.copyOfRange(offset, offset + 20).decodeUtf8Strict()?.trimControlCharacters()
            offset += 20
        }

        // v9+: client_repeat byte after version string (tolerant -- defaults to false if missing)
        var clientRepeat = false
        if (fwVer >= 9u.toUByte() && offset >= PacketSize.DEVICE_INFO_V3_FULL && data.size > offset) {
            clientRepeat = data[offset] != 0.toByte()
            offset += 1
        }

        // v10+: path_hash_mode byte after client_repeat (tolerant -- defaults to 0 if missing)
        var pathHashMode: UByte = 0u
        if (fwVer >= 10u.toUByte() && offset >= PacketSize.DEVICE_INFO_V3_FULL && data.size > offset) {
            pathHashMode = data[offset].toUByte()
        }

        return MeshEvent.DeviceInfo(
            DeviceCapabilities(
                firmwareVersion = fwVer,
                maxContacts = maxContacts ?: 0,
                maxChannels = maxChannels ?: 0,
                blePin = blePin ?: 0u,
                firmwareBuild = fwBuild ?: "",
                model = model ?: "",
                version = version ?: "",
                clientRepeat = clientRepeat,
                pathHashMode = pathHashMode,
            ),
        )
    }
}

/** Parser for exported private key data. */
object PrivateKeyParser {
    /** Parses the 64-byte private key. */
    fun parse(data: ByteArray): MeshEvent {
        if (data.size < PacketSize.PRIVATE_KEY_MINIMUM) {
            return MeshEvent.ParseFailure(data, "PrivateKey response too short: ${data.size} < ${PacketSize.PRIVATE_KEY_MINIMUM}")
        }
        return MeshEvent.PrivateKey(data.prefixBytes(PacketSize.PRIVATE_KEY_MINIMUM))
    }
}

/** Parser for user-defined custom variables. */
object CustomVarsParser {
    /**
     * Parses custom vars from a comma-separated key:value string.
     *
     * Format: `key1:value1,key2:value2,...`
     */
    fun parse(data: ByteArray): MeshEvent {
        val vars = mutableMapOf<String, String>()

        val rawString = data.decodeUtf8Strict()
        if (rawString == null || rawString.isEmpty()) {
            return MeshEvent.CustomVars(vars)
        }

        val pairs = rawString.split(",")
        for (pair in pairs) {
            val parts = pair.split(":", limit = 2)
            if (parts.size == 2) {
                vars[parts[0]] = parts[1]
            }
        }
        return MeshEvent.CustomVars(vars)
    }
}
