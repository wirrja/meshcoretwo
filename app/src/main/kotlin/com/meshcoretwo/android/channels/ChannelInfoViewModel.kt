// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.services.channels.ChannelService
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.channelService
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.pushChannelFloodScope
import com.meshcoretwo.services.persistence.ChannelDto
import com.meshcoretwo.services.persistence.ChannelFloodScope
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.persistence.NotificationLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ChannelInfoUiState {
    data object Loading : ChannelInfoUiState()
    data object NotFound : ChannelInfoUiState()
    /** [device] is the connected device, for the region-scope picker's known-regions/default-name
     * context — `null` on the rare reload that races a disconnect. */
    data class Loaded(val channel: ChannelDto, val device: DeviceDto?) : ChannelInfoUiState()
}

/**
 * Backs the channel info/actions screen (`ChannelInfoScreen.kt`) — a trimmed port of
 * `ChannelInfoSheet.swift`: favorite toggle, notification level, secret display + copy-as-link,
 * clear messages, leave (delete), and — since the "Per-channel flood scope" slice — the region
 * scope picker ([setFloodScope]). Not ported: inline region discovery (this screen's "Manage
 * Regions" action just navigates to the existing [com.meshcoretwo.android.settings.RegionManagementScreen]
 * instead of duplicating its discovery/add-manually flow inline like `ChannelInfoSheet`'s
 * `DisclosureGroup` does — same simplification `DefaultFloodScopeSection`'s Compose port already
 * made, see that composable's doc), QR code rendering (text link only — see
 * [AddChannelViewModel]'s class doc for why QR itself is a separate ticket), rename (iOS doesn't
 * have this action either — delete-and-recreate is the only path there too).
 */
class ChannelInfoViewModel(
    private val connectionManager: ConnectionManager,
    private val index: UByte,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ChannelInfoUiState>(ChannelInfoUiState.Loading)
    val uiState: StateFlow<ChannelInfoUiState> = _uiState.asStateFlow()

    private val _left = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits once [leave] actually removes the channel — the screen navigates back on it. */
    val left: SharedFlow<Unit> = _left.asSharedFlow()

    init {
        viewModelScope.launch { reload() }
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val radioID = connectionManager.lastConnectedRadioID ?: return
        val channel = connectionManager.channelService?.getChannel(radioID, index)
        _uiState.value = channel?.let { ChannelInfoUiState.Loaded(it, connectionManager.connectedDeviceRecord) } ?: ChannelInfoUiState.NotFound
    }

    fun toggleFavorite() = runAction { service, channel -> service.setFavorite(channel.id, !channel.isFavorite) }

    fun setNotificationLevel(level: NotificationLevel) = runAction { service, channel -> service.setNotificationLevel(channel.id, level) }

    /**
     * Persists [scope] as the channel's flood-scope preference, then pushes its resolution to the
     * radio session. [runAction] already swallows any failure from either step and reloads
     * afterward, so a push failure doesn't roll back an already-persisted preference — matching
     * `ChannelInfoSheet.selectFloodScope`'s `try?` on the push half. Ported from that method.
     */
    fun setFloodScope(scope: ChannelFloodScope) = runAction { service, channel ->
        service.setChannelFloodScope(channel.id, scope)
        connectionManager.pushChannelFloodScope(scope)
    }

    fun clearMessages() = runAction { service, channel -> service.clearChannelMessages(channel.radioID, channel.index) }

    fun leave() {
        viewModelScope.launch {
            val channel = (_uiState.value as? ChannelInfoUiState.Loaded)?.channel ?: return@launch
            val channelService = connectionManager.channelService ?: return@launch
            try {
                channelService.clearChannel(channel.radioID, channel.index)
                _left.emit(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reload()
            }
        }
    }

    private fun runAction(action: suspend (ChannelService, ChannelDto) -> Unit) {
        viewModelScope.launch {
            val channel = (_uiState.value as? ChannelInfoUiState.Loaded)?.channel ?: return@launch
            val channelService = connectionManager.channelService ?: return@launch
            try {
                action(channelService, channel)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Swallowed — reload() below still shows whatever actually stuck. Same
                // no-default-exception-handler concern as ContactDetailViewModel.runAction.
            }
            reload()
        }
    }

    class Factory(
        private val connectionManager: ConnectionManager,
        private val index: UByte,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChannelInfoViewModel(connectionManager, index) as T
    }
}
