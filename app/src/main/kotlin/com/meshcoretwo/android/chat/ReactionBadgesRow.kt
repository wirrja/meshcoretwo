// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.ui.theme.incomingBubbleColor
import com.meshcoretwo.services.utilities.ReactionParser

/** Badges shown under a bubble at once, matching `ReactionBadgesView.swift`'s `maxVisibleBadges`. */
private const val MAX_VISIBLE_BADGES = 3

/**
 * Reaction badge row rendered under a message bubble. Ported from `ReactionBadgesView.swift`,
 * minus its accessibility-action wiring (VoiceOver isn't in this port's scope yet, see other
 * screens' docs) and its 0.3s-timed long-press distinction (Compose's [combinedClickable] already
 * separates tap from long-press without a manual timer).
 *
 * Tapping a shown badge re-reacts with that emoji ([onTapReaction]); long-pressing any badge, or
 * tapping the overflow "+N" badge, opens the reaction-details sheet ([onOpenDetails]) — same
 * dispatch as `ReactionBadgesView`'s `onLongPress`/overflow-tap handlers.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReactionBadgesRow(
    summary: String,
    modifier: Modifier = Modifier,
    onTapReaction: (String) -> Unit,
    onOpenDetails: () -> Unit,
) {
    val reactions = remember(summary) { ReactionParser.parseSummary(summary) }
    if (reactions.isEmpty()) return

    val shown = reactions.take(MAX_VISIBLE_BADGES)
    val overflowCount = reactions.size - shown.size

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((emoji, count) in shown) {
            ReactionBadge(
                modifier = Modifier.combinedClickable(onClick = { onTapReaction(emoji) }, onLongClick = onOpenDetails),
            ) {
                Text(emoji, style = MaterialTheme.typography.labelMedium)
                if (count > 1) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (overflowCount > 0) {
            ReactionBadge(modifier = Modifier.clickable(onClick = onOpenDetails)) {
                Text("+$overflowCount", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ReactionBadge(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = incomingBubbleColor(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.background),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
