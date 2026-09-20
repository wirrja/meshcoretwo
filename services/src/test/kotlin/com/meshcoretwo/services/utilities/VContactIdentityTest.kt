// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

import com.meshcoretwo.protocol.decodeHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Port of VContactIdentityTests.swift, including its golden SHA256 test vectors. */
class VContactIdentityTest {
    /** Self key: 0x01 followed by 31 zero bytes. */
    private val selfKeyA = byteArrayOf(0x01) + ByteArray(31)

    /** Self key: 0xAB repeated 32 times. */
    private val selfKeyB = ByteArray(32) { 0xAB.toByte() }

    /** SHA256("zc-vcontact" || selfKeyA) */
    private val expectedVA = "a987552bd0518c37b4366e1cfb6df735a2e6d4c913385c32f5ea45980b459a73".decodeHex()!!

    /** SHA256("zc-vcontact" || selfKeyB) */
    private val expectedVB = "b02d900beb616f0ed4ff090cdce125630b8149f423bd7498442d66c4ca7bc083".decodeHex()!!

    @Test
    fun `salt is eleven bytes without null terminator`() {
        assertEquals(11, VContactIdentity.SALT.size)
        assertArrayEquals("zc-vcontact".toByteArray(Charsets.UTF_8), VContactIdentity.SALT)
    }

    @Test
    fun `derives golden V-contact public key for known self key`() {
        val derived = VContactIdentity.publicKey(selfKeyA)!!
        assertArrayEquals(expectedVA, derived)
        assertEquals(32, derived.size)
    }

    @Test
    fun `different self keys produce different V-contact keys`() {
        val a = VContactIdentity.publicKey(selfKeyA)
        val b = VContactIdentity.publicKey(selfKeyB)
        assertArrayEquals(expectedVA, a)
        assertArrayEquals(expectedVB, b)
        assertFalse(a!!.contentEquals(b!!))
    }

    @Test
    fun `derivation matches manual SHA256 concatenation`() {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(VContactIdentity.SALT)
        digest.update(selfKeyA)
        val manual = digest.digest()
        assertArrayEquals(manual, VContactIdentity.publicKey(selfKeyA))
    }

    @Test
    fun `isVContact matches only derived key`() {
        assertTrue(VContactIdentity.isVContact(expectedVA, selfKeyA))
        assertFalse(VContactIdentity.isVContact(expectedVB, selfKeyA))
        assertFalse(VContactIdentity.isVContact(selfKeyA, selfKeyA))
    }

    @Test
    fun `invalid key lengths fail open as non-V`() {
        assertNull(VContactIdentity.publicKey(ByteArray(0)))
        assertNull(VContactIdentity.publicKey(ByteArray(16) { 1 }))
        assertFalse(VContactIdentity.isVContact(expectedVA, ByteArray(0)))
        assertFalse(VContactIdentity.isVContact(ByteArray(0), selfKeyA))
    }
}
