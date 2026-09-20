// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.time.Instant
import kotlin.random.Random

/**
 * Stateless packet builder for constructing MeshCore protocol commands.
 *
 * Provides functions to construct the binary command packets sent to a MeshCore device.
 * Each function returns a [ByteArray] ready to send via the transport layer.
 *
 * ## Protocol Format
 * All commands follow the format:
 * - Byte 0: Command code (see [CommandCode])
 * - Bytes 1+: Command-specific payload
 *
 * Multi-byte integers are little-endian. Strings are UTF-8 encoded.
 */
object PacketBuilder {
    /** Size of a public key in bytes. */
    const val PUBLIC_KEY_SIZE = 32
    /** Size of an expanded private key in bytes (firmware `PRV_KEY_SIZE`). */
    internal const val PRIVATE_KEY_SIZE = 64
    internal const val RAW_DATA_MAX_PATH_BYTES = 64
    internal const val RAW_DATA_MAX_PAYLOAD_BYTES = 184
    /** Flood sentinel for `path_len` fields: firmware treats `0xFF` as "route via flood". */
    val FLOOD_PATH_SENTINEL: UByte = 0xFFu
    /** Maximum payload bytes for `CMD_SEND_CHANNEL_DATA` (`MAX_FRAME_SIZE - 9` per firmware). */
    internal const val CHANNEL_DATA_MAX_PAYLOAD_BYTES = 163
    /** Default-scope name-field width on the wire (31 bytes, zero-padded). */
    internal const val DEFAULT_SCOPE_NAME_FIELD = 31
    /**
     * Default-scope name maximum UTF-8 length (30 bytes; byte 31 is the null terminator
     * firmware's `strlen` relies on at `MyMesh.cpp:1895`).
     */
    internal const val DEFAULT_SCOPE_MAX_NAME_BYTES = 30
    /** Default-scope key width on the wire (16 bytes). */
    internal const val DEFAULT_SCOPE_KEY_BYTES = 16
    /** Fixed-point scale firmware applies to latitude/longitude (degrees x 1e6, stored as Int32). */
    internal const val COORDINATE_SCALE = 1_000_000.0
    /** Fixed-point scale firmware applies to radio frequency and bandwidth (MHz/kHz x 1,000). */
    internal const val RADIO_SCALE = 1000.0
    /** Valid latitude range in degrees. */
    val LATITUDE_RANGE = -90.0..90.0
    /** Valid longitude range in degrees. */
    val LONGITUDE_RANGE = -180.0..180.0
    /** Valid radio frequency range in kHz, matching firmware `CMD_SET_RADIO_PARAMS`. */
    val FREQUENCY_RANGE_KHZ = 150_000u..2_500_000u
    /** Valid radio bandwidth range in Hz, matching firmware `CMD_SET_RADIO_PARAMS`. */
    val BANDWIDTH_RANGE_HZ = 7000u..500_000u
    /** Valid LoRa spreading factor range, matching firmware `CMD_SET_RADIO_PARAMS`. */
    val SPREADING_FACTOR_RANGE = 5..12
    /** Valid LoRa coding rate range, matching firmware `CMD_SET_RADIO_PARAMS`. */
    val CODING_RATE_RANGE = 5..8
    /**
     * Minimum LoRa transmit power in dBm (firmware rejects below this). The upper bound is the
     * device-reported `maxTxPower`, not a fixed constant, so it is supplied per device.
     */
    const val TX_POWER_FLOOR: Byte = -9

    private fun encodePublicKey(publicKey: ByteArray): ByteArray =
        if (publicKey.size >= PUBLIC_KEY_SIZE) {
            publicKey.copyOfRange(0, PUBLIC_KEY_SIZE)
        } else {
            publicKey + ByteArray(PUBLIC_KEY_SIZE - publicKey.size)
        }

    /**
     * Scales a coordinate (degrees) into the firmware's Int32 fixed-point form, clamping to a
     * finite, valid range. A NaN, infinite, or out-of-range value saturates instead of throwing,
     * so no caller can crash the encoder.
     *
     * Public so config-import diffing can compare two coordinates by the integer the device
     * actually persists, sidestepping float-equality noise between values that encode identically.
     */
    fun scaledCoordinate(degrees: Double, range: ClosedRange<Double>): Int {
        val clamped = if (degrees.isFinite()) degrees.coerceIn(range) else 0.0
        return (clamped * COORDINATE_SCALE).toInt()
    }

    /**
     * Scales a radio value (MHz or kHz) by 1,000 into the firmware's UInt32 field, rounding to
     * the nearest unit and clamping to a finite, valid range. `Math.round` matches Swift's
     * `.rounded()` (half away from zero) here because these domain values are always
     * non-negative (frequency/bandwidth).
     */
    internal fun scaledRadioValue(value: Double, range: UIntRange): UInt {
        if (!value.isFinite()) return range.first
        val scaled = Math.round(value * RADIO_SCALE)
        return scaled.coerceIn(range.first.toLong(), range.last.toLong()).toUInt()
    }

    /**
     * Clamps an instant to the firmware's unsigned 32-bit seconds-since-epoch field, saturating
     * pre-1970 and post-2106 dates instead of throwing.
     */
    internal fun epochSeconds32(instant: Instant): UInt {
        val seconds = instant.epochSecond
        if (seconds <= 0) return 0u
        if (seconds >= UInt.MAX_VALUE.toLong()) return UInt.MAX_VALUE
        return seconds.toUInt()
    }

    // MARK: - Device Commands

