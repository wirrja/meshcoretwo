// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat.linkify

/**
 * One detected, styled span in a chat message body. Ported from `LinkToken.swift`, trimmed to the
 * five kinds [MessageLinkTokenizer] detects in this slice — [Kind.CONTACT_SHARE], [Kind.MENTION],
 * [Kind.URL], [Kind.HASHTAG], and [Kind.MESHCORE_CHANNEL_LINK]. Not ported: `coordinate` (needs a
 * "focus the Map screen on a coordinate" nav path that doesn't exist yet). Unlike the iOS source,
 * this carries no color/style — [MessageLinkTokenizer] stays a pure, theme-independent detector;
 * styling is applied where the message is rendered instead ([styledMessageText] resolves a
 * [Kind.MENTION]'s per-identity color the same way sender names already do, via
 * [com.meshcoretwo.android.ui.theme.identityColor]). For [Kind.CONTACT_SHARE], [value] is a
 * re-encoded `<publicKeyHex:type:name>` share token (see [com.meshcoretwo.services.contacts
 * .ContactService.formatContactShareToken]/`.parseContactShareToken`) carrying the sanitized
 * display name, not the original message bytes — [com.meshcoretwo.android.contacts.AddContactScreen]
 * parses it back to prefill its fields. For [Kind.MENTION], [value] is the mentioned name, without
 * the `@[]` wrapper — [styledMessageText] highlights it (identity color, underline, and a
 * background tint for a self-mention) but does not attach a tap destination: Swift's mention
 * tap-to-navigate (`MentionTapHandler`/`MentionPickerSheet`/deep-link routing) is a separate,
 * unported feature, so a rendered mention is visually distinct but inert to taps in this port.
 */
data class LinkToken(val range: IntRange, val kind: Kind, val value: String) {
    /**
     * Fixed overlap-resolution priority, highest first (declaration order is the priority) — the
     * same relative order as iOS's full six-case enum with the unported `coordinate` kind removed:
     * a contact chip or mention shadows any link/hashtag it contains, a URL shadows a hashtag or
     * meshcore link it contains, and a hashtag sitting inside a meshcore link wins over that link
     * (so the meshcore link is left unlinked and only the hashtag is styled).
     */
    enum class Kind { CONTACT_SHARE, MENTION, URL, HASHTAG, MESHCORE_CHANNEL_LINK }
}
