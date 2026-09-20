// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.meshcoretwo.android.R

/**
 * Returns a function that runs an action once `ACCESS_COARSE_LOCATION` is granted, requesting it
 * first if needed. `LocationProvider.requestCurrentLocation()` (see its class doc) deliberately
 * never triggers the runtime prompt itself — only an Activity/Compose launcher can do that — so
 * every screen with a click-triggered location action (Map/Line of Sight/Neighbor SNR map's "my
 * location" buttons) needs this. `LocationPickerScreen`'s silent best-effort camera-seed attempt is
 * deliberately not wired to this — it mirrors `LocationPickerView.loadCurrentLocation()`, which only
 * reads a fix when already authorized and never self-prompts either. Onboarding's Region step
 * ([com.meshcoretwo.android.onboarding.RegionStepView]) already requests this permission once up
 * front, but only when its own auto-detect flow runs — skipping straight to the manual picker
 * there, or denying it, leaves every other location feature permanently unable to prompt again
 * without this.
 *
 * [onDenied] is called instead of the action when the permission is refused (already-denied or
 * denied just now) — callers surface it as a snackbar/toast; this composable has no opinion on UI.
 */
@Composable
fun rememberLocationPermissionAction(onDenied: (String) -> Unit): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val latestOnDenied by rememberUpdatedState(onDenied)
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted) {
            action?.invoke()
        } else {
            latestOnDenied(context.getString(R.string.location_permission_required))
        }
    }

    return { action ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingAction = action
            launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}
