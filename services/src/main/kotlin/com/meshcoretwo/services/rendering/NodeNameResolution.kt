// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rendering

/**
 * Whether a name resolution corresponds to an exact public-key match, a proximity fallback
 * within a hash-prefix collision set, or no match at all. Ported from `NodeNameMatchKind.swift`
 * (`MC1Services/Models/Rendering`).
 */
enum class NodeNameMatchKind {
    EXACT,
    FALLBACK,
    UNRESOLVED,
}

/**
 * Resolution result for a sender node name. Pairs the resolved display name with the confidence
 * level ([matchKind]) so the caller can disambiguate exact identity from proximity-based guesses.
 * Ported from `NodeNameResolution.swift` (`MC1Services/Models/Rendering`), minus
 * [unverifiedNickname] — Swift's channel-sender-by-nickname resolution path isn't ported yet, so
 * every conformer here always passes `null` for it; add it back only once that path exists.
 */
data class NodeNameResolution(
    val displayName: String,
    val matchKind: NodeNameMatchKind,
) {
    val isFallback: Boolean get() = matchKind == NodeNameMatchKind.FALLBACK
}
