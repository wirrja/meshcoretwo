// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.ui.i18n.UiText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import java.util.UUID

/**
 * Repeater/room login — a port of `NodeAuthenticationSheet.swift`. See [NodeAuthViewModel]'s
 * class doc for what's ported; the route display and flood-routing toggle share one card via
 * [NodeRoutePathSection].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeAuthScreen(
    connectionManager: ConnectionManager,
    contactId: UUID,
    onBack: () -> Unit,
    onAuthenticated: (RemoteNodeSessionDto) -> Unit,
) {
    val viewModel: NodeAuthViewModel = viewModel(factory = NodeAuthViewModel.Factory(connectionManager, contactId))
    val contact by viewModel.contact.collectAsStateWithLifecycle()
    val savedPassword by viewModel.savedPassword.collectAsStateWithLifecycle()
    val isAuthenticating by viewModel.isAuthenticating.collectAsStateWithLifecycle()
    val useFloodRouting by viewModel.useFloodRouting.collectAsStateWithLifecycle()
    val isRetryingViaFlood by viewModel.isRetryingViaFlood.collectAsStateWithLifecycle()
    val authSecondsRemaining by viewModel.authSecondsRemaining.collectAsStateWithLifecycle()
    val routeContacts by viewModel.routeContacts.collectAsStateWithLifecycle()
    val routeDiscoveredNodes by viewModel.routeDiscoveredNodes.collectAsStateWithLifecycle()

    var password by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<UiText?>(null) }

    LaunchedEffect(savedPassword) {
        savedPassword?.let { password = it }
    }

    val isRoom = contact?.type == ContactType.ROOM
    val title = stringResource(if (isRoom) R.string.contacts_join_room else R.string.contacts_admin_access)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            contact?.let {
                Text(it.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (isRoom) R.string.contacts_type_room_server else R.string.map_type_repeater),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.size(16.dp))
            }

            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                label = { Text(stringResource(R.string.node_auth_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rememberPassword, onCheckedChange = { rememberPassword = it })
                Text(stringResource(R.string.node_auth_remember))
            }
            AuthenticationStatus(
                errorMessage = errorMessage,
                isPasswordTooLong = password.length > NodeAuthViewModel.MAX_PASSWORD_LENGTH,
                isRoom = isRoom,
                isRetryingViaFlood = isRetryingViaFlood,
                authSecondsRemaining = authSecondsRemaining,
            )

            contact?.let {
                Spacer(modifier = Modifier.size(16.dp))
                NodeRoutePathSection(
                    contact = it,
                    contacts = routeContacts,
                    discoveredNodes = routeDiscoveredNodes,
                    userLocation = null,
                    showStoredRoute = !useFloodRouting,
                ) {
                    FloodRoutingToggle(
                        hasStoredPath = !it.isFloodRouted,
                        useFloodRouting = useFloodRouting,
                        onToggle = viewModel::setUseFloodRouting,
                    )
                }
            }

            Spacer(modifier = Modifier.size(16.dp))
            Button(
                onClick = {
                    viewModel.authenticate(password, rememberPassword) { outcome ->
                        when (outcome) {
                            is NodeAuthOutcome.Success -> onAuthenticated(outcome.session)
                            is NodeAuthOutcome.Failed -> errorMessage = outcome.message
                        }
                    }
                },
                enabled = !isAuthenticating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isAuthenticating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(if (isRoom) R.string.contacts_join_room else R.string.common_connect))
                }
            }
        }
    }
}

/**
 * The text under the password field: an error, the password-length warning, or login progress, in
 * that priority. Ported from `AuthenticationSection`'s footer.
 */
@Composable
private fun AuthenticationStatus(
    errorMessage: UiText?,
    isPasswordTooLong: Boolean,
    isRoom: Boolean,
    isRetryingViaFlood: Boolean,
    authSecondsRemaining: Int?,
) {
    if (errorMessage != null) {
        Text(errorMessage.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val secondsRemaining = authSecondsRemaining?.takeIf { it > 0 }?.let { stringResource(R.string.node_auth_seconds_remaining, it) }
    val lines = when {
        isPasswordTooLong -> listOf(
            stringResource(if (isRoom) R.string.node_auth_pw_limit_room else R.string.node_auth_pw_limit_repeater, NodeAuthViewModel.MAX_PASSWORD_LENGTH),
        )
        isRetryingViaFlood -> listOfNotNull(stringResource(R.string.node_auth_retry_flood), secondsRemaining)
        else -> listOfNotNull(secondsRemaining)
    }
    lines.forEach {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The flood-routing switch and its explanation, inside [NodeRoutePathSection]'s group. Ported from
 * `PathSection`'s toggle and footer. Disabled when the contact has no stored route: the login
 * floods anyway.
 */
@Composable
private fun FloodRoutingToggle(hasStoredPath: Boolean, useFloodRouting: Boolean, onToggle: (Boolean) -> Unit) {
    Spacer(modifier = Modifier.size(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = useFloodRouting, enabled = hasStoredPath, role = Role.Switch, onValueChange = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.node_auth_flood_routing), modifier = Modifier.weight(1f))
        Switch(checked = useFloodRouting, onCheckedChange = null, enabled = hasStoredPath)
    }
    Text(
        when {
            !hasStoredPath -> stringResource(R.string.node_auth_no_route)
            useFloodRouting -> stringResource(R.string.node_auth_flood_hint)
            else -> stringResource(R.string.node_auth_route_hint)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
