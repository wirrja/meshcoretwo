// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.channels

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.SettingsListRow
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.utilities.HashtagUtilities

private enum class AddChannelMode(@StringRes val title: Int, @StringRes val subtitle: Int) {
    PUBLIC(R.string.add_channel_public_title, R.string.add_channel_public_desc),
    HASHTAG(R.string.add_channel_hashtag_title, R.string.add_channel_hashtag_desc),
    CREATE_PRIVATE(R.string.add_channel_create_title, R.string.add_channel_create_desc),
    JOIN_PRIVATE(R.string.add_channel_join_private_title, R.string.add_channel_join_private_desc),
    JOIN_LINK(R.string.add_channel_link_title, R.string.add_channel_link_desc),
}

/**
 * Add/join-channel screen. Ported from iOS's `ChannelOptionsSheet` launcher menu — see
 * [AddChannelViewModel]'s class doc for the full mapping. "Scan QR Code" (ported from
 * `ScanChannelQRView.swift`) launches `zxing-android-embedded`'s built-in `CaptureActivity` via
 * `ScanContract` and, on a successful decode, submits straight through [AddChannelViewModel.joinViaLink]
 * — same auto-join-on-scan behavior as [com.meshcoretwo.android.contacts.AddContactScreen]'s QR
 * scan, and the same reasoning for skipping Swift's confirmation/slot-picker screen: this port has
 * no manual channel-slot picker anywhere (see [AddChannelViewModel]'s "Free-slot selection" note),
 * so there is nothing left for that screen to ask the user to confirm.
 *
 * [initialLink], when non-null, opens straight into [AddChannelMode.JOIN_LINK] with the link
 * pre-filled — used when `MainActivity` hands this screen a `meshcore://channel/add` link the OS
 * delivered from outside the app (`MainScreen`'s deep-link `LaunchedEffect`, Android's counterpart
 * to `ChatLinkRouter.routeExternalOpen`). The user still taps "Join" explicitly, same as the
 * manual-paste path — this isn't the richer preview iOS's `JoinChannelConfirmationSheet` shows
 * before joining, since that sheet has no camera/QR-free equivalent need here (this reuses the
 * screen's own error text, e.g. "Not connected", for the failure cases it would otherwise show).
 *
 * [initialHashtag], when non-null (and [initialLink] is null), opens straight into
 * [AddChannelMode.HASHTAG] with the name pre-filled instead — the "Message text linkify" slice's
 * landing spot for a tapped `#hashtag` in a message with no locally-joined match yet (see
 * `ChatConversationScreen`'s `onJoinHashtag`, Android's counterpart to `ChatLinkRouter`'s
 * `handleHashtagTap` "not joined" branch — the "already joined" branch navigates straight to the
 * existing conversation instead, without visiting this screen at all).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddChannelScreen(
    connectionManager: ConnectionManager,
    initialLink: String? = null,
    initialHashtag: String? = null,
    onJoined: (UByte) -> Unit,
    onCancel: () -> Unit,
) {
    val viewModel: AddChannelViewModel = viewModel(factory = AddChannelViewModel.Factory(connectionManager))
    val isSubmitting by viewModel.isSubmitting.collectAsStateWithLifecycle()
    var mode by remember {
        mutableStateOf(
            when {
                initialLink != null -> AddChannelMode.JOIN_LINK
                initialHashtag != null -> AddChannelMode.HASHTAG
                else -> null
            },
        )
    }
    var error by remember { mutableStateOf<UiText?>(null) }

    fun handle(outcome: AddChannelOutcome) {
        when (outcome) {
            is AddChannelOutcome.Joined -> onJoined(outcome.index)
            is AddChannelOutcome.Failed -> error = outcome.message
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents ?: return@rememberLauncherForActivityResult
        error = null
        viewModel.joinViaLink(scanned, ::handle)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(mode?.title ?: R.string.add_channel_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (mode == null) onCancel() else { mode = null; error = null } }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            error?.let {
                Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.size(12.dp))
            }
            when (mode) {
                null -> {
                    OutlinedButton(
                        onClick = { scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(painterResource(R.drawable.ic_camera), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.add_channel_scan_qr))
                    }
                    Spacer(modifier = Modifier.size(12.dp))
                    ModeMenu(onSelect = { mode = it; error = null })
                }
                AddChannelMode.PUBLIC -> PublicForm(isSubmitting) { viewModel.joinPublic(::handle) }
                AddChannelMode.HASHTAG -> HashtagForm(isSubmitting, initialValue = initialHashtag.orEmpty()) { name -> viewModel.joinHashtag(name, ::handle) }
                AddChannelMode.CREATE_PRIVATE -> NameForm(isSubmitting, stringResource(R.string.add_channel_name_label), stringResource(R.string.common_create)) { name -> viewModel.createPrivate(name, ::handle) }
                AddChannelMode.JOIN_PRIVATE -> JoinPrivateForm(isSubmitting) { name, secret -> viewModel.joinPrivate(name, secret, ::handle) }
                AddChannelMode.JOIN_LINK -> NameForm(isSubmitting, stringResource(R.string.add_channel_invite_link), stringResource(R.string.common_join), initialValue = initialLink.orEmpty()) { link -> viewModel.joinViaLink(link, ::handle) }
            }
        }
    }
}

@Composable
private fun ModeMenu(onSelect: (AddChannelMode) -> Unit) {
    Column {
        AddChannelMode.entries.forEachIndexed { index, mode ->
            SettingsListRow(title = stringResource(mode.title), value = stringResource(mode.subtitle), singleLineValue = false, onClick = { onSelect(mode) })
            if (index != AddChannelMode.entries.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun PublicForm(isSubmitting: Boolean, onSubmit: () -> Unit) {
    Column {
        Text(
            stringResource(R.string.add_channel_public_warning),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.size(16.dp))
        SubmitButton(isSubmitting, stringResource(R.string.add_channel_join_public_button), onClick = onSubmit)
    }
}

@Composable
private fun HashtagForm(isSubmitting: Boolean, initialValue: String = "", onSubmit: (String) -> Unit) {
    var name by remember { mutableStateOf(HashtagUtilities.sanitizeHashtagNameInput(initialValue)) }
    Column {
        OutlinedTextField(
            value = name,
            onValueChange = { name = HashtagUtilities.sanitizeHashtagNameInput(it) },
            label = { Text(stringResource(R.string.add_channel_hashtag_label)) },
            placeholder = { Text("general") },
            prefix = { Text("#") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            stringResource(R.string.add_channel_hashtag_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(16.dp))
        SubmitButton(isSubmitting, stringResource(R.string.common_join), enabled = name.isNotBlank()) { onSubmit(name) }
    }
}

@Composable
private fun NameForm(isSubmitting: Boolean, label: String, submitLabel: String, initialValue: String = "", onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf(initialValue) }
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.size(16.dp))
        SubmitButton(isSubmitting, submitLabel, enabled = value.isNotBlank()) { onSubmit(value) }
    }
}

@Composable
private fun JoinPrivateForm(isSubmitting: Boolean, onSubmit: (name: String, secretHex: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.add_channel_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.size(12.dp))
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text(stringResource(R.string.add_channel_secret_label)) },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.size(16.dp))
        SubmitButton(isSubmitting, stringResource(R.string.common_join), enabled = name.isNotBlank() && secret.isNotBlank()) { onSubmit(name, secret) }
    }
}

@Composable
private fun SubmitButton(isSubmitting: Boolean, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !isSubmitting, modifier = Modifier.fillMaxWidth()) {
        if (isSubmitting) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(label)
        }
    }
}
