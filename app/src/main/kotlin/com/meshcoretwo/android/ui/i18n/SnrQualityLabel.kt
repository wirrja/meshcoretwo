// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.i18n

import androidx.annotation.StringRes
import com.meshcoretwo.android.R
import com.meshcoretwo.services.rendering.SNRQuality

/** Localized label for [SNRQuality.qualityLabel]'s developer-facing English text. */
@StringRes
fun SNRQuality.labelRes(): Int = when (this) {
    SNRQuality.EXCELLENT -> R.string.snr_quality_excellent
    SNRQuality.GOOD -> R.string.snr_quality_good
    SNRQuality.FAIR -> R.string.snr_quality_fair
    SNRQuality.POOR -> R.string.snr_quality_weak
    SNRQuality.UNKNOWN -> R.string.snr_quality_unknown
}
