// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

/**
 * Shared [android.content.SharedPreferences] keys for [ConnectionIntent] and [LastConnectionStore].
 * Ported from `PersistenceKeys.swift`, trimmed to the connection-infrastructure subset this slice
 * needs — the theme/app-color-scheme keys are Phase 5 UI and not ported here.
 *
 * Both types share [NotificationPreferences.PREFS_NAME][com.meshcoretwo.services.notifications.NotificationPreferences.PREFS_NAME]
 * (`"app_storage"`), matching iOS's single `UserDefaults.standard` namespace.
 */
object PersistenceKeys {
    const val LAST_CONNECTED_DEVICE_ID = "com.meshcoretwo.lastConnectedDeviceID"
    const val LAST_CONNECTED_DEVICE_NAME = "com.meshcoretwo.lastConnectedDeviceName"
    const val LAST_CONNECTED_RADIO_ID = "com.meshcoretwo.lastConnectedRadioID"
    const val LAST_DISCONNECT_DIAGNOSTIC = "com.meshcoretwo.lastDisconnectDiagnostic"
    const val LAST_BOND_VERIFIED_DEVICE_ID = "com.meshcoretwo.lastBondVerifiedDeviceID"
    const val LAST_BOND_VERIFIED_DATE = "com.meshcoretwo.lastBondVerifiedDate"
    const val USER_EXPLICITLY_DISCONNECTED = "com.meshcoretwo.userExplicitlyDisconnected"
}
