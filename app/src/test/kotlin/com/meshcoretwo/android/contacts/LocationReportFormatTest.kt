// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports the fixture cases of `LocationReportFormatTests.swift`. */
class LocationReportFormatTest {
    @Test
    fun `coordinates format to four decimal places with a dotted separator`() {
        assertEquals("37.7847, -122.4012", LocationReportFormat.coordinates(37.784712, -122.401233))
    }

    @Test
    fun `coordinates round to four places rather than truncate`() {
        assertEquals("37.7848, -122.4013", LocationReportFormat.coordinates(37.78475, -122.40125))
    }

    @Test
    fun `zero coordinates format without dropping the pair`() {
        assertEquals("0.0000, 0.0000", LocationReportFormat.coordinates(0.0, 0.0))
    }

    @Test
    fun `relative time for a past report counts hours`() {
        val now = Instant.ofEpochSecond(1_000_000)
        val twoHoursAgo = now.minusSeconds(2 * 3600)
        assertEquals(UiText.of(R.string.time_hour_ago, 2), LocationReportFormat.relativeTime(twoHoursAgo, now))
    }

    @Test
    fun `altitude formats to a non-empty value`() {
        assertTrue(LocationReportFormat.altitude(42.0).isNotEmpty())
    }

    @Test
    fun `sea-level altitude still renders`() {
        assertTrue(LocationReportFormat.altitude(0.0).isNotEmpty())
    }
}
