// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

/** Reasons for disconnecting from a device (for debugging). Ported from `DisconnectReason.swift`. */
enum class DisconnectReason(val description: String) {
    USER_INITIATED("user initiated disconnect"),
    STATUS_MENU_DISCONNECT_TAP("status menu disconnect tapped"),
    SWITCHING_DEVICE("switching to new device"),
    FACTORY_RESET("device factory reset"),
    WIFI_ADDRESS_CHANGE("WiFi address changed"),
    RESYNC_FAILED("resync failed after 3 attempts"),
    FORGET_DEVICE("user forgot device"),
    DEVICE_REMOVED_FROM_SETTINGS("device removed from Android settings"),
    PAIRING_FAILED("device pairing failed"),
    WIFI_RECONNECT_PREP("preparing for WiFi reconnect"),
}
