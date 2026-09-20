// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Derives Ed25519 identity key pairs from a 32-byte seed, for "Regenerate Identity"/"Import Key"
 * (`DeviceIdentitySection.swift`). Hand-rolled twisted-Edwards curve point arithmetic (extended
 * coordinates, the standard "dbl-2008-hwcd"/"add-2008-hwcd-3" formulas) rather than a platform
 * crypto provider: there's no portable `java.security` API to derive a public key from a
 * caller-supplied seed, and Conscrypt's own Ed25519 support only reaches back a few Android
 * versions past this project's `minSdk 26` — the same platform-support gap [DirectMessageCrypto]
 * already accepts for X25519 ECDH, but that one at least degrades to a caught exception; getting a
 * *wrong* generated key silently imported onto real hardware is a worse failure mode, so this
 * avoids the platform curve APIs entirely. Field/point arithmetic is `BigInteger`-based, matching
 * [Ed25519ToX25519]'s existing style in this module — simpler to get right than limb-based
 * arithmetic, at the cost of being too slow for anything beyond occasional, user-initiated,
 * cancellable key generation (see `KeyGenerationService` in the services module).
 *
 * Ported from the derivation CryptoKit's `Curve25519.Signing.PrivateKey(rawRepresentation:)`
 * performs internally (RFC 8032 §5.1.5): SHA-512(seed), clamp, treat the low 32 bytes as a scalar,
 * multiply the Ed25519 base point by it, and compress. Field constants (`P`, `D`, the base point)
 * and the point-addition/doubling formulas were cross-checked against Python's `cryptography`
 * library (OpenSSL-backed) on several seed/public-key pairs before porting — see
 * `Ed25519KeyDerivationTest`.
 */
object Ed25519KeyDerivation {
    /** The field prime, 2^255 - 19. */
    private val P: BigInteger = BigInteger.TWO.pow(255) - BigInteger.valueOf(19)

    /** Twisted-Edwards curve constant d = -121665/121666 mod p. */
    private val D: BigInteger = (BigInteger.valueOf(-121665).mod(P) * BigInteger.valueOf(121666).modInverse(P)).mod(P)

    /** Standard Ed25519 base point, in extended coordinates (X, Y, Z, T) with Z = 1. */
    private val BASE: Point = run {
        val y = (BigInteger.valueOf(4) * BigInteger.valueOf(5).modInverse(P)).mod(P)
        val x = BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
        Point(x, y, BigInteger.ONE, (x * y).mod(P))
    }

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    /** Extended twisted-Edwards coordinates: affine (x, y) = (X/Z, Y/Z), with T = XY/Z. */
    private data class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    /**
     * SHA-512(seed) with RFC 8032 bit clamping applied to the scalar half (byte 0's low 3 bits
     * cleared; byte 31's high bit cleared and second-highest bit set). The full 64-byte result is
     * MeshCore's "expanded private key" wire format — matches Swift's `expandSeed`.
     */
    fun expandedPrivateKey(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val expanded = MessageDigest.getInstance("SHA-512").digest(seed)
        expanded[0] = (expanded[0].toInt() and 0xF8).toByte()
        expanded[31] = ((expanded[31].toInt() and 0x7F) or 0x40).toByte()
        return expanded
    }

    /**
     * Derives the 32-byte compressed public key from a 64-byte expanded private key. Only the
     * first 32 (already-clamped) bytes are used as the scalar — the high 32 bytes are the EdDSA
     * signing nonce prefix, irrelevant to public-key derivation.
     */
    fun publicKey(expandedPrivateKey: ByteArray): ByteArray {
        require(expandedPrivateKey.size == 64) { "Expanded private key must be 64 bytes" }
        val scalar = littleEndianToBigInteger(expandedPrivateKey.copyOfRange(0, 32))
        return compress(scalarMultiply(scalar, BASE))
    }

    private fun scalarMultiply(scalar: BigInteger, point: Point): Point {
        var result = IDENTITY
        var addend = point
        var k = scalar
        while (k.signum() != 0) {
            if (k.testBit(0)) result = pointAdd(result, addend)
            addend = pointDouble(addend)
            k = k.shiftRight(1)
        }
        return result
    }

    /** "add-2008-hwcd-3" unified addition formula for a = -1 twisted Edwards curves. */
    private fun pointAdd(p1: Point, p2: Point): Point {
        val a = ((p1.y - p1.x) * (p2.y - p2.x)).mod(P)
        val b = ((p1.y + p1.x) * (p2.y + p2.x)).mod(P)
        val c = (p1.t * BigInteger.TWO * D * p2.t).mod(P)
        val dd = (p1.z * BigInteger.TWO * p2.z).mod(P)
        val e = (b - a).mod(P)
        val f = (dd - c).mod(P)
        val g = (dd + c).mod(P)
        val h = (b + a).mod(P)
        return Point((e * f).mod(P), (g * h).mod(P), (f * g).mod(P), (e * h).mod(P))
    }

    /** "dbl-2008-hwcd" doubling formula for a = -1 twisted Edwards curves. */
    private fun pointDouble(p: Point): Point {
        val a = (p.x * p.x).mod(P)
        val b = (p.y * p.y).mod(P)
        val c = (BigInteger.TWO * p.z * p.z).mod(P)
        val dd = a.negate().mod(P)
        val xy = p.x + p.y
        val e = ((xy * xy) - a - b).mod(P)
        val g = (dd + b).mod(P)
        val f = (g - c).mod(P)
        val h = (dd - b).mod(P)
        return Point((e * f).mod(P), (g * h).mod(P), (f * g).mod(P), (e * h).mod(P))
    }

    /** Converts to affine and encodes as 32 little-endian bytes, with x's parity in y's top bit. */
    private fun compress(point: Point): ByteArray {
        val zInv = point.z.modInverse(P)
        val x = (point.x * zInv).mod(P)
        val y = (point.y * zInv).mod(P)
        val out = bigIntegerToLittleEndian(y, 32)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
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
