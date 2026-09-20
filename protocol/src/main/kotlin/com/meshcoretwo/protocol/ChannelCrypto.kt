// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic operations for MeshCore channel messages.
 *
 * Channel messages use AES-128 ECB encryption with HMAC-SHA256 authentication in an
 * Encrypt-then-MAC pattern.
 */
object ChannelCrypto {
    /** Size of the truncated HMAC (2 bytes). */
    const val MAC_SIZE = 2

    /** Size of the AES key (16 bytes for AES-128). */
    const val KEY_SIZE = 16

    /** Size of the timestamp in decrypted payload (4 bytes). */
    const val TIMESTAMP_SIZE = 4

    /** Size of the txt_type field in decrypted payload (1 byte). */
    const val TXT_TYPE_SIZE = 1

    /** Total header size before message text: timestamp (4) + txt_type (1) = 5 bytes. */
    const val PLAINTEXT_HEADER_SIZE = TIMESTAMP_SIZE + TXT_TYPE_SIZE

    /** Result of attempting to decrypt a channel message. */
    sealed class DecryptResult {
        /**
         * Successfully decrypted the message.
         *
         * @param timestamp 4-byte sender timestamp.
         * @param txtType Message type indicator (0 = normal text, 1 = command, 2 = signed).
         * @param text The decrypted message text.
         */
        data class Success(val timestamp: UInt, val txtType: UByte, val text: String) : DecryptResult()

        /** HMAC verification failed. */
        data object HmacFailed : DecryptResult()

        /** Decryption failed (invalid padding or data). */
        data object DecryptFailed : DecryptResult()

        /** Payload too short to contain required fields. */
        data object PayloadTooShort : DecryptResult()
    }

    /**
     * Decrypts a channel message payload.
     *
     * @param payload The channel message payload (after channel index byte). Format:
     *   `[MAC: 2B] [ciphertext: N bytes]`.
     * @param secret The 16-byte channel secret.
     * @return Decryption result with message text or failure reason.
     */
    fun decrypt(payload: ByteArray, secret: ByteArray): DecryptResult {
        // Minimum: 2 bytes MAC + 16 bytes (1 AES block for timestamp + some text)
        if (payload.size < MAC_SIZE + 16) {
            return DecryptResult.PayloadTooShort
        }

        val receivedMac = payload.copyOfRange(0, MAC_SIZE)
        val ciphertext = payload.copyOfRange(MAC_SIZE, payload.size)

        // Verify HMAC-SHA256 (truncated to 2 bytes)
        val computedMac = computeHMAC(data = ciphertext, key = secret)
        if (!receivedMac.contentEquals(computedMac)) {
            return DecryptResult.HmacFailed
        }

        // Decrypt using AES-128 ECB
        val plaintext = decryptAES128ECB(ciphertext = ciphertext, key = secret) ?: return DecryptResult.DecryptFailed

        // Parse decrypted payload: [timestamp: 4B] [txt_type: 1B] [message: rest]
        if (plaintext.size < PLAINTEXT_HEADER_SIZE) {
            return DecryptResult.DecryptFailed
        }

        val timestamp = plaintext.readUInt32LE(0)
        val txtType = plaintext[TIMESTAMP_SIZE].toUByte()

        // Extract message text, trimming null padding
        val messageData = plaintext.copyOfRange(PLAINTEXT_HEADER_SIZE, plaintext.size)
        val nullIdx = messageData.indexOf(0).let { if (it < 0) messageData.size else it }
        val trimmedData = messageData.copyOfRange(0, nullIdx)

        val text = trimmedData.decodeUtf8Strict() ?: return DecryptResult.DecryptFailed

        return DecryptResult.Success(timestamp = timestamp, txtType = txtType, text = text)
    }

    /** Computes truncated HMAC-SHA256. */
    private fun computeHMAC(data: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data).copyOf(MAC_SIZE)
    }

    /**
     * Decrypts data using AES-128 ECB mode.
     *
     * Firmware stores the channel secret as a 32-byte buffer: the first 16 bytes key AES-128,
     * while all 32 bytes key the HMAC. Accept any secret of at least the AES key length and use
     * its first 16 bytes, mirroring [DirectMessageCrypto].
     */
    private fun decryptAES128ECB(ciphertext: ByteArray, key: ByteArray): ByteArray? {
        if (key.size < KEY_SIZE) return null
        if (ciphertext.size % KEY_SIZE != 0) return null

        return try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(KEY_SIZE), "AES"))
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            null
        }
    }
}
