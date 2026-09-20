// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.persistence.BlockedChannelSenderDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class BlockedChannelSendersUiState {
    data object Loading : BlockedChannelSendersUiState()
    data class Ready(val senders: List<BlockedChannelSenderDto>) : BlockedChannelSendersUiState()
}

/**
 * Backs [BlockedChannelSendersScreen]. Ported from `BlockedChannelSendersView.swift`'s data half —
 * `ContactService.getBlockedChannelSenders`/[unblock] already carry this port's equivalent of
 * `dataStore.fetchBlockedChannelSenders`/`deleteBlockedChannelSender` (see
 * [com.meshcoretwo.services.contacts.ContactService]'s class doc). `refreshBlockedContactsCache`
 * has no call here to mirror: this port's [com.meshcoretwo.services.persistence.ContactStore
 * .isBlockedSender] queries the block list directly instead of a cache that needs refreshing after
 * every change — see `SyncCoordinator.kt`'s class doc for where that Swift method was retired.
 * `notifyConversationsChanged()` is likewise not ported: unblocking doesn't restore the channel
 * messages the sender's block already deleted, so there's nothing for an open conversation list to
 * re-render.
 */
class BlockedChannelSendersViewModel(private val connectionManager: ConnectionManager) : ViewModel() {
    private val _uiState = MutableStateFlow<BlockedChannelSendersUiState>(BlockedChannelSendersUiState.Loading)
    val uiState: StateFlow<BlockedChannelSendersUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val radioID = connectionManager.connectedDeviceRecord?.radioID ?: return@launch
            val senders = connectionManager.contactService?.getBlockedChannelSenders(radioID) ?: emptyList()
            _uiState.value = BlockedChannelSendersUiState.Ready(senders)
        }
    }

    fun unblock(sender: BlockedChannelSenderDto) {
        viewModelScope.launch {
            val radioID = connectionManager.connectedDeviceRecord?.radioID ?: return@launch
            connectionManager.contactService?.unblockChannelSender(radioID, sender.name)
            refresh()
        }
    }

    class Factory(private val connectionManager: ConnectionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BlockedChannelSendersViewModel(connectionManager) as T
    }
}
