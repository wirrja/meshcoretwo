// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.ui.i18n.DatePatterns
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formats a single location report (one [com.meshcoretwo.services.persistence.NodeStatusSnapshotDto])
 * for the History list: a coarse relative recency, a precise absolute timestamp, and fixed-precision
 * coordinates. Pure and side-effect free so rows stay trivially unit-testable. Ported from
 * `LocationReportFormat.swift`.
 *
 * Not ported: locale-dependent metric/imperial altitude conversion
 * (`MeasurementFormatter.unitOptions = .naturalScale`) — this port has no unit-system preference
 * anywhere yet (see [telemetryChannelGroups]'s doc), so altitude always renders in meters.
 */
object LocationReportFormat {
    /** Decimal places shown for latitude/longitude. Four places (about 11 m) is enough to distinguish adjacent reports without implying false GPS precision. */
    private const val COORDINATE_DECIMALS = 4

    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val SECONDS_PER_DAY = 86_400L
    private const val SECONDS_PER_WEEK = SECONDS_PER_DAY * 7
    private const val SECONDS_PER_MONTH = SECONDS_PER_DAY * 30
    private const val SECONDS_PER_YEAR = SECONDS_PER_DAY * 365

    private fun absoluteTimeFormat(): DateTimeFormatter = DatePatterns.monthDayTime()

    /** "2h ago": single-unit coarse recency. Reports land ~15 min apart, so the absolute timestamp is what distinguishes adjacent rows. */
    fun relativeTime(timestamp: Instant, now: Instant): UiText {
        val seconds = Duration.between(timestamp, now).seconds.coerceAtLeast(0)
        return when {
            seconds < SECONDS_PER_MINUTE -> UiText.of(R.string.time_just_now)
            seconds < SECONDS_PER_HOUR -> UiText.of(R.string.time_min_ago, (seconds / SECONDS_PER_MINUTE).toInt())
            seconds < SECONDS_PER_DAY -> UiText.of(R.string.time_hour_ago, (seconds / SECONDS_PER_HOUR).toInt())
            seconds < SECONDS_PER_WEEK -> UiText.of(R.string.time_day_ago, (seconds / SECONDS_PER_DAY).toInt())
            seconds < SECONDS_PER_MONTH -> UiText.of(R.string.time_week_ago, (seconds / SECONDS_PER_WEEK).toInt())
            seconds < SECONDS_PER_YEAR -> UiText.of(R.string.time_month_ago, (seconds / SECONDS_PER_MONTH).toInt())
            else -> UiText.of(R.string.time_year_ago, (seconds / SECONDS_PER_YEAR).toInt())
        }
    }

    /** "Jul 13, 07:41": localized absolute timestamp of the report. */
    fun absoluteTime(timestamp: Instant): String = absoluteTimeFormat().format(timestamp)

    /** "37.7847, -122.4012": fixed-precision coordinates with a locale-independent decimal point. */
    fun coordinates(latitude: Double, longitude: Double): String =
        "%.${COORDINATE_DECIMALS}f, %.${COORDINATE_DECIMALS}f".format(Locale.US, latitude, longitude)

    /** "42 m": altitude in meters, rounded to the nearest whole meter. */
    fun altitude(meters: Double): String = "${Math.round(meters)} m"
}
