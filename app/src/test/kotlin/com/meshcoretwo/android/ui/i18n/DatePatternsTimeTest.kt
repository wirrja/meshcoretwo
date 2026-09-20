// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.i18n

import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatePatternsTimeTest {
    private val instant = Instant.parse("2026-09-20T11:05:09Z")

    @Test
    fun `short time uses a colon in every supported locale`() {
        for (tag in listOf("en", "ru", "fr", "de", "zh-CN", "tr", "fi", "sv")) {
            val text = DatePatterns.timeShort(Locale.forLanguageTag(tag)).withZone(ZoneOffset.UTC).format(instant)
            assertTrue("$tag: $text", Regex("""\d{1,2}:05""").containsMatchIn(text))
            assertFalse("$tag: $text", text.contains("11.05"))
        }
    }
}
