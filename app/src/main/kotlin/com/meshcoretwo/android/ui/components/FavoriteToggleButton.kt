// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.meshcoretwo.android.R

/**
 * The star "Add to Favorites"/"Favorited" toggle shared by [ContactDetailScreen]/[RoomStatusScreen]/
 * [ChannelInfoScreen]. Rendered as a full-width [SettingsListRow] rather than a pill button: a row
 * has the whole width for its label, so long translations (ru/de/fi) never squeeze or break it.
 */
@Composable
fun FavoriteToggleButton(isFavorite: Boolean, onToggle: () -> Unit) {
    SettingsListRow(
        title = stringResource(if (isFavorite) R.string.favorite_favorited else R.string.favorite_add),
        icon = if (isFavorite) R.drawable.ic_star else R.drawable.ic_star_border,
        onClick = onToggle,
        showChevron = false,
    )
}