    /**
     * Builds an appStart command to initialize the session.
     *
     * @param clientId Client identifier string (max 5 characters, will be truncated).
     *
     * ### Binary Format
     * (Per firmware MyMesh.cpp:842-845)
     * - Offset 0 (1 byte): Command code `0x01` (appStart)
     * - Offset 1 (1 byte): Protocol version marker `0x03`
     * - Offset 2 (6 bytes): Reserved padding (ASCII spaces `0x20`)
     * - Offset 8 (N bytes): Truncated Client ID (UTF-8)
     */
    fun appStart(clientId: String = "MCore"): ByteArray {
        var data = byteArrayOf(CommandCode.APP_START.value.toByte(), 0x03)
        // Add 6 reserved bytes (spaces) per Python reference device.py:15
        data += byteArrayOf(0x20, 0x20, 0x20, 0x20, 0x20, 0x20)
        // Client ID: 5 chars max (firmware reads from byte 8, limited display space).
        // take(5) is UTF-16-code-unit based, not grapheme-cluster based like Swift's
        // Character.prefix(5) — immaterial for the ASCII app names this field carries.
        data += clientId.take(5).toByteArray(Charsets.UTF_8)
        return data
    }

    /**
     * Builds a deviceQuery command to request device capabilities.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x16` (deviceQuery)
     * - Offset 1 (1 byte): Constant `0x03`
     */
    fun deviceQuery(): ByteArray = byteArrayOf(CommandCode.DEVICE_QUERY.value.toByte(), 0x03)

    /**
     * Builds a getBattery command to request battery level and storage info.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x14` (getBattery)
     */
    fun getBattery(): ByteArray = byteArrayOf(CommandCode.GET_BATTERY.value.toByte())

    /**
     * Builds a getTime command to request the device's current time.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x05` (getTime)
     */
    fun getTime(): ByteArray = byteArrayOf(CommandCode.GET_TIME.value.toByte())

    /**
     * Builds a setTime command to set the device's clock.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x06` (setTime)
     * - Offset 1 (4 bytes): Unix timestamp (seconds), Little-endian UInt32
     */
    fun setTime(instant: Instant): ByteArray {
        var data = byteArrayOf(CommandCode.SET_TIME.value.toByte())
        data += epochSeconds32(instant).toLittleEndianBytes()
        return data
    }

    /**
     * Builds a setName command to set the advertised device name.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x08` (setName)
     * - Offset 1 (N bytes): Name string (UTF-8 encoded)
     */
    fun setName(name: String): ByteArray {
        var data = byteArrayOf(CommandCode.SET_NAME.value.toByte())
        // Firmware uses char[32]; truncate to 31 bytes to leave room for null terminator.
        data += name.utf8Prefix(31).toByteArray(Charsets.UTF_8)
        return data
    }

    /**
     * Builds a setCoordinates command to set the device's GPS position.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x0E` (setCoordinates)
     * - Offset 1 (4 bytes): Latitude scaled by 1,000,000, Little-endian Int32
     * - Offset 5 (4 bytes): Longitude scaled by 1,000,000, Little-endian Int32
     * - Offset 9 (4 bytes): Altitude placeholder (zeros)
     */
    fun setCoordinates(latitude: Double, longitude: Double): ByteArray {
        var data = byteArrayOf(CommandCode.SET_COORDINATES.value.toByte())
        data += scaledCoordinate(latitude, LATITUDE_RANGE).toLittleEndianBytes()
        data += scaledCoordinate(longitude, LONGITUDE_RANGE).toLittleEndianBytes()
        data += byteArrayOf(0, 0, 0, 0) // altitude placeholder
        return data
    }

    /**
     * Builds a setTxPower command to set the radio transmission power.
     *
     * @param power Transmission power in dBm (range: -9 to 30).
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x0C` (setTxPower)
     * - Offset 1 (1 byte): Power value, Int8
     */
    fun setTxPower(power: Byte): ByteArray = byteArrayOf(CommandCode.SET_TX_POWER.value.toByte(), power)

    /**
     * Builds a setRadio command to configure radio modulation parameters.
     *
     * @param frequency Frequency in MHz.
     * @param bandwidth Bandwidth in kHz.
     * @param spreadingFactor LoRa spreading factor (6-12).
     * @param codingRate LoRa coding rate (5-8).
     * @param clientRepeat Whether to enable client repeat mode (v9+ firmware, omitted if null).
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x0B` (setRadio)
     * - Offset 1 (4 bytes): Frequency scaled by 1,000, Little-endian UInt32
     * - Offset 5 (4 bytes): Bandwidth scaled by 1,000, Little-endian UInt32
     * - Offset 9 (1 byte): Spreading Factor
     * - Offset 10 (1 byte): Coding Rate
     * - Offset 11 (1 byte, optional): Client repeat flag (v9+)
     */
    fun setRadio(
        frequency: Double,
        bandwidth: Double,
        spreadingFactor: UByte,
        codingRate: UByte,
        clientRepeat: Boolean? = null,
    ): ByteArray {
        var data = byteArrayOf(CommandCode.SET_RADIO.value.toByte())
        data += scaledRadioValue(frequency, FREQUENCY_RANGE_KHZ).toLittleEndianBytes()
        data += scaledRadioValue(bandwidth, BANDWIDTH_RANGE_HZ).toLittleEndianBytes()
        data += spreadingFactor.toByte()
        data += codingRate.toByte()
        if (clientRepeat != null) {
            data += if (clientRepeat) 1.toByte() else 0.toByte()
        }
        return data
    }

    /**
     * Builds a getRepeatFreq command to request allowed client repeat frequency ranges (v9+).
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x3C` (getRepeatFreq)
     */
    fun getRepeatFreq(): ByteArray = byteArrayOf(CommandCode.GET_REPEAT_FREQ.value.toByte())

    /**
     * Builds a sendAdvertisement command to broadcast device presence.
     *
     * @param flood Whether to flood the advertisement through the mesh.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x07` (sendAdvertisement)
     * - Offset 1 (1 byte, optional): Flood flag (`0x01` if true, omitted if false)
     */
    fun sendAdvertisement(flood: Boolean = false): ByteArray =
        if (flood) {
            byteArrayOf(CommandCode.SEND_ADVERTISEMENT.value.toByte(), 0x01)
        } else {
            byteArrayOf(CommandCode.SEND_ADVERTISEMENT.value.toByte())
        }

