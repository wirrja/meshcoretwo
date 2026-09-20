// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.Instant
import java.util.UUID

/**
 * [SharedPreferences]-backed record of the last connected device (device ID, radio ID, device
 * name) plus the most recent disconnect diagnostic and the last verified BLE bond. Ported from
 * `LastConnectionStore.swift`.
 *
 * `ConnectionManager` (not yet ported) will own the only production instance and expose thin
 * forwarders so callers never touch the keys directly. [ConnectionIntent] persistence is
 * deliberately separate: intent is the "does the user want to be connected" axis, not
 * last-device state.
 */
class LastConnectionStore(private val prefs: SharedPreferences) {
    /** The last connected device ID (for auto-reconnect). */
    val deviceID: UUID?
        get() = prefs.getString(PersistenceKeys.LAST_CONNECTED_DEVICE_ID, null)?.let(::runCatchingUUID)

    /** The last connected radio ID (for offline data scoping). */
    val radioID: UUID?
        get() = prefs.getString(PersistenceKeys.LAST_CONNECTED_RADIO_ID, null)?.let(::runCatchingUUID)

    /** The last connected device name (for offline display when disconnected). */
    val deviceName: String? get() = prefs.getString(PersistenceKeys.LAST_CONNECTED_DEVICE_NAME, null)

    /** Records a successful connection for future restoration. */
    fun persist(deviceID: UUID, radioID: UUID, deviceName: String) {
        prefs.edit {
            putString(PersistenceKeys.LAST_CONNECTED_DEVICE_ID, deviceID.toString())
            putString(PersistenceKeys.LAST_CONNECTED_RADIO_ID, radioID.toString())
            putString(PersistenceKeys.LAST_CONNECTED_DEVICE_NAME, deviceName)
        }
    }

    /**
     * Clears persisted records that belong to [deviceID].
     *
     * Last-connection keys are removed only when that device is the last-connected holder.
     * Bond-verification keys are removed only when that device holds the bond slot — an
     * unconditional wipe would destroy another radio's cross-launch shield when forgetting a
     * non-holder (for example after a WiFi connection overwrote the connection slot without
     * touching the bond slot).
     */
    fun clear(deviceID: UUID) {
        prefs.edit {
            if (this@LastConnectionStore.deviceID == deviceID) {
                remove(PersistenceKeys.LAST_CONNECTED_DEVICE_ID)
                remove(PersistenceKeys.LAST_CONNECTED_RADIO_ID)
                remove(PersistenceKeys.LAST_CONNECTED_DEVICE_NAME)
            }
            if (bondVerifiedDeviceID == deviceID) {
                remove(PersistenceKeys.LAST_BOND_VERIFIED_DEVICE_ID)
                remove(PersistenceKeys.LAST_BOND_VERIFIED_DATE)
            }
        }
    }

    /**
     * Records that the given device's bond completed a verified encrypted session just now.
     * Single-slot like the rest of the store: one radio is connected at a time, and the check is
     * keyed to the device ID.
     */
    fun persistBondVerification(deviceID: UUID) {
        prefs.edit {
            putString(PersistenceKeys.LAST_BOND_VERIFIED_DEVICE_ID, deviceID.toString())
            putLong(PersistenceKeys.LAST_BOND_VERIFIED_DATE, Instant.now().toEpochMilli())
        }
    }

    /**
     * The device holding the bond-verification slot, or null when no bond has verified. Distinct
     * from [deviceID]: a WiFi connection overwrites the connection slot without touching the bond
     * slot, so cross-launch grace seeding must key off this value.
     */
    val bondVerifiedDeviceID: UUID?
        get() = prefs.getString(PersistenceKeys.LAST_BOND_VERIFIED_DEVICE_ID, null)?.let(::runCatchingUUID)

    /**
     * When the given device's bond last completed a verified encrypted session, or null if it
     * never has (or the slot belongs to a different device).
     */
    fun bondVerificationDate(deviceID: UUID): Instant? {
        if (prefs.getString(PersistenceKeys.LAST_BOND_VERIFIED_DEVICE_ID, null) != deviceID.toString()) return null
        val epochMilli = prefs.getLong(PersistenceKeys.LAST_BOND_VERIFIED_DATE, -1L)
        return if (epochMilli < 0) null else Instant.ofEpochMilli(epochMilli)
    }

    /** Most recent disconnect diagnostic summary persisted across app launches. */
    val disconnectDiagnostic: String? get() = prefs.getString(PersistenceKeys.LAST_DISCONNECT_DIAGNOSTIC, null)

    /** Persists a disconnect diagnostic prefixed with the current ISO-8601 timestamp. */
    fun persistDisconnectDiagnostic(summary: String) {
        prefs.edit { putString(PersistenceKeys.LAST_DISCONNECT_DIAGNOSTIC, "${Instant.now()} $summary") }
    }

    private fun runCatchingUUID(value: String): UUID? = try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        null
    }
}
