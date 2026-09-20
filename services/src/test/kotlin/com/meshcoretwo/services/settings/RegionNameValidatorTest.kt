// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from `RegionNameValidatorTests.swift`. */
class RegionNameValidatorTest {
    @Test
    fun `blank or whitespace-only names are empty`() {
        assertEquals(RegionNameValidator.ValidationError.Empty, RegionNameValidator.validate("", emptyList()))
        assertEquals(RegionNameValidator.ValidationError.Empty, RegionNameValidator.validate("   ", emptyList()))
    }

    @Test
    fun `letters, digits, and hyphens are valid`() {
        assertNull(RegionNameValidator.validate("de-hh", emptyList()))
        assertNull(RegionNameValidator.validate("UK-London2", emptyList()))
        assertTrue(RegionNameValidator.isValid("region-1", emptyList()))
    }

    @Test
    fun `spaces, underscores, and non-ASCII characters are invalid`() {
        assertEquals(RegionNameValidator.ValidationError.InvalidCharacters, RegionNameValidator.validate("de hh", emptyList()))
        assertEquals(RegionNameValidator.ValidationError.InvalidCharacters, RegionNameValidator.validate("de_hh", emptyList()))
        assertEquals(RegionNameValidator.ValidationError.InvalidCharacters, RegionNameValidator.validate("münchen", emptyList()))
    }

    @Test
    fun `a leading dollar sign (private-region marker) is invalid for manual entry`() {
        assertEquals(RegionNameValidator.ValidationError.InvalidCharacters, RegionNameValidator.validate("\$secret", emptyList()))
    }

    @Test
    fun `names over 30 UTF-8 bytes are too long`() {
        val name = "a".repeat(31)
        assertEquals(RegionNameValidator.ValidationError.TooLong(30), RegionNameValidator.validate(name, emptyList()))
        assertNull(RegionNameValidator.validate("a".repeat(30), emptyList()))
    }

    @Test
    fun `names already in the existing list are duplicates`() {
        assertEquals(RegionNameValidator.ValidationError.Duplicate, RegionNameValidator.validate("de-hh", listOf("de-hh")))
        assertFalse(RegionNameValidator.isValid("de-hh", listOf("de-hh")))
    }

    @Test
    fun `leading and trailing whitespace is trimmed before validation`() {
        assertNull(RegionNameValidator.validate("  de-hh  ", emptyList()))
    }

    @Test
    fun `isPrivateRegion is true only for a leading dollar sign`() {
        assertTrue("\$secret".isPrivateRegion)
        assertFalse("secret".isPrivateRegion)
    }
}