    /**
     * Builds a reboot command to restart the device.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x13` (reboot)
     * - Offset 1 (6 bytes): "reboot" string (UTF-8)
     */
    fun reboot(): ByteArray {
        var data = byteArrayOf(CommandCode.REBOOT.value.toByte())
        data += "reboot".toByteArray(Charsets.UTF_8)
        return data
    }

    // MARK: - Contact Commands

    /**
     * Builds a getContacts command to fetch the contact list.
     *
     * @param since Optional timestamp to fetch only contacts modified since this date.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x04` (getContacts)
     * - Offset 1 (4 bytes, optional): Last modified timestamp, Little-endian UInt32
     */
    fun getContacts(since: Instant? = null): ByteArray {
        var data = byteArrayOf(CommandCode.GET_CONTACTS.value.toByte())
        if (since != null) {
            data += epochSeconds32(since).toLittleEndianBytes()
        }
        return data
    }

    /**
     * Builds a resetPath command to clear the routing path to a contact.
     *
     * @param publicKey The 32-byte public key of the contact.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x0D` (resetPath)
     * - Offset 1 (32 bytes): Full public key
     */
    fun resetPath(publicKey: ByteArray): ByteArray {
        var data = byteArrayOf(CommandCode.RESET_PATH.value.toByte())
        data += publicKey.prefixBytes(PUBLIC_KEY_SIZE)
        return data
    }

    /**
     * Builds a removeContact command to delete a contact from the device.
     *
     * @param publicKey The 32-byte public key of the contact.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x0F` (removeContact)
     * - Offset 1 (32 bytes): Full public key
     */
    fun removeContact(publicKey: ByteArray): ByteArray {
        var data = byteArrayOf(CommandCode.REMOVE_CONTACT.value.toByte())
        data += publicKey.prefixBytes(PUBLIC_KEY_SIZE)
        return data
    }

    /**
     * Builds a shareContact command to broadcast a contact's info.
     *
     * @param publicKey The 32-byte public key of the contact to share.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x10` (shareContact)
     * - Offset 1 (32 bytes): Full public key
     */
    fun shareContact(publicKey: ByteArray): ByteArray {
        var data = byteArrayOf(CommandCode.SHARE_CONTACT.value.toByte())
        data += publicKey.prefixBytes(PUBLIC_KEY_SIZE)
        return data
    }

    /**
     * Builds an exportContact command to generate a contact URI.
     *
     * @param publicKey Optional 32-byte public key. If null, exports the local device's contact.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x11` (exportContact)
     * - Offset 1 (32 bytes, optional): Full public key
     */
    fun exportContact(publicKey: ByteArray? = null): ByteArray {
        var data = byteArrayOf(CommandCode.EXPORT_CONTACT.value.toByte())
        if (publicKey != null) {
            data += publicKey.prefixBytes(PUBLIC_KEY_SIZE)
        }
        return data
    }

    // MARK: - Messaging Commands

    /**
     * Builds a getMessage command to fetch the next pending message.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x0A` (getMessage)
     */
    fun getMessage(): ByteArray = byteArrayOf(CommandCode.GET_MESSAGE.value.toByte())

    /**
     * Builds a sendMessage command for direct messaging.
     *
     * @param destination Destination public key (first 6 bytes used for prefix).
     * @param text Message text (UTF-8 encoded).
     * @param timestamp Message timestamp.
     * @param attempt Retry attempt number (for duplicate detection).
     *
     * The message type byte is fixed to `0x00` (PLAIN). For a direct message the firmware
     * accepts only PLAIN or CLI_DATA: SIGNED_PLAIN is rejected, and CLI_DATA sets
     * `expected_ack = 0`, which would defeat the end-to-end ACK correlation this send path
     * depends on. PLAIN is the only type that preserves the ACK design, so it is not exposed
     * as a parameter.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x02` (sendMessage)
     * - Offset 1 (1 byte): Message type `0x00` (PLAIN text)
     * - Offset 2 (1 byte): Retry attempt counter
     * - Offset 3 (4 bytes): Unix timestamp (seconds), Little-endian UInt32
     * - Offset 7 (6 bytes): Destination public key prefix
     * - Offset 13 (N bytes): Message payload (UTF-8)
     */
    fun sendMessage(
        destination: ByteArray,
        text: String,
        timestamp: Instant = Instant.now(),
        attempt: UByte = 0u,
    ): ByteArray {
        var data = byteArrayOf(CommandCode.SEND_MESSAGE.value.toByte(), 0x00, attempt.toByte())
        data += epochSeconds32(timestamp).toLittleEndianBytes()
        data += destination.prefixBytes(6)
        data += text.toByteArray(Charsets.UTF_8)
        return data
    }

    /**
     * Builds a command packet for sending structured commands to remote nodes.
     *
     * @param destination Destination public key prefix (6 bytes).
     * @param command Command string to execute.
     * @param timestamp Command timestamp.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x02` (sendMessage)
     * - Offset 1 (1 byte): Message type `0x01` (structured command)
     * - Offset 2 (1 byte): Reserved `0x00`
     * - Offset 3 (4 bytes): Unix timestamp, Little-endian UInt32
     * - Offset 7 (6 bytes): Destination prefix
     * - Offset 13 (N bytes): Command payload (UTF-8)
     */
    fun sendCommand(
        destination: ByteArray,
        command: String,
        timestamp: Instant = Instant.now(),
    ): ByteArray {
        var data = byteArrayOf(CommandCode.SEND_MESSAGE.value.toByte(), 0x01, 0x00)
        data += epochSeconds32(timestamp).toLittleEndianBytes()
        data += destination.prefixBytes(6)
        data += command.toByteArray(Charsets.UTF_8)
        return data
    }

