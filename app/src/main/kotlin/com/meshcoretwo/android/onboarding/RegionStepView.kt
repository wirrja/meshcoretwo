// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.meshcoretwo.android.AppViewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.services.region.RegionSelection
import com.meshcoretwo.services.region.RegionalAreas

/**
 * Onboarding step 4. Resolves region from location when authorized; falls back to the manual
 * picker on any failure (denied, timeout, no geocoder result) or when permission is denied.
 * Ported from `RegionStepView.swift`.
 *
 * The permission-request plumbing has no iOS analogue: `RegionResolver.resolve()` triggers the
 * system prompt implicitly on iOS (`CLLocationManager.requestWhenInUseAuthorization()`), while
 * Android can only request a runtime permission from an Activity/Compose launcher, so this view
 * fires that launcher itself on first composition instead — the outcome (auto-detect if granted,
 * straight to the manual picker if not) matches iOS either way.
 */
private sealed class RegionStepUiState {
    data object Resolving : RegionStepUiState()
    data class Detected(val region: RegionSelection) : RegionStepUiState()
    data object ManualPicker : RegionStepUiState()
}

@Composable
fun RegionStepView(appViewModel: AppViewModel, onContinue: () -> Unit) {
    val context = LocalContext.current
    var uiState by remember { mutableStateOf<RegionStepUiState>(RegionStepUiState.Resolving) }
    var manualSelection by remember { mutableStateOf<RegionSelection?>(null) }
    var resolveAttempt by remember { mutableIntStateOf(0) }
    var locationGranted by remember { mutableStateOf(isLocationGranted(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationGranted = granted
        if (granted) resolveAttempt++ else uiState = RegionStepUiState.ManualPicker
    }

    LaunchedEffect(Unit) {
        if (locationGranted) resolveAttempt++ else permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    LaunchedEffect(resolveAttempt) {
        if (resolveAttempt == 0) return@LaunchedEffect
        uiState = RegionStepUiState.Resolving
        val region = appViewModel.regionResolver.resolve()
        uiState = region?.let { RegionStepUiState.Detected(it) } ?: RegionStepUiState.ManualPicker
    }

    fun commit(region: RegionSelection) {
        appViewModel.setRegionSelection(region)
        onContinue()
    }

    // Manual radio settings finish onboarding here, skipping the preset step; a region picked or
    // detected so far is still kept for Settings' preset location.
    val manualSettings: @Composable () -> Unit = {
        ManualRadioSettingsButton(appViewModel = appViewModel, onApplied = {
            val region = (uiState as? RegionStepUiState.Detected)?.region ?: manualSelection
            region?.let(appViewModel::setRegionSelection)
            appViewModel.onboardingState.completeOnboarding()
        })
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            is RegionStepUiState.Resolving -> ResolvingContent()
            is RegionStepUiState.Detected -> DetectedContent(
                region = state.region,
                onChooseAnother = { uiState = RegionStepUiState.ManualPicker },
                onUseThisRegion = { commit(state.region) },
                manualSettings = manualSettings,
            )
            is RegionStepUiState.ManualPicker -> ManualPickerContent(
                selection = manualSelection,
                onSelectionChange = { manualSelection = it },
                showUseMyLocation = locationGranted,
                onUseMyLocation = { resolveAttempt++ },
                onContinue = { manualSelection?.let(::commit) },
                manualSettings = manualSettings,
            )
        }
    }
}

@Composable
private fun ResolvingContent() {
    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.region_finding), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DetectedContent(
    region: RegionSelection,
    onChooseAnother: () -> Unit,
    onUseThisRegion: () -> Unit,
    manualSettings: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
        Text(stringResource(R.string.region_choose_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.region_choose_subtitle),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(32.dp))
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.region_detected),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(RegionalAreas.displayName(region), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.region_from_location),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onChooseAnother) { Text(stringResource(R.string.region_choose_different)) }
        Spacer(modifier = Modifier.height(12.dp))
        manualSettings()
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = onUseThisRegion, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.region_use_this))
        }
    }
}

@Composable
private fun ManualPickerContent(
    selection: RegionSelection?,
    onSelectionChange: (RegionSelection) -> Unit,
    showUseMyLocation: Boolean,
    onUseMyLocation: () -> Unit,
    onContinue: () -> Unit,
    manualSettings: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
        Text(stringResource(R.string.region_choose_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.region_choose_subtitle),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(24.dp))

        RegionPickerRows(selection = selection, onSelectionChange = onSelectionChange)

        if (showUseMyLocation) {
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onUseMyLocation, contentPadding = PaddingValues(vertical = 8.dp)) { Text(stringResource(R.string.region_use_my_location)) }
        }

        Spacer(modifier = Modifier.height(16.dp))
        manualSettings()

        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = onContinue, enabled = selection != null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.common_continue))
        }
    }
}
