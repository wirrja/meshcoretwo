// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import com.meshcoretwo.android.ui.i18n.toUiText
import com.meshcoretwo.android.ui.i18n.UiText
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.about.AppLinks
import com.meshcoretwo.android.chat.ChatDisplayPreferences
import com.meshcoretwo.android.onboarding.WiFiAddressValidation
import com.meshcoretwo.android.ui.components.CompactSearchTopBar
import com.meshcoretwo.android.ui.components.ConfirmDialog
import com.meshcoretwo.android.ui.components.DetailRow
import com.meshcoretwo.android.ui.components.SectionCard
import com.meshcoretwo.android.ui.components.SettingsGroupLabel
import com.meshcoretwo.android.ui.components.SettingsListRow
import com.meshcoretwo.android.ui.theme.ThemeService
import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.protocol.decodeHex
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.location.LocationProvider
import com.meshcoretwo.services.notifications.NotificationPreferences
import com.meshcoretwo.services.notifications.NotificationPreferencesStore
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.region.RadioOptions
import com.meshcoretwo.services.region.RadioPresets
import com.meshcoretwo.services.region.RadioPresets.RadioPreset
import com.meshcoretwo.services.region.RegionResolver
import com.meshcoretwo.services.region.RegionSelection
import com.meshcoretwo.services.region.RegionSelectionStore
import com.meshcoretwo.services.region.RegionalAreas
import com.meshcoretwo.services.settings.AutoAddMode
import com.meshcoretwo.services.settings.DeviceGPSState
import com.meshcoretwo.services.settings.DevicePreferenceStore
import com.meshcoretwo.services.settings.GPSSource
import com.meshcoretwo.services.settings.KeyGenerationService
import com.meshcoretwo.services.settings.StaleNodeCleanupPreferencesStore
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings tab — PLAN.md's Phase 5 item 6, trimmed to "device" + "notifications" (the other two
 * nouns in that item, region and log, are separate follow-up tickets — see
 * [SettingsViewModel]'s class doc). Ported loosely from `SettingsView.swift`'s section list: one
 * flat scrolling screen instead of the iOS split-view/subpage navigation (`SettingsSubpage`),
 * since this slice's section count doesn't yet justify that structure — every section below is one
 * uppercase [com.meshcoretwo.android.ui.components.SettingsGroupLabel] plus
 * [com.meshcoretwo.android.ui.components.SettingsListRow]s in the same scrolling column (PLAN.md's
 * Phase 10 slices 7–8; [DeviceSection] is the one exception, a real inline-editing form rather than
 * a row list, so only its group label/card border changed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    connectionManager: ConnectionManager,
    notificationPreferencesStore: NotificationPreferencesStore,
    staleNodeCleanupPreferencesStore: StaleNodeCleanupPreferencesStore,
    devicePreferenceStore: DevicePreferenceStore,
    locationProvider: LocationProvider,
    regionSelectionStore: RegionSelectionStore,
    regionResolver: RegionResolver,
    themeService: ThemeService,
    prefs: SharedPreferences,
    onOpenConfigExport: () -> Unit,
    onOpenConfigImport: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onOpenDeviceSelection: () -> Unit,
    onOpenBlockedChannelSenders: () -> Unit,
    onOpenRegionManagement: () -> Unit,
    onOpenLocationPicker: () -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenDangerZone: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(connectionManager, notificationPreferencesStore, staleNodeCleanupPreferencesStore, devicePreferenceStore),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val notificationPreferences by viewModel.notificationPreferences.collectAsStateWithLifecycle()
    val staleNodeCleanupThresholdDays by viewModel.staleNodeCleanupThresholdDays.collectAsStateWithLifecycle()
    val staleNodeCleanupLastRun by viewModel.staleNodeCleanupLastRun.collectAsStateWithLifecycle()
    val deviceGpsState by viewModel.deviceGpsState.collectAsStateWithLifecycle()
    val autoUpdateLocation by viewModel.autoUpdateLocation.collectAsStateWithLifecycle()
    val gpsSource by viewModel.gpsSource.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Same staleness fix as ChannelInfoScreen's: re-reads the connected device on re-entry so
    // regions added via "Manage Regions" show up in the default flood-scope picker without a
    // full app restart.
    LaunchedEffect(Unit) { viewModel.refresh() }

    val context = LocalContext.current
    LaunchedEffect(errorMessage, statusMessage) {
        val message = errorMessage ?: statusMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message.resolve(context))
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = { CompactSearchTopBar(title = stringResource(R.string.settings_title), connectionManager = connectionManager) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val state = uiState) {
            // About stays reachable without a radio: the license texts must be readable offline and unpaired.
            is SettingsUiState.Connecting -> Column(
                modifier = Modifier.padding(padding).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.settings_connecting))
                ConnectionSection(onOpenDeviceSelection = onOpenDeviceSelection)
                AppearanceSection(themeService = themeService, onOpenAppearance = onOpenAppearance)
                LanguageRow()
                MapsSection(onOpenOfflineMaps = onOpenOfflineMaps)
                BackupRestoreSection(onOpenBackupRestore = onOpenBackupRestore)
                AboutSection(onOpenAbout = onOpenAbout, onOpenLicenses = onOpenLicenses)
            }
            is SettingsUiState.Ready -> SettingsContent(
                modifier = Modifier.padding(padding),
                device = state.device,
                notificationPreferences = notificationPreferences,
                notificationPreferencesStore = notificationPreferencesStore,
                staleNodeCleanupThresholdDays = staleNodeCleanupThresholdDays,
                staleNodeCleanupLastRun = staleNodeCleanupLastRun,
                deviceGpsState = deviceGpsState,
                autoUpdateLocation = autoUpdateLocation,
                gpsSource = gpsSource,
                locationProvider = locationProvider,
                regionSelectionStore = regionSelectionStore,
                regionResolver = regionResolver,
                themeService = themeService,
                prefs = prefs,
                isBusy = isBusy,
                viewModel = viewModel,
                onOpenConfigExport = onOpenConfigExport,
                onOpenConfigImport = onOpenConfigImport,
                onOpenBackupRestore = onOpenBackupRestore,
                onOpenDeviceSelection = onOpenDeviceSelection,
                onOpenBlockedChannelSenders = onOpenBlockedChannelSenders,
                onOpenRegionManagement = onOpenRegionManagement,
                onOpenLocationPicker = onOpenLocationPicker,
                onOpenOfflineMaps = onOpenOfflineMaps,
                onOpenAppearance = onOpenAppearance,
                onOpenDangerZone = onOpenDangerZone,
                onOpenAbout = onOpenAbout,
                onOpenLicenses = onOpenLicenses,
            )
        }
    }
}

