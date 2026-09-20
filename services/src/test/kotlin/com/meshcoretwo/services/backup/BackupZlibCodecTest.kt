// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupZlibCodecTest {
    @Test
    fun `round-trips arbitrary bytes through compress and decompress`() {
        val original = "hello mesh, ".repeat(500).toByteArray()

        val compressed = BackupZlibCodec.compress(original)
        val decompressed = BackupZlibCodec.decompress(compressed)

        assertArrayEquals(original, decompressed)
        assertTrue("repetitive input should compress smaller", compressed.size < original.size)
    }

    @Test
    fun `round-trips empty input`() {
        val compressed = BackupZlibCodec.compress(ByteArray(0))
        assertArrayEquals(ByteArray(0), BackupZlibCodec.decompress(compressed))
    }

    @Test
    fun `aborts decompression once output crosses the cap`() {
        val original = ByteArray(10_000) { it.toByte() }
        val compressed = BackupZlibCodec.compress(original)

        assertThrows(AppBackupError.DecompressedTooLarge::class.java) {
            BackupZlibCodec.decompress(compressed, maxUncompressedBytes = 1_000)
        }
    }

    @Test
    fun `rejects data that is not a valid zlib stream`() {
        assertThrows(AppBackupError.InvalidFile::class.java) {
            BackupZlibCodec.decompress(byteArrayOf(1, 2, 3, 4, 5))
        }
    }
}
