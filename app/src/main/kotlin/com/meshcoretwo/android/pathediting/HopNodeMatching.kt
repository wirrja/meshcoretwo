// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.pathediting

import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.persistence.ContactDto
import java.text.Normalizer
import java.util.Locale

/**
 * Query matching for the shared Add-Hop picker, kept as free functions so both the picker and the
 * view models feeding it can reach them without coupling to a concrete view model. Ported from
 * `HopNodeMatching`, narrowed from the Swift `PickerNode` union to [ContactDto] directly — this
 * port has no "Discover" list (nodes heard but not yet added as contacts; see
 * `AdvertisementService`'s class doc), so every node the picker can show is already a contact, and
 * a wrapper union type would carry no second case.
 */
object HopNodeMatching {
    /** True when [query] is non-empty and every character is a hex digit. */
    fun isHexQuery(query: String): Boolean = query.isNotEmpty() && query.all { Character.digit(it, 16) >= 0 }

    /**
     * True if [query] is empty, matches [ContactDto.displayName] as a substring, or (for a hex
     * query) prefixes the public-key hex. Name matching folds case and diacritics via NFD
     * normalization + combining-mark stripping — the JVM has no direct equivalent of Swift's
     * `[.caseInsensitive, .diacriticInsensitive]` string range option, so this is the standard
     * Java idiom for the same fold.
     */
    fun matches(node: ContactDto, query: String): Boolean {
        if (query.isEmpty()) return true
        val nameHit = fold(node.displayName).contains(fold(query))
        if (isHexQuery(query)) {
            return nameHit || node.publicKey.hexString.lowercase().startsWith(query.lowercase())
        }
        return nameHit
    }

    /** Filters [nodes] to those matching [query], preserving order. */
    fun filtered(nodes: List<ContactDto>, query: String): List<ContactDto> {
        if (query.isEmpty()) return nodes
        return nodes.filter { matches(it, query) }
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")
            .lowercase(Locale.ROOT)

    private val DIACRITICS_REGEX = Regex("\\p{Mn}+")
}
