// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.connection.discoveredNodeStore
import com.meshcoretwo.services.connection.remoteNodeService
import com.meshcoretwo.services.connection.repeaterAdminService
import com.meshcoretwo.services.connection.roomServerService
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.persistence.RemoteNodeRole
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class NodeAuthOutcome {
    data class Success(val session: RemoteNodeSessionDto) : NodeAuthOutcome()
    data class Failed(val message: UiText) : NodeAuthOutcome()
}

/**
 * Backs the repeater/room login screen (`NodeAuthScreen.kt`) — a port of
 * `NodeAuthenticationSheet.swift`. Ported: password entry, remember-password (including deleting
 * a saved password when the box is unchecked), login via
 * [com.meshcoretwo.services.remotenode.RoomServerService.joinRoom]/
 * [com.meshcoretwo.services.remotenode.RepeaterAdminService.connectAsAdmin], the flood-routing
 * toggle with its automatic flood retry after a timeout over the stored route (the route choice
 * lives in [NodeLoginRouting]), and the "Up to N seconds remaining" countdown
 * ([authSecondsRemaining]).
 *
 * Not ported: the VoiceOver announcements Swift posts as the countdown crosses 30/15/10 seconds
 * and on every tick of the last five. The screen shows the countdown as plain text; a TalkBack
 * equivalent would be a live region with the same thresholds.
 *
 * The read-only route display *is* ported ([routeContacts]/[routeDiscoveredNodes], loaded by
 * [loadRouteSources] and rendered via `NodeRoutePathSection` — mirrors `NodeAuthPathViewModel.load`,
 * minus the view model's own indirection: this class already owns [contact] and
 * [ConnectionManager], so there's no separate object to hold the three lines of derived state).
 *
 * [authenticate] hands back the live [RemoteNodeSessionDto] so the caller can navigate onward: a
 * room session continues into [RoomStatusScreen] (`MainScreen.kt`'s `MainRoute.ROOM_STATUS`); a
 * repeater session continues into [RepeaterStatusScreen] (`MainRoute.REPEATER_STATUS`), both since
 * the "RepeaterStatusScreen" slice.
 */
