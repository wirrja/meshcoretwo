// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat.linkify

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Builds the [AnnotatedString] for a message body from [MessageLinkTokenizer]'s tokens. Ported
 * from `MessageLinkStyler.swift`, trimmed to this slice's five kinds: a hashtag renders bold in
 * [hashtagColor] (or [textColor] itself when [isOutgoing] — the theme's hashtag color is tuned
 * against the incoming-bubble surface, not the accent-colored outgoing one, same as the iOS
 * source's outgoing override), URL/meshcore-channel-link/contact-share render underlined in
 * [textColor] — this port's plain-bubble MVP has no separate "link blue", the underline is the
 * only visual cue — and a mention renders underlined in its per-name [mentionColors] entry (or
 * [textColor] when [isOutgoing], matching the hashtag override — your own outgoing bubble already
 * has an accent fill, so there's no surface to tint identity colors against) with a translucent
 * background tint when it mentions [selfName]. [onLinkClick] fires with the tapped [LinkToken] so
 * the caller decides what each *clickable* kind does (open a browser, join a channel, add a
 * contact, ...) — a mention is deliberately not clickable (no [LinkAnnotation] is attached to it),
 * since it has no tap destination yet (see [LinkToken]'s doc). Callers must pass the *text*
 * [tokens] were produced against — [MessageLinkTokenizer.TokenizeResult.displayText], not
 * necessarily the message's raw stored text — since a contact-share/mention token's range is into
 * the shrunk display string, not the original.
 */
fun styledMessageText(
    text: String,
    tokens: List<LinkToken>,
    textColor: Color,
    hashtagColor: Color,
    isOutgoing: Boolean,
    onLinkClick: (LinkToken) -> Unit,
    mentionColors: Map<String, Color> = emptyMap(),
    selfName: String? = null,
): AnnotatedString {
    if (tokens.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (token in tokens) {
            val isHashtag = token.kind == LinkToken.Kind.HASHTAG
            val isMention = token.kind == LinkToken.Kind.MENTION
            val mentionColor = if (isMention) mentionColors[token.value] ?: textColor else null
            val isSelfMention = isMention && selfName != null && token.value.equals(selfName, ignoreCase = true)
            addStyle(
                SpanStyle(
                    color = when {
                        isOutgoing -> textColor
                        isHashtag -> hashtagColor
                        isMention -> mentionColor ?: textColor
                        else -> textColor
                    },
                    background = if (isSelfMention) (mentionColor ?: textColor).copy(alpha = SELF_MENTION_BACKGROUND_ALPHA) else Color.Unspecified,
                    fontWeight = if (isHashtag) FontWeight.Bold else null,
                    textDecoration = if (isHashtag) null else TextDecoration.Underline,
                ),
                token.range.first,
                token.range.last + 1,
            )
            if (!isMention) {
                addLink(
                    LinkAnnotation.Clickable(tag = "${token.kind}:${token.range.first}") { onLinkClick(token) },
                    token.range.first,
                    token.range.last + 1,
                )
            }
        }
    }
}

/** Ported from `MessageTextNormalizer`'s `opacity(0.15)` (incoming)/`opacity(0.3)` (outgoing) self-mention tints, collapsed to one value — this port's outgoing bubble already renders [textColor] for a mention (see [styledMessageText]'s doc), so there is no separate identity color whose weaker tint Swift's incoming branch uses instead. */
private const val SELF_MENTION_BACKGROUND_ALPHA = 0.2f
