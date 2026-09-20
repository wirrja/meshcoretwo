// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.onboarding

import com.meshcoretwo.android.ui.i18n.toUiText
import com.meshcoretwo.android.ui.i18n.UiText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoretwo.android.AppViewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.settingsService
import com.meshcoretwo.services.connection.updateDevice
import com.meshcoretwo.services.region.RadioPresets
import com.meshcoretwo.services.region.RadioPresets.RadioPreset
import com.meshcoretwo.services.region.RegionSelection
import com.meshcoretwo.services.region.RegionalAreas
import com.meshcoretwo.services.settings.SettingsServiceError
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Onboarding step 5, the flow's terminal step. Lists the selected place's presets (the region's
 * recommended one pre-selected); falls back to locale-sorted alternatives when no region was
 * resolved/picked. Always shows the picker — upstream `4a6b0da6` dropped the "already configured"
 * skip. Ported from `PresetStepView.swift`.
 */
@Composable
fun PresetStepView(appViewModel: AppViewModel) {
    val region by appViewModel.regionSelection.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val recommended = remember(region) { region?.let(RadioPresets::recommended) }
    val alternatives = remember(region) { alternativesFor(region) }
    val visiblePresets = remember(recommended, alternatives) {
        buildList {
            recommended?.let(::add)
            addAll(alternatives.filter { it.id != recommended?.id })
        }
    }

    var selectedId by remember { mutableStateOf(recommended?.id ?: alternatives.firstOrNull()?.id) }
    var isApplying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun apply(id: String) {
        val preset = alternatives.firstOrNull { it.id == id } ?: recommended ?: return
        val settingsService = appViewModel.connectionManager.settingsService
        if (settingsService == null) {
            errorMessage = context.getString(R.string.preset_not_connected)
            return
        }
        isApplying = true
        errorMessage = null
        scope.launch {
            try {
                settingsService.applyRadioPresetVerified(preset)
                appViewModel.connectionManager.connectedDeviceRecord?.let {
                    appViewModel.connectionManager.updateDevice(
                        it.copy(
                            frequency = preset.frequencyKHz,
                            bandwidth = preset.bandwidthHz,
                            spreadingFactor = preset.spreadingFactor,
                            codingRate = preset.codingRate,
                            appliedRadioPresetID = preset.id,
                            pathHashMode = if (it.supportsPathHashMode) preset.pathHashMode ?: it.pathHashMode else it.pathHashMode,
                        ),
                    )
                }
                appViewModel.onboardingState.completeOnboarding()
            } catch (error: CancellationException) {
                throw error
            } catch (error: SettingsServiceError) {
                errorMessage = error.toUiText(UiText.Plain(context.getString(R.string.preset_apply_failed))).resolve(context)
            } catch (error: Exception) {
                errorMessage = error.toUiText(UiText.Plain(context.getString(R.string.preset_apply_failed))).resolve(context)
            } finally {
                isApplying = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        PickerContent(
                region = region,
                presets = visiblePresets,
                selectedId = selectedId,
                onSelect = { selectedId = it },
                isApplying = isApplying,
                errorMessage = errorMessage,
                ctaText = ctaTextFor(alternatives.firstOrNull { it.id == selectedId } ?: recommended),
                onApply = { selectedId?.let(::apply) },
            )
    }
}

private fun alternativesFor(region: RegionSelection?): List<RadioPreset> {
    val regionPresets = region?.let(RadioPresets::presets) ?: emptyList()
    val base = if (region != null && regionPresets.isNotEmpty()) regionPresets else RadioPresets.presetsForLocale()
    return base.filter { RadioPresets.isSelectable(it, region) }.sortedBy { it.name }
}

@Composable
private fun ctaTextFor(preset: RadioPreset?): String =
    if (preset != null) stringResource(R.string.preset_use_named, preset.name) else stringResource(R.string.common_continue)

@Composable
private fun PickerContent(
    region: RegionSelection?,
    presets: List<RadioPreset>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    isApplying: Boolean,
    errorMessage: String?,
    ctaText: String,
    onApply: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
        Text(stringResource(R.string.preset_choose_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            region?.let { stringResource(R.string.preset_for_region, RegionalAreas.displayName(it)) }
                ?: stringResource(R.string.preset_sorted_locale),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.preset_legal),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(presets, key = { _, preset -> preset.id }) { index, preset ->
                PresetRow(preset = preset, selected = preset.id == selectedId, onClick = { onSelect(preset.id) })
                if (index < presets.lastIndex) HorizontalDivider()
            }
        }

        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(onClick = onApply, enabled = !isApplying && selectedId != null, modifier = Modifier.fillMaxWidth()) {
            if (isApplying) CircularProgressIndicator(modifier = Modifier.size(20.dp)) else Text(ctaText)
        }
    }
}

@Composable
private fun PresetRow(preset: RadioPreset, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(preset.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                String.format(Locale.US, "%.3f MHz", preset.frequencyMHz),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}
