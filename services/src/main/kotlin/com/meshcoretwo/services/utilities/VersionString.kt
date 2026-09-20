// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

/**
 * True when this string's first `major.minor` token is at least the required version. Accepts
 * plain forms ("v1.12.0", "1.12") and CLI banners like "MeshCore v1.15.0 (2025-04-18)", where the
 * date in parentheses would otherwise be mistaken for the version. Ported from
 * `String.isAtLeast(major:minor:)` (`Device.swift`).
 */
fun String.isAtLeastVersion(major: Int, minor: Int): Boolean {
    val (actualMajor, actualMinor) = firstMajorMinorVersion() ?: return false
    if (actualMajor != major) return actualMajor > major
    return actualMinor >= minor
}

private fun String.firstMajorMinorVersion(): Pair<Int, Int>? {
    for (token in split(Regex("[^0-9.]+"))) {
        if (token.isEmpty()) continue
        val parts = token.split(".")
        if (parts.size < 2) continue
        val major = parts[0].toIntOrNull() ?: continue
        val minor = parts[1].toIntOrNull() ?: continue
        return major to minor
    }
    return null
}
