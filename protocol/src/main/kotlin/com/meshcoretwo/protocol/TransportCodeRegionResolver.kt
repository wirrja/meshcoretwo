// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Resolves the per-message flood region a packet was transmitted under, by matching its
 * `transport_codes[0]` against precomputed scope keys for the caller's known regions.
 *
 * Ports the firmware algorithm from `TransportKey::calcTransportCode` (`TransportKeyStore.cpp`)
 * and `RegionMap::findMatch` / `getTransportKeysFor` (`RegionMap.cpp`). The resolver itself is
 * stateless: callers own and refresh the `(name, scopeKey)` cache.
 */
object TransportCodeRegionResolver {
    private const val SCOPE_KEY_BYTE_COUNT = 16
    private const val PAYLOAD_TYPE_MASK: UByte = 0x0Fu
    private const val TRANSPORT_CODE_MIN_VALUE: UShort = 1u
    private const val TRANSPORT_CODE_MAX_VALUE: UShort = 0xFFFEu
    private const val AUTO_REGION_PREFIX = '#'
    private const val PRIVATE_REGION_PREFIX = '$'

    /**
     * Derives the 16-byte scope key for an auto-named region.
     *
     * Mirrors firmware `TransportKeyStore::getAutoKeyFor` plus the
     * `RegionMap::getTransportKeysFor` rule that prepends "#" if the name does not already
     * start with "#".
     *
     * Returns `null` for `$`-prefixed (private) regions and empty / whitespace names — both are
     * filtered out of the matching pipeline.
     */
    fun deriveScopeKey(regionName: String): ByteArray? {
        val trimmed = regionName.trim()
        val first = trimmed.firstOrNull() ?: return null
        if (first == PRIVATE_REGION_PREFIX) return null

        val normalized = if (first == AUTO_REGION_PREFIX) trimmed else "#$trimmed"
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.copyOf(SCOPE_KEY_BYTE_COUNT)
    }

    /**
     * Computes `transport_codes[0]` for a given scope key and packet body.
     *
     * Mirrors `TransportKey::calcTransportCode`: HMAC-SHA256 over
     * `[payloadTypeBits & 0x0F] || payload`, with the scope key used as the HMAC key. The first
     * two bytes of the MAC are read as little-endian `UShort`, then `0` and `0xFFFF` are
     * rewritten to the reserved-boundary neighbors (`0x0001` / `0xFFFE`).
     */
    fun calcTransportCode(scopeKey: ByteArray, payloadTypeBits: UByte, payload: ByteArray): UShort {
        var combined = byteArrayOf((payloadTypeBits and PAYLOAD_TYPE_MASK).toByte())
        combined += payload

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(scopeKey, "HmacSHA256"))
        val macBytes = mac.doFinal(combined)

        val rawCode = macBytes.readUInt16LE(0)
        return rewriteReservedCode(rawCode)
    }

    /**
     * Rewrites the reserved transport-code values to their reserved-boundary neighbors.
     * Mirrors firmware `TransportKey::calcTransportCode` lines 12-15. Internal for direct
     * boundary-test coverage.
     */
    internal fun rewriteReservedCode(rawCode: UShort): UShort = when (rawCode) {
        0u.toUShort() -> TRANSPORT_CODE_MIN_VALUE
        0xFFFFu.toUShort() -> TRANSPORT_CODE_MAX_VALUE
        else -> rawCode
    }

    /**
     * Matches a packet against every precomputed `(regionName, scopeKey)` entry.
     *
     * Collects every name whose [calcTransportCode] equals [expectedTransportCode0] (no
     * first-hit early exit). Blank names are dropped. Names are sorted so ambiguous sets are
     * order-stable — plain lexicographic order here, unlike Swift's locale-aware
     * `localizedStandardCompare`; immaterial for the short ASCII region-code names this handles.
     * Empty input returns [RegionMatchResult.None]. Live cost is O(R) HMACs per
     * transport-coded packet; callers own and rebuild the cache.
     */
    fun matchRegions(
        scopeKeys: List<Pair<String, ByteArray>>,
        expectedTransportCode0: UShort,
        payloadTypeBits: UByte,
        payload: ByteArray,
    ): RegionMatchResult {
        if (scopeKeys.isEmpty()) return RegionMatchResult.None

        val matchedNames = mutableListOf<String>()
        for ((name, key) in scopeKeys) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) continue
            val code = calcTransportCode(scopeKey = key, payloadTypeBits = payloadTypeBits, payload = payload)
            if (code == expectedTransportCode0) {
                matchedNames.add(trimmed)
            }
        }

        val uniqueSorted = matchedNames.toSet().sorted()

        return when (uniqueSorted.size) {
            0 -> RegionMatchResult.None
            1 -> RegionMatchResult.Unique(uniqueSorted[0])
            else -> RegionMatchResult.Ambiguous(uniqueSorted)
        }
    }
}

/**
 * Result of matching a packet's `transport_codes[0]` against known public region scope keys.
 * [Ambiguous] sets always carry at least two sorted names.
 */
sealed class RegionMatchResult {
    data object None : RegionMatchResult()
    data class Unique(val name: String) : RegionMatchResult()
    data class Ambiguous(val names: List<String>) : RegionMatchResult()
}
