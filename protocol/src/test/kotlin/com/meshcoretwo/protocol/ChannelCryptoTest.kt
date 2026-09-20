// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ChannelCryptoTest {
    /** Test channel secret (16 bytes). */
    private val testSecret = byteArrayOf(
        0x8B.toByte(), 0x33, 0x87.toByte(), 0xE9.toByte(), 0xC5.toByte(), 0xCD.toByte(), 0xEA.toByte(), 0x6A,
        0xC9.toByte(), 0xE5.toByte(), 0xED.toByte(), 0xBA.toByte(), 0xA1.toByte(), 0x15, 0xCD.toByte(), 0x72,
    )

    // MARK: - Helper: Encrypt for testing

    /** Encrypts data using AES-128 ECB (for creating test vectors). */
    private fun encryptAES128ECB(plaintext: ByteArray, key: ByteArray): ByteArray? {
        if (key.size != 16) return null
        val padded = plaintext.paddedOrTruncated(((plaintext.size + 15) / 16) * 16)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(padded)
    }

    /** Computes truncated HMAC-SHA256 (2 bytes). */
    private fun computeMAC(data: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data).copyOf(ChannelCrypto.MAC_SIZE)
    }

    /** Creates an encrypted channel payload for testing. */
    private fun createEncryptedPayload(timestamp: UInt, txtType: UByte = 0u, message: String, secret: ByteArray): ByteArray? {
        // Build plaintext: [timestamp: 4B] [txt_type: 1B] [message bytes]
        var plaintext = timestamp.toLittleEndianBytes()
        plaintext += txtType.toByte()
        plaintext += message.toByteArray(Charsets.UTF_8)

        val ciphertext = encryptAES128ECB(plaintext, secret) ?: return null
        val mac = computeMAC(ciphertext, secret)
        return mac + ciphertext
    }

    // MARK: - Tests

    @Test
    fun `Decrypt success`() {
        val message = "Alice: Hello mesh!"
        val timestamp = 1_703_123_456u
        val txtType: UByte = 0u

        val payload = createEncryptedPayload(timestamp, txtType, message, testSecret)
            ?: run { fail("Failed to create test payload"); return }

        val result = ChannelCrypto.decrypt(payload, testSecret)
        val success = result as? ChannelCrypto.DecryptResult.Success ?: run {
            fail("Expected Success, got $result")
            return
        }
        assertEquals(timestamp, success.timestamp)
        assertEquals(txtType, success.txtType)
        assertEquals(message, success.text)
    }

    @Test
    fun `Decrypt wrong key`() {
        val message = "Bob: Secret message"
        val timestamp = 1_703_123_456u

        val payload = createEncryptedPayload(timestamp, message = message, secret = testSecret)
            ?: run { fail("Failed to create test payload"); return }

        val wrongKey = byteArrayOf(
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
        )

        val result = ChannelCrypto.decrypt(payload, wrongKey)
        assertEquals(ChannelCrypto.DecryptResult.HmacFailed, result)
    }

    @Test
    fun `Decrypt corrupted MAC`() {
        val message = "Test message"
        val timestamp = 1_703_123_456u

        val payload = createEncryptedPayload(timestamp, message = message, secret = testSecret)
            ?: run { fail("Failed to create test payload"); return }

        payload[0] = (payload[0].toInt() xor 0xFF).toByte()
        payload[1] = (payload[1].toInt() xor 0xFF).toByte()

        val result = ChannelCrypto.decrypt(payload, testSecret)
        assertEquals(ChannelCrypto.DecryptResult.HmacFailed, result)
    }

    @Test
    fun `Decrypt payload too short`() {
        val shortPayload = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val result = ChannelCrypto.decrypt(shortPayload, testSecret)
        assertEquals(ChannelCrypto.DecryptResult.PayloadTooShort, result)
    }

    @Test
    fun `Decrypt empty message`() {
        val message = ""
        val timestamp = 0u
        val txtType: UByte = 0u

        val payload = createEncryptedPayload(timestamp, txtType, message, testSecret)
            ?: run { fail("Failed to create test payload"); return }

        val result = ChannelCrypto.decrypt(payload, testSecret)
        val success = result as? ChannelCrypto.DecryptResult.Success ?: run {
            fail("Expected Success for empty message, got $result")
            return
        }
        assertEquals(timestamp, success.timestamp)
        assertEquals(txtType, success.txtType)
        assertEquals(message, success.text)
    }

    @Test
    fun `Decrypt long message`() {
        val message = "This is a longer message that will definitely span multiple AES blocks for encryption testing"
        val timestamp = 1_703_123_456u
        val txtType: UByte = 0u

        val payload = createEncryptedPayload(timestamp, txtType, message, testSecret)
            ?: run { fail("Failed to create test payload"); return }

        val result = ChannelCrypto.decrypt(payload, testSecret)
        val success = result as? ChannelCrypto.DecryptResult.Success ?: run {
            fail("Expected Success for long message, got $result")
            return
        }
        assertEquals(timestamp, success.timestamp)
        assertEquals(txtType, success.txtType)
        assertEquals(message, success.text)
    }

    @Test
    fun `Decrypt unicode message`() {
        val message = "Hello! 你好! 🌍"
        val timestamp = 1_703_123_456u
        val txtType: UByte = 0u

        val payload = createEncryptedPayload(timestamp, txtType, message, testSecret)
            ?: run { fail("Failed to create test payload"); return }

        val result = ChannelCrypto.decrypt(payload, testSecret)
        val success = result as? ChannelCrypto.DecryptResult.Success ?: run {
            fail("Expected Success for unicode message, got $result")
            return
        }
        assertEquals(timestamp, success.timestamp)
        assertEquals(txtType, success.txtType)
        assertEquals(message, success.text)
    }

    @Test
    fun constants() {
        assertEquals(2, ChannelCrypto.MAC_SIZE)
        assertEquals(16, ChannelCrypto.KEY_SIZE)
        assertEquals(4, ChannelCrypto.TIMESTAMP_SIZE)
        assertEquals(1, ChannelCrypto.TXT_TYPE_SIZE)
        assertEquals(5, ChannelCrypto.PLAINTEXT_HEADER_SIZE)
    }

    @Test
    fun `Decrypt with different txtTypes`() {
        val message = "Test message"
        val timestamp = 1_703_123_456u

        for (txtType in listOf<UByte>(0u, 1u, 2u)) {
            val payload = createEncryptedPayload(timestamp, txtType, message, testSecret)
            if (payload == null) {
                fail("Failed to create test payload for txtType $txtType")
                continue
            }

            val result = ChannelCrypto.decrypt(payload, testSecret)
            val success = result as? ChannelCrypto.DecryptResult.Success ?: run {
                fail("Expected Success for txtType $txtType, got $result")
                return
            }
            assertEquals(timestamp, success.timestamp)
            assertEquals(txtType, success.txtType)
            assertEquals(message, success.text)
        }
    }

    @Test
    fun `Decrypt with 32-byte secret uses first 16 bytes for AES`() {
        val message = "Carol: 256-bit key channel"
        val timestamp = 1_703_123_456u
        val txtType: UByte = 0u

        // 32-byte secret whose first 16 bytes match the canonical 16-byte testSecret.
        // Firmware keys AES-128 with the first 16 bytes and HMAC-SHA256 with all 32.
        val secret32 = testSecret + byteArrayOf(
            0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80.toByte(),
            0x90.toByte(), 0xA0.toByte(), 0xB0.toByte(), 0xC0.toByte(), 0xD0.toByte(), 0xE0.toByte(), 0xF0.toByte(), 0x00,
        )

        var plaintext = timestamp.toLittleEndianBytes()
        plaintext += txtType.toByte()
        plaintext += message.toByteArray(Charsets.UTF_8)

        // Encrypt with the 16-byte AES key (the first 16 bytes of the secret).
        val ciphertext = encryptAES128ECB(plaintext, testSecret) ?: run { fail("Failed to encrypt test payload"); return }

        // MAC over ciphertext, keyed with the full 32-byte secret.
        val mac = computeMAC(ciphertext, secret32)
        val payload = mac + ciphertext

        val result = ChannelCrypto.decrypt(payload, secret32)
        val success = result as? ChannelCrypto.DecryptResult.Success ?: run {
            fail("Expected Success for 32-byte secret, got $result")
            return
        }
        assertEquals(timestamp, success.timestamp)
        assertEquals(txtType, success.txtType)
        assertEquals(message, success.text)
    }
}
