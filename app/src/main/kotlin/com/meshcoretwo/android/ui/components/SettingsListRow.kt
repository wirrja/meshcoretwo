// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.meshcoretwo.android.R

/**
 * Uppercase section header for the Settings list — replaces a [SectionCard]'s implicit boundary
 * with a plain label (Redesign mockup principle 04, "grouped, not boxed"). Not a [SectionCard]
 * itself: the group below it renders directly against the screen background, no card/border.
 */
@Composable
fun SettingsGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.06.em,
        modifier = modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

/**
 * One row in a [SettingsGroupLabel] group — the mockup's `srow`: an optional leading icon in a
 * tonal `primaryContainer` square, a title/value pair, an optional trailing accessory (e.g. theme
 * swatch dots), and a chevron when [onClick] is set. Replaces the ad hoc
 * `Row(SpaceBetween){ Text(); Text() }`/`NavigationRow` shapes each Settings section previously
 * drew for itself, so every converted row reads the same regardless of which section it's in.
 */
@Composable
fun SettingsListRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    @DrawableRes icon: Int? = null,
    titleColor: Color = Color.Unspecified,
    singleLineValue: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    @DrawableRes trailingIcon: Int = R.drawable.ic_chevron_right,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = titleColor)
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (singleLineValue) 1 else Int.MAX_VALUE,
                    overflow = if (singleLineValue) TextOverflow.Ellipsis else TextOverflow.Clip,
                )
            }
        }
        trailing?.invoke()
        if (onClick != null && showChevron) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                painterResource(trailingIcon),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}
