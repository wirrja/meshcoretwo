// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.theme.LocalAppTheme
import com.meshcoretwo.android.ui.theme.LocalIsDarkTheme

/**
 * Shared "grouped list" section container — the Compose analog of iOS's
 * `.themedRowBackground(theme)` (`ThemedSurfaceModifiers.swift`), which paints each `Section` of a
 * themed `List`/`Form` with the theme's card surface. Since Compose screens here are flat scrolling
 * `Column`s rather than `List`+`Section`, one [SectionCard] per logical section replaces both the
 * per-section background and the `HorizontalDivider`s previously drawn between them.
 *
 * Background is [com.meshcoretwo.android.ui.theme.Theme.surfaces]'s `card`, same source and
 * fallback as [com.meshcoretwo.android.ui.theme.incomingBubbleColor] (`surfaceContainer` for themes
 * with no explicit card tier — Default, Ember — mirroring `ThemedRowBackgroundModifier`'s "no-op for
 * themes without a card tier" on iOS). Shape is [MaterialTheme.shapes]'s `large` (16dp) token, per
 * PLAN.md's Phase 6 slice 5.
 */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalAppTheme.current
    val isDark = LocalIsDarkTheme.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = theme.surfaces?.card?.resolve(isDark) ?: MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/**
 * A collapsible section: a clickable title row with an expand/collapse chevron and an optional
 * reload action, its content shown only when [expanded] — extracted from the identical private
 * composable that used to live separately in `RoomStatusScreen.kt` and `RepeaterStatusScreen.kt`.
 * Phase 11 dropped the bordered [SectionCard] this used to sit in (matching Phase 10/11's
 * "grouped, not boxed" principle for every other screen's sections) in favor of a flat header row +
 * a trailing [HorizontalDivider] — the same divider-between-groups language `SettingsScreen.kt` and
 * the other Phase 10/11 screens already use. The collapse/reload behavior itself (real UX for these
 * dashboards' long telemetry/neighbor lists, not just chrome) is unchanged.
 */
@Composable
fun ExpandableSectionCard(
    title: String,
    expanded: Boolean,
    isLoading: Boolean,
    onToggle: () -> Unit,
    onReload: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (onReload != null) {
                    IconButton(onClick = onReload) { Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.common_reload)) }
                }
                Icon(
                    painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        if (expanded) {
            Spacer(modifier = Modifier.size(8.dp))
            Column(content = content)
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}
