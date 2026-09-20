// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiDetectionTest {
    @Test
    fun `recognizes common single-scalar emoji`() {
        assertTrue(EmojiDetection.startsWithEmoji("👍 nice"))
        assertTrue(EmojiDetection.startsWithEmoji("❤️ love"))
        assertTrue(EmojiDetection.startsWithEmoji("😀"))
    }

    @Test
    fun `recognizes a multi-scalar flag emoji via the grapheme-cluster rule`() {
        assertTrue(EmojiDetection.startsWithEmoji("🇺🇸 USA"))
    }

    @Test
    fun `rejects plain ASCII text and empty strings`() {
        assertFalse(EmojiDetection.startsWithEmoji("hello"))
        assertFalse(EmojiDetection.startsWithEmoji(""))
        assertFalse(EmojiDetection.startsWithEmoji("123"))
    }
}
