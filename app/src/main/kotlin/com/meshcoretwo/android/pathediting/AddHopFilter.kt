// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

import androidx.annotation.StringRes
import com.meshcoretwo.android.R

/**
 * Sections the Add-Hop picker can narrow to. [ALL] shows every section. Ported from `AddHopFilter`,
 * minus the Swift `.discovered` case — this port has no "Discover" list (see [HopNodeMatching]'s
 * class doc), so there is nothing for that filter to narrow to.
 */
enum class AddHopFilter(@StringRes val labelRes: Int) {
    ALL(R.string.common_all),
    FAVORITES(R.string.common_favorites),
    RECENT(R.string.common_recent),
}
