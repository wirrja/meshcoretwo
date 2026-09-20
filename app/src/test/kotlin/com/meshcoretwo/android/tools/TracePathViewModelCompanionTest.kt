// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers [TracePathViewModel.inferredTraceHashMode], the one piece of `TracePathViewModel` that doesn't need a live [com.meshcoretwo.services.connection.ConnectionManager]. */
class TracePathViewModelCompanionTest {
    @Test
    fun `single-byte codes infer mode 0`() {
        assertEquals(0u.toUByte(), TracePathViewModel.inferredTraceHashMode("AB,CD,EF"))
    }

    @Test
    fun `two-byte codes infer mode 1`() {
        assertEquals(1u.toUByte(), TracePathViewModel.inferredTraceHashMode("AABB,CCDD"))
    }

    @Test
    fun `four-byte codes infer mode 2`() {
        assertEquals(2u.toUByte(), TracePathViewModel.inferredTraceHashMode("AABBCCDD"))
    }

    @Test
    fun `mixed-width codes are ambiguous, returning null`() {
        assertNull(TracePathViewModel.inferredTraceHashMode("AB,CCDD"))
    }

    @Test
    fun `three-byte width is not a valid power-of-2 trace width`() {
        assertNull(TracePathViewModel.inferredTraceHashMode("AABBCC"))
    }

    @Test
    fun `odd-length or non-hex tokens return null`() {
        assertNull(TracePathViewModel.inferredTraceHashMode("ABC"))
        assertNull(TracePathViewModel.inferredTraceHashMode("ZZ"))
    }

    @Test
    fun `empty or blank input returns null`() {
        assertNull(TracePathViewModel.inferredTraceHashMode(""))
        assertNull(TracePathViewModel.inferredTraceHashMode(" , , "))
    }

    @Test
    fun `whitespace around tokens is trimmed`() {
        assertEquals(0u.toUByte(), TracePathViewModel.inferredTraceHashMode(" AB , CD "))
    }
}
