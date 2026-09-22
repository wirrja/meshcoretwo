// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.meshcoretwo.android.settings.LocationPickerScreen
import com.meshcoretwo.services.location.LocationProvider
import org.maplibre.android.geometry.LatLng

/**
 * "Pick on Map" for a repeater/room's Identity & Location section — the other
 * [LocationPickerScreen] caller alongside `DeviceLocationPickerScreen`, ported from
 * `LocationPickerView`'s use in `RepeaterSettingsView.swift`/`RoomSettingsView.swift`: opened on
 * the fields' current (possibly just-typed, not-yet-applied) [initialLatitude]/[initialLongitude]
 * rather than the radio's last-confirmed value, and [onSave] just writes the picked coordinate back
 * into those same editable fields ([NodeSettingsViewModel.setLocationFromPicker]) — no radio access
 * here, so there is nothing to report a save error for; the existing "Apply Identity Settings"
 * button is what actually sends `set lat`/`set lon` once the user confirms.
 */
@Composable
fun RemoteNodeLocationPickerScreen(
    initialLatitude: Double?,
    initialLongitude: Double?,
    locationProvider: LocationProvider,
    onSave: (latitude: Double, longitude: Double) -> Unit,
    onCancel: () -> Unit,
) {
    val initial = remember(initialLatitude, initialLongitude) {
        if (initialLatitude != null && initialLongitude != null && (initialLatitude != 0.0 || initialLongitude != 0.0)) {
            LatLng(initialLatitude, initialLongitude)
        } else {
            null
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }

    LocationPickerScreen(
        initial = initial,
        locationProvider = locationProvider,
        isSaving = false,
        snackbarHostState = snackbarHostState,
        onSave = { latLng -> onSave(latLng.latitude, latLng.longitude) },
        onBack = onCancel,
        // Opaque: presented as a same-destination overlay over the settings screen (not a nav
        // push), so the default transparent-over-shared-backdrop container would let that screen
        // show through — see LocationPickerScreen's doc.
        containerColor = MaterialTheme.colorScheme.background,
    )
}
