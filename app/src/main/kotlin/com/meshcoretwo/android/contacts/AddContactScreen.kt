// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.meshcoretwo.android.R
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.protocol.decodeHex
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.contacts.ContactService
import com.meshcoretwo.services.contacts.ContactServiceError
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val PUBLIC_KEY_HEX_LENGTH = PacketBuilder.PUBLIC_KEY_SIZE * 2

/**
 * "Add Contact" — manual entry, paste-a-link, or scan-a-QR, ported from `AddContactSheet.swift` +
 * `ScanContactQRView.swift` folded into one screen: `zxing-android-embedded`'s `ScanContract`
 * launches its own built-in camera/permission/scan-frame `CaptureActivity` (matching what Swift's
 * separate `ScanContactQRView` hand-rolls with `VisionKit`), so no dedicated scanner screen is
 * needed here — a successful scan just fills this screen's fields and immediately submits, same
 * as Swift's auto-import-on-scan. "Paste URL" instead fills the fields for review, matching
 * Swift's `PasteURLSection` (paste is not auto-submitted).
 *
 * [prefilledLink] pre-fills the fields from either a `meshcore://contact/add` URI or a
 * `<publicKeyHex:type:name>` share token handed in by the caller — the latter is how a tapped
 * contact-share token in a chat message arrives (see `ChatConversationScreen`'s
 * `onOpenContactShareLink` and `MessageLinkTokenizer`'s doc on why it can't build a URI itself),
 * reusing this screen instead of porting Swift's separate `AddContactConfirmationSheet`. Unlike
 * that sheet, this doesn't check whether the contact already exists or whether the key is the
 * user's own device identity before showing the form — tapping "Add" on either just calls the
 * same [ContactService.addOrUpdateContact] any other add here does, a harmless no-op-ish update
 * rather than a hard error; a conscious simplification for this slice, see PLAN.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(connectionManager: ConnectionManager, prefilledLink: String? = null, onBack: () -> Unit) {
    val viewModel: AddContactViewModel = viewModel(factory = AddContactViewModel.Factory(connectionManager))
    val isSubmitting by viewModel.isSubmitting.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val prefilled = remember(prefilledLink) {
        prefilledLink?.let { ContactService.parseContactURI(it) ?: ContactService.parseContactShareToken(it) }
    }
    var name by remember { mutableStateOf(prefilled?.name.orEmpty()) }
    var publicKeyHexInput by remember { mutableStateOf(prefilled?.publicKey?.hexString.orEmpty()) }
    var selectedType by remember { mutableStateOf(prefilled?.contactType ?: ContactType.CHAT) }
    var pasteError by remember { mutableStateOf(false) }

    val normalizedHex = remember(publicKeyHexInput) { publicKeyHexInput.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.lowercase() }
    val isValidKey = normalizedHex.length == PUBLIC_KEY_HEX_LENGTH
    val canAdd = name.isNotBlank() && isValidKey && !isSubmitting

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents ?: return@rememberLauncherForActivityResult
        val parsed = ContactService.parseContactURI(scanned)
        if (parsed == null) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.contacts_qr_invalid)) }
            return@rememberLauncherForActivityResult
        }
        name = parsed.name
        publicKeyHexInput = parsed.publicKey.hexString
        selectedType = parsed.contactType
        scope.launch {
            if (viewModel.add(parsed.name, parsed.publicKey, parsed.contactType)) onBack()
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it.resolve(context))
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.contacts_add_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_cancel)) }
                },
                actions = {
                    TextButton(
                        enabled = canAdd,
                        onClick = {
                            val publicKey = normalizedHex.decodeHexOrEmpty()
                            scope.launch {
                                if (viewModel.add(name, publicKey, selectedType)) onBack()
                            }
                        },
                    ) { Text(stringResource(R.string.common_add)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(onClick = { scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false)) }, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_camera), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.add_channel_scan_qr))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContactType.entries.forEach { type ->
                    FilterChip(selected = type == selectedType, onClick = { selectedType = type }, label = { Text(type.label()) })
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.contacts_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = publicKeyHexInput,
                onValueChange = { publicKeyHexInput = it },
                label = { Text(stringResource(R.string.contacts_public_key_label, PUBLIC_KEY_HEX_LENGTH)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                supportingText = {
                    if (publicKeyHexInput.isNotEmpty() && !isValidKey) {
                        Text(stringResource(R.string.contacts_key_progress, normalizedHex.length, PUBLIC_KEY_HEX_LENGTH))
                    }
                },
                isError = publicKeyHexInput.isNotEmpty() && !isValidKey,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedButton(
                onClick = {
                    val clipboardText = clipboardManager.getText()?.text
                    val parsed = clipboardText?.let { ContactService.parseContactURI(it) }
                    if (parsed == null) {
                        pasteError = true
                    } else {
                        pasteError = false
                        name = parsed.name
                        publicKeyHexInput = parsed.publicKey.hexString
                        selectedType = parsed.contactType
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.contacts_paste_clipboard))
            }
            if (pasteError) {
                Text(stringResource(R.string.contacts_clipboard_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (isSubmitting) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

private fun String.decodeHexOrEmpty(): ByteArray = decodeHex() ?: ByteArray(0)

/**
 * Backs [AddContactScreen]. Kept separate from other Contacts view models since Compose
 * Navigation gives this route its own instance — same reasoning as [com.meshcoretwo.android.settings.LocationPickerViewModel].
 */
class AddContactViewModel(private val connectionManager: ConnectionManager) : ViewModel() {
    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting

    private val _errorMessage = MutableStateFlow<UiText?>(null)
    val errorMessage: StateFlow<UiText?> = _errorMessage

    fun clearError() {
        _errorMessage.value = null
    }

    /** Adds a manually entered or scanned contact. Ported from `AddContactSheet.handleAdd`/`ScanContactQRView.importContact`. */
    suspend fun add(name: String, publicKey: ByteArray, type: ContactType): Boolean {
        val radioID = connectionManager.lastConnectedRadioID
        val contactService = connectionManager.contactService
        if (radioID == null || contactService == null) {
            _errorMessage.value = UiText.of(R.string.preset_not_connected)
            return false
        }
        _isSubmitting.value = true
        try {
            val contact = MeshContact(
                id = publicKey.hexString,
                publicKey = publicKey,
                type = type,
                flags = ContactFlags.NONE,
                outPathLength = PacketBuilder.FLOOD_PATH_SENTINEL,
                outPath = ByteArray(0),
                advertisedName = name,
                lastAdvertisement = Instant.EPOCH,
                latitude = 0.0,
                longitude = 0.0,
                lastModified = Instant.now(),
            )
            contactService.addOrUpdateContact(radioID, contact)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ContactServiceError.ContactTableFull) {
            val maxContacts = connectionManager.connectedDeviceRecord?.maxContacts
            _errorMessage.value = if (maxContacts != null) UiText.of(R.string.contacts_err_list_full_max, maxContacts) else UiText.of(R.string.contacts_err_list_full)
            return false
        } catch (e: Exception) {
            _errorMessage.value = e.toUiText(UiText.of(R.string.contacts_err_add_failed))
            return false
        } finally {
            _isSubmitting.value = false
        }
    }

    class Factory(private val connectionManager: ConnectionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AddContactViewModel(connectionManager) as T
    }
}

@Composable
private fun ContactType.label(): String = stringResource(
    when (this) {
        ContactType.CHAT -> R.string.contacts_type_chat
        ContactType.REPEATER -> R.string.map_type_repeater
        ContactType.ROOM -> R.string.common_room
    },
)
