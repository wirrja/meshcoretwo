// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.contacts.ContactService
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.utilities.VContactIdentity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ContactDetailUiState {
    data object Loading : ContactDetailUiState()
    /** The contact is gone (deleted from another surface, or the connection dropped and reconnected to a different radio). */
    data object NotFound : ContactDetailUiState()
    /** [isVContact] disables the delete action — see [VContactIdentity]'s class doc. */
    data class Loaded(val contact: ContactDto, val isVContact: Boolean) : ContactDetailUiState()
}

/**
 * Backs the contact detail screen (`ContactDetailScreen.kt`) — a trimmed port of
 * `ContactDetailView.swift`. Ported actions: favorite toggle, nickname edit, block/unblock,
 * clear messages, delete ("forget"). Not ported (see PLAN.md's Phase 5 contacts slice for the
 * full list): QR/advert sharing, path discover/edit/reset, location map, telemetry/saved-history/
 * admin actions for repeater and room contacts (those need the not-yet-built diagnostics/remote-
 * node UI, Phase 5 items 4/7).
 *
 * Every mutating action reloads from [ContactService] afterward rather than patching local state,
 * matching [com.meshcoretwo.android.chat.ConversationViewModel]'s reload-over-patch convention —
 * simple and correct at this app's scale. A thrown [Exception] (radio NOT_FOUND, session error)
 * is swallowed rather than crashing the process — [ViewModel]'s `viewModelScope` has no default
 * exception handler, so an uncaught throw here would take the whole app down; [reload] afterward
 * still reflects whatever the store actually ended up with.
 */
class ContactDetailViewModel(
    private val connectionManager: ConnectionManager,
    private val contactId: UUID,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ContactDetailUiState>(ContactDetailUiState.Loading)
    val uiState: StateFlow<ContactDetailUiState> = _uiState.asStateFlow()

    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits once [delete] actually removes the contact — the screen navigates back on it. */
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    init {
        viewModelScope.launch { reload() }
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val contact = connectionManager.contactService?.getContactById(contactId)
        if (contact == null) {
            _uiState.value = ContactDetailUiState.NotFound
            return
        }
        val selfPublicKey = connectionManager.connectedDeviceRecord?.publicKey
        val isVContact = selfPublicKey?.let { VContactIdentity.isVContact(contact.publicKey, it) } ?: false
        _uiState.value = ContactDetailUiState.Loaded(contact, isVContact)
    }

    fun toggleFavorite() = runAction { service, contact -> service.updateContactPreferences(contact.id, isFavorite = !contact.isFavorite) }

    fun updateNickname(nickname: String) = runAction { service, contact -> service.updateContactPreferences(contact.id, nickname = nickname) }

    fun setBlocked(blocked: Boolean) = runAction { service, contact -> service.updateContactPreferences(contact.id, isBlocked = blocked) }

    fun clearMessages() = runAction { service, contact -> service.clearContactMessages(contact.id) }

    fun delete() {
        viewModelScope.launch {
            val loaded = _uiState.value as? ContactDetailUiState.Loaded ?: return@launch
            if (loaded.isVContact) return@launch
            val contactService = connectionManager.contactService ?: return@launch
            val radioID = connectionManager.lastConnectedRadioID ?: return@launch
            try {
                contactService.removeContact(radioID, loaded.contact.publicKey)
                _deleted.emit(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reload()
            }
        }
    }

    private fun runAction(action: suspend (ContactService, ContactDto) -> Unit) {
        viewModelScope.launch {
            val contact = (_uiState.value as? ContactDetailUiState.Loaded)?.contact ?: return@launch
            val contactService = connectionManager.contactService ?: return@launch
            try {
                action(contactService, contact)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Swallowed — see class doc. reload() below still shows whatever actually stuck.
            }
            reload()
        }
    }

    class Factory(
        private val connectionManager: ConnectionManager,
        private val contactId: UUID,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ContactDetailViewModel(connectionManager, contactId) as T
    }
}
