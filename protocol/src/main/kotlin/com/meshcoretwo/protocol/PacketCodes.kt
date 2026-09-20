// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/** Command codes sent from the app to the mesh device. */
enum class CommandCode(val value: UByte) {
    /** Starts the application session on the device. */
    APP_START(0x01u),
    /** Sends a direct message to a specific contact. */
    SEND_MESSAGE(0x02u),
    /** Sends a message to a specific channel index. */
    SEND_CHANNEL_MESSAGE(0x03u),
    /** Requests the list of stored contacts from the device. */
    GET_CONTACTS(0x04u),
    /** Requests the current system time from the device. */
    GET_TIME(0x05u),
    /** Sets the system time on the device. */
    SET_TIME(0x06u),
    /** Triggers a manual advertisement broadcast. */
    SEND_ADVERTISEMENT(0x07u),
    /** Sets the device name. */
    SET_NAME(0x08u),
    /** Updates an existing contact record. */
    UPDATE_CONTACT(0x09u),
    /** Fetches the next available message from the device's buffer. */
    GET_MESSAGE(0x0Au),
    /** Configures the radio parameters. */
    SET_RADIO(0x0Bu),
    /** Sets the transmit power level. */
    SET_TX_POWER(0x0Cu),
    /** Resets the routing path to a specific contact. */
    RESET_PATH(0x0Du),
    /** Sets the GPS coordinates for the device. */
    SET_COORDINATES(0x0Eu),
    /** Removes a contact from the device. */
    REMOVE_CONTACT(0x0Fu),
    /** Shares a contact with other nodes in the mesh. */
    SHARE_CONTACT(0x10u),
    /** Generates a URI for exporting a contact. */
    EXPORT_CONTACT(0x11u),
    /** Imports a contact from a provided URI or data. */
    IMPORT_CONTACT(0x12u),
    /** Reboots the device hardware. */
    REBOOT(0x13u),
    /** Requests battery and storage status. */
    GET_BATTERY(0x14u),
    /** Sets internal tuning parameters. */
    SET_TUNING(0x15u),
    /** Queries hardware capabilities and version info. */
    DEVICE_QUERY(0x16u),
    /** Exports the node's private key. */
    EXPORT_PRIVATE_KEY(0x17u),
    /** Imports a private key to the node. */
    IMPORT_PRIVATE_KEY(0x18u),
    /** Sends raw data through the mesh. */
    SEND_RAW_DATA(0x19u),
    /** Initiates a remote node login. */
    SEND_LOGIN(0x1Au),
    /** Requests status information from a remote node. */
    SEND_STATUS_REQUEST(0x1Bu),
    /** Checks if a connection exists to a specific node. */
    HAS_CONNECTION(0x1Cu),
    /** Logs out from a remote node. */
    SEND_LOGOUT(0x1Du),
    /** Retrieves a contact by public key. */
    GET_CONTACT_BY_KEY(0x1Eu),
    /** Requests channel configuration for a specific index. */
    GET_CHANNEL(0x1Fu),
    /** Sets channel configuration for a specific index. */
    SET_CHANNEL(0x20u),
    /** Begins a cryptographic signing operation. */
    SIGN_START(0x21u),
    /** Provides data for a signing operation. */
    SIGN_DATA(0x22u),
    /** Completes a signing operation and retrieves the signature. */
    SIGN_FINISH(0x23u),
    /** Sends trace data for debugging. */
    SEND_TRACE(0x24u),
    /** Sets the Bluetooth pairing PIN. */
    SET_DEVICE_PIN(0x25u),
    /** Sets miscellaneous device parameters. */
    SET_OTHER_PARAMS(0x26u),
    /** Requests self-telemetry data. */
    GET_SELF_TELEMETRY(0x27u),
    /** Requests current custom variable values. */
    GET_CUSTOM_VARS(0x28u),
    /** Sets a custom variable value. */
    SET_CUSTOM_VAR(0x29u),
    /** Retrieves the advertisement path to a contact. */
    GET_ADVERT_PATH(0x2Au),
    /** Retrieves tuning parameters. */
    GET_TUNING_PARAMS(0x2Bu),
    /** Initiates a binary data request. */
    BINARY_REQUEST(0x32u),
    /** Performs a factory reset of the device. */
    FACTORY_RESET(0x33u),
    /** Initiates a path discovery process to a remote node. */
    PATH_DISCOVERY(0x34u),
    /**
     * Sets the current (session-scoped) flood routing key.
     *
     * Firmware v1.15.0 renamed the on-device symbol `CMD_SET_FLOOD_SCOPE` to
     * `CMD_SET_FLOOD_SCOPE_KEY`; the opcode is unchanged. Use [SET_DEFAULT_FLOOD_SCOPE]
     * to persist a scope across reboots (Firmware v11+).
     */
    SET_FLOOD_SCOPE(0x36u),
    /** Sends raw control data. */
    SEND_CONTROL_DATA(0x37u),
    /** Requests device statistics. */
    GET_STATS(0x38u),
    /** Sends an anonymous request to a remote node. */
    SEND_ANON_REQ(0x39u),
    /** Sets the auto-add configuration bitmask. */
    SET_AUTO_ADD_CONFIG(0x3Au),
    /** Gets the current auto-add configuration bitmask. */
    GET_AUTO_ADD_CONFIG(0x3Bu),
    /** Gets the allowed frequency ranges for client repeat mode (v9+). */
    GET_REPEAT_FREQ(0x3Cu),
    /** Sets the path hash mode (0=1-byte, 1=2-byte, 2=3-byte hashes). */
    SET_PATH_HASH_MODE(0x3Du),
    /** Sends a binary datagram to a channel. Firmware v11+ (MeshCore v1.15.0+). */
    SEND_CHANNEL_DATA(0x3Eu),
    /** Sets the persisted default flood scope (name + 16-byte key). Firmware v11+ (MeshCore v1.15.0+). */
    SET_DEFAULT_FLOOD_SCOPE(0x3Fu),
    /** Gets the persisted default flood scope. Firmware v11+ (MeshCore v1.15.0+). */
    GET_DEFAULT_FLOOD_SCOPE(0x40u),
    /**
     * Injects a raw packet onto the mesh (priority byte followed by the raw packet bytes). Firmware v12+.
     *
     * No builder is provided; this library does not use raw-packet injection. The case
     * exists so the command table stays complete against firmware.
     */
    SEND_RAW_PACKET(0x41u);

