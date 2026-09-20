// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui

import com.meshcoretwo.android.ui.i18n.DatePatterns
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.meshcoretwo.android.R
import java.time.Instant
import java.time.ZoneId

/**
 * Ported from `ConversationTimestamp.formattedDate(relativeTo:)`: same-day shows a short time,
 * yesterday shows "Yesterday" (Swift's `.relative(presentation: .named)` for a one-day gap), older
 * shows an abbreviated month/day. Unlike the Swift view, this isn't re-evaluated every minute via
 * `TimelineView` — callers recompute it whenever their screen reloads, which in practice (any
 * incoming message/status/contact-sync event) is frequent enough that a stale "2m ago"-style label
 * was never in scope to begin with (this format doesn't show elapsed minutes).
 */
@Composable
fun formatRelativeTimestamp(date: Instant): String {
    val yesterday = stringResource(R.string.common_yesterday)
    val zone = ZoneId.systemDefault()
    val target = date.atZone(zone).toLocalDate()
    val today = Instant.now().atZone(zone).toLocalDate()
    return when {
        target == today -> DatePatterns.timeShort().withZone(zone).format(date)
        target == today.minusDays(1) -> yesterday
        else -> DatePatterns.monthDay().withZone(zone).format(date)
    }
}
