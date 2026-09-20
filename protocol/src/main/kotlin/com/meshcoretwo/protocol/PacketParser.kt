// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.time.Instant

// MARK: - PacketParser

/**
 * Stateless packet parser for decoding raw data into mesh events.
 *
 * Acts as a central router that identifies the type of incoming data based on its
 * [ResponseCode] and delegates parsing to domain-specific parsers (the `*Parser` objects
 * ported from `Parsers+*.swift`).
 *
 * Use [parse] to convert raw bytes received from a transport into a [MeshEvent].
 */
object PacketParser {
    /**
     * Parses raw binary data into a mesh event.
     *
     * @param data Raw data received from the device, including the response code byte.
     * @return A parsed [MeshEvent], or [MeshEvent.ParseFailure] if the data is malformed or the
     *   code is unknown.
     *
     * ### Routing Logic
     * 1. Extracts the first byte as the [ResponseCode].
     * 2. Validates the code exists in the protocol.
     * 3. Routes the remaining payload to a category-specific parser (Simple, Device, Contact, etc.).
     */
    fun parse(data: ByteArray): MeshEvent {
        if (data.isEmpty()) {
            return MeshEvent.ParseFailure(data, "Empty packet")
        }
        val firstByte = data[0].toUByte()

        val code = ResponseCode.fromValue(firstByte)
            ?: return MeshEvent.ParseFailure(data, "Unknown response code: 0x${"%02X".format(firstByte.toInt())}")

        val payload = data.copyOfRange(1, data.size)

        // Route by category - eliminates giant switch, groups by domain
        return when (code.category) {
            ResponseCategory.SIMPLE -> parseSimpleResponse(code, payload)
            ResponseCategory.DEVICE -> parseDeviceResponse(code, payload)
            ResponseCategory.CONTACT -> parseContactResponse(code, payload)
            ResponseCategory.MESSAGE -> parseMessageResponse(code, payload)
            ResponseCategory.PUSH -> parsePushNotification(code, payload)
            ResponseCategory.LOGIN -> parseLoginResponse(code, payload)
            ResponseCategory.SIGNING -> parseSigningResponse(code, payload)
            ResponseCategory.MISC -> parseMiscResponse(code, payload)
        }
    }

    // MARK: - Simple Responses (inlined - trivial logic)

    /** Handles trivial response codes like OK and Error. */
    private fun parseSimpleResponse(code: ResponseCode, payload: ByteArray): MeshEvent = when (code) {
        ResponseCode.OK ->
            // OK can have optional payload (often a UInt32 value)
            if (payload.size >= 4) MeshEvent.Ok(payload.readUInt32LE(0)) else MeshEvent.Ok(null)

        ResponseCode.ERROR -> {
            val errorCode = payload.firstOrNull()?.toUByte()
            MeshEvent.Error(errorCode)
        }

        else -> MeshEvent.ParseFailure(payload, "Unexpected code in simple response: $code")
    }

    // MARK: - Device Responses (mix of inline and extracted)

    /** Handles responses related to local device information. */
    private fun parseDeviceResponse(code: ResponseCode, payload: ByteArray): MeshEvent = when (code) {
        ResponseCode.BATTERY -> {
            // Inline - simple structure
            if (payload.size < PacketSize.BATTERY_MINIMUM) {
                MeshEvent.ParseFailure(payload, "Battery response too short: ${payload.size} < ${PacketSize.BATTERY_MINIMUM}")
            } else if (payload.size > PacketSize.BATTERY_MINIMUM && payload.size < PacketSize.BATTERY_EXTENDED) {
                MeshEvent.ParseFailure(
                    payload,
                    "Battery response has partial extended payload: ${payload.size} < ${PacketSize.BATTERY_EXTENDED}",
                )
            } else {
                val level = payload.readUInt16LE(0).toInt()
                var usedKB: Int? = null
                var totalKB: Int? = null
                if (payload.size >= PacketSize.BATTERY_EXTENDED) {
                    usedKB = payload.readUInt32LE(2).toInt()
                    totalKB = payload.readUInt32LE(6).toInt()
                }
                MeshEvent.Battery(BatteryInfo(level = level, usedStorageKB = usedKB, totalStorageKB = totalKB))
            }
        }

        ResponseCode.CURRENT_TIME -> {
            // Inline - trivial
            if (payload.size < 4) {
                MeshEvent.ParseFailure(payload, "CurrentTime response too short: ${payload.size} < 4")
            } else {
                val timestamp = payload.readUInt32LE(0)
                MeshEvent.CurrentTime(Instant.ofEpochSecond(timestamp.toLong()))
            }
        }

        ResponseCode.DISABLED -> MeshEvent.Disabled("private_key_export_disabled")

        // Extracted - complex (55+ bytes, many fields)
        ResponseCode.SELF_INFO -> SelfInfoParser.parse(payload)

        // Extracted - complex (version-dependent, many fields)
        ResponseCode.DEVICE_INFO -> DeviceInfoParser.parse(payload)

        // Extracted - needs validation
        ResponseCode.PRIVATE_KEY -> PrivateKeyParser.parse(payload)

        ResponseCode.ADVERT_PATH -> AdvertPathResponseParser.parse(payload)

        ResponseCode.TUNING_PARAMS -> TuningParamsResponseParser.parse(payload)

        ResponseCode.AUTO_ADD_CONFIG ->
            if (payload.isEmpty()) {
                MeshEvent.ParseFailure(payload, "AutoAddConfig response too short: ${payload.size} < 1")
            } else {
                val maxHops = if (payload.size >= 2) payload[1].toUByte() else 0u
                MeshEvent.AutoAddConfigEvent(AutoAddConfig(bitmask = payload[0].toUByte(), maxHops = maxHops))
            }

        ResponseCode.ALLOWED_REPEAT_FREQ -> AllowedRepeatFreqParser.parse(payload)

        ResponseCode.DEFAULT_FLOOD_SCOPE -> DefaultFloodScopeParser.parse(payload)

        else -> MeshEvent.ParseFailure(payload, "Unexpected code in device response: $code")
    }

