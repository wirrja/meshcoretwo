// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.ExpandableSectionCard
import com.meshcoretwo.android.ui.theme.AvatarCategory
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.location.LocationProvider
import com.meshcoretwo.services.utilities.isAtLeastVersion
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Repeater settings — device info/radio/identity/contact info/security/device actions (shared with
 * [RoomSettingsScreen] via `SharedNodeSettingsSections.kt`) plus repeater-only behavior settings and
 * region management. Ported from `RepeaterSettingsView.swift`, fourth and final sub-slice of the
 * Node Settings epic (PLAN.md).
 *
 * Reached as its own pushed destination from a "Settings" icon button on [RepeaterStatusScreen]'s
 * `TopAppBar`, alongside the existing CLI icon button — not a merged Settings/CLI/Telemetry
 * segmented-tab single screen the way iOS's `RepeaterSettingsView` hosts all three via
 * `NodeManagementTabPicker`. Android's established convention here (already used for
 * [RepeaterStatusScreen]/[NodeCLIScreen]) is separate navigable screens, each with its own
 * `Scaffold`/`TopAppBar`/nav route; introducing a SwiftUI-style in-place tab switcher would be a new
 * architecture pattern not used anywhere else in this codebase, so this screen follows the existing
 * one instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeaterSettingsScreen(
    connectionManager: ConnectionManager,
    locationProvider: LocationProvider,
    sessionId: UUID,
    onBack: () -> Unit,
) {
    val viewModel: RepeaterSettingsViewModel = viewModel(factory = RepeaterSettingsViewModel.Factory(connectionManager, sessionId))
    val settingsState by viewModel.settings.uiState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showingLocationPicker by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.nodeadmin_repeater_settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { NodeSettingsHeader(name = settingsState.session?.name ?: "", category = AvatarCategory.REPEATER) }

            item {
                NodeRadioSettingsSection(
                    state = settingsState,
                    settings = viewModel.settings,
                    onReload = { scope.launch { viewModel.settings.fetchRadioSettings() } },
                    onApply = { scope.launch { viewModel.settings.applyRadioSettings() } },
                )
            }

            item { RepeaterBehaviorSection(uiState, isApplying = settingsState.isApplying, viewModel = viewModel) }

            item {
                RepeaterRegionsSection(
                    uiState,
                    isApplying = settingsState.isApplying,
                    supportsDefaultScope = settingsState.firmwareVersion?.isAtLeastVersion(major = 1, minor = 15) == true,
                    viewModel = viewModel,
                )
            }

            item {
                NodeIdentitySection(
                    state = settingsState,
                    settings = viewModel.settings,
                    onReload = { scope.launch { viewModel.settings.fetchIdentity() } },
                    onApply = { scope.launch { viewModel.settings.applyIdentitySettings() } },
                    onPickLocation = { showingLocationPicker = true },
                )
            }

            item {
                NodeContactInfoSection(
                    state = settingsState,
                    settings = viewModel.settings,
                    onReload = { scope.launch { viewModel.settings.fetchContactInfo() } },
                    onApply = { scope.launch { viewModel.settings.applyContactInfoSettings() } },
                )
            }

            item {
                NodeSecuritySection(
                    state = settingsState,
                    settings = viewModel.settings,
                    onApply = { scope.launch { viewModel.settings.changePassword() } },
                )
            }

            item {
                NodeDeviceInfoSection(
                    state = settingsState,
                    settings = viewModel.settings,
                    onReload = { scope.launch { viewModel.settings.fetchDeviceInfo() } },
                )
            }

            item {
                NodeActionsSection(
                    state = settingsState,
                    onForceAdvert = { scope.launch { viewModel.settings.forceAdvert() } },
                    onSyncTime = { scope.launch { viewModel.settings.syncTime() } },
                    onReboot = { scope.launch { viewModel.settings.reboot() } },
                    rebootConfirmTitle = R.string.nodeadmin_reboot_repeater_title,
                    rebootMessage = R.string.nodeadmin_reboot_repeater_msg,
                )
            }
        }
    }

    if (settingsState.showSuccessAlert) {
        AlertDialog(
            onDismissRequest = viewModel.settings::dismissSuccessAlert,
            title = { Text(stringResource(R.string.common_success)) },
            text = { Text(settingsState.successMessage?.asString() ?: stringResource(R.string.nodeset_settings_applied)) },
            confirmButton = { TextButton(onClick = viewModel.settings::dismissSuccessAlert) { Text(stringResource(R.string.common_ok)) } },
        )
    }

    // Full-screen, not a pushed nav destination — same reasoning `LocationPickerScreen`'s own doc
    // gives for skipping a nav-result round trip: this picker only ever needs to hand one coordinate
    // pair back to the screen that opened it.
    if (showingLocationPicker) {
        RemoteNodeLocationPickerScreen(
            initialLatitude = settingsState.latitude,
            initialLongitude = settingsState.longitude,
            locationProvider = locationProvider,
            onSave = { latitude, longitude -> viewModel.settings.setLocationFromPicker(latitude, longitude); showingLocationPicker = false },
            onCancel = { showingLocationPicker = false },
        )
    }
}

