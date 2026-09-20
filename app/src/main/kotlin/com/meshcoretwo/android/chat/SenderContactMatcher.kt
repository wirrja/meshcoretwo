// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import com.meshcoretwo.services.persistence.ContactDto

/**
 * Resolves a channel sender's display name to stored contacts. Ported from
 * `SenderContactMatcher.swift` — channel messages carry a human-readable sender name rather than a
 * stable id, so [BlockSenderSheet]/[SendDMSheet] match against the local contact list by
 * case-insensitive name.
 */
internal object SenderContactMatcher {
    /** Contacts whose [ContactDto.name] matches [senderName] case-insensitively, already-blocked ones dropped when [excludeBlocked]. */
    fun filter(contacts: List<ContactDto>, senderName: String, excludeBlocked: Boolean = false): List<ContactDto> =
        contacts.filter { (!excludeBlocked || !it.isBlocked) && it.name.equals(senderName, ignoreCase = true) }
}
