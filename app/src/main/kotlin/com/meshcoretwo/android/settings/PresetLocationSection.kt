// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoretwo.android.R
import com.meshcoretwo.android.onboarding.RegionPickerRows
import com.meshcoretwo.android.onboarding.isLocationGranted
import com.meshcoretwo.android.settings.PresetLocationPolicy.ResolveKind
import com.meshcoretwo.android.ui.components.SettingsListRow
import com.meshcoretwo.services.region.RegionResolver
import com.meshcoretwo.services.region.RegionSelectionStore
import com.meshcoretwo.services.region.RegionalAreas
import kotlinx.coroutines.launch

/**
 * Preset-location filter on Settings → Radio: narrows [RadioPresetPickerDialog]'s list to the
 * chosen place without writing any radio parameters. Hidden by the caller when Repeat Mode is on.
 * Ported from `PresetLocationSection.swift`/`PresetLocationSession.swift`/`PresetLocationView.swift`,
 * folded into one row + dialog (the iOS inline-expanded/pushed-subpage split has no analogue in this
 * screen's flat structure). Silently refreshes from GPS once when first composed, unless the user
 * picked a place by hand.
 */
@Composable
internal fun PresetLocationSection(regionSelectionStore: RegionSelectionStore, regionResolver: RegionResolver) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selection by regionSelectionStore.selection.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    var isResolving by remember { mutableStateOf(false) }
    var lookupMiss by remember { mutableStateOf(false) }
    var showOpenSettings by remember { mutableStateOf(false) }

    fun resolve(kind: ResolveKind) {
        if (isResolving) return
        scope.launch {
            isResolving = true
            try {
                val result = regionResolver.resolve()
                val next = PresetLocationPolicy.committedSelection(regionSelectionStore.selection.value, result, kind)
                if (next != regionSelectionStore.selection.value) regionSelectionStore.set(next)
                if (result == null && PresetLocationPolicy.shouldPresentLookupMiss(kind)) lookupMiss = true
            } finally {
                isResolving = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) resolve(ResolveKind.USER_INITIATED) else showOpenSettings = true
    }

    LaunchedEffect(Unit) {
        if (PresetLocationPolicy.shouldResolveOnAppear(isLocationGranted(context), regionSelectionStore.selection.value?.source)) {
            resolve(ResolveKind.APPEAR)
        }
    }

    SettingsListRow(
        title = stringResource(R.string.settings_location),
        value = selection?.let(RegionalAreas::displayName) ?: stringResource(R.string.settings_not_set),
        trailing = { TextButton(onClick = { showDialog = true }) { Text(stringResource(R.string.common_change)) } },
    )
    Text(
        stringResource(R.string.preset_location_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.preset_location_title)) },
            text = {
                Column {
                    RegionPickerRows(selection = selection, onSelectionChange = regionSelectionStore::set)
                    TextButton(
                        onClick = {
                            when (PresetLocationPolicy.useMyLocationAction(isLocationGranted(context))) {
                                PresetLocationPolicy.UseMyLocationAction.RESOLVE -> resolve(ResolveKind.USER_INITIATED)
                                PresetLocationPolicy.UseMyLocationAction.REQUEST_PERMISSION ->
                                    permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                            }
                        },
                        enabled = !isResolving,
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        if (isResolving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.preset_locating))
                        } else {
                            Text(stringResource(R.string.region_use_my_location))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.common_done)) } },
        )
    }

    if (lookupMiss) {
        AlertDialog(
            onDismissRequest = { lookupMiss = false },
            title = { Text(stringResource(R.string.preset_location_not_found_title)) },
            text = { Text(stringResource(R.string.preset_location_not_found_body)) },
            confirmButton = { TextButton(onClick = { lookupMiss = false }) { Text(stringResource(R.string.common_ok)) } },
        )
    }

    if (showOpenSettings) {
        AlertDialog(
            onDismissRequest = { showOpenSettings = false },
            title = { Text(stringResource(R.string.preset_location_off_title)) },
            text = { Text(stringResource(R.string.preset_location_off_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showOpenSettings = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text(stringResource(R.string.common_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { showOpenSettings = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
