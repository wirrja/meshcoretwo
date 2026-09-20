// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat.emoji

import com.meshcoretwo.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCatalogTest {
    @Test
    fun `blank query returns every category, unfiltered`() {
        val sections = EmojiCatalog.sections(query = "", recentUnicode = emptyList())

        assertEquals(EmojiCategory.entries.map { it.displayNameRes }, sections.map { it.titleRes })
        for ((category, emoji) in EmojiCatalog.categories) {
            val section = sections.first { it.titleRes == category.displayNameRes }
            assertEquals(emoji, section.emoji)
        }
    }

    @Test
    fun `blank query with recents prepends a Frequently Used section`() {
        val sections = EmojiCatalog.sections(query = "", recentUnicode = listOf("👍", "😀"))

        assertEquals(R.string.emoji_frequently_used, sections.first().titleRes)
        assertEquals(listOf("👍", "😀"), sections.first().emoji.map { it.unicode })
    }

    @Test
    fun `unknown recent unicode is silently dropped`() {
        val sections = EmojiCatalog.sections(query = "", recentUnicode = listOf("👍", "🛸🛸not-real"))

        assertEquals(listOf("👍"), sections.first().emoji.map { it.unicode })
    }

    @Test
    fun `no recents means no Frequently Used section`() {
        val sections = EmojiCatalog.sections(query = "", recentUnicode = emptyList())

        assertTrue(sections.none { it.titleRes == R.string.emoji_frequently_used })
    }

    @Test
    fun `query matches by label substring, case-insensitively`() {
        val sections = EmojiCatalog.sections(query = "HEART", recentUnicode = emptyList())

        val matched = sections.flatMap { it.emoji }
        assertTrue(matched.any { it.unicode == "❤️" })
        assertTrue(matched.all { it.label.contains("heart", ignoreCase = true) || it.shortcode.contains("heart", ignoreCase = true) })
    }

    @Test
    fun `query matches by shortcode substring`() {
        val sections = EmojiCatalog.sections(query = "+1", recentUnicode = emptyList())

        assertTrue(sections.flatMap { it.emoji }.any { it.unicode == "👍" })
    }

    @Test
    fun `empty categories after filtering are dropped, not shown empty`() {
        val sections = EmojiCatalog.sections(query = "zzz-no-such-emoji", recentUnicode = emptyList())

        assertTrue(sections.isEmpty())
    }

    @Test
    fun `a non-blank query never surfaces the Frequently Used section`() {
        val sections = EmojiCatalog.sections(query = "heart", recentUnicode = listOf("👍"))

        assertTrue(sections.none { it.titleRes == R.string.emoji_frequently_used })
    }
}
