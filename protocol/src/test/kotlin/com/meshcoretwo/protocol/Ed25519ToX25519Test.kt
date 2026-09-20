// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.EdECPrivateKey
import java.security.interfaces.EdECPublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Port of the two meaningful cases from Ed25519ToX25519Tests.swift. The Swift suite's first
 * test ("Public key conversion round-trip with CryptoKit") duplicates ECDH plumbing purely to
 * check both directions agree; that's exactly what "DM decrypt with converted Ed25519 keys"
 * below already proves by going through the real DirectMessageCrypto public API, so that's
 * ported instead as the single, more meaningful integration test.
 */
class Ed25519ToX25519Test {
    @Test
    fun `Conversion rejects invalid inputs`() {
        assertNull(Ed25519ToX25519.convertPublicKey(ByteArray(0)))
        assertNull(Ed25519ToX25519.convertPublicKey(ByteArray(16)))
    }

    @Test
    fun `DM decrypt with converted Ed25519 keys`() {
        // Simulate firmware: Ed25519 keys -> expanded scalars + converted public keys.
        val (senderSeed, senderEdPublic) = generateEd25519KeyPair()
        val (recipientSeed, recipientEdPublic) = generateEd25519KeyPair()

        val senderScalar = expandSeed(senderSeed)
        val recipientScalar = expandSeed(recipientSeed)

        val senderX25519Public = Ed25519ToX25519.convertPublicKey(senderEdPublic)
            ?: run { fail("convertPublicKey failed for sender"); return }
        val recipientX25519Public = Ed25519ToX25519.convertPublicKey(recipientEdPublic)
            ?: run { fail("convertPublicKey failed for recipient"); return }

        // Sender computes the shared secret (simulating firmware ECDH).
        val sharedSecret = computeSharedSecret(senderScalar, uFromLittleEndian(recipientX25519Public))

        val timestamp = 1_703_123_456u
        var plaintext = timestamp.toLittleEndianBytes()
        plaintext += 0 // typeAttempt
        plaintext += "Hello".toByteArray(Charsets.UTF_8)
        val padded = plaintext.paddedOrTruncated(((plaintext.size + 15) / 16) * 16)

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret.copyOf(16), "AES"))
        val ciphertext = cipher.doFinal(padded)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sharedSecret, "HmacSHA256"))
        val macBytes = mac.doFinal(ciphertext).copyOf(2)

        var packet = byteArrayOf(recipientX25519Public[0], senderX25519Public[0])
        packet += macBytes
        packet += ciphertext

        // Recipient decrypts with their scalar and the sender's converted public key.
        val result = DirectMessageCrypto.decrypt(
            payload = packet,
            myPrivateKey = recipientScalar,
            senderPublicKey = senderX25519Public,
        )

        val success = result as? DirectMessageCrypto.DecryptResult.Success ?: run {
            fail("Expected Success, got $result")
            return
        }
        assertEquals(timestamp, success.timestamp)
        assertEquals("Hello", success.text)
    }

    // MARK: - Helpers

    /**
     * Generates a real Ed25519 key pair and returns (32-byte seed, 32-byte compressed public
     * key) in the standard RFC 8032 wire encodings.
     */
    private fun generateEd25519KeyPair(): Pair<ByteArray, ByteArray> {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val seed = (kp.private as EdECPrivateKey).bytes.get()
        val point = (kp.public as EdECPublicKey).point
        val encoded = uToLittleEndianBytes(point.y, 32)
        if (point.isXOdd) {
            encoded[31] = (encoded[31].toInt() or 0x80).toByte()
        }
        return seed to encoded
    }

    /** Simulates the firmware's key expansion: SHA-512(seed), clamp first 32 bytes. */
    private fun expandSeed(seed: ByteArray): ByteArray {
        val hash = MessageDigest.getInstance("SHA-512").digest(seed)
        hash[0] = (hash[0].toInt() and 248).toByte()
        hash[31] = (hash[31].toInt() and 63).toByte()
        hash[31] = (hash[31].toInt() or 64).toByte()
        return hash.copyOf(32)
    }

    private fun computeSharedSecret(myPrivateKey: ByteArray, theirPublicKeyU: BigInteger): ByteArray {
        val keyFactory = KeyFactory.getInstance("XDH")
        val privateKey = keyFactory.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, myPrivateKey))
        val publicKey = keyFactory.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, theirPublicKeyU))
        val ka = KeyAgreement.getInstance("XDH")
        ka.init(privateKey)
        ka.doPhase(publicKey, true)
        return ka.generateSecret()
    }

    private fun uFromLittleEndian(bytes: ByteArray): BigInteger {
        val clamped = bytes.copyOf()
        clamped[31] = (clamped[31].toInt() and 0x7F).toByte()
        return BigInteger(1, clamped.reversedArray())
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
