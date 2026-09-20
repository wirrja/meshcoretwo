// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.persistence.ContactDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class BlockedContactsUiState {
    data object Loading : BlockedContactsUiState()
    data class Ready(val contacts: List<ContactDto>) : BlockedContactsUiState()
}

/**
 * Backs the blocked-contacts management screen (`BlockedContactsScreen.kt`). Ported from
 * `BlockedContactsView.swift`, trimmed to this port's imperative-refetch shape (see
 * [ContactsListViewModel]'s doc for why no reactive store-change stream exists below this layer
 * yet) — reuses [com.meshcoretwo.services.contacts.ContactService.getContacts] rather than a
 * dedicated `fetchBlockedContacts` store query, since that method doesn't exist on this port and a
 * client-side [ContactDto.isBlocked] filter is enough for a list this small. Unblocking itself
 * still happens on the detail screen's existing toggle — same as Swift, which pushes
 * `ContactDetailView` from a tap here rather than offering an inline unblock action.
 */
class BlockedContactsViewModel(private val connectionManager: ConnectionManager) : ViewModel() {
    private val _uiState = MutableStateFlow<BlockedContactsUiState>(BlockedContactsUiState.Loading)
    val uiState: StateFlow<BlockedContactsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val radioID = connectionManager.lastConnectedRadioID ?: return@launch
            val contacts = connectionManager.contactService?.getContacts(radioID) ?: return@launch
            _uiState.value = BlockedContactsUiState.Ready(contacts.filter { it.isBlocked })
        }
    }

    class Factory(private val connectionManager: ConnectionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BlockedContactsViewModel(connectionManager) as T
    }
}
