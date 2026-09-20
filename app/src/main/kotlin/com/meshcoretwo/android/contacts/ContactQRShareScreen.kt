// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.GhostButton
import com.meshcoretwo.android.ui.components.QRSharePanel
import com.meshcoretwo.android.ui.components.generateQRCodeBitmap
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.decodeHex
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.contacts.ContactService
import kotlinx.coroutines.delay

private const val QR_SIZE_PX = 600

/**
 * Shows a scannable QR code for a contact identity (name/public key/type), ported from
 * `ContactQRShareSheet.swift`. Generic over "which identity", same as Swift's sheet: reached both
 * from [ContactDetailScreen] (share an existing contact) and from [ContactsListScreen]'s "Share My
 * Contact" action (share the connected device's own identity as [ContactType.CHAT]).
 *
 * QR generation uses `com.google.zxing:core` directly (no camera/scanning involved here) — chosen
 * over Google's ML Kit because that requires Play Services (the project's no-GMS constraint); see
 * [AddContactScreen] for the scanning counterpart via `zxing-android-embedded`.
 *
 * Simplified from Swift: the "Share" action sends plain text (name + key + URI) via
 * `Intent.ACTION_SEND` rather than Swift's `ShareLink` with an attached QR image preview — Android
 * would need a `FileProvider`-backed content URI to attach an image, which is unjustified
 * complexity for a code the recipient can already reconstruct from the text/URI alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactQRShareScreen(contactName: String, publicKeyHex: String, contactTypeValue: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val publicKey = remember(publicKeyHex) { publicKeyHex.decodeHex() ?: ByteArray(0) }
    val contactType = remember(contactTypeValue) {
        ContactType.fromValue(contactTypeValue.coerceIn(0, 255).toUByte()) ?: ContactType.CHAT
    }
    val contactURI = remember(contactName, publicKey, contactType) {
        ContactService.exportContactURI(contactName, publicKey, contactType)
    }
    val qrBitmap = remember(contactURI) { generateQRCodeBitmap(contactURI, QR_SIZE_PX) }

    var showCopyFeedback by remember { mutableStateOf(false) }
    LaunchedEffect(showCopyFeedback) {
        if (showCopyFeedback) {
            delay(2000)
            showCopyFeedback = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_share_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_done)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            QRSharePanel(modifier = Modifier.fillMaxWidth()) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.contacts_qr_desc, contactName),
                        modifier = Modifier.size(200.dp),
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                Text(contactName, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.size(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.contacts_public_key), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(
                        publicKeyHex.uppercase().chunked(4).joinToString(" "),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(modifier = Modifier.size(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(
                        text = stringResource(if (showCopyFeedback) R.string.common_copied else R.string.common_copy),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            clipboardManager.setText(AnnotatedString(publicKeyHex.uppercase()))
                            showCopyFeedback = true
                        },
                    )
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val shareText = "Contact: $contactName\nKey: ${publicKeyHex.uppercase()}\n$contactURI"
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "MeshCore Contact")
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(intent, context.getString(R.string.contacts_share_title)))
                        },
                    ) { Text(stringResource(R.string.common_share)) }
                }
            }
        }
    }
}
