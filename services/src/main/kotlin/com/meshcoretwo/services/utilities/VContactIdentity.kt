// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

import com.meshcoretwo.protocol.PacketBuilder
import java.security.MessageDigest

/**
 * Identifies ZephCore's loopback admin "V-contact" (chat CLI over BLE/USB). Ported from
 * `VContactIdentity.swift`.
 *
 * Firmware derives the public key as `SHA256("zc-vcontact" || self_pub_key)` with no private
 * key. The contact is a virtual GET_CONTACTS tail entry and is never a real contact-table slot.
 * Used for capacity bookkeeping and to disable remove paths that would `CMD_REMOVE` and turn the
 * firmware feature off.
 */
object VContactIdentity {
    /** Salt used by ZephCore `CompanionMesh::begin()` (11 bytes, no NUL terminator). */
    val SALT = "zc-vcontact".toByteArray(Charsets.UTF_8)

    /**
     * Derives the V-contact public key for a companion's own public key.
     *
     * @param selfPublicKey The radio's 32-byte Ed25519 public key.
     * @return The 32-byte derived key, or `null` if [selfPublicKey] is not 32 bytes.
     */
    fun publicKey(selfPublicKey: ByteArray): ByteArray? {
        if (selfPublicKey.size != PacketBuilder.PUBLIC_KEY_SIZE) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(SALT)
        digest.update(selfPublicKey)
        return digest.digest()
    }

    /**
     * Whether [publicKey] is the V-contact for [selfPublicKey].
     *
     * Fail-open: returns `false` if either key is not 32 bytes so real overwrite cleanup is
     * never skipped due to a missing identity.
     */
    fun isVContact(publicKey: ByteArray, selfPublicKey: ByteArray): Boolean {
        if (publicKey.size != PacketBuilder.PUBLIC_KEY_SIZE) return false
        val derived = publicKey(selfPublicKey) ?: return false
        return publicKey.contentEquals(derived)
    }
}