    /**
     * Builds a sendChannelMessage command for broadcasting to a mesh channel.
     *
     * @param channel The 0-based index of the channel.
     * @param text Message text (UTF-8 encoded).
     * @param timestamp Message timestamp.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x03` (sendChannelMessage)
     * - Offset 1 (1 byte): Message type `0x00`
     * - Offset 2 (1 byte): Channel index
     * - Offset 3 (4 bytes): Unix timestamp, Little-endian UInt32
     * - Offset 7 (N bytes): Message payload (UTF-8)
     */
    fun sendChannelMessage(
        channel: UByte,
        text: String,
        timestamp: Instant = Instant.now(),
    ): ByteArray {
        var data = byteArrayOf(CommandCode.SEND_CHANNEL_MESSAGE.value.toByte(), 0x00, channel.toByte())
        data += epochSeconds32(timestamp).toLittleEndianBytes()
        data += text.toByteArray(Charsets.UTF_8)
        return data
    }

    /**
     * Builds a sendLogin command to authenticate with a remote node.
     *
     * @param destination The 32-byte public key of the node to login to.
     * @param password The password for authentication.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x1A` (sendLogin)
     * - Offset 1 (32 bytes): Full public key of target
     * - Offset 33 (N bytes): Password (UTF-8)
     */
    fun sendLogin(destination: ByteArray, password: String): ByteArray {
        var data = byteArrayOf(CommandCode.SEND_LOGIN.value.toByte())
        data += destination.prefixBytes(PUBLIC_KEY_SIZE)
        data += password.toByteArray(Charsets.UTF_8)
        return data
    }

    /**
     * Builds a sendLogout command to end an authenticated session.
     *
     * @param destination The 32-byte public key of the node.
     */
    fun sendLogout(destination: ByteArray): ByteArray {
        var data = byteArrayOf(CommandCode.SEND_LOGOUT.value.toByte())
        data += destination.prefixBytes(PUBLIC_KEY_SIZE)
        return data
    }

    /**
     * Builds a sendStatusRequest command to query a remote node's status.
     *
     * @param destination The 32-byte public key of the node.
     */
    fun sendStatusRequest(destination: ByteArray): ByteArray {
        var data = byteArrayOf(CommandCode.SEND_STATUS_REQUEST.value.toByte())
        data += destination.prefixBytes(PUBLIC_KEY_SIZE)
        return data
    }

    // MARK: - Binary Protocol Commands

    /**
     * Builds a binaryRequest command for specialized data requests.
     *
     * @param destination The 32-byte public key of the target node.
     * @param type The type of binary request (e.g., MMA, neighbours).
     * @param payload Optional additional request data.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x32` (binaryRequest)
     * - Offset 1 (32 bytes): Full public key
     * - Offset 33 (1 byte): Request type code
     * - Offset 34 (N bytes, optional): Payload
     */
    fun binaryRequest(
        destination: ByteArray,
        type: BinaryRequestType,
        payload: ByteArray? = null,
    ): ByteArray {
        var data = byteArrayOf(CommandCode.BINARY_REQUEST.value.toByte())
        data += destination.prefixBytes(PUBLIC_KEY_SIZE)
        data += type.value.toByte()
        if (payload != null) {
            data += payload
        }
        return data
    }

    // MARK: - Channel Commands

    /**
     * Builds a getChannel command to fetch configuration for a specific channel.
     *
     * @param index The 0-based index of the channel.
     */
    fun getChannel(index: UByte): ByteArray = byteArrayOf(CommandCode.GET_CHANNEL.value.toByte(), index.toByte())

    /**
     * Builds a setChannel command to configure a mesh channel.
     *
     * @param index The 0-based index of the channel to configure.
     * @param name The name of the channel (max 32 bytes).
     * @param secret The 16-byte PSK (Pre-Shared Key) for the channel.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x20` (setChannel)
     * - Offset 1 (1 byte): Channel index
     * - Offset 2 (32 bytes): Padded channel name (UTF-8, zero-filled)
     * - Offset 34 (16 bytes): PSK secret
     */
    fun setChannel(index: UByte, name: String, secret: ByteArray): ByteArray {
        var data = byteArrayOf(CommandCode.SET_CHANNEL.value.toByte(), index.toByte())
        // Pad name to 32 bytes (UTF-8-safe truncation)
        data += name.utf8PaddedOrTruncated(32)
        // Secret must be 16 bytes (caller's responsibility; not padded here)
        data += secret.prefixBytes(16)
        return data
    }

    // MARK: - Stats Commands

    /** Builds a command to fetch core system statistics. */
    fun getStatsCore(): ByteArray = byteArrayOf(CommandCode.GET_STATS.value.toByte(), StatsType.CORE.value.toByte())

    /** Builds a command to fetch radio performance statistics. */
    fun getStatsRadio(): ByteArray =
        byteArrayOf(CommandCode.GET_STATS.value.toByte(), StatsType.RADIO.value.toByte())

    /** Builds a command to fetch packet counters. */
    fun getStatsPackets(): ByteArray =
        byteArrayOf(CommandCode.GET_STATS.value.toByte(), StatsType.PACKETS.value.toByte())

    // MARK: - Additional Commands (from Python reference)

