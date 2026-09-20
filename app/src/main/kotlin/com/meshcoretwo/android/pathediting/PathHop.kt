// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

import com.meshcoretwo.protocol.hexString

/**
 * A single hop in a path being built — the trace path builder today, and (once ported) the
 * contact/room manual path editor. Ported from `PathHop` (`PathManagementViewModel.swift`),
 * shared by both surfaces on iOS via the same `PathEditing` module this package mirrors.
 *
 * Swift's `PathHop` is `Identifiable` via a random per-instance `UUID`, used only for SwiftUI
 * list identity. Nothing in this port needs that yet — the future hop-picker UI can key a list by
 * index or [hashHex] — so it's dropped rather than carried as dead weight.
 */
data class PathHop(
    /** Public-key prefix bytes (1-4 bytes depending on hash mode). */
    val hashBytes: ByteArray,
    /** Full 32-byte key when known, for unambiguous matching. */
    val publicKey: ByteArray? = null,
    /** Contact/discovered-node name if resolved, `null` if unknown. */
    val resolvedName: String? = null,
) {
    val hashHex: String get() = hashBytes.hexString.uppercase()

    val displayText: String get() = resolvedName?.let { "$it ($hashHex)" } ?: hashHex

    // ByteArray fields have reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PathHop) return false
        val publicKeysEqual = when {
            publicKey == null || other.publicKey == null -> publicKey == null && other.publicKey == null
            else -> publicKey.contentEquals(other.publicKey)
        }
        return hashBytes.contentEquals(other.hashBytes) && publicKeysEqual && resolvedName == other.resolvedName
    }

    override fun hashCode(): Int {
        var result = hashBytes.contentHashCode()
        result = 31 * result + (publicKey?.contentHashCode() ?: 0)
        result = 31 * result + (resolvedName?.hashCode() ?: 0)
        return result
    }
}
