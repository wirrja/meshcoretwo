// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.meshcoretwo.android.R
import com.meshcoretwo.services.persistence.NotificationLevel

/** Localized label for a channel/room notification level (shared by channel info and room status). */
@Composable
fun NotificationLevel.label(): String = stringResource(
    when (this) {
        NotificationLevel.ALL -> R.string.notif_level_all
        NotificationLevel.MENTIONS_ONLY -> R.string.notif_level_mentions
        NotificationLevel.MUTED -> R.string.notif_level_muted
    },
)
