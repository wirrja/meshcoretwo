// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rendering

/**
 * Signal quality classification based on LoRa SNR (Signal-to-Noise Ratio) in dB. Ported from
 * `SNRQuality.swift` (`MC1Services/Models/Rendering`). Originally landed in the `rxlog` package
 * (its first consumer) with a note to hoist it once a second one appeared; Trace Path's
 * `TraceHop.snrQuality` is that second consumer, so it now lives here, shared.
 */
enum class SNRQuality {
    EXCELLENT, // SNR > +6 dB
    GOOD, // SNR > +0 dB
    FAIR, // SNR > -6 dB
    POOR, // SNR <= -6 dB
    UNKNOWN, // null SNR
    ;

    /** Bar level for a 4-bar signal icon (0-1). */
    val barLevel: Double
        get() = when (this) {
            EXCELLENT -> 1.0
            GOOD -> 0.75
            FAIR -> 0.5
            POOR -> 0.25
            UNKNOWN -> 0.0
        }

    /** Developer-facing English label; the `app` layer localizes for display. */
    val qualityLabel: String
        get() = when (this) {
            EXCELLENT -> "Excellent"
            GOOD -> "Good"
            FAIR -> "Fair"
            POOR -> "Weak"
            UNKNOWN -> "Unknown"
        }

    companion object {
        fun of(snr: Double?): SNRQuality = when {
            snr == null -> UNKNOWN
            snr > 6 -> EXCELLENT
            snr > 0 -> GOOD
            snr > -6 -> FAIR
            else -> POOR
        }
    }
}