@Composable
private fun RepeaterBehaviorSection(uiState: RepeaterSettingsUiState, isApplying: Boolean, viewModel: RepeaterSettingsViewModel) {
    ExpandableSectionCard(
        title = stringResource(R.string.nodeadmin_behavior),
        expanded = uiState.isBehaviorExpanded,
        isLoading = uiState.isLoadingBehavior,
        onToggle = {
            val expand = !uiState.isBehaviorExpanded
            viewModel.setBehaviorExpanded(expand)
            if (expand && !uiState.behaviorLoaded && !uiState.isLoadingBehavior) viewModel.fetchBehaviorSettings()
        },
        onReload = if (uiState.behaviorLoaded) viewModel::fetchBehaviorSettings else null,
    ) {
        when {
            uiState.isLoadingBehavior && !uiState.behaviorLoaded -> SectionLoadingRow()
            uiState.behaviorError && !uiState.behaviorLoaded -> ErrorRetryRow(onRetry = viewModel::fetchBehaviorSettings)
            uiState.behaviorLoaded -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.nodeadmin_repeater_mode))
                    Switch(checked = uiState.repeaterEnabled ?: false, onCheckedChange = viewModel::setRepeaterEnabled)
                }
                IntFieldRow("Advert Interval (0-hop, min)", uiState.advertIntervalMinutes, viewModel::setAdvertIntervalMinutes, uiState.advertIntervalError)
                IntFieldRow("Advert Interval (flood, hrs)", uiState.floodAdvertIntervalHours, viewModel::setFloodAdvertIntervalHours, uiState.floodAdvertIntervalError)
                IntFieldRow("Max Flood Hops", uiState.floodMaxHops, viewModel::setFloodMaxHops, uiState.floodMaxHopsError)
                AsyncApplyButton(
                    stringResource(R.string.nodeadmin_apply_behavior),
                    isLoading = isApplying,
                    showSuccess = uiState.behaviorApplySuccess,
                    enabled = uiState.behaviorSettingsModified,
                    onClick = viewModel::applyBehaviorSettings,
                )
            }
        }
    }
}

private const val REGION_INDENT_PER_LEVEL_DP = 16