    // MARK: - Contact Responses

    /** Handles responses related to contact list management. */
    private fun parseContactResponse(code: ResponseCode, payload: ByteArray): MeshEvent = when (code) {
        ResponseCode.CONTACT_START ->
            if (payload.size < PacketSize.CONTACTS_START_MINIMUM) {
                MeshEvent.ParseFailure(
                    payload,
                    "ContactStart response too short: ${payload.size} < ${PacketSize.CONTACTS_START_MINIMUM}",
                )
            } else {
                MeshEvent.ContactsStart(payload.readUInt32LE(0).toInt())
            }

        // Extracted - 147 bytes, many fields
        ResponseCode.CONTACT -> ContactParser.parse(payload)

        ResponseCode.CONTACT_END ->
            // ContactEnd may include last modified timestamp
            if (payload.size >= 4) {
                val lastMod = Instant.ofEpochSecond(payload.readUInt32LE(0).toLong())
                MeshEvent.ContactsEnd(lastMod)
            } else {
                MeshEvent.ContactsEnd(Instant.now())
            }

        ResponseCode.CONTACT_URI -> {
            // Inline - simple transformation
            val hex = payload.hexString
            MeshEvent.ContactURI("meshcore://$hex")
        }

        else -> MeshEvent.ParseFailure(payload, "Unexpected code in contact response: $code")
    }

    // MARK: - Message Responses

    /** Handles responses related to direct and channel messaging. */
    private fun parseMessageResponse(code: ResponseCode, payload: ByteArray): MeshEvent = when (code) {
        ResponseCode.MESSAGE_SENT ->
            // Inline - simple structure
            if (payload.size < PacketSize.MESSAGE_SENT_MINIMUM) {
                MeshEvent.ParseFailure(
                    payload,
                    "MessageSent response too short: ${payload.size} < ${PacketSize.MESSAGE_SENT_MINIMUM}",
                )
            } else {
                MeshEvent.MessageSent(
                    MessageSentInfo(
                        route = payload[0].toUByte(),
                        expectedAck = payload.copyOfRange(1, 5),
                        suggestedTimeoutMs = payload.readUInt32LE(5),
                    ),
                )
            }

        ResponseCode.NO_MORE_MESSAGES -> MeshEvent.NoMoreMessages

        ResponseCode.CONTACT_MESSAGE_RECEIVED -> ContactMessageParser.parse(payload, ContactMessageParser.Version.V1)

        ResponseCode.CONTACT_MESSAGE_RECEIVED_V3 -> ContactMessageParser.parse(payload, ContactMessageParser.Version.V3)

        ResponseCode.CHANNEL_MESSAGE_RECEIVED -> ChannelMessageParser.parse(payload, ChannelMessageParser.Version.V1)

        ResponseCode.CHANNEL_MESSAGE_RECEIVED_V3 -> ChannelMessageParser.parse(payload, ChannelMessageParser.Version.V3)

        ResponseCode.CHANNEL_DATA_RECEIVED -> ChannelDatagramParser.parse(payload)

        else -> MeshEvent.ParseFailure(payload, "Unexpected code in message response: $code")
    }

    // MARK: - Push Notifications

