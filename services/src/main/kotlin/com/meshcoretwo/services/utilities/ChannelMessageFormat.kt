// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

/**
 * Parses the `"NodeName: MessageText"` format the firmware prepends to a channel message before
 * encryption. Ported from `ChannelMessageFormat.swift`.
 *
 * Distinct from [com.meshcoretwo.services.messages.IncomingMessageService.parseChannelMessage]
 * (`SyncCoordinator.parseChannelMessage` on the Swift side) despite the similar shape: that one
 * trims the sender name and falls back to `(null, text)` verbatim when there's no colon, because
 * it must always produce *some* displayable body for an incoming message. This one is stricter —
 * used only to recognize a sent message's own echo — and returns `null` outright when there's no
 * sender to key off (no colon, or an empty one), matching Swift's `guard let colonIndex = ...,
 * colonIndex != text.startIndex else { return nil }`.
 */
object ChannelMessageFormat {
    /**
     * Returns `(senderName, messageText)`, or `null` if [text] has no non-empty sender prefix
     * before a colon. The sender name is trimmed so it matches what
     * [com.meshcoretwo.services.messages.IncomingMessageService.parseChannelMessage] hashed into
     * the message's `deduplicationKey` — [ChannelRXCorrelation] joins on that key.
     */
    fun parse(text: String): Pair<String, String>? {
        val colonIndex = text.indexOf(':')
        if (colonIndex <= 0) return null
        val senderName = text.substring(0, colonIndex).trim()
        val messageText = text.substring(colonIndex + 1).trim()
        return senderName to messageText
    }
}
