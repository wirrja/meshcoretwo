// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.settings

/** Whether this region name represents a private region (pre-shared-key-gated). Ported from `String.isPrivateRegion`. */
val String.isPrivateRegion: Boolean get() = startsWith("$")

/**
 * Validates region names before adding them to a device's known-regions list. Ported from
 * `RegionNameValidator.swift`.
 */
object RegionNameValidator {
    sealed class ValidationError {
        object Empty : ValidationError()
        object InvalidCharacters : ValidationError()
        data class TooLong(val maxBytes: Int) : ValidationError()
        object Duplicate : ValidationError()
    }

    fun validate(name: String, existingRegions: List<String>): ValidationError? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ValidationError.Empty
        if (!trimmed.all { it.code < 128 && (it.isLetter() || it.isDigit() || it == '-') }) {
            return ValidationError.InvalidCharacters
        }
        val maxBytes = SettingsService.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES
        if (trimmed.toByteArray(Charsets.UTF_8).size > maxBytes) return ValidationError.TooLong(maxBytes)
        if (existingRegions.contains(trimmed)) return ValidationError.Duplicate
        return null
    }

    fun isValid(name: String, existingRegions: List<String>): Boolean = validate(name, existingRegions) == null
}
