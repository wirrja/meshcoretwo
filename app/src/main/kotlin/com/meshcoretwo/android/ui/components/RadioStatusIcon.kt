// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import com.meshcoretwo.android.ui.i18n.toUiText
import com.meshcoretwo.android.ui.i18n.UiText
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.theme.LocalMeshExtendedColors
import com.meshcoretwo.protocol.BatteryInfo
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.DeviceConnectionState
import com.meshcoretwo.services.connection.advertisementService
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.settingsService
import com.meshcoretwo.services.persistence.activeOCVArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Radio (BLE) connection status indicator, color-coded by [DeviceConnectionState]. Ported from
 * `BLEStatusIndicatorView.swift`'s icon (`StatusIcon`); the popover
 * menu it sits inside (device switch/disconnect, send zero-hop/flood advert, battery readout,
 * jump to advanced settings) needs `DeviceSelectionSheet`/advert-sending/`BatteryMonitor` UI this
 * port doesn't have yet, so this is a plain, non-interactive status glyph for now — a scoped-down
 * read of PLAN.md's "индикатор" wording, not the full menu.
 *
 * `clientRepeat`'s dedicated [LocalMeshExtendedColors]'s `radioRepeatMode` color (`AppColors.Radio
 * .repeatMode`, overriding every other state) is not wired in: [ConnectionManager.connectedDevice]
 * (`DeviceDto`) has no `clientRepeat` field yet — see `DeviceDto`'s trimmed field list — so this
 * only distinguishes disconnected/connecting/ready, matching [DeviceConnectionState]'s cases
 * 1:1 minus that repeat-mode override.
 *
 * Tapping it opens [RadioStatusMenu] (battery, zero-hop/flood advert, change device), a scoped-down
 * `BLEStatusIndicatorView` menu: no disconnect/advanced-settings entries.
 *
 * Ported from `BLEStatusToolbarItem.swift`: every top-level section (`bleStatusToolbarItem()`
 * call sites — Chats/Contacts/Map/Tools/Settings) surfaces this unconditionally at its
 * `TopAppBar`'s leading edge, since (unlike iOS's iPad sidebar) none of this app's top-level
 * screens have a competing leading item.
 */
/** Opens the saved-devices switcher; provided by `MainScreen` so every top-level header shares it. */
val LocalOpenDeviceSelection = compositionLocalOf<() -> Unit> { {} }

@Composable
fun RadioStatusIcon(connectionManager: ConnectionManager) {
    val connectionState by connectionManager.connectionStateEvents.collectAsStateWithLifecycle()
    val extended = LocalMeshExtendedColors.current
    var menuOpen by remember { mutableStateOf(false) }
    val (icon, tint) = when (connectionState) {
        DeviceConnectionState.DISCONNECTED -> R.drawable.ic_signal_cellular_off to null
        DeviceConnectionState.CONNECTING, DeviceConnectionState.CONNECTED, DeviceConnectionState.SYNCING ->
            R.drawable.ic_cell_tower to extended.radioConnecting
        DeviceConnectionState.READY -> R.drawable.ic_cell_tower to extended.radioReady
    }
    Box(Modifier.clip(CircleShape).clickable { menuOpen = true }.padding(8.dp)) {
        val description = stringResource(
            when (connectionState) {
                DeviceConnectionState.DISCONNECTED -> R.string.radio_state_disconnected
                DeviceConnectionState.CONNECTING, DeviceConnectionState.CONNECTED -> R.string.radio_state_connecting
                DeviceConnectionState.SYNCING -> R.string.radio_state_syncing
                DeviceConnectionState.READY -> R.string.radio_state_ready
            },
        )
        if (tint != null) {
            Icon(painterResource(icon), contentDescription = description, tint = tint)
        } else {
            Icon(painterResource(icon), contentDescription = description)
        }
        RadioStatusMenu(connectionManager, connectionState, menuOpen) { menuOpen = false }
    }
}

/**
 * Popup menu behind [RadioStatusIcon]. Ported from `BLEStatusIndicatorView.swift`'s `menuContent`:
 * device name + battery (`NN% (X.XXV)`, OCV-curve percentage), change device, zero-hop and flood
 * self-advert. Disconnected shows only "Connect". Advert items are enabled only at
 * [DeviceConnectionState.READY] and while no advert is in flight.
 */
@Composable
private fun RadioStatusMenu(
    connectionManager: ConnectionManager,
    connectionState: DeviceConnectionState,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    val openDeviceSelection = LocalOpenDeviceSelection.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var battery by remember { mutableStateOf<BatteryInfo?>(null) }
    var isSendingAdvert by remember { mutableStateOf(false) }
    val device = if (connectionState == DeviceConnectionState.DISCONNECTED) null else connectionManager.connectedDeviceRecord

    LaunchedEffect(expanded, connectionState) {
        if (!expanded || connectionState != DeviceConnectionState.READY) return@LaunchedEffect
        battery = try {
            connectionManager.settingsService?.getBattery()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    fun sendAdvert(flood: Boolean) {
        if (isSendingAdvert) return
        isSendingAdvert = true
        onDismiss()
        scope.launch {
            val message = try {
                connectionManager.advertisementService?.sendSelfAdvertisement(flood = flood)
                context.getString(if (flood) R.string.radio_advert_flood_sent else R.string.radio_advert_zero_hop_sent)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                context.getString(R.string.radio_advert_failed, error.toUiText(UiText.of(R.string.radio_unknown_error)).resolve(context))
            }
            isSendingAdvert = false
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (device == null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_connect)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_cell_tower), contentDescription = null) },
                onClick = { onDismiss(); openDeviceSelection() },
            )
            return@DropdownMenu
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(device.nodeName, style = MaterialTheme.typography.titleSmall)
            battery?.takeIf { it.level > 0 }?.let { info ->
                val percent = batteryPercentage(info.level, device.activeOCVArray)
                Text(
                    "$percent% (${"%.2f".format(info.level / 1000.0)} V)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.radio_change_device)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_smartphone), contentDescription = null) },
            onClick = { onDismiss(); openDeviceSelection() },
        )
        val canAdvert = connectionState == DeviceConnectionState.READY && !isSendingAdvert
        DropdownMenuItem(
            text = { Text(stringResource(R.string.radio_send_zero_hop_advert)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_sensors_one_hop), contentDescription = null) },
            enabled = canAdvert,
            onClick = { sendAdvert(flood = false) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.radio_send_flood_advert)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_sensors), contentDescription = null) },
            enabled = canAdvert,
            onClick = { sendAdvert(flood = true) },
        )
    }
}

/**
 * Battery percentage from an 11-point OCV array (100%, 90% … 0%) with linear interpolation. Ported
 * from `BatteryInfo.percentage(using:)` (`BatteryInfo+Display.swift`).
 */
internal fun batteryPercentage(millivolts: Int, ocv: List<Int>): Int {
    if (ocv.size != 11) return (((millivolts / 1000.0 - 3.0) / 1.2) * 100).toInt().coerceIn(0, 100)
    if (millivolts >= ocv[0]) return 100
    if (millivolts <= ocv[10]) return 0
    for (index in 0 until 10) {
        val upper = ocv[index]
        val lower = ocv[index + 1]
        if (millivolts >= lower) {
            val segment = (millivolts - lower).toDouble() / (upper - lower)
            return (10 - index - 1) * 10 + Math.round(segment * 10).toInt()
        }
    }
    return 0
}