    companion object {
        private val byValue = entries.associateBy { it.value }
        fun fromValue(value: UByte): CommandCode? = byValue[value]
    }
}

/** Response codes received from the mesh device. */
enum class ResponseCode(val value: UByte) {
    /** Command executed successfully. */
    OK(0x00u),
    /** Command execution failed. */
    ERROR(0x01u),
    /** Indicates the start of a contact list transfer. */
    CONTACT_START(0x02u),
    /** Contains a single contact record. */
    CONTACT(0x03u),
    /** Indicates the end of a contact list transfer. */
    CONTACT_END(0x04u),
    /** Contains device configuration info. */
    SELF_INFO(0x05u),
    /** Confirms that a message was successfully queued for transmission. */
    MESSAGE_SENT(0x06u),
    /** Indicates a direct message was received from a contact. */
    CONTACT_MESSAGE_RECEIVED(0x07u),
    /** Indicates a message was received on a channel. */
    CHANNEL_MESSAGE_RECEIVED(0x08u),
    /** Contains the current system time. */
    CURRENT_TIME(0x09u),
    /** Indicates no more messages are available in the buffer. */
    NO_MORE_MESSAGES(0x0Au),
    /** Contains a contact export URI. */
    CONTACT_URI(0x0Bu),
    /** Contains battery and storage status. */
    BATTERY(0x0Cu),
    /** Contains hardware and firmware version info. */
    DEVICE_INFO(0x0Du),
    /** Contains the exported private key. */
    PRIVATE_KEY(0x0Eu),
    /** Indicates a feature is disabled. */
    DISABLED(0x0Fu),
    /** Indicates a V3 format direct message was received. */
    CONTACT_MESSAGE_RECEIVED_V3(0x10u),
    /** Indicates a V3 format channel message was received. */
    CHANNEL_MESSAGE_RECEIVED_V3(0x11u),
    /** Contains channel configuration details. */
    CHANNEL_INFO(0x12u),
    /** Confirms the start of a signing operation. */
    SIGN_START(0x13u),
    /** Contains the generated signature. */
    SIGNATURE(0x14u),
    /** Contains custom variable values. */
    CUSTOM_VARS(0x15u),
    /** Contains advertisement path information. */
    ADVERT_PATH(0x16u),
    /** Contains tuning parameters. */
    TUNING_PARAMS(0x17u),
    /** Contains device statistics. */
    STATS(0x18u),
    /** Contains the auto-add configuration bitmask. */
    AUTO_ADD_CONFIG(0x19u),
    /** Contains the allowed frequency ranges for client repeat mode (v9+). */
    ALLOWED_REPEAT_FREQ(0x1Au),
    /** A binary datagram was received on a channel. Firmware v11+ (MeshCore v1.15.0+). */
    CHANNEL_DATA_RECEIVED(0x1Bu),
    /** Contains the persisted default flood scope. Firmware v11+ (MeshCore v1.15.0+). */
    DEFAULT_FLOOD_SCOPE(0x1Cu),