    /**
     * Builds an updateContact command to sync a full contact record to firmware.
     *
     * @param contact The contact to update.
     * @return The command packet data (147 bytes).
     *
     * ### Binary Format
     * (Per firmware MyMesh.cpp updateContactFromFrame and Python meshcore_py)
     * - Offset 0 (1 byte): Command code `0x09`
     * - Offset 1 (32 bytes): Public key
     * - Offset 33 (1 byte): Type
     * - Offset 34 (1 byte): Flags
     * - Offset 35 (1 byte): Out path length (encoded: upper 2 bits = hash mode, lower 6 bits = hop count; 0xFF = flood)
     * - Offset 36 (64 bytes): Out path (zero-padded)
     * - Offset 100 (32 bytes): Advertised name (UTF-8, zero-padded)
     * - Offset 132 (4 bytes): Last advert timestamp (UInt32 LE)
     * - Offset 136 (4 bytes): Latitude (Int32 LE, scaled by 1,000,000)
     * - Offset 140 (4 bytes): Longitude (Int32 LE, scaled by 1,000,000)
     * - Offset 144 (3 bytes): Reserved/padding (zeros)
     *
     * Total: 147 bytes
     */
    fun updateContact(contact: MeshContact): ByteArray {
        var data = byteArrayOf(CommandCode.UPDATE_CONTACT.value.toByte()) // 1 byte
        data += contact.publicKey.paddedOrTruncated(32) // 32 bytes
        data += contact.typeRawValue.toByte() // 1 byte
        data += contact.flags.rawValue.toByte() // 1 byte
        data += contact.outPathLength.toByte() // 1 byte
        data += contact.outPath.paddedOrTruncated(64) // 64 bytes
        data += contact.advertisedName.utf8PaddedOrTruncated(32) // 32 bytes

        data += epochSeconds32(contact.lastAdvertisement).toLittleEndianBytes() // 4 bytes
        data += scaledCoordinate(contact.latitude, LATITUDE_RANGE).toLittleEndianBytes() // 4 bytes
        data += scaledCoordinate(contact.longitude, LONGITUDE_RANGE).toLittleEndianBytes() // 4 bytes

        // Subtotal so far: 1 + 32 + 1 + 1 + 1 + 64 + 32 + 4 + 4 + 4 = 144 bytes.
        // Firmware expects a 147-byte frame, so pad with 3 reserved bytes.
        data += byteArrayOf(0x00, 0x00, 0x00) // 3 bytes

        return data // 144 + 3 = 147 bytes
    }

    /**
     * Builds a setTuning command to adjust low-level radio timing.
     *
     * @param rxDelay Receive delay in microseconds.
     * @param af Automatic frequency correction parameter.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x15` (setTuning)
     * - Offset 1 (4 bytes): rxDelay, Little-endian UInt32
     * - Offset 5 (4 bytes): af, Little-endian UInt32
     * - Offset 9 (2 bytes): Reserved padding (zeros)
     */
    fun setTuning(rxDelay: UInt, af: UInt): ByteArray {
        var data = byteArrayOf(CommandCode.SET_TUNING.value.toByte())
        data += rxDelay.toLittleEndianBytes()
        data += af.toLittleEndianBytes()
        data += byteArrayOf(0, 0) // 2 reserved bytes
        return data
    }

    /**
     * Builds a setOtherParams command for various system configurations.
     *
     * @param manualAddContacts Whether to allow manual contact addition.
     * @param telemetryModeEnvironment Environment telemetry mode (0-3).
     * @param telemetryModeLocation Location telemetry mode (0-3).
     * @param telemetryModeBase Base telemetry mode (0-3).
     * @param advertisementLocationPolicy Location advertisement policy.
     * @param multiAcks Optional multi-ACK configuration (newer firmware).
     *
     * ### Binary Format
     * (Per Python device.py:95-128)
     * - Offset 0 (1 byte): Command code `0x26` (setOtherParams)
     * - Offset 1 (1 byte): Manual contact add flag (0/1)
     * - Offset 2 (1 byte): Combined telemetry mode (Env:2 | Loc:2 | Base:2)
     * - Offset 3 (1 byte): Advertisement location policy
     * - Offset 4 (1 byte, optional): Multi-ACKs flag
     */
    fun setOtherParams(
        manualAddContacts: Boolean,
        telemetryModeEnvironment: UByte,
        telemetryModeLocation: UByte,
        telemetryModeBase: UByte,
        advertisementLocationPolicy: UByte,
        multiAcks: UByte? = null,
    ): ByteArray {
        var data = byteArrayOf(CommandCode.SET_OTHER_PARAMS.value.toByte())
        data += if (manualAddContacts) 1.toByte() else 0.toByte()
        // Combine telemetry modes into single byte: env(2) | loc(2) | base(2)
        val telemetryMode = ((telemetryModeEnvironment.toInt() and 0b11) shl 4) or
            ((telemetryModeLocation.toInt() and 0b11) shl 2) or
            (telemetryModeBase.toInt() and 0b11)
        data += telemetryMode.toByte()
        data += advertisementLocationPolicy.toByte()
        if (multiAcks != null) {
            data += multiAcks.toByte()
        }
        return data
    }

    /** Builds a packet to get the auto-add configuration. */
    fun getAutoAddConfig(): ByteArray = byteArrayOf(CommandCode.GET_AUTO_ADD_CONFIG.value.toByte())

    /**
     * Builds a packet to set the auto-add configuration.
     * @param config The auto-add configuration (bitmask + max hops).
     */
    fun setAutoAddConfig(config: AutoAddConfig): ByteArray =
        byteArrayOf(CommandCode.SET_AUTO_ADD_CONFIG.value.toByte(), config.bitmask.toByte(), config.maxHops.toByte())

    /**
     * Builds a getSelfTelemetry command to request current sensor data from the device.
     *
     * @param destination Optional 32-byte public key to send telemetry to.
     */
    fun getSelfTelemetry(destination: ByteArray? = null): ByteArray {
        var data = byteArrayOf(CommandCode.GET_SELF_TELEMETRY.value.toByte(), 0x00, 0x00, 0x00)
        if (destination != null) {
            data += destination.prefixBytes(PUBLIC_KEY_SIZE)
        }
        return data
    }

    // MARK: - Security Commands

