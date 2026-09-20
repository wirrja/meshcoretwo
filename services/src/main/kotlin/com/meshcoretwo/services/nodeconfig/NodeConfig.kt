// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.nodeconfig

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON-serializable node configuration, compatible with the official MeshCore companion app
 * format. Ported from `NodeConfig.swift`'s `MeshCoreNodeConfig` (a `Codable` struct there; here a
 * mutable `data class` plus [toJson]/[fromJson] using `org.json`, matching the only other
 * JSON-shaped persistence in this codebase — see
 * [com.meshcoretwo.services.InlineImageDimensionsStore]'s class doc for why `org.json` over
 * `kotlinx.serialization`). Field names use `snake_case` on the wire (companion-app
 * compatibility) via explicit keys in [toJson]/[fromJson] rather than a naming-strategy library.
 */
data class MeshCoreNodeConfig(
    var name: String? = null,
    var publicKey: String? = null,
    var privateKey: String? = null,
    var radioSettings: RadioSettings? = null,
    var positionSettings: PositionSettings? = null,
    var otherSettings: OtherSettings? = null,
    var channels: List<ChannelConfig>? = null,
    var contacts: List<ContactConfig>? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name ?: JSONObject.NULL)
        put("public_key", publicKey ?: JSONObject.NULL)
        put("private_key", privateKey ?: JSONObject.NULL)
        put("radio_settings", radioSettings?.toJson() ?: JSONObject.NULL)
        put("position_settings", positionSettings?.toJson() ?: JSONObject.NULL)
        put("other_settings", otherSettings?.toJson() ?: JSONObject.NULL)
        put("channels", channels?.let { list -> JSONArray().apply { list.forEach { put(it.toJson()) } } } ?: JSONObject.NULL)
        put("contacts", contacts?.let { list -> JSONArray().apply { list.forEach { put(it.toJson()) } } } ?: JSONObject.NULL)
    }

    // MARK: - Radio Settings

    data class RadioSettings(
        /** Frequency in kHz (e.g. 910525 = 910.525 MHz) */
        val frequency: UInt,
        /** Bandwidth in Hz (e.g. 62500 = 62.5 kHz) */
        val bandwidth: UInt,
        val spreadingFactor: UByte,
        val codingRate: UByte,
        /** Transmit power in dBm (may be negative) */
        val txPower: Byte,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("frequency", frequency.toLong())
            put("bandwidth", bandwidth.toLong())
            put("spreading_factor", spreadingFactor.toInt())
            put("coding_rate", codingRate.toInt())
            put("tx_power", txPower.toInt())
        }

        companion object {
            fun fromJson(json: JSONObject): RadioSettings = RadioSettings(
                frequency = json.getLong("frequency").toUInt(),
                bandwidth = json.getLong("bandwidth").toUInt(),
                spreadingFactor = json.getInt("spreading_factor").toUByte(),
                codingRate = json.getInt("coding_rate").toUByte(),
                txPower = json.getInt("tx_power").toByte(),
            )
        }
    }

    // MARK: - Position Settings

    data class PositionSettings(val latitude: String, val longitude: String) {
        /** Both lat and lon are zero (likely unset). */
        val isZero: Boolean get() = (latitude.toDoubleOrNull() ?: 0.0) == 0.0 && (longitude.toDoubleOrNull() ?: 0.0) == 0.0

        fun toJson(): JSONObject = JSONObject().apply {
            put("latitude", latitude)
            put("longitude", longitude)
        }

        companion object {
            fun fromJson(json: JSONObject): PositionSettings =
                PositionSettings(latitude = json.getString("latitude"), longitude = json.getString("longitude"))
        }
    }

    // MARK: - Other Settings

    /**
     * Other device parameters. Only `manual_add_contacts` and `advert_location_policy` are
     * exported, matching the official companion app format. All 7 fields are decoded on import
     * for forward compatibility.
     */
    data class OtherSettings(
        var manualAddContacts: UByte? = null,
        var advertLocationPolicy: UByte? = null,
        var telemetryModeBase: UByte? = null,
        var telemetryModeLocation: UByte? = null,
        var telemetryModeEnvironment: UByte? = null,
        var multiAcks: UByte? = null,
        var advertisementType: UByte? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("manual_add_contacts", manualAddContacts?.toInt() ?: JSONObject.NULL)
            put("advert_location_policy", advertLocationPolicy?.toInt() ?: JSONObject.NULL)
        }

        companion object {
            fun fromJson(json: JSONObject): OtherSettings = OtherSettings(
                manualAddContacts = json.optUByteOrNull("manual_add_contacts"),
                advertLocationPolicy = json.optUByteOrNull("advert_location_policy"),
                telemetryModeBase = json.optUByteOrNull("telemetry_mode_base"),
                telemetryModeLocation = json.optUByteOrNull("telemetry_mode_location"),
                telemetryModeEnvironment = json.optUByteOrNull("telemetry_mode_environment"),
                multiAcks = json.optUByteOrNull("multi_acks"),
                advertisementType = json.optUByteOrNull("advertisement_type"),
            )
        }
    }

    // MARK: - Channel Config

    data class ChannelConfig(
        val name: String,
        /** Hex-encoded 16-byte secret (32 hex characters). */
        val secret: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("secret", secret)
        }

        companion object {
            fun fromJson(json: JSONObject): ChannelConfig =
                ChannelConfig(name = json.getString("name"), secret = json.getString("secret"))
        }
    }

    // MARK: - Contact Config

    data class ContactConfig(
        val type: UByte,
        val name: String,
        /** App-local nickname (not part of wire protocol). Decoded for companion-app compatibility, never imported to firmware. */
        val customName: String? = null,
        /** Hex-encoded 32-byte public key (64 hex characters) */
        val publicKey: String,
        val flags: UByte,
        val latitude: String,
        val longitude: String,
        val lastAdvert: UInt,
        /** Read-only; firmware sets this value */
        val lastModified: UInt,
        /** Hex-encoded path data, or null for no path */
        val outPath: String? = null,
        /** Path hash mode (0=1-byte, 1=2-byte, 2=3-byte). Null in configs from older versions. */
        val pathHashMode: UByte? = null,
    ) {
        /** Encodes with explicit `null` for `customName`/`outPath`, matching the official companion app JSON format. */
        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type.toInt())
            put("name", name)
            put("custom_name", customName ?: JSONObject.NULL)
            put("public_key", publicKey)
            put("flags", flags.toInt())
            put("latitude", latitude)
            put("longitude", longitude)
            put("last_advert", lastAdvert.toLong())
            put("last_modified", lastModified.toLong())
            put("out_path", outPath ?: JSONObject.NULL)
            pathHashMode?.let { put("path_hash_mode", it.toInt()) }
        }

        companion object {
            fun fromJson(json: JSONObject): ContactConfig = ContactConfig(
                type = json.getInt("type").toUByte(),
                name = json.getString("name"),
                customName = json.optStringOrNull("custom_name"),
                publicKey = json.getString("public_key"),
                flags = json.getInt("flags").toUByte(),
                latitude = json.getString("latitude"),
                longitude = json.getString("longitude"),
                lastAdvert = json.getLong("last_advert").toUInt(),
                lastModified = json.getLong("last_modified").toUInt(),
                outPath = json.optStringOrNull("out_path"),
                pathHashMode = json.optUByteOrNull("path_hash_mode"),
            )
        }
    }

    companion object {
        fun fromJson(json: JSONObject): MeshCoreNodeConfig = MeshCoreNodeConfig(
            name = json.optStringOrNull("name"),
            publicKey = json.optStringOrNull("public_key"),
            privateKey = json.optStringOrNull("private_key"),
            radioSettings = json.optJSONObjectOrNull("radio_settings")?.let { RadioSettings.fromJson(it) },
            positionSettings = json.optJSONObjectOrNull("position_settings")?.let { PositionSettings.fromJson(it) },
            otherSettings = json.optJSONObjectOrNull("other_settings")?.let { OtherSettings.fromJson(it) },
            channels = json.optJSONArrayOrNull("channels")?.let { array ->
                (0 until array.length()).map { ChannelConfig.fromJson(array.getJSONObject(it)) }
            },
            contacts = json.optJSONArrayOrNull("contacts")?.let { array ->
                (0 until array.length()).map { ContactConfig.fromJson(array.getJSONObject(it)) }
            },
        )
    }
}

