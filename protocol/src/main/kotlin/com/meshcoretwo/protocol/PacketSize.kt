// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

// MARK: - Packet Size Constants

/** Named constants for packet size validation to avoid magic numbers. */
internal object PacketSize {
    /** Full contact structure size. */
    const val CONTACT = 147
    /** Minimum size for self info response. */
    const val SELF_INFO_MINIMUM = 57
    /** Minimum size for message sent confirmation. */
    const val MESSAGE_SENT_MINIMUM = 9
    /** Minimum size for version 1 contact messages. */
    const val CONTACT_MESSAGE_V1_MINIMUM = 12
    /** Minimum size for version 3 contact messages. */
    const val CONTACT_MESSAGE_V3_MINIMUM = 15
    /** Minimum size for version 1 channel messages. */
    const val CHANNEL_MESSAGE_V1_MINIMUM = 7
    /** Minimum size for version 3 channel messages. */
    const val CHANNEL_MESSAGE_V3_MINIMUM = 10
    /** Minimum size for private key export. */
    const val PRIVATE_KEY_MINIMUM = 64
    /** Minimum size for basic battery info. */
    const val BATTERY_MINIMUM = 2
    /** Size for battery info with storage statistics. */
    const val BATTERY_EXTENDED = 10
    /** Minimum size for signing session start. */
    const val SIGN_START_MINIMUM = 5
    /** Full size for version 3 device info. */
    const val DEVICE_INFO_V3_FULL = 79
    /** Minimum size for acknowledgement packets. */
    const val ACK_MINIMUM = 4
    /** Size for acknowledgement packets that include firmware trip time. */
    const val ACK_WITH_TRIP_TIME = 8
    /** Minimum size for contact synchronization start. */
    const val CONTACTS_START_MINIMUM = 4
    /** Minimum size for core system statistics. */
    const val CORE_STATS_MINIMUM = 9
    /** Minimum size for radio statistics. */
    const val RADIO_STATS_MINIMUM = 12
    /** Minimum size for packet counters. */
    const val PACKET_STATS_MINIMUM = 24
    /** Size for packet counters with receive errors field. */
    const val PACKET_STATS_WITH_RECEIVE_ERRORS = 28
    /** Minimum size for channel configuration info. */
    const val CHANNEL_INFO_MINIMUM = 49
    /** Size of the public key in contact deleted notifications. */
    const val CONTACT_DELETED_PUBLIC_KEY = 32
    /**
     * Minimum size for status response push notification.
     * Format: `reserved(1) + pubkey(6) + fields(51) = 58 bytes`
     */
    const val STATUS_RESPONSE_MINIMUM = 58
    /** Minimum size for trace route data. */
    const val TRACE_DATA_MINIMUM = 11
    /**
     * Minimum size for raw packet data.
     * Format: `[snr:1][rssi:1][reserved:1]`
     */
    const val RAW_DATA_MINIMUM = 3
    /** Minimum size for control protocol data. */
    const val CONTROL_DATA_MINIMUM = 4
    /**
     * Minimum size for path discovery results.
     * Format: `reserved(1) + pubkey(6) + out_path_len(1) + in_path_len(1) = 9 bytes`
     */
    const val PATH_DISCOVERY_MINIMUM = 9
    /**
     * Minimum size for login success response (legacy format).
     * Format: `[adminIndicator:1][pubkeyPrefix:6]` (companion radio hardcodes `0` for
     * legacy "OK" replies).
     */
    const val LOGIN_SUCCESS_MINIMUM = 7
    /**
     * Size for v7+ login success with ACL permissions.
     * Format: `[adminIndicator:1][pubkeyPrefix:6][timestamp:4][aclPermissions:1][fwVersion:1]`
     * where `adminIndicator == 1` means admin; any other value is non-admin.
     */
    const val LOGIN_SUCCESS_EXTENDED = 13
    /** Size for binary response status payload without rxAirtime field (48 bytes). */
    const val BINARY_RESPONSE_STATUS_BASE = 48
    /** Minimum size for binary response status payload with rxAirtime field (52 bytes). */
    const val BINARY_RESPONSE_STATUS_WITH_RX_AIRTIME = 52
    /** Minimum size for binary response status payload with receiveErrors field (56 bytes). */
    const val BINARY_RESPONSE_STATUS_WITH_RECEIVE_ERRORS = 56
    /**
     * Minimum size for channel datagram payload (header fields before data).
     * Format: `[snr:1][rsv:2][channel:1][path_len:1][data_type:2][data_len:1]` = 8 bytes.
     */
    const val CHANNEL_DATAGRAM_MINIMUM = 8
    /** Default-scope name-field width on the wire (zero-padded, null-terminated). */
    const val DEFAULT_FLOOD_SCOPE_NAME_FIELD = 31
    /** Default-scope key width on the wire. */
    const val DEFAULT_FLOOD_SCOPE_KEY_BYTES = 16
    /** Size for populated default flood scope response (name field + key). */
    const val DEFAULT_FLOOD_SCOPE_SET = DEFAULT_FLOOD_SCOPE_NAME_FIELD + DEFAULT_FLOOD_SCOPE_KEY_BYTES
}
