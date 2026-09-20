// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Configuration and state information for the local mesh node.
 *
 * Typically retrieved from the device after the initial application startup sequence.
 */
class SelfInfo(
    /** The type of advertisement used by the device. */
    val advertisementType: UByte,
    /** The current transmit power level in dBm (may be negative). */
    val txPower: Byte,
    /** The maximum supported transmit power level in dBm. */
    val maxTxPower: Byte,
    /** The node's 32-byte public key. */
    val publicKey: ByteArray,
    /** The current latitude coordinate. */
    val latitude: Double,
    /** The current longitude coordinate. */
    val longitude: Double,
    /** Whether multiple acknowledgments are enabled. */
    val multiAcks: UByte,
    /** The policy for location sharing in advertisements. */
    val advertisementLocationPolicy: UByte,
    /** The environment telemetry reporting mode. */
    val telemetryModeEnvironment: UByte,
    /** The location telemetry reporting mode. */
    val telemetryModeLocation: UByte,
    /** The base telemetry reporting mode. */
    val telemetryModeBase: UByte,
    /** Whether contacts must be added manually. */
    val manualAddContacts: Boolean,
    /** The radio center frequency in MHz. */
    val radioFrequency: Double,
    /** The radio bandwidth in kHz. */
    val radioBandwidth: Double,
    /** The radio spreading factor. */
    val radioSpreadingFactor: UByte,
    /** The radio coding rate. */
    val radioCodingRate: UByte,
    /** The user-defined name for this device. */
    val name: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SelfInfo) return false
        return advertisementType == other.advertisementType &&
            txPower == other.txPower &&
            maxTxPower == other.maxTxPower &&
            publicKey.contentEquals(other.publicKey) &&
            latitude == other.latitude &&
            longitude == other.longitude &&
            multiAcks == other.multiAcks &&
            advertisementLocationPolicy == other.advertisementLocationPolicy &&
            telemetryModeEnvironment == other.telemetryModeEnvironment &&
            telemetryModeLocation == other.telemetryModeLocation &&
            telemetryModeBase == other.telemetryModeBase &&
            manualAddContacts == other.manualAddContacts &&
            radioFrequency == other.radioFrequency &&
            radioBandwidth == other.radioBandwidth &&
            radioSpreadingFactor == other.radioSpreadingFactor &&
            radioCodingRate == other.radioCodingRate &&
            name == other.name
    }

    override fun hashCode(): Int {
        var result = advertisementType.hashCode()
        result = 31 * result + txPower.hashCode()
        result = 31 * result + maxTxPower.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        result = 31 * result + multiAcks.hashCode()
        result = 31 * result + advertisementLocationPolicy.hashCode()
        result = 31 * result + telemetryModeEnvironment.hashCode()
        result = 31 * result + telemetryModeLocation.hashCode()
        result = 31 * result + telemetryModeBase.hashCode()
        result = 31 * result + manualAddContacts.hashCode()
        result = 31 * result + radioFrequency.hashCode()
        result = 31 * result + radioBandwidth.hashCode()
        result = 31 * result + radioSpreadingFactor.hashCode()
        result = 31 * result + radioCodingRate.hashCode()
        result = 31 * result + name.hashCode()
        return result
    }
}

/** The hardware capabilities and firmware details of the mesh device. */
data class DeviceCapabilities(
    /** The numeric firmware version. */
    val firmwareVersion: UByte,
    /** The maximum number of contacts that can be stored on the device. */
    val maxContacts: Int,
    /** The maximum number of channels supported by the device. */
    val maxChannels: Int,
    /** The Bluetooth PIN used for pairing, if applicable. */
    val blePin: UInt,
    /** The firmware build identifier string. */
    val firmwareBuild: String,
    /** The hardware model name. */
    val model: String,
    /** The semantic version string. */
    val version: String,
    /** Whether client repeat mode is enabled (v9+ firmware). */
    val clientRepeat: Boolean = false,
    /** The path hash mode (0=1-byte, 1=2-byte, 2=3-byte hashes). Firmware v10+. */
    val pathHashMode: UByte = 0u,
) {
    /** Whether `CMD_SET_PATH_HASH_MODE` is available (firmware v10+). */
    val supportsPathHashMode: Boolean
        get() = firmwareVersion >= 10u

    /** The hash size per hop in bytes (1, 2, or 3), derived from [pathHashMode]. */
    val hashSize: Int
        get() = pathHashMode.toInt() + 1
}

/**
 * The battery status and storage utilization of the device.
 *
 * Per the MeshCore protocol, battery level is reported in millivolts rather than a percentage.
 */
data class BatteryInfo(
    /**
     * The raw battery level in millivolts (e.g., 3700 for 3.7V).
     *
     * Convert to a percentage based on the specific hardware model's battery curve.
     */
    val level: Int,
    /** The amount of storage currently in use, in kilobytes. */
    val usedStorageKB: Int? = null,
    /** The total storage capacity available on the device, in kilobytes. */
    val totalStorageKB: Int? = null,
)
