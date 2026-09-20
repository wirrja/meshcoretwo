// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.i18n

import android.text.format.DateFormat
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Locale-aware date/time formatters built per call (the default locale can change at runtime with
 * the in-app language switch). Falls back to the English pattern when the Android framework isn't
 * available, i.e. in plain JVM unit tests.
 */
object DatePatterns {
    /** "Sep 3" / "3 сент." */
    fun monthDay(locale: Locale = Locale.getDefault()): DateTimeFormatter = formatter("MMMd", "MMM d", locale)

    /** "Sep 3, 07:41" / "3 сент., 07:41" */
    fun monthDayTime(locale: Locale = Locale.getDefault()): DateTimeFormatter = formatter("MMMdHm", "MMM d, HH:mm", locale)

    /** "7:41 AM" / "07:41" — the locale's short time, always with a colon (fi/da/… would use "07.41"). */
    fun timeShort(locale: Locale = Locale.getDefault()): DateTimeFormatter = localized(null, FormatStyle.SHORT, locale)

    /** "Sep 3, 2026" in the user's locale. */
    fun dateMedium(locale: Locale = Locale.getDefault()): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneId.systemDefault())

    /** "Sep 3, 2026, 7:41 AM" in the user's locale. */
    fun dateTimeMedium(locale: Locale = Locale.getDefault()): DateTimeFormatter =
        localized(FormatStyle.MEDIUM, FormatStyle.SHORT, locale)

    /** "Sep 3, 2026, 7:41:05 AM" in the user's locale (seconds included). */
    fun dateTimeMediumSeconds(locale: Locale = Locale.getDefault()): DateTimeFormatter =
        localized(FormatStyle.MEDIUM, FormatStyle.MEDIUM, locale)

    private val dottedSeconds = Regex("""(m{1,2})\.(s{1,2})""")
    private val dottedTime = Regex("""([Hhkk]{1,2})\.(m{1,2})""")

    /** Localized pattern with the hour/minute(/second) separator forced to ':' (ICU uses '.' for fi, da, …). */
    private fun localized(date: FormatStyle?, time: FormatStyle?, locale: Locale): DateTimeFormatter {
        val raw = DateTimeFormatterBuilder.getLocalizedDateTimePattern(date, time, IsoChronology.INSTANCE, locale)
        val pattern = raw
            .replace(dottedTime) { it.groupValues[1] + ":" + it.groupValues[2] }
            .replace(dottedSeconds) { it.groupValues[1] + ":" + it.groupValues[2] }
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault())
    }

    private fun formatter(skeleton: String, fallback: String, locale: Locale): DateTimeFormatter {
        val pattern = try {
            DateFormat.getBestDateTimePattern(locale, skeleton)
        } catch (e: RuntimeException) {
            fallback
        }
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault())
    }
}
