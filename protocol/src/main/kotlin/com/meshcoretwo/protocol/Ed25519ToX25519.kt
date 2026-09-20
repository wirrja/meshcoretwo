// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.math.BigInteger

/**
 * Converts Ed25519 public keys (used by MeshCore firmware for node identity) to
 * Curve25519/X25519 public keys (used by [DirectMessageCrypto] for ECDH).
 *
 * Contact public keys stored in the database are 32-byte Ed25519 public keys.
 * [DirectMessageCrypto] expects 32-byte Curve25519 public keys for key agreement.
 *
 * Equivalent to libsodium's `crypto_sign_ed25519_pk_to_curve25519`.
 *
 * Swift hand-rolls 256-bit field arithmetic here (four `UInt64` limbs with manual carry
 * propagation) because Swift has no arbitrary-precision integer type. `java.math.BigInteger`
 * does the same modular arithmetic natively and is well-tested for exactly this, so the
 * birational map below — the actual algorithm — is the entire port; no limb-level
 * reimplementation needed.
 */
object Ed25519ToX25519 {
    /** p = 2^255 - 19 */
    private val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
    private val ONE: BigInteger = BigInteger.ONE

    /**
     * Converts an Ed25519 public key to a Curve25519 public key.
     *
     * Uses the birational map: `u = (1 + y) / (1 - y) mod p` where `p = 2^255 - 19`.
     *
     * @param ed25519PublicKey 32-byte Ed25519 compressed public key.
     * @return 32-byte Curve25519 public key, or `null` if the input is invalid.
     */
    fun convertPublicKey(ed25519PublicKey: ByteArray): ByteArray? {
        if (ed25519PublicKey.size != 32) return null

        val yBytes = ed25519PublicKey.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte() // Clear sign bit to get y-coordinate

        val y = littleEndianToBigInteger(yBytes)

        val numerator = ONE.add(y).mod(P)
        val denominator = ONE.subtract(y).mod(P)
        val denominatorInv = denominator.modInverse(P)
        val u = numerator.multiply(denominatorInv).mod(P)

        return bigIntegerToLittleEndian(u, 32)
    }

    private fun littleEndianToBigInteger(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun bigIntegerToLittleEndian(value: BigInteger, length: Int): ByteArray {
        val bigEndian = value.toByteArray() // two's-complement; may carry a leading zero sign byte
        val unsigned = if (bigEndian.size > 1 && bigEndian[0] == 0.toByte()) {
            bigEndian.copyOfRange(1, bigEndian.size)
        } else {
            bigEndian
        }
        val padded = ByteArray(length)
        val copyLen = minOf(unsigned.size, length)
        System.arraycopy(unsigned, unsigned.size - copyLen, padded, length - copyLen, copyLen)
        return padded.reversedArray()
    }
}