@Composable
private fun SettingsContent(
    modifier: Modifier,
    device: DeviceDto,
    notificationPreferences: NotificationPreferences,
    notificationPreferencesStore: NotificationPreferencesStore,
    staleNodeCleanupThresholdDays: Int,
    staleNodeCleanupLastRun: Instant?,
    deviceGpsState: DeviceGPSState?,
    autoUpdateLocation: Boolean,
    gpsSource: GPSSource,
    locationProvider: LocationProvider,
    regionSelectionStore: RegionSelectionStore,
    regionResolver: RegionResolver,
    themeService: ThemeService,
    prefs: SharedPreferences,
    isBusy: Boolean,
    viewModel: SettingsViewModel,
    onOpenConfigExport: () -> Unit,
    onOpenConfigImport: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onOpenDeviceSelection: () -> Unit,
    onOpenBlockedChannelSenders: () -> Unit,
    onOpenRegionManagement: () -> Unit,
    onOpenLocationPicker: () -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenDangerZone: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConnectionSection(onOpenDeviceSelection = onOpenDeviceSelection)
        HorizontalDivider()
        AppearanceSection(themeService = themeService, onOpenAppearance = onOpenAppearance)
        HorizontalDivider()
        LanguageRow()
        HorizontalDivider()
        MapsSection(onOpenOfflineMaps = onOpenOfflineMaps)
        HorizontalDivider()
        DeviceSection(device = device, isBusy = isBusy, viewModel = viewModel, regionSelectionStore = regionSelectionStore, regionResolver = regionResolver)
        if (device.supportsDefaultFloodScope) {
            HorizontalDivider()
            DefaultFloodScopeSection(device = device, isBusy = isBusy, viewModel = viewModel, onOpenRegionManagement = onOpenRegionManagement)
        }
        HorizontalDivider()
        LocationSection(
            device = device,
            deviceGpsState = deviceGpsState,
            autoUpdateLocation = autoUpdateLocation,
            gpsSource = gpsSource,
            isBusy = isBusy,
            viewModel = viewModel,
            locationProvider = locationProvider,
            onOpenLocationPicker = onOpenLocationPicker,
        )
        if (deviceGpsState?.isSupported == true) {
            HorizontalDivider()
            DeviceGpsSection(deviceGpsState = deviceGpsState, isBusy = isBusy, viewModel = viewModel)
        }
        HorizontalDivider()
        NotificationsSection(preferences = notificationPreferences, store = notificationPreferencesStore)
        HorizontalDivider()
        NodesSection(device = device, isBusy = isBusy, viewModel = viewModel)
        HorizontalDivider()
        StaleNodeCleanupSection(
            thresholdDays = staleNodeCleanupThresholdDays,
            lastRun = staleNodeCleanupLastRun,
            isBusy = isBusy,
            viewModel = viewModel,
        )
        HorizontalDivider()
        TelemetrySection(device = device, isBusy = isBusy, viewModel = viewModel)
        HorizontalDivider()
        DirectMessagesSection(device = device, isBusy = isBusy, viewModel = viewModel)
        HorizontalDivider()
        MessageInfoSection(prefs = prefs)
        HorizontalDivider()
        BlockingSection(onOpenBlockedChannelSenders = onOpenBlockedChannelSenders)
        HorizontalDivider()
        ConfigExportImportSection(onOpenConfigExport = onOpenConfigExport, onOpenConfigImport = onOpenConfigImport)
        HorizontalDivider()
        BackupRestoreSection(onOpenBackupRestore = onOpenBackupRestore)
        HorizontalDivider()
        DeviceIdentitySection(isBusy = isBusy, viewModel = viewModel)
        HorizontalDivider()
        DangerZoneEntry(onOpenDangerZone = onOpenDangerZone)
        HorizontalDivider()
        AboutSection(onOpenAbout = onOpenAbout, onOpenLicenses = onOpenLicenses)
        Spacer(modifier = Modifier.size(8.dp))
    }
}

/**
 * Settings → Appearance row. Trailing text shows the active theme's name, matching iOS's
 * `AppearanceSection.swift` (which also shows a trailing detail label). Placed near the top, with
 * other display-style settings — same placement note as the Swift source.
 */
@Composable
private fun AppearanceSection(themeService: ThemeService, onOpenAppearance: () -> Unit) {
    val current by themeService.current.collectAsStateWithLifecycle()
    Column {
        SettingsGroupLabel(stringResource(R.string.appearance_title))
        SettingsListRow(
            title = stringResource(R.string.appearance_title),
            value = current.displayName,
            icon = R.drawable.ic_palette,
            onClick = onOpenAppearance,
        )
    }
}

/**
 * Settings → Maps. Ported from `MapsSettingsView.swift`, minus its basemap light/dark appearance
 * picker: that needs a map-wide light/dark style switch, which `MapScreen.kt` consciously never
 * got (one fixed `openfreemap/liberty` style — see its class doc), so this section is just the
 * single row that hub's `NavigationLink` led to, `OfflineMapSettingsView`'s screen, linked
 * directly rather than through a single-item hub.
 */
@Composable
private fun MapsSection(onOpenOfflineMaps: () -> Unit) {
    Column {
        SettingsGroupLabel(stringResource(R.string.settings_maps))
        SettingsListRow(title = stringResource(R.string.settings_offline_maps), icon = R.drawable.ic_map, onClick = onOpenOfflineMaps)
    }
}

/**
 * Ported from `AboutSection.swift`, minus the sponsor, GitHub and privacy-policy links that lead to
 * the original author (project constraints, hard constraint 3). "Report a problem" lives on the About screen,
 * since it needs this port's own repository (PLAN.md Л2).
 */
@Composable
private fun AboutSection(onOpenAbout: () -> Unit, onOpenLicenses: () -> Unit) {
    val context = LocalContext.current
    Column {
        SettingsGroupLabel(stringResource(R.string.settings_about))
        SettingsListRow(title = stringResource(R.string.settings_about_app), onClick = onOpenAbout)
        HorizontalDivider()
        SettingsListRow(title = stringResource(R.string.settings_licenses), onClick = onOpenLicenses)
        HorizontalDivider()
        SettingsListRow(
            title = stringResource(R.string.settings_website),
            trailingIcon = R.drawable.ic_open_in_new,
            onClick = { openUrl(context, AppLinks.MESHCORE_WEBSITE) },
        )
        HorizontalDivider()
        SettingsListRow(
            title = stringResource(R.string.settings_online_map),
            trailingIcon = R.drawable.ic_open_in_new,
            onClick = { openUrl(context, AppLinks.MESHCORE_ONLINE_MAP) },
        )
    }
}

/** Same browser-launch behavior as [com.meshcoretwo.android.about.ExternalLinkRow], for this screen's own rows. */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (error: ActivityNotFoundException) {
        // No browser installed; there is nothing sensible to fall back to.
    }
}

