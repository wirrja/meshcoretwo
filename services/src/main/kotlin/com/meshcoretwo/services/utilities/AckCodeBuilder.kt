// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

import com.meshcoretwo.protocol.toLittleEndianBytes
import java.security.MessageDigest

/**
 * Computes the expected ACK CRC for an outgoing direct message, mirroring firmware
 * `BaseChatMesh::sendMessage`'s derivation:
 * `sha256(LE32(timestamp) || byte(attempt & 0x03) || text || pubkey)[0..3]`. Ported from
 * `AckCodeBuilder.swift`.
 *
 * Used to populate `pendingAcks` *before* the send round-trip so the persistent ACK listener
 * cannot race ahead of `trackPendingAck`. See the Swift original's doc comment for the full
 * firmware-contract invariants (recipient not hashed, attempt masked to two bits) this depends on.
 */
object AckCodeBuilder {
    private const val ATTEMPT_MASK = 0x03
    private const val ACK_CODE_BYTE_COUNT = 4

    fun expectedAck(timestamp: UInt, attempt: UByte, text: String, senderPublicKey: ByteArray): ByteArray {
        require(attempt < 5u) {
            "MessageServiceConfig caps maxAttempts at 5 (4 direct + 1 flood); attempt $attempt exceeds " +
                "the index range and would over-wrap the & 0x03 ACK mask"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(timestamp.toLittleEndianBytes())
        digest.update((attempt.toInt() and ATTEMPT_MASK).toByte())
        digest.update(text.toByteArray(Charsets.UTF_8))
        digest.update(senderPublicKey)
        return digest.digest().copyOf(ACK_CODE_BYTE_COUNT)
    }
}
