// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HopCodeParserTest {
    private fun resolver(vararg entries: Pair<String, String>): (ByteArray) -> Pair<ByteArray, String?>? {
        val byHex = entries.toMap()
        return { bytes ->
            val hex = bytes.joinToString("") { "%02X".format(it) }
            byHex[hex]?.let { name -> bytes to name }
        }
    }

    @Test
    fun `classifies a resolvable code as willAdd`() {
        val result = HopCodeParser.classify(
            input = "AABBCC",
            hashSize = 3,
            existingHashes = emptySet(),
            remainingCapacity = null,
            resolve = resolver("AABBCC" to "Tower"),
        )

        assertEquals(1, result.size)
        val status = result[0].status
        assertTrue(status is HopCodeStatus.WillAdd)
        assertEquals("Tower", (status as HopCodeStatus.WillAdd).hop.resolvedName)
    }

    @Test
    fun `rejects a code with the wrong length as invalid format`() {
        val result = HopCodeParser.classify("AABB", hashSize = 3, existingHashes = emptySet(), remainingCapacity = null, resolve = resolver())
        assertEquals(HopCodeStatus.InvalidFormat, result.single().status)
    }

    @Test
    fun `rejects non-hex characters as invalid format`() {
        val result = HopCodeParser.classify("ZZBBCC", hashSize = 3, existingHashes = emptySet(), remainingCapacity = null, resolve = resolver())
        assertEquals(HopCodeStatus.InvalidFormat, result.single().status)
    }

    @Test
    fun `marks a code already in the path`() {
        val result = HopCodeParser.classify(
            input = "AABBCC",
            hashSize = 3,
            existingHashes = setOf("AABBCC"),
            remainingCapacity = null,
            resolve = resolver("AABBCC" to "Tower"),
        )
        assertEquals(HopCodeStatus.AlreadyInPath, result.single().status)
    }

    @Test
    fun `marks an unresolvable code as not found`() {
        val result = HopCodeParser.classify("AABBCC", hashSize = 3, existingHashes = emptySet(), remainingCapacity = null, resolve = resolver())
        assertEquals(HopCodeStatus.NotFound, result.single().status)
    }

    @Test
    fun `marks a resolvable code past remaining capacity as pathFull`() {
        val result = HopCodeParser.classify(
            input = "AABBCC",
            hashSize = 3,
            existingHashes = emptySet(),
            remainingCapacity = 0,
            resolve = resolver("AABBCC" to "Tower"),
        )
        assertEquals(HopCodeStatus.PathFull, result.single().status)
    }

    @Test
    fun `deduplicates repeated codes, keeping only the first occurrence`() {
        val result = HopCodeParser.classify(
            input = "AABBCC, aabbcc, AABBCC",
            hashSize = 3,
            existingHashes = emptySet(),
            remainingCapacity = null,
            resolve = resolver("AABBCC" to "Tower"),
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `a second resolvable code in the same bulk paste is not blocked by the first filling capacity early`() {
        val result = HopCodeParser.classify(
            input = "AAAAAA,BBBBBB",
            hashSize = 3,
            existingHashes = emptySet(),
            remainingCapacity = 1,
            resolve = resolver("AAAAAA" to "First", "BBBBBB" to "Second"),
        )
        assertEquals(HopCodeStatus.WillAdd::class, result[0].status::class)
        assertEquals(HopCodeStatus.PathFull, result[1].status)
    }

    @Test
    fun `ignores blank entries and surrounding whitespace`() {
        val result = HopCodeParser.classify(
            input = " AABBCC ,, ",
            hashSize = 3,
            existingHashes = emptySet(),
            remainingCapacity = null,
            resolve = resolver("AABBCC" to "Tower"),
        )
        assertEquals(1, result.size)
        assertEquals("AABBCC", result.single().code)
    }
}
