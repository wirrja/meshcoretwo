// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.settings

import com.meshcoretwo.protocol.Ed25519KeyDerivation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.SecureRandom

/** Expanded (64-byte) Ed25519 private key length — mirrors `PacketBuilder.PRIVATE_KEY_SIZE`, which is `internal` to the protocol module (see `NodeConfigImportPlanner.kt` for the same local-copy precedent). */
private const val PRIVATE_KEY_SIZE = 64

/**
 * Generates Ed25519 identities for "Regenerate Identity" (optionally matching a vanity hex
 * prefix) and validates a manually pasted expanded private key for "Import Key". Ported from
 * `KeyGenerationService.swift`; the actual curve math lives one layer down, in
 * [Ed25519KeyDerivation] (protocol module, pure crypto with no Android dependency) — the same
 * split as Swift's version, which is in `MC1Services` but calls into CryptoKit rather than
 * hand-rolling the curve.
 */
object KeyGenerationService {
    /** A freshly generated identity: the 64-byte expanded private key and its 32-byte public key. */
    data class GeneratedIdentity(val expandedPrivateKey: ByteArray, val publicKey: ByteArray)

    sealed class KeyGenerationError(message: String) : Exception(message) {
        /** No key matching the requested prefix was found within the attempt budget — see [maxAttempts]. */
        data object MaxAttemptsExceeded : KeyGenerationError("Could not generate a key with that prefix. Try a different one.")

        /** The requested prefix maps to a public-key first byte (0x00/0xFF) reserved by firmware. */
        data object ReservedPrefix : KeyGenerationError("That prefix is reserved and cannot be used.")

        /** The supplied data isn't a validly clamped 64-byte Ed25519 expanded private key. */
        data object InvalidKey : KeyGenerationError("The key is not a valid Ed25519 private key.")
    }

    private val reservedPrefixes = listOf("00", "FF")
    private val secureRandom = SecureRandom()

    /**
     * Generates a new identity, optionally searching for a public key starting with [hexPrefix]
     * (1-4 uppercase hex characters; `null`/blank accepts any valid key). Checks
     * [kotlinx.coroutines.CoroutineContext.ensureActive] every attempt so a caller can cancel a
     * slow vanity search — mirrors `RegenerateIdentityViewModel.cancelGeneration`'s
     * `Task.checkCancellation()` loop. Callers should dispatch this off the main thread: unlike
     * Swift's CryptoKit-backed generation, [Ed25519KeyDerivation]'s hand-rolled `BigInteger` curve
     * math makes each attempt take low-single-digit milliseconds, so a 3-4 character prefix search
     * (tens of thousands of attempts) is genuinely slow — see that object's class doc for why.
     */
    suspend fun generateIdentity(hexPrefix: String?): GeneratedIdentity {
        val prefix = hexPrefix?.uppercase().orEmpty()
        if (prefix.length >= 2 && reservedPrefixes.any { prefix.startsWith(it) }) {
            throw KeyGenerationError.ReservedPrefix
        }

        repeat(maxAttempts(prefix.length)) {
            currentCoroutineContext().ensureActive()

            val seed = ByteArray(32).also(secureRandom::nextBytes)
            val expanded = Ed25519KeyDerivation.expandedPrivateKey(seed)
            val publicKey = Ed25519KeyDerivation.publicKey(expanded)
            val firstByte = publicKey[0].toInt() and 0xFF

            // Always reject firmware-reserved prefix bytes, regardless of what was requested.
            if (firstByte == 0x00 || firstByte == 0xFF) return@repeat
            if (prefix.isNotEmpty() && !publicKey.toUpperHex().startsWith(prefix)) return@repeat

            return GeneratedIdentity(expandedPrivateKey = expanded, publicKey = publicKey)
        }
        throw KeyGenerationError.MaxAttemptsExceeded
    }

    /**
     * Validates RFC 8032 clamping on a manually supplied expanded private key: byte 0's low 3
     * bits clear, byte 31's high bit clear and second-highest bit set. Ported from
     * `KeyGenerationService.validateExpandedKey`.
     */
    fun validateExpandedKey(data: ByteArray) {
        if (data.size != PRIVATE_KEY_SIZE) throw KeyGenerationError.InvalidKey
        val first = data[0].toInt()
        val last = data[31].toInt()
        if (first and 0x07 != 0) throw KeyGenerationError.InvalidKey
        if (last and 0x80 != 0 || last and 0x40 != 0x40) throw KeyGenerationError.InvalidKey
    }

    /** Scales max attempts based on prefix length (~20x the expected attempts needed), matching Swift's `maxAttempts(forPrefixLength:)`. */
    private fun maxAttempts(prefixLength: Int): Int {
        if (prefixLength <= 0) return 10_000
        val expected = 1 shl (prefixLength * 4) // 16^length
        return maxOf(10_000, expected * 20)
    }

    private fun ByteArray.toUpperHex(): String = joinToString("") { "%02X".format(it) }
}