@Composable
private fun RepeaterRegionsSection(
    uiState: RepeaterSettingsUiState,
    isApplying: Boolean,
    supportsDefaultScope: Boolean,
    viewModel: RepeaterSettingsViewModel,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var newRegionName by remember { mutableStateOf("") }
    var newRegionParent by remember { mutableStateOf<RepeaterRegionEntry.Parent>(RepeaterRegionEntry.Parent.Wildcard) }

    ExpandableSectionCard(
        title = stringResource(R.string.nodeadmin_regions),
        expanded = uiState.isRegionsExpanded,
        isLoading = uiState.isLoadingRegions,
        onToggle = {
            val expand = !uiState.isRegionsExpanded
            viewModel.setRegionsExpanded(expand)
            if (expand && !uiState.regionsLoaded && !uiState.isLoadingRegions) viewModel.fetchRegions()
        },
        onReload = if (uiState.regionsLoaded) viewModel::fetchRegions else null,
    ) {
        when {
            uiState.isLoadingRegions && !uiState.regionsLoaded -> SectionLoadingRow()
            uiState.regionsError && !uiState.regionsLoaded -> ErrorRetryRow(onRetry = viewModel::fetchRegions)
            uiState.regionsLoaded -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (uiState.regions.isEmpty()) {
                    Text(stringResource(R.string.nodeadmin_no_regions), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    // Firmware already returns regions in tree pre-order (parent immediately
                    // before its children) — re-sorting alphabetically would scramble that shape.
                    val namesWithChildren = remember(uiState.regions) { uiState.regions.mapNotNullTo(mutableSetOf()) { it.namedParent } }
                    uiState.regions.forEach { region ->
                        val displayName = if (region.isWildcard) stringResource(R.string.nodeadmin_wildcard) else region.name
                        val indentLevels = region.depth.coerceAtMost(4)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (indentLevels > 0) Spacer(modifier = Modifier.width((indentLevels * REGION_INDENT_PER_LEVEL_DP).dp))
                            Text(
                                displayName,
                                modifier = Modifier.weight(1f),
                                fontWeight = if (!region.isWildcard && region.name in namesWithChildren) FontWeight.Medium else FontWeight.Normal,
                            )
                            Switch(checked = region.floodAllowed, onCheckedChange = { viewModel.toggleRegionFlood(region.name) })
                            if (!region.isWildcard) {
                                IconButton(onClick = { viewModel.removeRegion(region.name) }) {
                                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.nodeadmin_remove_named, displayName))
                                }
                            }
                        }
                    }
                }

                if (supportsDefaultScope && uiState.defaultScopeLoaded) DefaultScopePicker(uiState, viewModel)

                TextButton(onClick = { newRegionName = ""; newRegionParent = RepeaterRegionEntry.Parent.Wildcard; showAddDialog = true }) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(stringResource(R.string.nodeadmin_add_region))
                }

                AsyncApplyButton(
                    stringResource(R.string.nodeadmin_save_to_repeater),
                    isLoading = isApplying,
                    showSuccess = uiState.regionsSaveSuccess,
                    enabled = uiState.hasUnsavedRegionChanges,
                    onClick = viewModel::saveRegions,
                )
            }
        }
    }

    if (showAddDialog) {
        var parentMenuExpanded by remember { mutableStateOf(false) }
        val parentOptions = remember(uiState.regions) { uiState.regions.filterNot { it.isWildcard }.map { it.name } }
        val parentLabel = when (val parent = newRegionParent) {
            is RepeaterRegionEntry.Parent.Wildcard -> stringResource(R.string.nodeadmin_wildcard)
            is RepeaterRegionEntry.Parent.Named -> parent.name
        }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.nodeadmin_add_region)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newRegionName,
                        onValueChange = { newRegionName = it },
                        label = { Text(stringResource(R.string.nodeadmin_region_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { parentMenuExpanded = true },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.nodeadmin_parent))
                            Text(parentLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = parentMenuExpanded, onDismissRequest = { parentMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.nodeadmin_wildcard)) },
                                onClick = { parentMenuExpanded = false; newRegionParent = RepeaterRegionEntry.Parent.Wildcard },
                            )
                            parentOptions.forEach { name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = { parentMenuExpanded = false; newRegionParent = RepeaterRegionEntry.Parent.Named(name) },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAddDialog = false; viewModel.addRegion(newRegionName, newRegionParent) }) { Text(stringResource(R.string.nodeadmin_add_region)) } },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun DefaultScopePicker(uiState: RepeaterSettingsUiState, viewModel: RepeaterSettingsViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val names = remember(uiState.regions, uiState.defaultScopeName) {
        val nonWildcard = uiState.regions.filterNot { it.isWildcard }.map { it.name }.toMutableList()
        val current = uiState.defaultScopeName
        if (current != null && current != RepeaterSettingsViewModel.WILDCARD_NAME && current !in nonWildcard) nonWildcard.add(current)
        nonWildcard
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.nodeadmin_default_scope))
            Text(uiState.defaultScopeName ?: stringResource(R.string.nodeadmin_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.nodeadmin_none)) }, onClick = { expanded = false; viewModel.setDefaultScope(null) })
            names.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; viewModel.setDefaultScope(name) })
            }
        }
        Text(
            stringResource(R.string.nodeadmin_scope_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
