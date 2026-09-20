// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import com.meshcoretwo.protocol.hexString
import java.util.UUID

/** Comma-separated, uppercase hex string of [bytes] chunked every [chunkSize] bytes. */
fun chunkedHexString(bytes: ByteArray, chunkSize: Int): String {
    if (bytes.isEmpty() || chunkSize <= 0) return ""
    return bytes.toList()
        .chunked(chunkSize)
        .joinToString(",") { it.toByteArray().hexString.uppercase() }
}

/**
 * Result of a trace operation. Ported from `TraceResult` (`MC1/Views/Tools/TracePath`). Unlike
 * [TraceHop]/[com.meshcoretwo.android.pathediting.PathHop], Swift's `Identifiable` [id] is kept —
 * `TracePathViewModel.savePath`'s batch path skips re-saving whichever completed result is the
 * one already recorded as the saved path's initial run, and needs a stable identity for that (not
 * just list-display identity): two distinct failed runs can otherwise carry equal field values.
 */
data class TraceResult(
    val id: UUID = UUID.randomUUID(),
    val hops: List<TraceHop>,
    val durationMs: Int,
    val success: Boolean,
    val errorMessage: String?,
    /** Path that was actually traced. */
    val tracedPathBytes: ByteArray,
    /** Bytes per hop (1, 2, or 4). */
    val hashSize: Int,
) {
    /** Comma-separated path string for display/copy, chunked by [hashSize]. */
    val tracedPathString: String get() = chunkedHexString(tracedPathBytes, hashSize)

    // ByteArray has reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TraceResult) return false
        return id == other.id &&
            hops == other.hops &&
            durationMs == other.durationMs &&
            success == other.success &&
            errorMessage == other.errorMessage &&
            tracedPathBytes.contentEquals(other.tracedPathBytes) &&
            hashSize == other.hashSize
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + hops.hashCode()
        result = 31 * result + durationMs
        result = 31 * result + success.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + tracedPathBytes.contentHashCode()
        result = 31 * result + hashSize
        return result
    }

    companion object {
        fun timeout(attemptedPath: ByteArray, hashSize: Int): TraceResult = TraceResult(
            hops = emptyList(),
            durationMs = 0,
            success = false,
            errorMessage = TracePathStrings.ERROR_NO_RESPONSE,
            tracedPathBytes = attemptedPath,
            hashSize = hashSize,
        )

        fun sendFailed(message: String, attemptedPath: ByteArray, hashSize: Int): TraceResult = TraceResult(
            hops = emptyList(),
            durationMs = 0,
            success = false,
            errorMessage = message,
            tracedPathBytes = attemptedPath,
            hashSize = hashSize,
        )
    }
}

/**
 * Plain-English copy for Trace Path, standing in for Swift's `L10n.Contacts.Contacts.Trace.*` —
 * this port has no string-resource layer yet, same as [com.meshcoretwo.android.pathediting.CodeInputResult].
 */
object TracePathStrings {
    const val ERROR_NO_RESPONSE = "No response received"
    const val ERROR_SEND_FAILED = "Failed to send trace packet"
    fun errorAllFailed(batchSize: Int) = "All $batchSize traces failed"
    const val MY_DEVICE = "My Device"
}
