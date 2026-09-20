// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.DeviceConnectionState
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.connection.discoveredNodeStore
import com.meshcoretwo.services.contacts.ContactService
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.utilities.VContactIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ContactsListUiState {
    data object Connecting : ContactsListUiState()

    /** [inboundHopByKey] is a contact's public-key hex mapped to its Discover-list inbound advert
     * hop count — see [ContactDto.displayedHopCount]'s doc — refreshed alongside [contacts]. */
    data class Ready(val contacts: List<ContactDto>, val inboundHopByKey: Map<String, Int>) : ContactsListUiState()
}

/**
 * Backs the contacts list screen (`ContactsListScreen.kt`) — the app-layer analogue of the list
 * half of iOS's `ContactsViewModel`. Same imperative-refetch shape as [com.meshcoretwo.android.chat.ChatListViewModel]
 * (see that class's doc for why: no reactive store-change stream exists below this layer yet).
 * Unlike the chat list, contacts don't change nearly as often mid-session (no per-message churn),
 * so this only reloads on entering [DeviceConnectionState.READY] plus an explicit [refresh] the
 * screen calls on pull-to-refresh and on returning from the detail screen — no event-stream
 * subscription.
 */
class ContactsListViewModel(private val connectionManager: ConnectionManager) : ViewModel() {
    private val _uiState = MutableStateFlow<ContactsListUiState>(ContactsListUiState.Connecting)
    val uiState: StateFlow<ContactsListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            connectionManager.connectionStateEvents.collect { state ->
                if (state == DeviceConnectionState.READY) refresh() else _uiState.value = ContactsListUiState.Connecting
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            if (connectionManager.connectionState != DeviceConnectionState.READY) return@launch
            val radioID = connectionManager.lastConnectedRadioID ?: return@launch
            val contacts = connectionManager.contactService?.getContacts(radioID) ?: return@launch
            val inboundHopByKey = connectionManager.discoveredNodeStore?.fetchDiscoveredNodes(radioID)
                ?.mapNotNull { node -> node.inboundHopCount?.let { node.publicKey.hexString to it } }
                ?.toMap().orEmpty()
            _uiState.value = ContactsListUiState.Ready(contacts, inboundHopByKey)
        }
    }

    /** The virtual self-contact can't be removed — same rule as [ContactDetailViewModel.delete]. */
    fun canDelete(contact: ContactDto): Boolean {
        val selfPublicKey = connectionManager.connectedDeviceRecord?.publicKey ?: return true
        return !VContactIdentity.isVContact(contact.publicKey, selfPublicKey)
    }

    fun setBlocked(contact: ContactDto, blocked: Boolean) =
        runAction { it.updateContactPreferences(contact.id, isBlocked = blocked) }

    fun delete(contact: ContactDto) {
        if (!canDelete(contact)) return
        runAction { service ->
            val radioID = connectionManager.lastConnectedRadioID ?: return@runAction
            service.removeContact(radioID, contact.publicKey)
        }
    }

    /**
     * Runs a contact mutation, then refetches. A thrown [Exception] (radio NOT_FOUND, session error)
     * is swallowed — [viewModelScope] has no default handler, so an uncaught throw would crash the
     * process — and the refetch shows whatever actually stuck; same policy as [ContactDetailViewModel].
     */
    private fun runAction(action: suspend (ContactService) -> Unit) {
        viewModelScope.launch {
            val service = connectionManager.contactService ?: return@launch
            try {
                action(service)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // See doc above.
            }
            refresh()
        }
    }

    class Factory(private val connectionManager: ConnectionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ContactsListViewModel(connectionManager) as T
    }
}
