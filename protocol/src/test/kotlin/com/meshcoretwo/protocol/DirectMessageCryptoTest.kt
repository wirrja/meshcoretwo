// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class DirectMessageCryptoTest {
    /** A raw (private scalar, public u-coordinate) X25519 key pair, both 32-byte little-endian wire format. */
    private data class RawKeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    private fun generateX25519KeyPair(): RawKeyPair {
        val kpg = KeyPairGenerator.getInstance("XDH")
        kpg.initialize(NamedParameterSpec.X25519)
        val kp = kpg.generateKeyPair()
        val scalar = (kp.private as XECPrivateKey).scalar.get()
        val u = (kp.public as XECPublicKey).u
        return RawKeyPair(privateKey = scalar, publicKey = uToLittleEndianBytes(u))
    }

    private fun uToLittleEndianBytes(value: BigInteger, length: Int = 32): ByteArray {
        val bigEndian = value.toByteArray()
        val unsigned = if (bigEndian.size > 1 && bigEndian[0] == 0.toByte()) bigEndian.copyOfRange(1, bigEndian.size) else bigEndian
        val padded = ByteArray(length)
        val copyLen = minOf(unsigned.size, length)
        System.arraycopy(unsigned, unsigned.size - copyLen, padded, length - copyLen, copyLen)
        return padded.reversedArray()
    }

    /** Computes the shared secret directly (same algorithm DirectMessageCrypto uses internally), for building test fixtures. */
    private fun computeSharedSecret(myPrivateKey: ByteArray, theirPublicKeyU: BigInteger): ByteArray {
        val keyFactory = KeyFactory.getInstance("XDH")
        val privateKey = keyFactory.generatePrivate(java.security.spec.XECPrivateKeySpec(NamedParameterSpec.X25519, myPrivateKey))
        val publicKey = keyFactory.generatePublic(java.security.spec.XECPublicKeySpec(NamedParameterSpec.X25519, theirPublicKeyU))
        val ka = javax.crypto.KeyAgreement.getInstance("XDH")
        ka.init(privateKey)
        ka.doPhase(publicKey, true)
        return ka.generateSecret()
    }

    /** Encrypts AES-128-ECB (for creating test vectors). */
    private fun encryptAES128ECB(plaintext: ByteArray, key: ByteArray): ByteArray? {
        if (key.size < 16) return null
        val padded = plaintext.paddedOrTruncated(((plaintext.size + 15) / 16) * 16)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOf(16), "AES"))
        return cipher.doFinal(padded)
    }

    /** Computes truncated HMAC-SHA256 (2 bytes). */
    private fun computeMAC(data: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data).copyOf(2)
    }

    /**
     * Creates an encrypted direct message payload for testing.
     * Format: [destHash:1][srcHash:1][MAC:2][ciphertext:N]
     * Plaintext: [timestamp:4][typeAttempt:1][text:N]
     */
    private fun createEncryptedPayload(
        destHash: Byte,
        srcHash: Byte,
        timestamp: UInt,
        typeAttempt: UByte,
        message: String,
        sharedSecret: ByteArray,
    ): ByteArray? {
        var plaintext = timestamp.toLittleEndianBytes()
        plaintext += typeAttempt.toByte()
        plaintext += message.toByteArray(Charsets.UTF_8)

        val ciphertext = encryptAES128ECB(plaintext, sharedSecret) ?: return null
        val mac = computeMAC(ciphertext, sharedSecret)

        var packet = byteArrayOf(destHash, srcHash)
        packet += mac
        packet += ciphertext
        return packet
    }

    @Test
    fun `Decrypt success`() {
        val sender = generateX25519KeyPair()
        val recipient = generateX25519KeyPair()
        val sharedSecret = computeSharedSecret(recipient.privateKey, uFromLittleEndian(sender.publicKey))

        val timestamp = 1_703_123_456u
        val typeAttempt: UByte = 0u
        val message = "Hello from sender!"
        val destHash = recipient.publicKey[0]
        val srcHash = sender.publicKey[0]

        val payload = createEncryptedPayload(destHash, srcHash, timestamp, typeAttempt, message, sharedSecret)
            ?: run { fail("Failed to create test payload"); return }

        val result = DirectMessageCrypto.decrypt(payload, recipient.privateKey, sender.publicKey)
        val success = result as? DirectMessageCrypto.DecryptResult.Success ?: run {
            fail("Expected Success, got $result")
            return
        }
        assertEquals(timestamp, success.timestamp)
        assertEquals(typeAttempt, success.typeAttempt)
        assertEquals(message, success.text)
    }

    private fun uFromLittleEndian(bytes: ByteArray): BigInteger {
        val clamped = bytes.copyOf()
        clamped[31] = (clamped[31].toInt() and 0x7F).toByte()
        return BigInteger(1, clamped.reversedArray())
    }

    @Test
    fun `Decrypt wrong key`() {
        val sender = generateX25519KeyPair()
        val recipient = generateX25519KeyPair()
        val sharedSecret = computeSharedSecret(recipient.privateKey, uFromLittleEndian(sender.publicKey))

        val payload = createEncryptedPayload(0xAA.toByte(), 0xBB.toByte(), 1_703_123_456u, 0u, "Secret message", sharedSecret)
            ?: run { fail("Failed to create test payload"); return }

        val wrongKeyPair = generateX25519KeyPair()

        val result = DirectMessageCrypto.decrypt(payload, wrongKeyPair.privateKey, sender.publicKey)
        assertEquals(DirectMessageCrypto.DecryptResult.MacMismatch, result)
    }

    @Test
    fun `Decrypt corrupted MAC`() {
        val sender = generateX25519KeyPair()
        val recipient = generateX25519KeyPair()
        val sharedSecret = computeSharedSecret(recipient.privateKey, uFromLittleEndian(sender.publicKey))

        val payload = createEncryptedPayload(0xAA.toByte(), 0xBB.toByte(), 1_703_123_456u, 0u, "Test", sharedSecret)
            ?: run { fail("Failed to create test payload"); return }

        payload[2] = (payload[2].toInt() xor 0xFF).toByte()
        payload[3] = (payload[3].toInt() xor 0xFF).toByte()

        val result = DirectMessageCrypto.decrypt(payload, recipient.privateKey, sender.publicKey)
        assertEquals(DirectMessageCrypto.DecryptResult.MacMismatch, result)
    }

    @Test
    fun `Decrypt payload too short`() {
        val sender = generateX25519KeyPair()
        val recipient = generateX25519KeyPair()
        val shortPayload = byteArrayOf(0x00, 0x01, 0x02, 0x03)

        val result = DirectMessageCrypto.decrypt(shortPayload, recipient.privateKey, sender.publicKey)
        assertEquals(DirectMessageCrypto.DecryptResult.InvalidPayload, result)
    }

    @Test
    fun `Decrypt empty message`() {
        val sender = generateX25519KeyPair()
        val recipient = generateX25519KeyPair()
        val sharedSecret = computeSharedSecret(recipient.privateKey, uFromLittleEndian(sender.publicKey))

        val payload = createEncryptedPayload(0xAA.toByte(), 0xBB.toByte(), 0u, 0u, "", sharedSecret)
            ?: run { fail("Failed to create test payload"); return }

        val result = DirectMessageCrypto.decrypt(payload, recipient.privateKey, sender.publicKey)
        val success = result as? DirectMessageCrypto.DecryptResult.Success ?: run {
            fail("Expected Success, got $result")
            return
        }
        assertEquals(0u, success.timestamp)
        assertEquals(0u.toUByte(), success.typeAttempt)
        assertEquals("", success.text)
    }

    @Test
    fun `Decrypt unicode message`() {
        val sender = generateX25519KeyPair()
        val recipient = generateX25519KeyPair()
        val sharedSecret = computeSharedSecret(recipient.privateKey, uFromLittleEndian(sender.publicKey))

        val message = "Hello! 你好! 🌍"
        val payload = createEncryptedPayload(0xAA.toByte(), 0xBB.toByte(), 1_703_123_456u, 0u, message, sharedSecret)
            ?: run { fail("Failed to create test payload"); return }

        val result = DirectMessageCrypto.decrypt(payload, recipient.privateKey, sender.publicKey)
        val success = result as? DirectMessageCrypto.DecryptResult.Success ?: run {
            fail("Expected Success, got $result")
            return
        }
        assertEquals(message, success.text)
    }

    @Test
    fun `Extract timestamp`() {
        val sender = generateX25519KeyPair()
        val recipient = generateX25519KeyPair()
        val sharedSecret = computeSharedSecret(recipient.privateKey, uFromLittleEndian(sender.publicKey))

        val expectedTimestamp = 1_703_123_456u
        val payload = createEncryptedPayload(0xAA.toByte(), 0xBB.toByte(), expectedTimestamp, 0u, "Test", sharedSecret)
            ?: run { fail("Failed to create test payload"); return }

        val timestamp = DirectMessageCrypto.extractTimestamp(payload, recipient.privateKey, sender.publicKey)
        assertEquals(expectedTimestamp, timestamp)
    }

    @Test
    fun constants() {
        assertEquals(2, DirectMessageCrypto.MAC_SIZE)
        assertEquals(2, DirectMessageCrypto.HEADER_SIZE)
        assertEquals(4, DirectMessageCrypto.TIMESTAMP_SIZE)
        assertEquals(1, DirectMessageCrypto.TYPE_ATTEMPT_SIZE)
        assertEquals(16, DirectMessageCrypto.MIN_CIPHERTEXT_SIZE)
        assertEquals(20, DirectMessageCrypto.MIN_PACKET_SIZE)
    }

    @Test
    fun `Invalid key length`() {
        val payload = ByteArray(24)
        val result = DirectMessageCrypto.decrypt(payload, byteArrayOf(0x01, 0x02), ByteArray(32))
        assertEquals(DirectMessageCrypto.DecryptResult.KeyError, result)
    }
}
