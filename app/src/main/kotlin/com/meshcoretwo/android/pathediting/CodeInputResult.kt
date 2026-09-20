// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

/**
 * Result of parsing and adding repeater codes. Ported from `CodeInputResult`.
 * Swift's `errorMessage` isn't ported: nothing here surfaces it — the bulk-add preview shows the
 * per-code outcome ([HopCodeStatus]) instead.
 */
data class CodeInputResult(
    val added: MutableList<String> = mutableListOf(),
    val notFound: MutableList<String> = mutableListOf(),
    val alreadyInPath: MutableList<String> = mutableListOf(),
    val invalidFormat: MutableList<String> = mutableListOf(),
) {
    val hasErrors: Boolean get() = notFound.isNotEmpty() || alreadyInPath.isNotEmpty() || invalidFormat.isNotEmpty()
}
