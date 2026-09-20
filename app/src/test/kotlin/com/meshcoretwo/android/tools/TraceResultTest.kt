// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TraceResultTest {
    @Test
    fun `chunkedHexString chunks bytes by size, comma-separated and uppercase`() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertEquals("01,02,03,04", chunkedHexString(bytes, 1))
        assertEquals("0102,0304", chunkedHexString(bytes, 2))
        assertEquals("01020304", chunkedHexString(bytes, 4))
    }

    @Test
    fun `chunkedHexString of empty bytes is empty string`() {
        assertEquals("", chunkedHexString(ByteArray(0), 1))
    }

    @Test
    fun `tracedPathString chunks tracedPathBytes by hashSize`() {
        val result = TraceResult(
            hops = emptyList(), durationMs = 100, success = true, errorMessage = null,
            tracedPathBytes = byteArrayOf(0xAA.toByte(), 0xBB.toByte()), hashSize = 1,
        )
        assertEquals("AA,BB", result.tracedPathString)
    }

    @Test
    fun `timeout factory produces a failed result carrying the attempted path`() {
        val result = TraceResult.timeout(byteArrayOf(0x01), hashSize = 1)
        assertFalse(result.success)
        assertEquals(TracePathStrings.ERROR_NO_RESPONSE, result.errorMessage)
        assertEquals(listOf<Byte>(0x01), result.tracedPathBytes.toList())
    }

    @Test
    fun `sendFailed factory carries the given message`() {
        val result = TraceResult.sendFailed("boom", byteArrayOf(), hashSize = 1)
        assertFalse(result.success)
        assertEquals("boom", result.errorMessage)
    }

    @Test
    fun `two results with equal fields but different ids are not equal`() {
        val a = TraceResult(hops = emptyList(), durationMs = 0, success = false, errorMessage = null, tracedPathBytes = byteArrayOf(), hashSize = 1)
        val b = TraceResult(hops = emptyList(), durationMs = 0, success = false, errorMessage = null, tracedPathBytes = byteArrayOf(), hashSize = 1)
        assertNotEquals(a, b)
        assertEquals(a, a.copy())
    }
}