class NodeAuthViewModel(
    private val connectionManager: ConnectionManager,
    private val contactId: UUID,
) : ViewModel() {
    private val _contact = MutableStateFlow<ContactDto?>(null)
    val contact: StateFlow<ContactDto?> = _contact.asStateFlow()

    private val _savedPassword = MutableStateFlow<String?>(null)
    val savedPassword: StateFlow<String?> = _savedPassword.asStateFlow()

    private val _isAuthenticating = MutableStateFlow(false)
    val isAuthenticating: StateFlow<Boolean> = _isAuthenticating.asStateFlow()

    /** Starts out as the contact's own routing, like Swift's `useFloodRouting` initial value; only switchable when [contact] has a stored path. */
    private val _useFloodRouting = MutableStateFlow(false)
    val useFloodRouting: StateFlow<Boolean> = _useFloodRouting.asStateFlow()

    /** True while the automatic flood retry runs after the stored route timed out. */
    private val _isRetryingViaFlood = MutableStateFlow(false)
    val isRetryingViaFlood: StateFlow<Boolean> = _isRetryingViaFlood.asStateFlow()

    /** Seconds until the pending login attempt times out; `null` until firmware reports its timeout, and when no login is pending. */
    private val _authSecondsRemaining = MutableStateFlow<Int?>(null)
    val authSecondsRemaining: StateFlow<Int?> = _authSecondsRemaining.asStateFlow()

    /** Fed to `NodeRoutePathSection`, populated only when [contact] actually has a stored path — see [loadRouteSources]. */
    private val _routeContacts = MutableStateFlow<List<ContactDto>>(emptyList())
    val routeContacts: StateFlow<List<ContactDto>> = _routeContacts.asStateFlow()

    private val _routeDiscoveredNodes = MutableStateFlow<List<DiscoveredNodeDto>>(emptyList())
    val routeDiscoveredNodes: StateFlow<List<DiscoveredNodeDto>> = _routeDiscoveredNodes.asStateFlow()

    private val routing = NodeLoginRouting()
    private var countdownJob: Job? = null

    init {
        viewModelScope.launch {
            val loaded = connectionManager.contactService?.getContactById(contactId) ?: return@launch
            _contact.value = loaded
            _useFloodRouting.value = loaded.isFloodRouted
            _savedPassword.value = connectionManager.remoteNodeService?.retrievePassword(loaded)
            loadRouteSources(loaded)
        }
    }

    /**
     * Loads the contact/discovered-node lists `NodeRoutePathSection` needs to resolve [contact]'s
     * stored path hops to repeater names. Skipped when there's nothing to resolve (flood-routed, or
     * a stored zero-hop direct route) — matches Swift's `NodeAuthPathViewModel`-triggering `.task`
     * guard, avoiding two avoidable Room queries on the common case of a fresh/direct contact.
     */
    private suspend fun loadRouteSources(contact: ContactDto) {
        if (contact.isFloodRouted || contact.pathHopCount == 0) return
        val radioID = connectionManager.lastConnectedRadioID ?: return
        _routeContacts.value = connectionManager.contactService?.getContacts(radioID) ?: emptyList()
        _routeDiscoveredNodes.value = connectionManager.discoveredNodeStore?.fetchDiscoveredNodes(radioID) ?: emptyList()
    }

    fun setUseFloodRouting(enabled: Boolean) {
        _useFloodRouting.value = enabled
    }

    fun authenticate(password: String, rememberPassword: Boolean, onResult: (NodeAuthOutcome) -> Unit) {
        viewModelScope.launch {
            _isAuthenticating.value = true
            _isRetryingViaFlood.value = false
            stopCountdown()
            val outcome = try {
                performAuthenticate(password, rememberPassword)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NodeAuthOutcome.Failed(error.toUiText(UiText.of(R.string.node_auth_err_connect)))
            }
            _isAuthenticating.value = false
            _isRetryingViaFlood.value = false
            stopCountdown()
            onResult(outcome)
        }
    }

    private suspend fun performAuthenticate(password: String, rememberPassword: Boolean): NodeAuthOutcome {
        val contact = _contact.value ?: return NodeAuthOutcome.Failed(UiText.of(R.string.add_channel_err_not_connected))
        val radioID = connectionManager.lastConnectedRadioID ?: return NodeAuthOutcome.Failed(UiText.of(R.string.add_channel_err_not_connected))
        val contactService = connectionManager.contactService ?: return NodeAuthOutcome.Failed(UiText.of(R.string.add_channel_err_not_connected))
        val role = RemoteNodeRole.fromContactType(contact.type)
            ?: return NodeAuthOutcome.Failed(UiText.of(R.string.node_auth_err_not_node))
        val trimmedPassword = truncatePassword(password)

        val login: suspend (UByte) -> RemoteNodeSessionDto = when (role) {
            RemoteNodeRole.ROOM_SERVER -> {
                val roomServerService = connectionManager.roomServerService ?: return NodeAuthOutcome.Failed(UiText.of(R.string.add_channel_err_not_connected))
                val joinRoom: suspend (UByte) -> RemoteNodeSessionDto = { pathLength ->
                    roomServerService.joinRoom(radioID, contact, trimmedPassword, rememberPassword, pathLength, onTimeoutKnown)
                }
                joinRoom
            }
            RemoteNodeRole.REPEATER -> {
                val repeaterAdminService = connectionManager.repeaterAdminService ?: return NodeAuthOutcome.Failed(UiText.of(R.string.add_channel_err_not_connected))
                val connectAsAdmin: suspend (UByte) -> RemoteNodeSessionDto = { pathLength ->
                    repeaterAdminService.connectAsAdmin(radioID, contact, trimmedPassword, rememberPassword, pathLength, onTimeoutKnown)
                }
                connectAsAdmin
            }
        }

        val session = routing.login(
            contact = contact,
            useFloodRouting = _useFloodRouting.value,
            resetPath = { contactService.resetPath(radioID, contact.publicKey) },
            onFloodRetry = {
                _isRetryingViaFlood.value = true
                stopCountdown()
            },
            performLogin = login,
        )

        if (_savedPassword.value != null && !rememberPassword) {
            try {
                connectionManager.remoteNodeService?.deletePassword(contact)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Swift logs and still completes the login; the saved password just stays behind.
            }
        }
        return NodeAuthOutcome.Success(session)
    }

    /**
     * Called by the service from its own coroutine once firmware reports the login timeout. Hops
     * onto [viewModelScope] rather than switching context, so nothing here can throw back into the
     * service's retransmit loop.
     */
    private val onTimeoutKnown: suspend (Int) -> Unit = { timeoutSeconds ->
        viewModelScope.launch { startCountdown(timeoutSeconds) }
    }

    /** Ported from `startCountdownTask()`: recomputed from elapsed time each tick, so a late tick can't drift the count. */
    private fun startCountdown(timeoutSeconds: Int) {
        countdownJob?.cancel()
        val startNanos = System.nanoTime()
        _authSecondsRemaining.value = timeoutSeconds
        countdownJob = viewModelScope.launch {
            while (true) {
                delay(COUNTDOWN_TICK_MS)
                val elapsedSeconds = ((System.nanoTime() - startNanos) / 1_000_000_000L).toInt()
                val remaining = maxOf(0, timeoutSeconds - elapsedSeconds)
                _authSecondsRemaining.value = remaining
                if (remaining == 0) break
            }
        }
    }

    private fun stopCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _authSecondsRemaining.value = null
    }

    class Factory(
        private val connectionManager: ConnectionManager,
        private val contactId: UUID,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NodeAuthViewModel(connectionManager, contactId) as T
    }

    companion object {
        /** MeshCore repeaters and rooms only support 15-character passwords — matches `NodeAuthenticationSheet.swift`. */
        const val MAX_PASSWORD_LENGTH = 15

        private const val COUNTDOWN_TICK_MS = 1_000L

        fun truncatePassword(password: String): String =
            if (password.length > MAX_PASSWORD_LENGTH) password.take(MAX_PASSWORD_LENGTH) else password
    }
}
