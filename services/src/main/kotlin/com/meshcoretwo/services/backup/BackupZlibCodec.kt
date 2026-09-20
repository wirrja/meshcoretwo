// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Compresses/decompresses a backup payload. Ported from `Data+Extensions.swift`'s
 * `zlibCompressed()`/`zlibDecompressed(maxUncompressedBytes:)`. `java.util.zip.Deflater`/
 * `Inflater` default to `nowrap = false`, the same RFC 1950 zlib framing Apple's
 * `Compression.zlib` algorithm produces/consumes, so no cross-platform format gap — a backup
 * exported here decompresses the same way Foundation's `NSData.compressed(using: .zlib)` output
 * would (not that the two apps ever need to read each other's files, but the wire format lines
 * up for free).
 */
object BackupZlibCodec {
    private const val BUFFER_SIZE = 8 * 1024

    /** Compressed-payload size cap `parseBackup` (a later sub-slice) checks before ever calling [decompress] — rejecting a clearly-oversized file up front. */
    const val MAX_BACKUP_COMPRESSED_BYTES = 50L * 1_048_576

    /** Streaming-decompression abort cap — see [decompress]. */
    const val MAX_BACKUP_UNCOMPRESSED_BYTES = 512L * 1_048_576

    fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            deflater.setInput(data)
            deflater.finish()
            val output = ByteArrayOutputStream(maxOf(64, data.size / 2))
            val buffer = ByteArray(BUFFER_SIZE)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /**
     * Stream-decompresses a zlib payload, aborting once the output crosses
     * [maxUncompressedBytes]. Throws [AppBackupError.DecompressedTooLarge] on cap overflow so
     * callers can surface a specific user-facing reason instead of a generic invalid-file error,
     * and [AppBackupError.InvalidFile] for a payload that isn't valid zlib data.
     */
    fun decompress(data: ByteArray, maxUncompressedBytes: Long = MAX_BACKUP_UNCOMPRESSED_BYTES): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (!inflater.finished()) {
                val count = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    throw AppBackupError.InvalidFile
                }
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                output.write(buffer, 0, count)
                if (output.size().toLong() > maxUncompressedBytes) {
                    throw AppBackupError.DecompressedTooLarge(maxUncompressedBytes)
                }
            }
            return output.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
