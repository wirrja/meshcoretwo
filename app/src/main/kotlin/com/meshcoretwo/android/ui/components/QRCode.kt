// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Renders [content] as a QR code bitmap via `com.google.zxing:core` — chosen over Google's ML Kit
 * because that requires Play Services (the project's no-GMS constraint). Shared by
 * [com.meshcoretwo.android.contacts.ContactQRShareScreen] and
 * [com.meshcoretwo.android.channels.ChannelInfoScreen]'s "Share Channel" section.
 */
fun generateQRCodeBitmap(content: String, sizePx: Int): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
    }
    bitmap
}.getOrNull()

/**
 * Wraps a QR code and its surrounding text/actions in one rounded `surfaceContainer` panel — the
 * Phase 12 mockup's deliberate exception to "grouped, not boxed": a QR code is a single object to
 * hand someone, not a list to scan, so it keeps a card treatment the rest of the app dropped. Shared
 * by [com.meshcoretwo.android.contacts.ContactQRShareScreen] and
 * [com.meshcoretwo.android.channels.ChannelInfoScreen]'s "Share Channel" section.
 */
@Composable
fun QRSharePanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
