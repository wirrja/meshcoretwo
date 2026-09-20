// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.contacts.ContactRow
import com.meshcoretwo.services.persistence.ContactDto

/** How [ContactMatchRow] presents selection — ported from `ContactMatchRow.SelectionStyle`. */
internal sealed class ContactMatchStyle {
    data class Toggle(val isSelected: Boolean) : ContactMatchStyle()
    data object Tap : ContactMatchStyle()
}

/**
 * A contact matched by a channel sender's name — [BlockSenderSheet]'s multi-select list and
 * [SendDMSheet]'s tap-to-pick list. Ported from `ContactMatchRow.swift`, but reuses
 * [com.meshcoretwo.android.contacts.ContactRow] (the same row [com.meshcoretwo.android.contacts
 * .ContactsListScreen] and [com.meshcoretwo.android.settings.BlockedChannelSendersScreen] already
 * reuse, for name/avatar/route/location) instead of hand-rolling a second one — Swift's version
 * hand-rolls its own layout because there's no shared row to reuse from in the first place. No
 * user-location distance readout here (unlike Swift's version): `ChatConversationScreen` has no
 * [com.meshcoretwo.services.location.LocationProvider] threaded in for what's otherwise a
 * peripheral distance display on a sender-matching sheet.
 */
@Composable
internal fun ContactMatchRow(contact: ContactDto, style: ContactMatchStyle, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (style is ContactMatchStyle.Toggle) {
            Checkbox(checked = style.isSelected, onCheckedChange = { onClick() }, modifier = Modifier.padding(start = 4.dp))
        }
        Box(modifier = Modifier.weight(1f)) {
            ContactRow(contact = contact, userLocation = null, onClick = onClick)
        }
        if (style is ContactMatchStyle.Tap) {
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.padding(end = 16.dp).size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