private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
private fun JSONObject.optUByteOrNull(key: String): UByte? = if (has(key) && !isNull(key)) getInt(key).toUByte() else null
private fun JSONObject.optJSONObjectOrNull(key: String): JSONObject? = if (has(key) && !isNull(key)) getJSONObject(key) else null
private fun JSONObject.optJSONArrayOrNull(key: String): JSONArray? = if (has(key) && !isNull(key)) getJSONArray(key) else null

// MARK: - Section Selection

/** Controls which config sections to include in export/import. */
data class ConfigSections(
    var nodeIdentity: Boolean = false,
    var radioSettings: Boolean = false,
    var positionSettings: Boolean = false,
    var otherSettings: Boolean = false,
    var channels: Boolean = false,
    var contacts: Boolean = false,
) {
    /** True when all sections are selected. */
    val allSelected: Boolean get() = nodeIdentity && radioSettings && positionSettings && otherSettings && channels && contacts

    /** True when at least one section is selected. */
    val anySectionSelected: Boolean get() = nodeIdentity || radioSettings || positionSettings || otherSettings || channels || contacts

    fun selectAll() {
        nodeIdentity = true
        radioSettings = true
        positionSettings = true
        otherSettings = true
        channels = true
        contacts = true
    }

    fun deselectAll() {
        nodeIdentity = false
        radioSettings = false
        positionSettings = false
        otherSettings = false
        channels = false
        contacts = false
    }
}