    // Push notifications (0x80+)
    /** Indicates a node advertisement was received. */
    ADVERTISEMENT(0x80u),
    /** Indicates a routing path update occurred. */
    PATH_UPDATE(0x81u),
    /** Indicates a message acknowledgment was received. */
    ACK(0x82u),
    /** Indicates messages are waiting to be fetched. */
    MESSAGES_WAITING(0x83u),
    /** Contains raw protocol data. */
    RAW_DATA(0x84u),
    /** Indicates a remote login was successful. */
    LOGIN_SUCCESS(0x85u),
    /** Indicates a remote login failed. */
    LOGIN_FAILED(0x86u),
    /** Contains a response to a status request. */
    STATUS_RESPONSE(0x87u),
    /** Contains raw log output. */
    LOG_DATA(0x88u),
    /** Contains trace debugging data. */
    TRACE_DATA(0x89u),
    /** Indicates a new node was discovered. */
    NEW_ADVERTISEMENT(0x8Au),
    /** Contains telemetry data from a remote node. */
    TELEMETRY_RESPONSE(0x8Bu),
    /** Contains binary data requested from a remote node. */
    BINARY_RESPONSE(0x8Cu),
    /** Contains the result of a path discovery operation. */
    PATH_DISCOVERY_RESPONSE(0x8Du),
    /** Contains raw control data. */
    CONTROL_DATA(0x8Eu),
    /** Indicates a contact was automatically deleted (overwritten by auto-add). */
    CONTACT_DELETED(0x8Fu),
    /** Indicates the device's contact storage is full. */
    CONTACTS_FULL(0x90u);

    companion object {
        private val byValue = entries.associateBy { it.value }
        fun fromValue(value: UByte): ResponseCode? = byValue[value]
    }
}

/** Types of binary requests used in asynchronous operations. */
enum class BinaryRequestType(val value: UByte) {
    /** Requests status information. */
    STATUS(0x01u),
    /** Sends a keep-alive signal. */
    KEEP_ALIVE(0x02u),
    /** Requests telemetry data. */
    TELEMETRY(0x03u),
    /** Requests Min/Max/Average data. */
    MMA(0x04u),
    /** Requests Access Control List data. */
    ACL(0x05u),
    /** Requests the list of visible neighbor nodes. */
    NEIGHBOURS(0x06u),
    /** Requests owner information from a repeater. */
    OWNER_INFO(0x07u);

    companion object {
        private val byValue = entries.associateBy { it.value }
        fun fromValue(value: UByte): BinaryRequestType? = byValue[value]
    }
}

/** Types of anonymous requests that can be sent to remote nodes. */
enum class AnonRequestType(val value: UByte) {
    /** Requests the list of allowed regions from a repeater. */
    REGIONS(0x01u),
    /** Requests owner information from a repeater. */
    OWNER(0x02u),
    /** Requests basic information (clock, features) from a repeater. */
    BASIC(0x03u);

    companion object {
        private val byValue = entries.associateBy { it.value }
        fun fromValue(value: UByte): AnonRequestType? = byValue[value]
    }
}

/** Types of control data packets. */
enum class ControlType(val value: UByte) {
    /** Requests node discovery. */
    NODE_DISCOVER_REQUEST(0x80u),
    /** Provides a response to a node discovery request. */
    NODE_DISCOVER_RESPONSE(0x90u);

