// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.EdECPrivateKey
import java.security.interfaces.EdECPublicKey
import java.security.spec.NamedParameterSpec

/**
 * Cross-checks [Ed25519KeyDerivation] — this module hand-rolls the curve arithmetic rather than
 * calling a platform provider, so it needs an independent ground truth. Two sources:
 *
 * 1. Fixed seed/public-key pairs generated with Python's `cryptography` library (OpenSSL-backed).
 * 2. Randomized: the host JVM's own `KeyPairGenerator.getInstance("Ed25519")` (SunEC), the same
 *    provider [Ed25519ToX25519Test] already uses to generate real key pairs for its own DM-crypto
 *    round-trip test — reused here as an independent, in-repo oracle across many random seeds.
 */
class Ed25519KeyDerivationTest {
    @Test
    fun `derives known public keys from seed`() {
        val vectors = listOf(
            "0000000000000000000000000000000000000000000000000000000000000000" to
                "3b6a27bcceb6a42d62a3a8d02a6f0d73653215771de243a63ac048a18b59da29",
            "0101010101010101010101010101010101010101010101010101010101010101" to
                "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" to
                "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8",
        )

        for ((seedHex, expectedPublicKeyHex) in vectors) {
            val seed = seedHex.decodeHex()!!
            assertEquals(32, seed.size)
            val expected = expectedPublicKeyHex.decodeHex()!!
            assertEquals(32, expected.size)

            val expanded = Ed25519KeyDerivation.expandedPrivateKey(seed)
            assertEquals(64, expanded.size)
            val publicKey = Ed25519KeyDerivation.publicKey(expanded)
            assertTrue(publicKey.contentEquals(expected))
        }
    }

    @Test
    fun `matches the host JVM's own Ed25519 provider across random seeds`() {
        repeat(20) {
            val (seed, expectedPublicKey) = generateEd25519KeyPair()
            val expanded = Ed25519KeyDerivation.expandedPrivateKey(seed)
            val publicKey = Ed25519KeyDerivation.publicKey(expanded)
            assertTrue(publicKey.contentEquals(expectedPublicKey))
        }
    }

    @Test
    fun `clamps the scalar bytes of the expanded key`() {
        val expanded = Ed25519KeyDerivation.expandedPrivateKey(ByteArray(32) { it.toByte() })
        assertEquals(0, expanded[0].toInt() and 0x07)
        assertEquals(0, expanded[31].toInt() and 0x80)
        assertEquals(0x40, expanded[31].toInt() and 0x40)
    }

    @Test
    fun `different seeds produce different public keys`() {
        val a = Ed25519KeyDerivation.publicKey(Ed25519KeyDerivation.expandedPrivateKey(ByteArray(32) { 0x11 }))
        val b = Ed25519KeyDerivation.publicKey(Ed25519KeyDerivation.expandedPrivateKey(ByteArray(32) { 0x22 }))
        assertFalse(a.contentEquals(b))
    }

    /**
     * Generates a real Ed25519 key pair via the host JVM's own provider and returns (32-byte
     * seed, 32-byte compressed public key) in the standard RFC 8032 wire encodings — same
     * extraction [Ed25519ToX25519Test] uses for its own DM-crypto round-trip test (duplicated
     * rather than shared: that helper is private to a different, unrelated test class).
     */
    private fun generateEd25519KeyPair(): Pair<ByteArray, ByteArray> {
        val kp = KeyPairGenerator.getInstance(NamedParameterSpec.ED25519.name).generateKeyPair()
        val seed = (kp.private as EdECPrivateKey).bytes.get()
        val point = (kp.public as EdECPublicKey).point
        val encoded = uToLittleEndianBytes(point.y, 32)
        if (point.isXOdd) {
            encoded[31] = (encoded[31].toInt() or 0x80).toByte()
        }
        return seed to encoded
    }

    private fun uToLittleEndianBytes(value: BigInteger, length: Int): ByteArray {
        val bigEndian = value.toByteArray()
        val unsigned = if (bigEndian.size > 1 && bigEndian[0] == 0.toByte()) bigEndian.copyOfRange(1, bigEndian.size) else bigEndian
        val padded = ByteArray(length)
        val copyLen = minOf(unsigned.size, length)
        System.arraycopy(unsigned, unsigned.size - copyLen, padded, length - copyLen, copyLen)
        return padded.reversedArray()
    }
}
