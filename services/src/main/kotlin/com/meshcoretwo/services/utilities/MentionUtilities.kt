// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.persistence.ContactDto

/**
 * Utilities for working with MeshCore's mention format `@[nodeContactName]`. Ported from
 * `MentionUtilities.swift`. [extractMentions]/[containsSelfMention] back the mention-count backfill
 * slice; [createMention]/[detectActiveMention]/[filterContacts] back the chat composer's @mention
 * autocomplete (`ChatConversationScreen.kt`'s `ChatInputBar`); [appendMention]/[buildReplyText]
 * back the message-actions menu's "Reply" (PLAN.md's "Message Actions" epic, sub-slice 3) — see
 * [com.meshcoretwo.android.chat.MessageActionsSheet]'s doc for why only [appendMention] is
 * actually wired: this port has no `ChatSettingsView`-equivalent "Reply with Quote" toggle yet
 * (Swift's `AppStorageKey.replyWithQuote`, default `false`), so [buildReplyText]'s quoted-reply
 * mode stays a tested-but-unreachable function until that setting exists.
 */
object MentionUtilities {
    /** Matches Swift's `mentionPattern`: `@[name]`, capturing `name`. Public so [com.meshcoretwo.android.chat.linkify.MessageLinkTokenizer] can detect the same spans it renders. */
    val mentionRegex = Regex("""@\[([^\]]+)]""")

    /** Extracts every mentioned contact name (without the `@[]` wrapper) from [text]. Ported from `extractMentions(from:)`. */
    fun extractMentions(text: String): List<String> = mentionRegex.findAll(text).map { it.groupValues[1] }.toList()

    /**
     * Whether [text] contains a mention of [selfName] (case-insensitive). Ported from
     * `containsSelfMention(in:selfName:)`.
     */
    fun containsSelfMention(text: String, selfName: String): Boolean =
        extractMentions(text).any { it.equals(selfName, ignoreCase = true) }

    /** Formats [name] (a contact's mesh name, not its local nickname) as a mention token. Ported from `createMention(for:)`. */
    fun createMention(name: String): String = "@[$name]"

    /**
     * Detects an active mention query from composer input text — the search query (text after `@`)
     * if the user is typing a mention, `null` otherwise. Triggers when `@` is at the start of [text]
     * or after whitespace; a standalone `@` returns the empty string (show every contact). Ported
     * from `detectActiveMention(in:)`.
     */
    fun detectActiveMention(text: String): String? {
        if (text.isEmpty()) return null

        var searchEnd = text.length
        while (true) {
            val atIndex = text.lastIndexOf('@', searchEnd - 1)
            if (atIndex < 0) return null

            val isAtStart = atIndex == 0
            val isAfterWhitespace = !isAtStart && text[atIndex - 1].isWhitespace()
            if (!isAtStart && !isAfterWhitespace) {
                // `@` is mid-word (like email@) — try an earlier `@`.
                searchEnd = atIndex
                continue
            }

            val afterAt = text.substring(atIndex + 1)
            if (afterAt.isEmpty()) return ""
            val firstChar = afterAt.first()
            if (firstChar.isWhitespace() || firstChar == '@') return null

            if (afterAt.startsWith("[")) {
                val closeBracket = afterAt.indexOf(']')
                if (closeBracket < 0) return null // Unclosed bracket: a manual mention in progress, no suggestions.
                if (closeBracket + 1 < afterAt.length) {
                    // Completed mention with more text after it — keep searching for another `@`.
                    searchEnd = atIndex
                    continue
                }
                return null
            }

            return afterAt.takeWhile { !it.isWhitespace() }
        }
    }

    /**
     * Filters [contacts] to chat-type contacts matching [query] (case-insensitive substring of
     * [ContactDto.displayName]), sorted alphabetically. Ported from `filterContacts(_:query:senderOrder:)`,
     * trimmed to drop its `senderOrder` recency parameter — this port has no channel-senders index
     * yet (see `ChatConversationScreen.kt`'s doc), so every suggestion list sorts alphabetically,
     * matching Swift's own DM-conversation branch.
     */
    fun filterContacts(contacts: List<ContactDto>, query: String): List<ContactDto> = contacts
        .filter { it.type == ContactType.CHAT }
        .filter { query.isEmpty() || it.displayName.contains(query, ignoreCase = true) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })

    /**
     * Appends a mention of [name] to [draft], adding a separating space first only if [draft] is
     * non-empty and doesn't already end in whitespace, and always trailing the mention with a
     * space so typing continues naturally. Ported from `appendMention(for:to:)`.
     */
    fun appendMention(name: String, draft: String): String {
        val mention = createMention(name)
        if (draft.isEmpty()) return "$mention "
        val separator = if (draft.last().isWhitespace()) "" else " "
        return "$draft$separator$mention "
    }

    /** Strips a single leading `@[name]` mention (and any whitespace right after it) from the start of a string. Ported from `buildReplyText`'s private `leadingMentionRegex`. */
    private val leadingMentionRegex = Regex("""^@\[[^]]+]\s*""")

    /**
     * Builds a quoted-reply draft: a mention of [mentionName] followed by a short, `>`-prefixed
     * preview of [messageText] (its own leading mention, if any, stripped first so a reply chain
     * doesn't nest mentions). Ported from `buildReplyText(mentionName:messageText:)` — not wired to
     * any UI yet, see this file's class doc. The 10-character preview cutoff is a plain
     * [String.take] here rather than Swift's grapheme-cluster-aware `prefix(10)`; an emoji or other
     * multi-code-unit character right at the boundary could split oddly, the same acknowledged
     * Char-vs-Character gap as elsewhere in this port.
     */
    fun buildReplyText(mentionName: String, messageText: String): String {
        val previewSource = leadingMentionRegex.replaceFirst(messageText, "")
        val preview = previewSource.take(10)
        val suffix = if (previewSource.length > 10) ".." else ""
        return "${createMention(mentionName)}\n>$preview$suffix\n"
    }
}
