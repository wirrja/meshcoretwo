// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import com.google.crypto.tink.subtle.X25519
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic operations for MeshCore direct (peer-to-peer) messages.
 *
 * Direct messages use ECDH Curve25519 key exchange with AES-128 ECB encryption and
 * HMAC-SHA256 authentication in an Encrypt-then-MAC pattern.
 *
 * Packet format: `[destHash:1][srcHash:1][MAC:2][ciphertext:N]`
 * Decrypted payload: `[timestamp:4][typeAttempt:1][text:N]`
 */
object DirectMessageCrypto {
    /** Size of the truncated HMAC (2 bytes). */
    const val MAC_SIZE = 2

    /** Size of the header (destHash + srcHash = 2 bytes). */
    const val HEADER_SIZE = 2

    /** Size of the timestamp in decrypted payload (4 bytes). */
    const val TIMESTAMP_SIZE = 4

    /** Size of the typeAttempt field (1 byte). */
    const val TYPE_ATTEMPT_SIZE = 1

    /** Minimum ciphertext size (one AES block). */
    const val MIN_CIPHERTEXT_SIZE = 16

    /** Minimum packet size: header + mac + one AES block. */
    const val MIN_PACKET_SIZE = HEADER_SIZE + MAC_SIZE + MIN_CIPHERTEXT_SIZE

    /** Result of attempting to decrypt a direct message. */
    sealed class DecryptResult {
        /** Successfully decrypted the message. */
        data class Success(val timestamp: UInt, val typeAttempt: UByte, val text: String?) : DecryptResult()

        /** MAC verification failed (wrong key or corrupted packet). */
        data object MacMismatch : DecryptResult()

        /** Decryption failed (invalid ciphertext). */
        data object DecryptionFailed : DecryptResult()

        /** Payload too short or malformed. */
        data object InvalidPayload : DecryptResult()

        /** Key derivation failed (invalid key data). */
        data object KeyError : DecryptResult()
    }

    /**
     * Decrypts a direct message payload.
     *
     * @param payload Raw packet `[destHash:1][srcHash:1][MAC:2][ciphertext:N]`.
     * @param myPrivateKey Recipient's 32-byte Curve25519 private key.
     * @param senderPublicKey Sender's 32-byte Curve25519 public key.
     * @return [DecryptResult] with timestamp and text on success.
     */
    fun decrypt(payload: ByteArray, myPrivateKey: ByteArray, senderPublicKey: ByteArray): DecryptResult {
        // Validate payload size
        if (payload.size < MIN_PACKET_SIZE) {
            return DecryptResult.InvalidPayload
        }

        // Compute shared secret
        val sharedSecret = computeSharedSecret(myPrivateKey = myPrivateKey, theirPublicKey = senderPublicKey)
            ?: return DecryptResult.KeyError

        // Extract packet components
        val receivedMac = payload.copyOfRange(HEADER_SIZE, HEADER_SIZE + MAC_SIZE)
        val ciphertext = payload.copyOfRange(HEADER_SIZE + MAC_SIZE, payload.size)

        // Verify MAC over ciphertext only (per MeshCore spec and ChannelCrypto)
        val computedMac = computeHMAC(data = ciphertext, key = sharedSecret)
        if (!receivedMac.contentEquals(computedMac)) {
            return DecryptResult.MacMismatch
        }

        // Decrypt using AES-128 ECB with first 16 bytes of shared secret
        val plaintext = decryptAES128ECB(ciphertext = ciphertext, key = sharedSecret)
            ?: return DecryptResult.DecryptionFailed

        // Parse decrypted payload: [timestamp:4][typeAttempt:1][text:rest]
        if (plaintext.size < TIMESTAMP_SIZE + TYPE_ATTEMPT_SIZE) {
            return DecryptResult.DecryptionFailed
        }

        val timestamp = plaintext.readUInt32LE(0)
        val typeAttempt = plaintext[TIMESTAMP_SIZE].toUByte()

        // Extract message text, trimming null padding
        val messageData = plaintext.copyOfRange(TIMESTAMP_SIZE + TYPE_ATTEMPT_SIZE, plaintext.size)
        val nullIdx = messageData.indexOf(0).let { if (it < 0) messageData.size else it }
        val trimmedData = messageData.copyOfRange(0, nullIdx)

        val text: String? = if (trimmedData.isEmpty()) "" else trimmedData.decodeUtf8Strict()

        return DecryptResult.Success(timestamp = timestamp, typeAttempt = typeAttempt, text = text)
    }

    /**
     * Extracts only the senderTimestamp from a direct message.
     * Convenience wrapper around [decrypt] for RxLogService use case.
     *
     * @return Timestamp if decryption succeeds, `null` otherwise.
     */
    fun extractTimestamp(payload: ByteArray, myPrivateKey: ByteArray, senderPublicKey: ByteArray): UInt? {
        val result = decrypt(payload = payload, myPrivateKey = myPrivateKey, senderPublicKey = senderPublicKey)
        return (result as? DecryptResult.Success)?.timestamp
    }

    // MARK: - Private Helpers

    /**
     * Computes the ECDH shared secret using Curve25519.
     *
     * Uses Tink's pure-Java [X25519] rather than `java.security.spec.XECPrivateKeySpec`/`"XDH"`
     * (the JDK 11+ approach the JVM-only unit tests for this class originally ran against): that
     * API's classes aren't present in Android's bootclasspath below API 33
     * (`NoClassDefFoundError` on a real API-31 device crashed the app on every incoming DM — see
     * PLAN.md Фаза 33.9), while `minSdk` here is 26. Tink both clamps the private scalar and
     * decodes the raw little-endian public u-coordinate internally per RFC 7748, so no manual
     * `BigInteger` decoding is needed here either.
     */
    private fun computeSharedSecret(myPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray? {
        if (myPrivateKey.size != PacketBuilder.PUBLIC_KEY_SIZE || theirPublicKey.size != PacketBuilder.PUBLIC_KEY_SIZE) {
            return null
        }

        return try {
            X25519.computeSharedSecret(myPrivateKey, theirPublicKey)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Computes truncated HMAC-SHA256 (2 bytes). */
    private fun computeHMAC(data: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data).copyOf(MAC_SIZE)
    }

    /** Decrypts data using AES-128 ECB mode. */
    private fun decryptAES128ECB(ciphertext: ByteArray, key: ByteArray): ByteArray? {
        if (key.size < 16) return null
        if (ciphertext.size % 16 != 0) return null

        return try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(16), "AES"))
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            null
        }
    }
}