/** Ported from `ConfigExportImportSection.swift`. Always enabled here — unlike Swift's `isDisabled` gate on `connectionState != .ready`, this whole screen only renders once [SettingsUiState.Ready], so a separate connectivity check would be redundant. */
@Composable
private fun ConfigExportImportSection(onOpenConfigExport: () -> Unit, onOpenConfigImport: () -> Unit) {
    Column {
        SettingsGroupLabel(stringResource(R.string.settings_device_config))
        Text(
            stringResource(R.string.settings_config_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsListRow(title = stringResource(R.string.settings_export_config), onClick = onOpenConfigExport)
        HorizontalDivider()
        SettingsListRow(title = stringResource(R.string.settings_import_config), onClick = onOpenConfigImport)
    }
}

/**
 * Ported from `BackupRestoreView.swift`'s entry point in `SettingsView.swift`. Placed alongside
 * [AboutSection] rather than only inside the connected-only [SettingsContent] — backup/restore
 * concerns the local app database, not the radio, so (matching [AboutSection]'s own reasoning) it
 * stays reachable from the `Connecting` state too, letting a user restore data before ever pairing
 * a device.
 */
/**
 * Ported from `BlockingSection.swift`, minus its "Blocked Contacts" `NavigationLink` — that
 * destination already lives in [com.meshcoretwo.android.contacts.ContactsListScreen]'s toolbar on
 * this port (an earlier, independent placement decision predating this section), so it isn't
 * duplicated or moved here. See [BlockedChannelSendersScreen]'s doc for the rest of this section's
 * porting notes.
 */
@Composable
private fun BlockingSection(onOpenBlockedChannelSenders: () -> Unit) {
    Column {
        SettingsGroupLabel(stringResource(R.string.settings_blocking))
        SettingsListRow(title = stringResource(R.string.settings_blocked_senders), onClick = onOpenBlockedChannelSenders)
    }
}

/**
 * Entry point to [DeviceSelectionScreen] — Settings' "switch device" surface, ported from
 * `ConnectionSettingsView.swift`'s call site in `SettingsView.swift` (there wrapping Bluetooth/
 * WiFi sub-sections this port already folds into [DeviceSection] instead — see
 * [DeviceSelectionScreen]'s doc for why the actual `DeviceSelectionSheet.swift` port lives on its
 * own screen). Placed alongside [BackupRestoreSection] rather than only inside the connected-only
 * [SettingsContent]: picking a different saved device doesn't require one to already be connected
 * — that's the main reason to open this screen from the `Connecting` state in the first place.
 */
@Composable
private fun ConnectionSection(onOpenDeviceSelection: () -> Unit) {
    Column {
        SettingsGroupLabel(stringResource(R.string.settings_connection))
        SettingsListRow(title = stringResource(R.string.settings_saved_devices), icon = R.drawable.ic_bluetooth, onClick = onOpenDeviceSelection)
    }
}

@Composable
private fun BackupRestoreSection(onOpenBackupRestore: () -> Unit) {
    Column {
        SettingsGroupLabel(stringResource(R.string.settings_backup_restore))
        SettingsListRow(title = stringResource(R.string.settings_backup_restore), onClick = onOpenBackupRestore)
    }
}

@Composable
private fun DeviceSection(
    device: DeviceDto,
    isBusy: Boolean,
    viewModel: SettingsViewModel,
    regionSelectionStore: RegionSelectionStore,
    regionResolver: RegionResolver,
) {
    val regionSelection by regionSelectionStore.selection.collectAsStateWithLifecycle()
    var nameInput by remember(device.id) { mutableStateOf(device.nodeName) }
    var showPinEditor by remember { mutableStateOf(false) }
    var showAdvancedRadio by remember { mutableStateOf(false) }
    var showWifiEditor by remember { mutableStateOf(false) }

    SettingsGroupLabel(stringResource(R.string.settings_device))

    OutlinedTextField(
        value = nameInput,
        onValueChange = { nameInput = it },
        label = { Text(stringResource(R.string.settings_device_name)) },
        singleLine = true,
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            if (nameInput.isNotBlank() && nameInput != device.nodeName) {
                TextButton(onClick = { viewModel.renameDevice(nameInput) }) { Text(stringResource(R.string.common_save)) }
            }
        },
    )

    Spacer(modifier = Modifier.size(12.dp))
    SelectionContainer {
        DetailRow(stringResource(R.string.settings_public_key), device.publicKey.hexString.uppercase().chunked(4).joinToString(" "), dense = true, singleLine = false)
    }
    DetailRow("Firmware", device.firmwareVersionString, dense = true, singleLine = false)
    DetailRow(stringResource(R.string.settings_manufacturer), device.manufacturerName, dense = true, singleLine = false)
    DetailRow(stringResource(R.string.settings_build_date), device.buildDate, dense = true, singleLine = false)
    DetailRow(stringResource(R.string.settings_max_contacts_channels), "${device.maxContacts} / ${device.maxChannels}", dense = true, singleLine = false)

    Spacer(modifier = Modifier.size(12.dp))
    Text(stringResource(R.string.settings_radio), style = MaterialTheme.typography.titleSmall)
    DetailRow(stringResource(R.string.rf_frequency), String.format(Locale.US, "%.3f MHz", device.frequency.toDouble() / 1000.0), dense = true, singleLine = false)
    DetailRow("Bandwidth", String.format(Locale.US, "%.1f kHz", device.bandwidth.toDouble() / 1000.0), dense = true, singleLine = false)
    if (device.supportsPathHashMode) {
        PathHashModeRow(pathHashMode = device.pathHashMode, isBusy = isBusy, onSelect = viewModel::setPathHashMode)
    } else {
        DetailRow("Path hash", "${device.pathHashMode.toInt() + 1} byte${if (device.pathHashMode.toInt() == 0) "" else "s"}", dense = true, singleLine = false)
    }
    DetailRow("Spreading factor / coding rate", "SF${device.spreadingFactor} / CR${device.codingRate}", dense = true, singleLine = false)
    DetailRow("TX power", "${device.txPower} dBm (max ${device.maxTxPower})", dense = true, singleLine = false)
    if (device.clientRepeat) DetailRow(stringResource(R.string.settings_repeat_mode), stringResource(R.string.settings_on), dense = true, singleLine = false)
    if (!device.clientRepeat) {
        Spacer(modifier = Modifier.size(8.dp))
        PresetLocationSection(regionSelectionStore = regionSelectionStore, regionResolver = regionResolver)
    }
    SettingsListRow(
        title = stringResource(R.string.settings_manual_settings),
        trailing = { TextButton(onClick = { showAdvancedRadio = true }, enabled = !isBusy) { Text(stringResource(R.string.settings_set)) } },
    )

    HorizontalDivider()
    SettingsListRow(
        title = "Bluetooth",
        value = "PIN ${device.blePin}",
        trailing = { TextButton(onClick = { showPinEditor = true }, enabled = !isBusy) { Text(stringResource(R.string.settings_set_pin)) } },
    )
    HorizontalDivider()
    val wifiHost = device.wifiHost
    SettingsListRow(
        title = "WiFi",
        value = if (wifiHost != null) "$wifiHost:${device.wifiPort ?: 0}" else stringResource(R.string.settings_not_configured),
        trailing = { TextButton(onClick = { showWifiEditor = true }, enabled = !isBusy) { Text(stringResource(R.string.common_connect)) } },
    )

    if (showAdvancedRadio) {
        AdvancedRadioDialog(
            device = device,
            onDismiss = { showAdvancedRadio = false },
            onApply = { frequencyKHz, bandwidthHz, spreadingFactor, codingRate, txPower, clientRepeat ->
                viewModel.setAdvancedRadioSettings(frequencyKHz, bandwidthHz, spreadingFactor, codingRate, txPower, clientRepeat)
                showAdvancedRadio = false
            },
        )
    }

    if (showPinEditor) {
        BlePinDialog(
            currentPin = device.blePin,
            onDismiss = { showPinEditor = false },
            onSave = { pin ->
                viewModel.setBlePin(pin)
                showPinEditor = false
            },
        )
    }

    if (showWifiEditor) {
        WifiEditDialog(
            initialHost = device.wifiHost ?: "",
            initialPort = device.wifiPort?.toString() ?: "5000",
            onDismiss = { showWifiEditor = false },
            onSave = { host, port ->
                viewModel.updateWifiConnection(host, port)
                showWifiEditor = false
            },
        )
    }
}

/** Edit dialog for the radio's BLE pairing PIN (up to 6 digits), opened from the Bluetooth row's "Set PIN". */
@Composable
private fun BlePinDialog(currentPin: UInt, onDismiss: () -> Unit, onSave: (UInt) -> Unit) {
    var pinInput by remember { mutableStateOf(currentPin.toString()) }
    val pin = pinInput.toUIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("BLE PIN") },
        text = {
            OutlinedTextField(
                value = pinInput,
                onValueChange = { pinInput = it.filter(Char::isDigit).take(6) },
                label = { Text("PIN") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { pin?.let(onSave) }, enabled = pin != null && pin != currentPin) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * Device's persisted default flood-routing scope (firmware v11+). Ported from
 * `DefaultFloodScopeSection.swift`, folding its full list of selectable rows into a single
 * dropdown row — the same simplification [PathHashModeRow] already applies to
 * `PathHashModeSection.swift`'s menu-style picker. "Discover"/"Add Manually" (both also present
 * on Swift's section directly) live on [RegionManagementScreen] instead of being duplicated here:
 * Swift's `NavigationStack` keeps this section and `RegionManagementView` alive together sharing
 * one `@State`, which a Compose back-stack navigation doesn't give for free — see
 * `RegionManagementViewModel`'s doc.
 */
@Composable
private fun DefaultFloodScopeSection(device: DeviceDto, isBusy: Boolean, viewModel: SettingsViewModel, onOpenRegionManagement: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val sortedRegions = remember(device.knownRegions) { device.knownRegions.sorted() }
    val currentLabel = device.defaultFloodScopeName ?: stringResource(R.string.nodeadmin_none)

    SettingsGroupLabel(stringResource(R.string.settings_default_flood_scope))
    Box {
        SettingsListRow(title = stringResource(R.string.channel_info_scope), value = currentLabel, onClick = { if (!isBusy) expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.nodeadmin_none)) }, onClick = { expanded = false; viewModel.setDefaultFloodScope(null) })
            sortedRegions.forEach { region ->
                DropdownMenuItem(text = { Text(region) }, onClick = { expanded = false; viewModel.setDefaultFloodScope(region) })
            }
        }
    }
    HorizontalDivider()
    SettingsListRow(title = stringResource(R.string.common_manage_regions), onClick = onOpenRegionManagement)
    Text(
        stringResource(R.string.settings_flood_scope_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * Ported from `LocationSettingsSection.swift`'s first `Section` (set/share/auto-update). Trimmed
 * relative to Swift: no phone-permission "denied → open system Settings" alert (see
 * [SettingsViewModel.setAutoUpdateLocation]'s doc) and no live 3-second poll refreshing device info
 * while waiting for a device GPS fix (`shouldPollDeviceGPS`) — the next full resync already catches
 * the UI up, the same convention every other setter on this screen relies on. The device-GPS-enable
 * toggle itself is [DeviceGpsSection], a separate group matching Swift's second `Section`.
 */
@Composable
private fun LocationSection(
    device: DeviceDto,
    deviceGpsState: DeviceGPSState?,
    autoUpdateLocation: Boolean,
    gpsSource: GPSSource,
    isBusy: Boolean,
    viewModel: SettingsViewModel,
    locationProvider: LocationProvider,
    onOpenLocationPicker: () -> Unit,
) {
    var sourceExpanded by remember { mutableStateOf(false) }
    val hasLocation = device.latitude != 0.0 || device.longitude != 0.0
    val deviceHasGps = deviceGpsState?.isSupported == true

    SettingsGroupLabel(stringResource(R.string.settings_location))
    SettingsListRow(
        title = stringResource(R.string.settings_set_location),
        value = if (hasLocation) stringResource(R.string.settings_set) else stringResource(R.string.settings_not_set),
        onClick = { if (!isBusy) onOpenLocationPicker() },
    )
    HorizontalDivider()
    SwitchRow(
        stringResource(R.string.settings_share_location),
        checked = device.advertLocationPolicyMode.isEnabled,
        enabled = !isBusy,
        onCheckedChange = viewModel::setShareLocationPublicly,
    )
    HorizontalDivider()
    SwitchRow(
        stringResource(R.string.settings_auto_update_location),
        checked = autoUpdateLocation,
        enabled = !isBusy,
        onCheckedChange = { viewModel.setAutoUpdateLocation(it, locationProvider) },
    )
    if (autoUpdateLocation) {
        HorizontalDivider()
        if (deviceHasGps) {
            Box {
                SettingsListRow(
                    title = stringResource(R.string.settings_gps_source),
                    value = if (gpsSource == GPSSource.DEVICE) stringResource(R.string.settings_device) else stringResource(R.string.settings_phone),
                    onClick = { if (!isBusy) sourceExpanded = true },
                )
                DropdownMenu(expanded = sourceExpanded, onDismissRequest = { sourceExpanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings_phone)) }, onClick = { sourceExpanded = false; viewModel.setGpsSource(GPSSource.PHONE, locationProvider) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings_device)) }, onClick = { sourceExpanded = false; viewModel.setGpsSource(GPSSource.DEVICE, locationProvider) })
                }
            }
        } else {
            SettingsListRow(title = stringResource(R.string.settings_gps_source), value = stringResource(R.string.settings_phone))
        }
    }
    Text(
        stringResource(R.string.settings_location_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Ported from `LocationSettingsSection.swift`'s second `Section`, shown only when [DeviceGPSState.isSupported]. */
@Composable
private fun DeviceGpsSection(deviceGpsState: DeviceGPSState, isBusy: Boolean, viewModel: SettingsViewModel) {
    SettingsGroupLabel(stringResource(R.string.settings_device_gps))
    SwitchRowWithDescription(
        label = stringResource(R.string.settings_enable_device_gps),
        description = stringResource(R.string.settings_device_gps_desc),
        checked = deviceGpsState.isEnabled,
        enabled = !isBusy,
        onCheckedChange = viewModel::setDeviceGpsEnabled,
    )
}

/**
 * Mirrors `WiFiEditSheet.swift`'s form, ported into an [AlertDialog] the same way `PairScreen.kt`
 * ported `WiFiConnectionSheet.swift` — see [WiFiAddressValidation] (shared by both) for why no
 * `WiFiAddressFields`/`WiFiSheetToolbarModifier` component pair exists yet.
 *
 * Unlike the onboarding dialog, this one closes immediately on Save rather than waiting for
 * [SettingsViewModel.updateWifiConnection] to finish, matching how the Danger Zone actions in
 * this same screen already behave: [isBusy]'s "Working…" indicator and the error/status snackbar
 * (both wired in [SettingsScreen]) carry the outcome instead. Also not ported: Swift's
 * `hasChanges` guard disabling Save when the fields match the current connection — reconnecting
 * to the same host/port is harmless, just a wasted round trip.
 */
@Composable
private fun WifiEditDialog(
    initialHost: String,
    initialPort: String,
    onDismiss: () -> Unit,
    onSave: (host: String, port: Int) -> Unit,
) {
    var ipAddress by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort) }
    val isValidInput = WiFiAddressValidation.isValidHost(ipAddress) && WiFiAddressValidation.isValidPort(port)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_wifi_connection)) },
        text = {
            Column {
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it.replace(',', '.') },
                    label = { Text(stringResource(R.string.wifi_host_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.common_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(WiFiAddressValidation.normalizedHost(ipAddress), port.toInt()) },
                enabled = isValidInput,
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun RadioPresetPickerDialog(
    currentPreset: RadioPreset?,
    region: RegionSelection?,
    onDismiss: () -> Unit,
    onSelect: (RadioPreset) -> Unit,
) {
    val presets = remember(region, currentPreset?.id) { RadioPresets.visiblePresets(region, activeID = currentPreset?.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_choose_preset)) },
        text = {
            LazyColumn {
                item {
                    Text(
                        (region?.let { stringResource(R.string.settings_showing_presets, RegionalAreas.displayName(it)) } ?: "") +
                            stringResource(R.string.preset_legal),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(presets, key = { it.id }) { preset ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(preset.name)
                            Text(
                                String.format(Locale.US, "%.3f MHz", preset.frequencyMHz),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onSelect(preset) }, enabled = preset.id != currentPreset?.id) {
                            Text(if (preset.id == currentPreset?.id) stringResource(R.string.settings_current) else stringResource(R.string.settings_use))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

/**
 * Manual radio parameter entry, ported from `AdvancedRadioSection.swift`. Unlike
 * [RadioPresetPickerDialog], this port folds Swift's separate `RadioSettingsView`/
 * `AdvancedSettingsView` screens' repeat-mode handling into one dialog reachable from
 * [DeviceSection]'s Radio subsection — see that section's "Manual settings" button — rather than
 * adding another top-level [SectionCard]/subpage, matching this screen's existing flat structure
 * (see its class doc).
 *
 * Frequency/TX power are free-text, validated against [PacketBuilder.FREQUENCY_RANGE_KHZ]/
 * [PacketBuilder.TX_POWER_FLOOR] before enabling Apply — the same non-trapping-conversion checks
 * `AdvancedRadioSection.applySettings()` does. Toggling repeat mode snaps/restores the frequency
 * field the same way Swift's `snapFrequencyForRepeat`/`restoreFrequencyAfterRepeat` do.
 */
@Composable
private fun AdvancedRadioDialog(
    device: DeviceDto,
    onDismiss: () -> Unit,
    onApply: (frequencyKHz: UInt, bandwidthHz: UInt, spreadingFactor: UByte, codingRate: UByte, txPower: Byte, clientRepeat: Boolean) -> Unit,
) {
    var frequencyInput by remember { mutableStateOf(String.format(Locale.US, "%.3f", device.frequency.toDouble() / 1000.0)) }
    var bandwidth by remember { mutableStateOf(RadioOptions.nearestBandwidth(device.bandwidth)) }
    var spreadingFactor by remember { mutableStateOf(device.spreadingFactor.toInt()) }
    var codingRate by remember { mutableStateOf(device.codingRate.toInt()) }
    var txPowerInput by remember { mutableStateOf(device.txPower.toString()) }
    var clientRepeat by remember { mutableStateOf(device.clientRepeat) }

    val scaledFreqKHz = frequencyInput.toDoubleOrNull()?.let { (it * 1000).roundToLong() }
    val freqValid = scaledFreqKHz != null && scaledFreqKHz in 0..UInt.MAX_VALUE.toLong() &&
        PacketBuilder.FREQUENCY_RANGE_KHZ.contains(scaledFreqKHz.toUInt())
    val txPower = txPowerInput.toIntOrNull()
    val txPowerValid = txPower != null && txPower >= PacketBuilder.TX_POWER_FLOOR && txPower <= device.maxTxPower

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_radio_config)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = frequencyInput,
                    onValueChange = { frequencyInput = it },
                    label = { Text(stringResource(R.string.rf_frequency_mhz)) },
                    singleLine = true,
                    isError = !freqValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(8.dp))
                RadioParamPicker(
                    label = "Bandwidth (kHz)",
                    valueText = RadioOptions.formatBandwidth(bandwidth),
                    options = RadioOptions.bandwidthsHz.map { it to RadioOptions.formatBandwidth(it) },
                    onSelect = { bandwidth = it },
                )
                RadioParamPicker(
                    label = "Spreading Factor",
                    valueText = spreadingFactor.toString(),
                    options = RadioOptions.spreadingFactors.map { it to it.toString() },
                    onSelect = { spreadingFactor = it },
                )
                RadioParamPicker(
                    label = "Coding Rate",
                    valueText = codingRate.toString(),
                    options = RadioOptions.codingRates.map { it to it.toString() },
                    onSelect = { codingRate = it },
                )
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedTextField(
                    value = txPowerInput,
                    onValueChange = { input -> txPowerInput = input.filterIndexed { i, c -> c.isDigit() || (c == '-' && i == 0) } },
                    label = { Text("TX Power (dBm)") },
                    singleLine = true,
                    isError = !txPowerValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (device.supportsClientRepeat) {
                    Spacer(modifier = Modifier.size(8.dp))
                    SwitchRowWithDescription(
                        label = stringResource(R.string.settings_repeat_mode_label),
                        description = stringResource(R.string.settings_repeat_mode_desc),
                        checked = clientRepeat,
                        enabled = true,
                        onCheckedChange = { enabling ->
                            clientRepeat = enabling
                            if (enabling) {
                                val currentKHz = scaledFreqKHz?.toUInt()
                                val nearest = currentKHz?.let {
                                    if (RadioPresets.matchingRepeatPreset(it) != null) null else RadioPresets.nearestRepeatPreset(it)
                                }
                                if (nearest != null) frequencyInput = String.format(Locale.US, "%.3f", nearest.frequencyMHz)
                            } else {
                                val restoredKHz = device.preRepeatFrequency ?: device.frequency
                                frequencyInput = String.format(Locale.US, "%.3f", restoredKHz.toDouble() / 1000.0)
                            }
                        },
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    stringResource(R.string.settings_radio_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = freqValid && txPowerValid,
                onClick = {
                    onApply(scaledFreqKHz!!.toUInt(), bandwidth, spreadingFactor.toUByte(), codingRate.toUByte(), txPower!!.toByte(), clientRepeat)
                },
            ) { Text(stringResource(R.string.common_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun <T> RadioParamPicker(label: String, valueText: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { expanded = true },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(value) })
            }
        }
    }
}

@Composable
private fun NotificationsSection(preferences: NotificationPreferences, store: NotificationPreferencesStore) {
    SettingsGroupLabel(stringResource(R.string.common_notifications))
    NotificationPermissionRow()
    SwitchRow(stringResource(R.string.settings_notif_direct), preferences.contactMessagesEnabled, store::setContactMessagesEnabled)
    HorizontalDivider()
    SwitchRow(stringResource(R.string.settings_notif_channel), preferences.channelMessagesEnabled, store::setChannelMessagesEnabled)
    HorizontalDivider()
    SwitchRow(stringResource(R.string.settings_notif_room), preferences.roomMessagesEnabled, store::setRoomMessagesEnabled)
    HorizontalDivider()
    SwitchRow(stringResource(R.string.settings_notif_new_contact), preferences.newContactDiscoveredEnabled, store::setNewContactDiscoveredEnabled)
    HorizontalDivider()
    SwitchRow(stringResource(R.string.settings_notif_reactions), preferences.reactionNotificationsEnabled, store::setReactionNotificationsEnabled)
    HorizontalDivider()
    SwitchRow(stringResource(R.string.settings_notif_sound), preferences.soundEnabled, store::setSoundEnabled)
    HorizontalDivider()
    SwitchRow(stringResource(R.string.settings_notif_low_battery), preferences.lowBatteryEnabled, store::setLowBatteryEnabled)
}

/**
 * Ported from `NodesSettingsSection.swift`. Applies each field immediately on change instead of
 * Swift's staged local-edit-state + single "Apply" button — same immediate-apply convention as
 * [TelemetrySection]/[DirectMessagesSection] elsewhere on this screen, at the cost of one BLE
 * round trip per toggle instead of one batched write (see
 * [SettingsViewModel.setAutoAddSettings]'s doc). [StaleNodeCleanupSection] below is a separate
 * group, matching its own top-level `Section` in Swift.
 */
@Composable
private fun NodesSection(device: DeviceDto, isBusy: Boolean, viewModel: SettingsViewModel) {
    var modeExpanded by remember { mutableStateOf(false) }
    var hopsExpanded by remember { mutableStateOf(false) }
    val mode = device.autoAddMode

    SettingsGroupLabel(stringResource(R.string.settings_nodes))

    Box {
        SettingsListRow(title = stringResource(R.string.settings_auto_add_mode), value = autoAddModeLabel(mode), onClick = { if (!isBusy) modeExpanded = true })
        DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
            val options = if (device.supportsAutoAddConfig) {
                listOf(AutoAddMode.MANUAL, AutoAddMode.SELECTED_TYPES, AutoAddMode.ALL)
            } else {
                listOf(AutoAddMode.MANUAL, AutoAddMode.ALL)
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(autoAddModeLabel(option)) },
                    onClick = { modeExpanded = false; viewModel.setAutoAddSettings(mode = option) },
                )
            }
        }
    }

    if (device.supportsAutoAddConfig && mode == AutoAddMode.SELECTED_TYPES) {
        HorizontalDivider()
        SwitchRow(stringResource(R.string.common_contacts), device.autoAddContacts, enabled = !isBusy, onCheckedChange = { viewModel.setAutoAddSettings(contacts = it) })
        HorizontalDivider()
        SwitchRow(stringResource(R.string.settings_auto_add_repeaters), device.autoAddRepeaters, enabled = !isBusy, onCheckedChange = { viewModel.setAutoAddSettings(repeaters = it) })
        HorizontalDivider()
        SwitchRow(stringResource(R.string.settings_auto_add_rooms), device.autoAddRoomServers, enabled = !isBusy, onCheckedChange = { viewModel.setAutoAddSettings(roomServers = it) })
    }

    if (device.supportsAutoAddMaxHops && mode != AutoAddMode.MANUAL) {
        HorizontalDivider()
        Box {
            SettingsListRow(title = stringResource(R.string.settings_auto_add_max_hops), value = maxHopsLabel(device.autoAddMaxHops), onClick = { if (!isBusy) hopsExpanded = true })
            DropdownMenu(expanded = hopsExpanded, onDismissRequest = { hopsExpanded = false }) {
                MAX_HOPS_OPTIONS.forEach { hops ->
                    DropdownMenuItem(
                        text = { Text(maxHopsLabel(hops)) },
                        onClick = { hopsExpanded = false; viewModel.setAutoAddSettings(maxHops = hops) },
                    )
                }
            }
        }
        if (device.autoAddMaxHops > 0u) {
            Text(
                stringResource(R.string.settings_auto_add_max_hops_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (device.supportsAutoAddConfig) {
        HorizontalDivider()
        SwitchRowWithDescription(
            label = stringResource(R.string.settings_overwrite_oldest),
            description = stringResource(R.string.settings_overwrite_oldest_desc),
            checked = device.overwriteOldest,
            enabled = !isBusy,
            onCheckedChange = { viewModel.setAutoAddSettings(overwriteOldest = it) },
        )
    }

    Text(autoAddModeDescription(mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun autoAddModeLabel(mode: AutoAddMode): String = stringResource(
    when (mode) {
        AutoAddMode.MANUAL -> R.string.settings_mode_manual
        AutoAddMode.SELECTED_TYPES -> R.string.settings_mode_selected
        AutoAddMode.ALL -> R.string.settings_mode_all
    },
)

@Composable
private fun autoAddModeDescription(mode: AutoAddMode): String = stringResource(
    when (mode) {
        AutoAddMode.MANUAL -> R.string.settings_mode_manual_desc
        AutoAddMode.SELECTED_TYPES -> R.string.settings_mode_selected_desc
        AutoAddMode.ALL -> R.string.settings_mode_all_desc
    },
)

private val MAX_HOPS_OPTIONS: List<UByte> = (0..7).map { it.toUByte() }

@Composable
private fun maxHopsLabel(hops: UByte): String = when (val h = hops.toInt()) {
    0 -> stringResource(R.string.settings_hops_no_limit)
    1 -> stringResource(R.string.settings_hops_direct_only)
    2 -> "1 Hop"
    else -> "${h - 1} Hops"
}

/**
 * Ported from `StaleNodeCleanupSection.swift`. `isEnabled` mirrors Swift's local `@State` (derived
 * from [thresholdDays] `> 0` but editable before a day option is actually picked, so toggling on
 * shows the picker without yet writing a threshold) rather than being driven straight off
 * [thresholdDays] — see [SettingsViewModel.setStaleNodeCleanupThreshold]'s doc for what's not
 * ported: the automatic post-sync cleanup pass and its 3-hour cooldown. `footerDisconnected` isn't
 * reachable here — this composable only renders inside [SettingsUiState.Ready], i.e. while
 * connected, so Swift's `appState.connectionState == .ready` footer branch is unconditionally true.
 * Swift uses its section header text as the toggle's own label (no separate title); this port
 * splits them into a [com.meshcoretwo.android.ui.components.SettingsGroupLabel] (matching every
 * other group on this screen) plus a plain "Enabled" switch row.
 */
@Composable
private fun StaleNodeCleanupSection(thresholdDays: Int, lastRun: Instant?, isBusy: Boolean, viewModel: SettingsViewModel) {
    var isEnabled by remember(thresholdDays) { mutableStateOf(thresholdDays > 0) }
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(7, 14, 30, 90)

    SettingsGroupLabel(stringResource(R.string.settings_auto_remove))

    SwitchRow(
        stringResource(R.string.settings_enabled),
        checked = isEnabled,
        enabled = !isBusy,
        onCheckedChange = { enabling ->
            isEnabled = enabling
            if (!enabling) viewModel.setStaleNodeCleanupThreshold(0)
        },
    )

    if (isEnabled) {
        HorizontalDivider()
        Box {
            SettingsListRow(
                title = stringResource(R.string.settings_remove_older_than),
                value = if (thresholdDays > 0) stringResource(R.string.settings_days_count, thresholdDays) else stringResource(R.string.settings_select),
                onClick = { if (!isBusy) expanded = true },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { days ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_days_count, days)) },
                        onClick = { expanded = false; viewModel.setStaleNodeCleanupThreshold(days) },
                    )
                }
            }
        }

        if (thresholdDays > 0 && lastRun != null) {
            HorizontalDivider()
            SettingsListRow(
                title = stringResource(R.string.settings_last_checked),
                value = DateUtils.getRelativeTimeSpanString(lastRun.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
            )
        }
    }

    Spacer(modifier = Modifier.size(4.dp))
    Text(
        when {
            !isEnabled -> stringResource(R.string.settings_auto_remove_off)
            thresholdDays == 0 -> stringResource(R.string.settings_auto_remove_choose)
            else -> stringResource(R.string.settings_auto_remove_active, thresholdDays)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Packed telemetry permission levels, ported from Swift's private `TelemetryMode` enum in `TelemetrySettingsSection.swift`. */
private object TelemetryMode {
    const val OFF: UByte = 0u
    const val TRUSTED_ONLY: UByte = 1u
    const val EVERYONE: UByte = 2u
}

/**
 * Ported from `TelemetrySettingsSection.swift`. Not ported: the "Manage Trusted Contacts"
 * `NavigationLink` shown when trusted-only filtering is on — there's no per-contact trust flag or
 * a trusted-contacts management screen on this port yet, so the toggle below can be switched on
 * but has nothing to actually list/edit against.
 */
@Composable
private fun TelemetrySection(device: DeviceDto, isBusy: Boolean, viewModel: SettingsViewModel) {
    val baseOn = device.telemetryModeBase > TelemetryMode.OFF
    val isFilterByTrusted = device.telemetryModeBase == TelemetryMode.TRUSTED_ONLY
    val enabledMode = if (isFilterByTrusted) TelemetryMode.TRUSTED_ONLY else TelemetryMode.EVERYONE

    SettingsGroupLabel(stringResource(R.string.settings_telemetry))
    SwitchRowWithDescription(
        label = stringResource(R.string.settings_telemetry_allow),
        description = stringResource(R.string.settings_telemetry_allow_desc),
        checked = baseOn,
        enabled = !isBusy,
        onCheckedChange = { checked -> viewModel.setTelemetry(base = if (checked) enabledMode else TelemetryMode.OFF) },
    )
    if (baseOn) {
        HorizontalDivider()
        SwitchRowWithDescription(
            label = stringResource(R.string.settings_telemetry_location),
            description = stringResource(R.string.settings_telemetry_location_desc),
            checked = device.telemetryModeLocation > TelemetryMode.OFF,
            enabled = !isBusy,
            onCheckedChange = { checked -> viewModel.setTelemetry(location = if (checked) enabledMode else TelemetryMode.OFF) },
        )
        HorizontalDivider()
        SwitchRowWithDescription(
            label = stringResource(R.string.settings_telemetry_env),
            description = stringResource(R.string.settings_telemetry_env_desc),
            checked = device.telemetryModeEnvironment > TelemetryMode.OFF,
            enabled = !isBusy,
            onCheckedChange = { checked -> viewModel.setTelemetry(environment = if (checked) enabledMode else TelemetryMode.OFF) },
        )
        HorizontalDivider()
        SwitchRowWithDescription(
            label = stringResource(R.string.settings_telemetry_trusted),
            description = stringResource(R.string.settings_telemetry_trusted_desc),
            checked = isFilterByTrusted,
            enabled = !isBusy,
            onCheckedChange = { checked ->
                val mode = if (checked) TelemetryMode.TRUSTED_ONLY else TelemetryMode.EVERYONE
                viewModel.setTelemetry(
                    base = if (device.telemetryModeBase > TelemetryMode.OFF) mode else TelemetryMode.OFF,
                    location = if (device.telemetryModeLocation > TelemetryMode.OFF) mode else TelemetryMode.OFF,
                    environment = if (device.telemetryModeEnvironment > TelemetryMode.OFF) mode else TelemetryMode.OFF,
                )
            },
        )
    }
    Text(
        stringResource(R.string.settings_telemetry_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SwitchRowWithDescription(label: String, description: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SettingsListRow(
        title = label,
        value = description,
        singleLineValue = false,
        trailing = { Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange) },
    )
}

/**
 * Ported from `DirectMessagesSettingsSection.swift` — how many BLE-level ACKs the device sends
 * per direct message (1 or 2; the Swift picker's displayed 1/2 maps onto the raw
 * [DeviceDto.multiAcks] 0/1, same off-by-one the Swift `acksBinding` uses). Placed next to
 * [NotificationsSection] rather than folded into [DeviceSection] — unlike that section's radio/
 * Bluetooth/WiFi hardware rows, this setting is about message delivery, not the device itself.
 */
@Composable
private fun DirectMessagesSection(device: DeviceDto, isBusy: Boolean, viewModel: SettingsViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val ackCount = device.multiAcks.toInt() + 1

    SettingsGroupLabel(stringResource(R.string.settings_direct_messages))
    Box {
        SettingsListRow(title = stringResource(R.string.settings_acks), value = "$ackCount", onClick = { if (!isBusy) expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(1, 2).forEach { count ->
                DropdownMenuItem(
                    text = { Text("$count") },
                    onClick = { expanded = false; viewModel.setMultiAcks((count - 1).toUByte()) },
                )
            }
        }
    }
    Text(
        stringResource(R.string.settings_acks_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Ported from `MessagesSettingsSection.swift` — off by default, same as the Swift toggles.
 */
@Composable
private fun MessageInfoSection(prefs: SharedPreferences) {
    var showIncomingPath by remember { mutableStateOf(ChatDisplayPreferences.isIncomingPathEnabled(prefs)) }
    var showIncomingHopCount by remember { mutableStateOf(ChatDisplayPreferences.isIncomingHopCountEnabled(prefs)) }
    var showIncomingRegion by remember { mutableStateOf(ChatDisplayPreferences.isIncomingRegionEnabled(prefs)) }

    SettingsGroupLabel(stringResource(R.string.settings_message_info))
    SwitchRow(
        stringResource(R.string.settings_incoming_path),
        showIncomingPath,
        onCheckedChange = { enabled ->
            showIncomingPath = enabled
            ChatDisplayPreferences.setIncomingPathEnabled(prefs, enabled)
        },
    )
    SwitchRow(
        stringResource(R.string.settings_incoming_hops),
        showIncomingHopCount,
        onCheckedChange = { enabled ->
            showIncomingHopCount = enabled
            ChatDisplayPreferences.setIncomingHopCountEnabled(prefs, enabled)
        },
    )
    SwitchRow(
        stringResource(R.string.settings_incoming_region),
        showIncomingRegion,
        onCheckedChange = { enabled ->
            showIncomingRegion = enabled
            ChatDisplayPreferences.setIncomingRegionEnabled(prefs, enabled)
        },
    )
    Text(
        stringResource(R.string.settings_message_info_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Ported from `DeviceIdentitySection.swift`: two entry points that replace the device's
 * cryptographic identity. Both `ImportKeySheet`/`RegenerateIdentitySheet` full-screen sheets fold
 * into [AlertDialog]s here, matching this screen's existing flat-dialog convention
 * ([AdvancedRadioDialog]/[WifiEditDialog]) instead of adding subpage navigation. See
 * [SettingsViewModel.importPrivateKey]'s doc for why both dialogs end in a forced
 * [SettingsViewModel.forgetDevice] rather than Swift's in-place `refreshDeviceInfo()`.
 */
@Composable
private fun DeviceIdentitySection(isBusy: Boolean, viewModel: SettingsViewModel) {
    var showImportKey by remember { mutableStateOf(false) }
    var showRegenerate by remember { mutableStateOf(false) }

    SettingsGroupLabel(stringResource(R.string.settings_identity))
    Text(
        stringResource(R.string.settings_identity_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingsListRow(title = stringResource(R.string.settings_import_key), onClick = { if (!isBusy) showImportKey = true })
    HorizontalDivider()
    SettingsListRow(title = stringResource(R.string.settings_regenerate), onClick = { if (!isBusy) showRegenerate = true })

    if (showImportKey) {
        ImportKeyDialog(
            onDismiss = { showImportKey = false },
            onConfirm = { key -> viewModel.importPrivateKey(key); showImportKey = false },
        )
    }
    if (showRegenerate) {
        RegenerateIdentityDialog(
            onDismiss = { showRegenerate = false },
            onConfirm = { key -> viewModel.importPrivateKey(key); showRegenerate = false },
        )
    }
}


/**
 * Ported from `ImportKeySheet.swift`/`ImportKeyViewModel.swift`. Hex input is filtered to hex
 * digits as typed (`sanitizeInput`); [KeyGenerationService.validateExpandedKey] runs the same
 * RFC 8032 clamping check as Swift's `validateAndConfirm()` before showing the replace-confirm
 * dialog — the actual device write only happens after that confirmation, via [onConfirm].
 */
@Composable
private fun ImportKeyDialog(onDismiss: () -> Unit, onConfirm: (ByteArray) -> Unit) {
    var hexInput by remember { mutableStateOf("") }
    val invalidHexText = stringResource(R.string.settings_invalid_hex)
    val context = LocalContext.current
    var errorText by remember { mutableStateOf<String?>(null) }
    var validatedKey by remember { mutableStateOf<ByteArray?>(null) }
    var showReplaceConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_import_private_key)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_import_key_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { input ->
                        hexInput = input.uppercase().filter { it.isDigit() || it in 'A'..'F' }
                        errorText = null
                    },
                    label = { Text(stringResource(R.string.settings_private_key_hex)) },
                    isError = errorText != null,
                    supportingText = errorText?.let { message -> { Text(message) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = hexInput.isNotEmpty(),
                onClick = {
                    val bytes = hexInput.decodeHex()
                    if (bytes == null) {
                        errorText = invalidHexText
                        return@TextButton
                    }
                    try {
                        KeyGenerationService.validateExpandedKey(bytes)
                        validatedKey = bytes
                        showReplaceConfirm = true
                    } catch (e: KeyGenerationService.KeyGenerationError) {
                        errorText = e.toUiText(UiText.of(R.string.settings_err_generic)).resolve(context)
                    }
                },
            ) { Text(stringResource(R.string.common_import)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )

    if (showReplaceConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_replace_identity_title),
            body = stringResource(R.string.settings_replace_identity_body),
            confirmLabel = stringResource(R.string.common_replace),
            onDismiss = { showReplaceConfirm = false },
            onConfirm = { validatedKey?.let(onConfirm) },
        )
    }
}

/**
 * Ported from `RegenerateIdentitySheet.swift`/`RegenerateIdentityViewModel.swift`. Generation is
 * pure local computation — no device I/O — so it runs as a cancellable [Job] owned by this
 * composable (mirroring `generateTask`/`cancelGeneration`) rather than going through
 * [SettingsViewModel]; only the final "Replace" confirm calls back into the view model, the same
 * split [AdvancedRadioDialog] already uses for its own local-then-apply staging.
 */
@Composable
private fun RegenerateIdentityDialog(onDismiss: () -> Unit, onConfirm: (ByteArray) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var hexPrefix by remember { mutableStateOf("") }
    var prefixError by remember { mutableStateOf<String?>(null) }
    var generationError by remember { mutableStateOf<String?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var generated by remember { mutableStateOf<KeyGenerationService.GeneratedIdentity?>(null) }
    var generateJob by remember { mutableStateOf<Job?>(null) }
    var showReplaceConfirm by remember { mutableStateOf(false) }

    fun generate() {
        prefixError = null
        generationError = null
        generated = null
        isGenerating = true
        generateJob = scope.launch {
            try {
                val identity = withContext(Dispatchers.Default) {
                    KeyGenerationService.generateIdentity(hexPrefix.ifBlank { null })
                }
                generated = identity
            } catch (e: CancellationException) {
                throw e
            } catch (e: KeyGenerationService.KeyGenerationError.ReservedPrefix) {
                prefixError = e.toUiText(UiText.of(R.string.settings_err_generic)).resolve(context)
            } catch (e: KeyGenerationService.KeyGenerationError) {
                generationError = e.toUiText(UiText.of(R.string.settings_err_generic)).resolve(context)
            } finally {
                isGenerating = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { generateJob?.cancel() }
    }

    fun dismiss() {
        generateJob?.cancel()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(stringResource(R.string.settings_regenerate_identity)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.settings_regenerate_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedTextField(
                    value = hexPrefix,
                    onValueChange = { input ->
                        hexPrefix = input.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(4)
                        prefixError = null
                    },
                    label = { Text(stringResource(R.string.settings_vanity_prefix)) },
                    singleLine = true,
                    isError = prefixError != null,
                    supportingText = prefixError?.let { message -> { Text(message) } },
                    enabled = !isGenerating,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = ::generate, enabled = !isGenerating, modifier = Modifier.fillMaxWidth()) {
                    if (isGenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.settings_generating))
                    } else {
                        Text(stringResource(R.string.settings_generate))
                    }
                }
                generationError?.let { message ->
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                generated?.let { identity ->
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(stringResource(R.string.settings_public_key), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(
                            identity.publicKey.hexString.uppercase().chunked(4).joinToString(" "),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.settings_private_key), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(
                            identity.expandedPrivateKey.hexString.uppercase().chunked(4).joinToString(" "),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = generated != null && !isGenerating, onClick = { showReplaceConfirm = true }) { Text(stringResource(R.string.common_replace)) }
        },
        dismissButton = { TextButton(onClick = ::dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )

    if (showReplaceConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_replace_identity_title),
            body = stringResource(R.string.settings_replace_identity_body),
            confirmLabel = stringResource(R.string.common_replace),
            onDismiss = { showReplaceConfirm = false },
            onConfirm = { generated?.let { onConfirm(it.expandedPrivateKey) } },
        )
    }
}

/**
 * Settings → Danger zone entry: a tinted `errorContainer` card (readable in every theme, since all
 * palettes define the container pair) that opens [DangerZoneScreen], where the destructive actions
 * live — kept off this long scrolling list so they can't be tapped by accident.
 */
@Composable
private fun DangerZoneEntry(onOpenDangerZone: () -> Unit) {
    Surface(
        onClick = onOpenDangerZone,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.common_danger_zone), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.settings_danger_zone_desc), style = MaterialTheme.typography.bodySmall)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    SettingsListRow(title = label, trailing = { Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange) })
}

/**
 * Editable counterpart to the read-only "Path hash" [InfoRow], shown instead of it once
 * [DeviceDto.supportsPathHashMode] (firmware v10+). Ported from `PathHashModeSection.swift`'s
 * menu-style `Picker`, folded into [DeviceSection]'s existing Radio rows rather than a separate
 * `AdvancedSettingsView` subpage — this screen doesn't have that split yet (see its class doc).
 */
@Composable
private fun PathHashModeRow(pathHashMode: UByte, isBusy: Boolean, onSelect: (UByte) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labels = listOf("1 byte", "2 bytes", "3 bytes")
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(enabled = !isBusy) { expanded = true },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Path hash", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(labels[pathHashMode.toInt().coerceIn(0, 2)], style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            labels.forEachIndexed { index, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onSelect(index.toUByte()) })
            }
        }
    }
}