    companion object {
        private val byValue = entries.associateBy { it.value }
        fun fromValue(value: UByte): ControlType? = byValue[value]
    }
}

/** Categories of statistics that can be requested from the device. */
enum class StatsType(val value: UByte) {
    /** Core system statistics. */
    CORE(0x00u),
    /** Radio hardware statistics. */
    RADIO(0x01u),
    /** Packet processing statistics. */
    PACKETS(0x02u);

    companion object {
        private val byValue = entries.associateBy { it.value }
        fun fromValue(value: UByte): StatsType? = byValue[value]
    }
}

/** Encoding type for messages. */
enum class TextType(val value: UByte) {
    /** Plain UTF-8 text. */
    PLAIN_TEXT(0x00u),
    /** A CLI command (firmware TXT_TYPE_CLI_DATA). */
    CLI_DATA(0x01u),
    /** Cryptographically signed message. */
    SIGNED(0x02u);

    companion object {
        private val byValue = entries.associateBy { it.value }
        fun fromValue(value: UByte): TextType? = byValue[value]
    }
}

// MARK: - Response Categories

/** Categorizes response codes for routing to specialized domain parsers. */
enum class ResponseCategory {
    /** Basic success or error responses. */
    SIMPLE,
    /** Device-related status and configuration. */
    DEVICE,
    /** Contact list management responses. */
    CONTACT,
    /** Messaging and buffer status. */
    MESSAGE,
    /** Asynchronous push notifications. */
    PUSH,
    /** Remote authentication responses. */
    LOGIN,
    /** Cryptographic signing results. */
    SIGNING,
    /** Miscellaneous data and logs. */
    MISC,
}

/** Determines the category for this response code to facilitate routing. */
val ResponseCode.category: ResponseCategory
    get() = when (this) {
        ResponseCode.OK, ResponseCode.ERROR ->
            ResponseCategory.SIMPLE
        ResponseCode.SELF_INFO, ResponseCode.DEVICE_INFO, ResponseCode.BATTERY, ResponseCode.CURRENT_TIME,
        ResponseCode.PRIVATE_KEY, ResponseCode.DISABLED, ResponseCode.ADVERT_PATH, ResponseCode.TUNING_PARAMS,
        ResponseCode.AUTO_ADD_CONFIG, ResponseCode.ALLOWED_REPEAT_FREQ, ResponseCode.DEFAULT_FLOOD_SCOPE ->
            ResponseCategory.DEVICE
        ResponseCode.CONTACT_START, ResponseCode.CONTACT, ResponseCode.CONTACT_END, ResponseCode.CONTACT_URI ->
            ResponseCategory.CONTACT
        ResponseCode.MESSAGE_SENT, ResponseCode.CONTACT_MESSAGE_RECEIVED, ResponseCode.CONTACT_MESSAGE_RECEIVED_V3,
        ResponseCode.CHANNEL_MESSAGE_RECEIVED, ResponseCode.CHANNEL_MESSAGE_RECEIVED_V3,
        ResponseCode.CHANNEL_DATA_RECEIVED, ResponseCode.NO_MORE_MESSAGES ->
            ResponseCategory.MESSAGE
        ResponseCode.ADVERTISEMENT, ResponseCode.PATH_UPDATE, ResponseCode.ACK, ResponseCode.MESSAGES_WAITING,
        ResponseCode.NEW_ADVERTISEMENT, ResponseCode.STATUS_RESPONSE, ResponseCode.TELEMETRY_RESPONSE,
        ResponseCode.BINARY_RESPONSE, ResponseCode.PATH_DISCOVERY_RESPONSE, ResponseCode.CONTROL_DATA,
        ResponseCode.CONTACT_DELETED, ResponseCode.CONTACTS_FULL ->
            ResponseCategory.PUSH
        ResponseCode.LOGIN_SUCCESS, ResponseCode.LOGIN_FAILED ->
            ResponseCategory.LOGIN
        ResponseCode.SIGN_START, ResponseCode.SIGNATURE ->
            ResponseCategory.SIGNING
        ResponseCode.STATS, ResponseCode.CUSTOM_VARS, ResponseCode.CHANNEL_INFO, ResponseCode.RAW_DATA,
        ResponseCode.LOG_DATA, ResponseCode.TRACE_DATA ->
            ResponseCategory.MISC
    }