    /** Handles asynchronous push notifications from the device. */
    private fun parsePushNotification(code: ResponseCode, payload: ByteArray): MeshEvent = when (code) {
        ResponseCode.ACK ->
            if (payload.size < PacketSize.ACK_MINIMUM) {
                MeshEvent.ParseFailure(payload, "Ack response too short: ${payload.size} < ${PacketSize.ACK_MINIMUM}")
            } else {
                val ackCode = payload.prefixBytes(PacketSize.ACK_MINIMUM)
                val tripTime = if (payload.size >= PacketSize.ACK_WITH_TRIP_TIME) {
                    payload.readUInt32LE(PacketSize.ACK_MINIMUM)
                } else {
                    null
                }
                MeshEvent.Acknowledgement(code = ackCode, tripTime = tripTime)
            }

        ResponseCode.MESSAGES_WAITING -> MeshEvent.MessagesWaiting

        ResponseCode.ADVERTISEMENT -> AdvertisementParser.parse(payload)

        ResponseCode.NEW_ADVERTISEMENT -> NewAdvertisementParser.parse(payload)

        ResponseCode.PATH_UPDATE -> PathUpdateParser.parse(payload)

        ResponseCode.STATUS_RESPONSE -> StatusResponseParser.parse(payload)

        ResponseCode.TELEMETRY_RESPONSE -> TelemetryResponseParser.parse(payload)

        ResponseCode.BINARY_RESPONSE -> BinaryResponseParser.parse(payload)

        ResponseCode.PATH_DISCOVERY_RESPONSE -> PathDiscoveryResponseParser.parse(payload)

        ResponseCode.CONTROL_DATA -> ControlDataParser.parse(payload)

        ResponseCode.CONTACT_DELETED -> ContactDeletedParser.parse(payload)

        ResponseCode.CONTACTS_FULL -> ContactsFullParser.parse(payload)

        else -> MeshEvent.ParseFailure(payload, "Unexpected code in push notification: $code")
    }

    // MARK: - Login Responses

    /** Handles authentication-related responses. */
    private fun parseLoginResponse(code: ResponseCode, payload: ByteArray): MeshEvent = when (code) {
        ResponseCode.LOGIN_SUCCESS -> LoginSuccessParser.parse(payload)

        ResponseCode.LOGIN_FAILED -> {
            // 1 reserved byte + optional 6-byte pubkey prefix
            val pubkeyPrefix = if (payload.size >= 7) payload.copyOfRange(1, 7) else null
            MeshEvent.LoginFailed(pubkeyPrefix)
        }

        else -> MeshEvent.ParseFailure(payload, "Unexpected code in login response: $code")
    }

    // MARK: - Signing Responses

    /** Handles responses from the cryptographic signing engine. */
    private fun parseSigningResponse(code: ResponseCode, payload: ByteArray): MeshEvent = when (code) {
        ResponseCode.SIGN_START ->
            // Per Python reader.py:716-719: 1 reserved + 4 bytes max_length
            if (payload.size < PacketSize.SIGN_START_MINIMUM) {
                MeshEvent.ParseFailure(
                    payload,
                    "SignStart response too short: ${payload.size} < ${PacketSize.SIGN_START_MINIMUM}",
                )
            } else {
                MeshEvent.SignStart(payload.readUInt32LE(1).toInt())
            }

        ResponseCode.SIGNATURE -> SignatureParser.parse(payload)

        else -> MeshEvent.ParseFailure(payload, "Unexpected code in signing response: $code")
    }

    // MARK: - Misc Responses

    /** Handles utility responses like statistics and channel info. */
    private fun parseMiscResponse(code: ResponseCode, payload: ByteArray): MeshEvent = when (code) {
        ResponseCode.STATS ->
            if (payload.isEmpty()) {
                MeshEvent.ParseFailure(payload, "Stats response too short: ${payload.size} < 1")
            } else {
                val statsType = payload[0].toUByte()
                val statsPayload = payload.copyOfRange(1, payload.size)

                when (statsType) {
                    StatsType.CORE.value -> CoreStatsParser.parse(statsPayload)
                    StatsType.RADIO.value -> RadioStatsParser.parse(statsPayload)
                    StatsType.PACKETS.value -> PacketStatsParser.parse(statsPayload)
                    else -> MeshEvent.ParseFailure(payload, "Unknown stats type: $statsType")
                }
            }

        ResponseCode.CHANNEL_INFO -> ChannelInfoParser.parse(payload)

        ResponseCode.CUSTOM_VARS -> CustomVarsParser.parse(payload)

        ResponseCode.RAW_DATA -> RawDataParser.parse(payload)

        ResponseCode.LOG_DATA -> LogDataParser.parse(payload)

        ResponseCode.TRACE_DATA -> TraceDataParser.parse(payload)

        else -> MeshEvent.ParseFailure(payload, "Unexpected code in misc response: $code")
    }
}
