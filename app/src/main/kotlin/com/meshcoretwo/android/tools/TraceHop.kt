// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.rendering.SNRQuality

/**
 * A single hop in a trace result. Ported from `TraceHop` (`MC1/Views/Tools/TracePath`). Swift's
 * `TraceHop` is `Identifiable` via a random per-instance `UUID`, used only for SwiftUI list
 * identity — dropped here as dead weight until the future hop-picker/result-list UI needs it,
 * same call [com.meshcoretwo.android.pathediting.PathHop] already made.
 */
data class TraceHop(
    /** `null` for the start/end node (local device). */
    val hashBytes: ByteArray?,
    /** From contacts lookup. */
    val resolvedName: String?,
    val snr: Double,
    val isStartNode: Boolean,
    val isEndNode: Boolean,
    val latitude: Double?,
    val longitude: Double?,
) {
    /** Display string for hash (shows all bytes). */
    val hashDisplayString: String? get() = hashBytes?.hexString?.uppercase()

    /**
     * Whether this hop has a valid (non-zero) location. Uses OR logic to match
     * `ContactDto.hasLocation` — if either coordinate is non-zero, we have some location data.
     * (0,0) is "Null Island" and extremely unlikely.
     */
    val hasLocation: Boolean get() = latitude != null && longitude != null && (latitude != 0.0 || longitude != 0.0)

    val snrQuality: SNRQuality get() = SNRQuality.of(snr)

    // ByteArray has reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TraceHop) return false
        val hashesEqual = when {
            hashBytes == null || other.hashBytes == null -> hashBytes == null && other.hashBytes == null
            else -> hashBytes.contentEquals(other.hashBytes)
        }
        return hashesEqual &&
            resolvedName == other.resolvedName &&
            snr == other.snr &&
            isStartNode == other.isStartNode &&
            isEndNode == other.isEndNode &&
            latitude == other.latitude &&
            longitude == other.longitude
    }

    override fun hashCode(): Int {
        var result = hashBytes?.contentHashCode() ?: 0
        result = 31 * result + (resolvedName?.hashCode() ?: 0)
        result = 31 * result + snr.hashCode()
        result = 31 * result + isStartNode.hashCode()
        result = 31 * result + isEndNode.hashCode()
        result = 31 * result + (latitude?.hashCode() ?: 0)
        result = 31 * result + (longitude?.hashCode() ?: 0)
        return result
    }
}
