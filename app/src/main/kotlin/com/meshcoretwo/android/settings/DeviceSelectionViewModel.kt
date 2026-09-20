// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.connect
import com.meshcoretwo.services.connection.connectViaWiFi
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.deleteDevice
import com.meshcoretwo.services.connection.fetchSavedDevices
import com.meshcoretwo.services.connection.isDeviceConnectedToOtherApp
import com.meshcoretwo.services.persistence.DeviceDto
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class DeviceSelectionUiState {
    data object Loading : DeviceSelectionUiState()
    data class Loaded(val devices: List<DeviceDto>, val connectedDeviceId: UUID?, val connectedElsewhere: Set<UUID>) : DeviceSelectionUiState()
}

/**
 * Backs [DeviceSelectionScreen] — the saved-devices switcher reached from Settings' `DeviceSection`
 * (and, unlike most Settings sub-screens, also from the `Connecting` state — see that screen's
 * doc). Ported from `DeviceSelectionSheet.swift`'s non-UI half; the BLE-scan/pairing half
 * ([com.meshcoretwo.services.connection.pairNewDevice], fed by
 * [com.meshcoretwo.android.ui.components.DeviceScanSheet]) stays composable-local state in the
 * screen itself, matching how [com.meshcoretwo.android.onboarding.PairScreen] already handles that
 * same ceremony without a `ViewModel` — see this class's screen doc for why splitting it out here
 * would just duplicate that shape for no benefit.
 *
 * Not ported: `RSSIScanTracker`/`SignalBars` continuous-scan signal-tier gating that disables a
 * saved BLE row until it's seen advertising. Reusing that here would mean running a second
 * concurrent BLE scan underneath the list (Android's `startBLEScanning()` isn't shared/ref-counted
 * across callers) purely to decide whether a tap is *allowed* — tapping an out-of-range device
 * already fails cleanly through the normal connect-error path below, the same way tapping "Add
 * Device" on [com.meshcoretwo.android.onboarding.PairScreen] does for a device that never
 * advertises. A future slice can revisit this if out-of-range taps prove confusing in practice.
 */
class DeviceSelectionViewModel(private val connectionManager: ConnectionManager) : ViewModel() {
    private val _uiState = MutableStateFlow<DeviceSelectionUiState>(DeviceSelectionUiState.Loading)
    val uiState: StateFlow<DeviceSelectionUiState> = _uiState.asStateFlow()

    private val _connectingDeviceId = MutableStateFlow<UUID?>(null)
    val connectingDeviceId: StateFlow<UUID?> = _connectingDeviceId.asStateFlow()

    private val _errorMessage = MutableStateFlow<UiText?>(null)
    val errorMessage: StateFlow<UiText?> = _errorMessage.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = DeviceSelectionUiState.Loading
            try {
                val saved = connectionManager.fetchSavedDevices().filter(DeviceSelectionFilter::isSelectable)
                val connectedElsewhere = saved
                    .mapNotNull { device -> device.bleAddress?.let { device.id to it } }
                    .filter { (_, address) -> connectionManager.isDeviceConnectedToOtherApp(address) }
                    .mapTo(mutableSetOf()) { it.first }
                _uiState.value = DeviceSelectionUiState.Loaded(saved, connectionManager.connectedDeviceRecord?.id, connectedElsewhere)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.value = DeviceSelectionUiState.Loaded(emptyList(), null, emptySet())
                _errorMessage.value = error.toUiText(UiText.of(R.string.devices_err_load))
            }
        }
    }

    /** No-ops if [device] is already the connected one — see [ConnectionManager.connect]'s doc. */
    fun connect(device: DeviceDto, onConnected: () -> Unit) {
        if (device.id == connectionManager.connectedDeviceRecord?.id) {
            onConnected()
            return
        }
        viewModelScope.launch {
            _connectingDeviceId.value = device.id
            try {
                val wifiHost = device.wifiHost
                if (wifiHost != null) {
                    connectionManager.connectViaWiFi(wifiHost, device.wifiPort ?: DEFAULT_WIFI_PORT, forceFullSync = true)
                } else {
                    val address = device.bleAddress ?: error("Selectable device has no connection method")
                    connectionManager.connect(address, forceFullSync = true, forceReconnect = true)
                }
                onConnected()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _errorMessage.value = error.toUiText(UiText.of(R.string.wifi_connect_failed))
            } finally {
                _connectingDeviceId.value = null
            }
        }
    }

    fun delete(device: DeviceDto) {
        viewModelScope.launch {
            connectionManager.deleteDevice(device.id)
            refresh()
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    companion object {
        private const val DEFAULT_WIFI_PORT = 5000
    }

    class Factory(private val connectionManager: ConnectionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DeviceSelectionViewModel(connectionManager) as T
    }
}