    /**
     * Builds a setDevicePin command to set the BLE pairing PIN.
     *
     * @param pin The 6-digit PIN code.
     */
    fun setDevicePin(pin: UInt): ByteArray {
        var data = byteArrayOf(CommandCode.SET_DEVICE_PIN.value.toByte())
        data += pin.toLittleEndianBytes()
        return data
    }

    /** Builds a getCustomVars command to fetch user-defined variables. */
    fun getCustomVars(): ByteArray = byteArrayOf(CommandCode.GET_CUSTOM_VARS.value.toByte())

    /**
     * Builds a setCustomVar command to set a user-defined key-value pair.
     *
     * @param key The variable name.
     * @param value The variable value.
     */
    fun setCustomVar(key: String, value: String): ByteArray {
        var data = byteArrayOf(CommandCode.SET_CUSTOM_VAR.value.toByte())
        data += "$key:$value".toByteArray(Charsets.UTF_8)
        return data
    }

    /** Builds an exportPrivateKey command to retrieve the device's private key. */
    fun exportPrivateKey(): ByteArray = byteArrayOf(CommandCode.EXPORT_PRIVATE_KEY.value.toByte())

    /**
     * Builds an importPrivateKey command to set the device's private key.
     *
     * @param key The private key data.
     */
    fun importPrivateKey(key: ByteArray): ByteArray {
        var data = byteArrayOf(CommandCode.IMPORT_PRIVATE_KEY.value.toByte())
        data += key
        return data
    }

    // MARK: - Signing Commands

    /** Builds a signStart command to begin a cryptographic signing session. */
    fun signStart(): ByteArray = byteArrayOf(CommandCode.SIGN_START.value.toByte())

    /**
     * Builds a signData command to append a chunk of data for signing.
     *
     * @param chunk The data chunk to sign.
     */
    fun signData(chunk: ByteArray): ByteArray {
        var data = byteArrayOf(CommandCode.SIGN_DATA.value.toByte())
        data += chunk
        return data
    }

    /** Builds a signFinish command to finalize signing and receive the signature. */
    fun signFinish(): ByteArray = byteArrayOf(CommandCode.SIGN_FINISH.value.toByte())

    // MARK: - Path Discovery Commands

    /**
     * Builds a sendPathDiscovery command to initiate route finding to a destination.
     *
     * @param destination The 32-byte public key of the target node.
     */
    fun sendPathDiscovery(destination: ByteArray): ByteArray {
        var data = byteArrayOf(CommandCode.PATH_DISCOVERY.value.toByte(), 0x00)
        data += destination.prefixBytes(PUBLIC_KEY_SIZE)
        return data
    }

    /**
     * Builds a sendTrace command to test packet routing and signal strength.
     *
     * @param tag 32-bit identifier for this trace (used to match response).
     * @param authCode 32-bit authentication code for secure tracing.
     * @param flags 8-bit flags field for trace configuration.
     * @param path Optional sequence of repeater pubkey hashes for source routing.
     */
    fun sendTrace(
        tag: UInt,
        authCode: UInt,
        flags: UByte,
        path: ByteArray? = null,
    ): ByteArray {
        var data = byteArrayOf(CommandCode.SEND_TRACE.value.toByte())
        data += tag.toLittleEndianBytes()
        data += authCode.toLittleEndianBytes()
        data += flags.toByte()
        if (path != null) {
            data += path
        }
        return data
    }

    /**
     * Builds a setFloodScope command to restrict message routing to a specific group.
     *
     * @param scopeKey 16-byte scope key (or zeros to disable scope).
     */
    fun setFloodScope(scopeKey: ByteArray): ByteArray {
        var data = byteArrayOf(CommandCode.SET_FLOOD_SCOPE.value.toByte(), 0x00)
        data += scopeKey.prefixBytes(16)
        return data
    }

    /**
     * Builds a setFloodScope command that forces un-scoped flood broadcasts, overriding any
     * persisted default flood scope on the device.
     *
     * Unlike [setFloodScope] (sub-command 0, which resets the session scope and falls back to
     * the device default), this emits sub-command 1, which sets the firmware `send_unscoped`
     * flag. Requires firmware ver 12+; older firmware has no handler for sub-command 1 and
     * rejects the frame with `ERR_CODE_UNSUPPORTED_CMD`, so callers must gate on the reported
     * firmware version. No scope key follows the sub-command byte.
     */
    fun setFloodScopeUnscoped(): ByteArray = byteArrayOf(CommandCode.SET_FLOOD_SCOPE.value.toByte(), 0x01)

