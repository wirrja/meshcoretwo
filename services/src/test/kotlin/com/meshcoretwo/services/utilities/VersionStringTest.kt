// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionStringTest {
    @Test
    fun `isAtLeastVersion accepts plain and v-prefixed forms`() {
        assertTrue("1.15".isAtLeastVersion(major = 1, minor = 15))
        assertTrue("v1.15".isAtLeastVersion(major = 1, minor = 15))
        assertTrue("v1.15.0".isAtLeastVersion(major = 1, minor = 15))
        assertTrue("v1.16.0".isAtLeastVersion(major = 1, minor = 15))
        assertFalse("v1.14.9".isAtLeastVersion(major = 1, minor = 15))
    }

    @Test
    fun `isAtLeastVersion parses a CLI ver banner without mistaking the date for the version`() {
        assertTrue("MeshCore v1.15.0 (2025-04-18)".isAtLeastVersion(major = 1, minor = 15))
        assertFalse("MeshCore v1.14.1 (2025-01-02)".isAtLeastVersion(major = 1, minor = 15))
    }

    @Test
    fun `isAtLeastVersion compares major before minor`() {
        assertTrue("v2.0.0".isAtLeastVersion(major = 1, minor = 15))
        assertFalse("v0.20.0".isAtLeastVersion(major = 1, minor = 15))
    }

    @Test
    fun `isAtLeastVersion is false for unparseable input`() {
        assertFalse("".isAtLeastVersion(major = 1, minor = 15))
        assertFalse("unknown".isAtLeastVersion(major = 1, minor = 15))
    }
}
