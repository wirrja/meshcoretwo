// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.settings

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeyGenerationServiceTest {
    @Test
    fun `generateIdentity without a prefix returns a validly clamped identity`() = runTest {
        val identity = KeyGenerationService.generateIdentity(null)

        assertEquals(64, identity.expandedPrivateKey.size)
        assertEquals(32, identity.publicKey.size)
        KeyGenerationService.validateExpandedKey(identity.expandedPrivateKey) // does not throw

        val firstByte = identity.publicKey[0].toInt() and 0xFF
        assertTrue(firstByte != 0x00 && firstByte != 0xFF)
    }

    @Test
    fun `generateIdentity matches a short vanity prefix`() = runTest {
        val identity = KeyGenerationService.generateIdentity("0")
        val hex = identity.publicKey.joinToString("") { "%02X".format(it) }
        assertTrue(hex.startsWith("0"))
    }

    @Test
    fun `generateIdentity rejects a reserved prefix`() = runTest {
        try {
            KeyGenerationService.generateIdentity("00")
            fail("expected KeyGenerationError.ReservedPrefix")
        } catch (error: KeyGenerationService.KeyGenerationError.ReservedPrefix) {
            // expected
        }
        try {
            KeyGenerationService.generateIdentity("FF1")
            fail("expected KeyGenerationError.ReservedPrefix")
        } catch (error: KeyGenerationService.KeyGenerationError.ReservedPrefix) {
            // expected
        }
    }

    @Test
    fun `validateExpandedKey rejects wrong length`() {
        assertThrows(KeyGenerationService.KeyGenerationError.InvalidKey::class.java) {
            KeyGenerationService.validateExpandedKey(ByteArray(32))
        }
    }

    @Test
    fun `validateExpandedKey rejects unclamped bytes`() {
        val unclamped = ByteArray(64) { 0xFF.toByte() }
        assertThrows(KeyGenerationService.KeyGenerationError.InvalidKey::class.java) {
            KeyGenerationService.validateExpandedKey(unclamped)
        }
    }

    @Test
    fun `validateExpandedKey accepts a freshly generated key`() = runTest {
        val identity = KeyGenerationService.generateIdentity(null)
        KeyGenerationService.validateExpandedKey(identity.expandedPrivateKey) // does not throw
    }
}