    /**
     * Builds a `sendChannelData` command for sending a binary datagram to a channel.
     *
     * Requires firmware v11+ (MeshCore v1.15.0+).
     *
     * Wire format:
     * - Offset 0 (1 byte): Command code `0x3E`
     * - Offset 1 (1 byte): Channel index (0-255)
     * - Offset 2 (1 byte): Encoded `pathLength` byte (`0xFF` = flood)
     * - Offset 3 (pathBytes.size bytes, absent if flood): Path bytes, caller-supplied verbatim
     * - Next (2 bytes): `dataType` (little-endian UInt16)
     * - Remaining: Payload bytes (clamped to 163 bytes)
     *
     * `pathLength` is the **encoded** path-length byte used by firmware's packet format (see
     * `Packet::isValidPathLen` at `mc-ref/MeshCore/src/Packet.cpp:13-18`):
     * - Upper 2 bits (6-7): hash size mode (0 = 1 byte, 1 = 2 bytes, 2 = 3 bytes, 3 = reserved).
     * - Lower 6 bits (0-5): hop count (0-63).
     * - Special value `0xFF`: flood routing (no path bytes follow).
     *
     * The number of bytes consumed from `pathBytes` by firmware is `hash_count * hash_size` —
     * NOT `pathBytes.size`. The builder passes both `pathLength` and `pathBytes` through
     * verbatim; callers are responsible for keeping them consistent. Callers that already have
     * a [MeshContact] can pass `contact.outPathLength` and `contact.outPath` directly.
     *
     * Note: Upstream `companion_protocol.md` §6 documents an incorrect wire format (wrong field
     * order, missing `path_len`) as of v1.15.0. `MyMesh.cpp` is canonical.
     *
     * @param channelIndex The channel slot index.
     * @param dataType Application data-type namespace. At runtime, firmware rejects only
     *   `0x0000` with `ERR_CODE_ILLEGAL_ARG` (see `MyMesh.cpp:1153-1154`). Values
     *   `0x0001-0x00FF` are reserved by convention in `number_allocations.md` but are not
     *   enforced by firmware; `0xFF00-0xFFFF` is the developer/testing namespace. Custom apps
     *   should request an allocation in `0x0100-0xFEFF`.
     * @param payload Binary payload, clamped to [CHANNEL_DATA_MAX_PAYLOAD_BYTES] (163).
     * @param pathLength Encoded `path_len` byte. Defaults to [FLOOD_PATH_SENTINEL] (`0xFF`).
     *   When set to a non-flood value it must satisfy `Packet::isValidPathLen`.
     * @param pathBytes Path bytes, written verbatim after `pathLength`. Pass an empty array
     *   (the default) for flood; firmware ignores any bytes when `pathLength == 0xFF` so the
     *   builder omits them.
     */
    fun sendChannelData(
        channelIndex: UByte,
        dataType: UShort,
        payload: ByteArray,
        pathLength: UByte = FLOOD_PATH_SENTINEL,
        pathBytes: ByteArray = ByteArray(0),
    ): ByteArray {
        var data = byteArrayOf(
            CommandCode.SEND_CHANNEL_DATA.value.toByte(),
            channelIndex.toByte(),
            pathLength.toByte(),
        )

        if (pathLength != FLOOD_PATH_SENTINEL) {
            data += pathBytes
        }

        data += dataType.toLittleEndianBytes()
        data += payload.prefixBytes(CHANNEL_DATA_MAX_PAYLOAD_BYTES)
        return data
    }

    /**
     * Builds a `setDefaultFloodScope` command to persist the device's default flood scope.
     *
     * The device uses this scope for flood sends when no session-scoped key has been set.
     * Passing an empty name clears the persisted scope. Firmware's name-field parse at
     * `MyMesh.cpp:1896` accepts only `0 < strlen(name) < 31`, so both empty and 31-plus-byte
     * names are rejected with `ERR_CODE_ILLEGAL_ARG`. The builder defends against both:
     * empty-name normalises to the single-byte clear form, and the 30-byte UTF-8 cap plus
     * zero-padding to 31 guarantees `strlen < 31` for any non-empty input.
     *
     * Requires firmware v11+ (MeshCore v1.15.0+).
     *
     * Wire format:
     * - `[0x3F]` (1 byte) to clear the default scope.
     * - `[0x3F][name:31 zero-padded UTF-8][key:16]` (48 bytes) to set it.
     *
     * @param name Display name. Encoded to at most 30 UTF-8 bytes; byte 31 is always zero so
     *   firmware's `strlen`-based parse terminates. Truncation happens byte-safely via
     *   [utf8Prefix] so invalid UTF-8 is never emitted. Passing an empty name clears the scope.
     * @param scopeKey 16-byte scope key. Shorter keys are right-padded with zeros.
     */
    fun setDefaultFloodScope(name: String, scopeKey: ByteArray): ByteArray {
        // Firmware rejects n == 0 — normalise empty name to clear, ignoring any key.
        if (name.isEmpty()) {
            return byteArrayOf(CommandCode.SET_DEFAULT_FLOOD_SCOPE.value.toByte())
        }

        var data = byteArrayOf(CommandCode.SET_DEFAULT_FLOOD_SCOPE.value.toByte())

        // Cap UTF-8 at 30 bytes (code-point safe) then zero-pad to 31. Zero-padding guarantees
        // at least one null byte in the 31-byte field for firmware's strlen.
        val truncated = name.utf8Prefix(DEFAULT_SCOPE_MAX_NAME_BYTES)
        data += truncated.toByteArray(Charsets.UTF_8).paddedOrTruncated(DEFAULT_SCOPE_NAME_FIELD)

        data += scopeKey.paddedOrTruncated(DEFAULT_SCOPE_KEY_BYTES)

        return data
    }

    /**
     * Convenience overload that derives the 16-byte key from a [FloodScope].
     *
     * @param name Display name for the scope (stored on-device).
     * @param scope Any [FloodScope]; [FloodScope.Disabled] clears the scope.
     */
    fun setDefaultFloodScope(name: String, scope: FloodScope): ByteArray {
        if (scope is FloodScope.Disabled) {
            return setDefaultFloodScope(name = "", scopeKey = ByteArray(0))
        }
        return setDefaultFloodScope(name = name, scopeKey = scope.scopeKey())
    }

    /**
     * Builds a getDefaultFloodScope command.
     *
     * Requires firmware v11+ (MeshCore v1.15.0+).
     *
     * @return A single-byte command packet.
     */
    fun getDefaultFloodScope(): ByteArray = byteArrayOf(CommandCode.GET_DEFAULT_FLOOD_SCOPE.value.toByte())

    /**
     * Builds a command to send an anonymous request to a remote node.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x39`
     * - Offset 1-32 (32 bytes): Destination public key
     * - Offset 33 (1 byte): Anonymous request type
     * - Offset 34 (1 byte): Encoded path length (bits 7-6 = hash_size-1, bits 5-0 = hop count)
     * - Offset 35+ (variable): Out-path bytes, reversed to form the return route
     *
     * @param publicKey The 32-byte public key of the destination node.
     * @param type The anonymous request type.
     * @param pathLength The encoded path length byte.
     * @param path The raw out-path bytes for the destination. Reversed to form the return route.
     */
    fun sendAnonReq(
        publicKey: ByteArray,
        type: AnonRequestType,
        pathLength: UByte,
        path: ByteArray,
    ): ByteArray {
        var data = byteArrayOf(CommandCode.SEND_ANON_REQ.value.toByte())
        data += publicKey.prefixBytes(PUBLIC_KEY_SIZE)
        data += type.value.toByte()
        data += pathLength.toByte()
        data += path.reversedArray()
        return data
    }

