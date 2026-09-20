// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.time.Instant

/**
 * Session operations for reading and writing device configuration: identity, radio parameters,
 * statistics, custom variables, and key management.
 */
interface ConfigurationSessionOps {
    // MARK: - Device Info

    /**
     * Sends the app-start command and returns the device's self info.
     *
     * @throws MeshCoreError if the device doesn't emit `selfInfo`.
     */
    suspend fun sendAppStart(): SelfInfo

    /**
     * Queries the device for its capabilities and system information.
     *
     * @throws MeshCoreError if the device doesn't emit `deviceInfo`.
     */
    suspend fun queryDevice(): DeviceCapabilities

    /**
     * Retrieves the current battery status from the device.
     *
     * @throws MeshCoreError if the device doesn't emit battery info.
     */
    suspend fun getBattery(): BatteryInfo

    /**
     * Reads the device's real-time clock.
     *
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun getTime(): Instant

    /**
     * Sets the device's real-time clock. Firmware rejects backwards moves.
     *
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun setTime(date: Instant)

    // MARK: - Device Configuration

    /**
     * Sets the device's advertised name.
     *
     * @param name The name to advertise (max 32 bytes UTF-8).
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun setName(name: String)

    /**
     * Sets the device's GPS coordinates.
     *
     * @param latitude Latitude in degrees (-90 to 90).
     * @param longitude Longitude in degrees (-180 to 180).
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun setCoordinates(latitude: Double, longitude: Double)

    /**
     * Sets the radio transmission power level.
     *
     * @param power Power level in dBm (range: -9 to 30).
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun setTxPower(power: Byte)

    /**
     * Configures radio parameters for LoRa communication.
     *
     * @param frequency Center frequency in MHz (e.g., 915.0).
     * @param bandwidth Signal bandwidth in kHz (e.g., 125.0, 250.0, 500.0).
     * @param spreadingFactor LoRa spreading factor (5-12, higher = longer range but slower).
     * @param codingRate Error correction coding rate (5-8).
     * @param clientRepeat Whether to enable client repeat mode (v9+ firmware, omitted if `null`).
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun setRadio(
        frequency: Double,
        bandwidth: Double,
        spreadingFactor: UByte,
        codingRate: UByte,
        clientRepeat: Boolean? = null,
    )

    /**
     * Gets the allowed frequency ranges for client repeat mode (v9+ firmware).
     *
     * @throws MeshCoreError if the device doesn't emit repeat-frequency data.
     */
    suspend fun getRepeatFreq(): List<FrequencyRange>

    /**
     * Sets miscellaneous device parameters.
     *
     * @param manualAddContacts Whether contacts require manual approval before adding.
     * @param telemetryModeEnvironment Environment telemetry reporting mode (0-3).
     * @param telemetryModeLocation Location telemetry reporting mode (0-3).
     * @param telemetryModeBase Base telemetry reporting mode (0-3).
     * @param advertisementLocationPolicy Location inclusion policy for advertisements.
     * @param multiAcks Number of acknowledgment retries.
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun setOtherParams(
        manualAddContacts: Boolean,
        telemetryModeEnvironment: UByte,
        telemetryModeLocation: UByte,
        telemetryModeBase: UByte,
        advertisementLocationPolicy: UByte,
        multiAcks: UByte? = null,
    )

    /**
     * Sets the device PIN for administrative access.
     *
     * @param pin 4-digit PIN as a 32-bit unsigned integer.
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun setDevicePin(pin: UInt)

    /**
     * Gets the current auto-add configuration from the device.
     *
     * @throws MeshCoreError if the device doesn't emit auto-add configuration.
     */
    suspend fun getAutoAddConfig(): AutoAddConfig

    /**
     * Sets the auto-add configuration on the device.
     *
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun setAutoAddConfig(config: AutoAddConfig)

    /**
     * Sets the path hash mode on the device.
     *
     * @param mode Hash mode (0=1-byte, 1=2-byte, 2=3-byte hashes).
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun setPathHashMode(mode: UByte)

    // MARK: - Default Flood Scope

    /**
     * Persists the device's default flood scope from a [FloodScope].
     *
     * @param name Display name stored on the device.
     * @param scope The scope to persist. Passing [FloodScope.Disabled] clears the scope.
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun setDefaultFloodScope(name: String, scope: FloodScope)

    /**
     * Fetches the device's persisted default flood scope.
     *
     * Requires firmware v11+; older firmware surfaces the unknown opcode as a device error.
     *
     * @return The persisted scope, or `null` if none is configured.
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun getDefaultFloodScope(): DefaultFloodScope?

    // MARK: - Lifecycle

    /**
     * Reboots the device. The session will be disconnected.
     *
     * @throws MeshTransportError if the command cannot be sent.
     */
    suspend fun reboot()

    /**
     * Performs a factory reset, erasing all device configuration, contacts, and messages.
     *
     * This operation is irreversible.
     *
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun factoryReset()

    // MARK: - Stats

    /**
     * Retrieves core device statistics.
     *
     * @throws MeshCoreError if the device doesn't respond.
     */
    suspend fun getStatsCore(): CoreStats

    /**
     * Retrieves radio statistics.
     *
     * @throws MeshCoreError if the device doesn't respond.
     */
    suspend fun getStatsRadio(): RadioStats

    /**
     * Retrieves packet statistics.
     *
     * @throws MeshCoreError if the device doesn't respond.
     */
    suspend fun getStatsPackets(): PacketStats

    // MARK: - Custom Variables

    /**
     * Retrieves all custom variables stored on the device.
     *
     * @throws MeshCoreError if the device doesn't respond.
     */
    suspend fun getCustomVars(): Map<String, String>

    /**
     * Sets a custom variable on the device.
     *
     * @param key Variable name (max 32 bytes).
     * @param value Variable value (max 256 bytes).
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun setCustomVar(key: String, value: String)

    // MARK: - Key Management and Signing

    /**
     * Exports the device's private key.
     *
     * @return The 32-byte private key.
     * @throws MeshCoreError.FeatureDisabled if export is disabled on the device, or
     *   MeshCoreError.Timeout if the device doesn't respond.
     */
    suspend fun exportPrivateKey(): ByteArray

    /**
     * Imports a private key into the device, replacing its cryptographic identity.
     *
     * @param key The 64-byte expanded private key to import.
     * @throws MeshCoreError if the device rejects or doesn't support key import.
     */
    suspend fun importPrivateKey(key: ByteArray)

    /**
     * Signs data using the device's private key.
     *
     * @param chunkSize Size of each chunk in bytes.
     * @param timeout Optional timeout for the finalization step.
     * @return The cryptographic signature.
     * @throws MeshCoreError if data exceeds device limits or any step times out.
     */
    suspend fun sign(data: ByteArray, chunkSize: Int = 120, timeout: Double? = null): ByteArray
}