    /**
     * Builds a setPathHashMode command to configure the path hash size.
     *
     * @param mode Hash mode (0=1-byte, 1=2-byte, 2=3-byte hashes).
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x3D`
     * - Offset 1 (1 byte): Reserved `0x00`
     * - Offset 2 (1 byte): Mode value (0, 1, or 2)
     */
    fun setPathHashMode(mode: UByte): ByteArray {
        val clamped = minOf(mode.toInt(), PathEncoding.MAX_PATH_HASH_MODE).toUByte()
        return byteArrayOf(CommandCode.SET_PATH_HASH_MODE.value.toByte(), 0x00, clamped.toByte())
    }

    /**
     * Builds a factoryReset command to wipe all settings and data from the device.
     *
     * ### Binary Format
     * (Per firmware MyMesh.cpp - requires guard string)
     * - Offset 0 (1 byte): Command code `0x33`
     * - Offset 1 (5 bytes): Guard string "reset" (UTF-8)
     */
    fun factoryReset(): ByteArray {
        var data = byteArrayOf(CommandCode.FACTORY_RESET.value.toByte())
        data += "reset".toByteArray(Charsets.UTF_8)
        return data
    }

    // MARK: - Control Data Commands

    /**
     * Builds a generic sendControlData command for protocol-level signalling.
     *
     * @param type The control data type code.
     * @param payload The data payload.
     */
    fun sendControlData(type: UByte, payload: ByteArray): ByteArray {
        var data = byteArrayOf(CommandCode.SEND_CONTROL_DATA.value.toByte(), type.toByte())
        data += payload
        return data
    }

    /**
     * Builds a nodeDiscoverRequest command to find active nodes in the mesh.
     *
     * @param filter Filter criteria for discovery.
     * @param prefixOnly Whether to return only public key prefixes (saves bandwidth).
     * @param tag Optional 32-bit tag. If null, a random non-zero tag is generated.
     * @param since Optional timestamp to fetch nodes seen since this time.
     */
    fun sendNodeDiscoverRequest(
        filter: UByte,
        prefixOnly: Boolean = true,
        tag: UInt? = null,
        since: UInt? = null,
    ): ByteArray {
        val actualTag = tag ?: run {
            // 1...UInt32.max: any random 32-bit pattern except zero.
            var candidate: UInt
            do {
                candidate = Random.nextInt().toUInt()
            } while (candidate == 0u)
            candidate
        }
        val flags: UByte = if (prefixOnly) 1u else 0u
        val controlType = ControlType.NODE_DISCOVER_REQUEST.value or flags

        var data = byteArrayOf(CommandCode.SEND_CONTROL_DATA.value.toByte(), controlType.toByte())
        data += filter.toByte()
        data += actualTag.toLittleEndianBytes()
        if (since != null) {
            data += since.toLittleEndianBytes()
        }
        return data
    }

    // MARK: - Raw Data and Query Commands

    /**
     * Builds a sendRawData command to send raw data through the mesh.
     *
     * @param path The routing path data.
     * @param payload The raw payload to send.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x19`
     * - Offset 1 (1 byte): Path length
     * - Offset 2 (N bytes): Path data
     * - Offset 2+N (M bytes): Payload
     */
    fun sendRawData(path: ByteArray, payload: ByteArray): ByteArray {
        var data = byteArrayOf(CommandCode.SEND_RAW_DATA.value.toByte())
        val clampedPath = path.prefixBytes(RAW_DATA_MAX_PATH_BYTES)
        data += clampedPath.size.toUByte().toByte()
        data += clampedPath
        data += payload.prefixBytes(RAW_DATA_MAX_PAYLOAD_BYTES)
        return data
    }

    /**
     * Builds a hasConnection command to check if a connection exists to a node.
     *
     * @param publicKey The 32-byte public key of the node.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x1C`
     * - Offset 1 (32 bytes): Full public key
     */
    fun hasConnection(publicKey: ByteArray): ByteArray {
        var data = byteArrayOf(CommandCode.HAS_CONNECTION.value.toByte())
        data += encodePublicKey(publicKey)
        return data
    }

    /**
     * Builds a getContactByKey command to retrieve a contact by public key.
     *
     * @param publicKey The 32-byte public key of the contact.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x1E`
     * - Offset 1 (32 bytes): Full public key
     */
    fun getContactByKey(publicKey: ByteArray): ByteArray {
        var data = byteArrayOf(CommandCode.GET_CONTACT_BY_KEY.value.toByte())
        data += encodePublicKey(publicKey)
        return data
    }

    /**
     * Builds a getAdvertPath command to retrieve the advertisement path to a contact.
     *
     * @param publicKey The 32-byte public key of the contact.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x2A`
     * - Offset 1 (1 byte): Reserved `0x00`
     * - Offset 2 (32 bytes): Full public key
     */
    fun getAdvertPath(publicKey: ByteArray): ByteArray {
        var data = byteArrayOf(CommandCode.GET_ADVERT_PATH.value.toByte(), 0x00)
        data += encodePublicKey(publicKey)
        return data
    }

    /**
     * Builds a getTuningParams command to retrieve radio tuning parameters.
     *
     * ### Binary Format
     * - Offset 0 (1 byte): Command code `0x2B`
     */
    fun getTuningParams(): ByteArray = byteArrayOf(CommandCode.GET_TUNING_PARAMS.value.toByte())
}
