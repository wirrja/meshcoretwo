// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Port of TransportCodeRegionResolverTests.swift. */
class TransportCodeRegionResolverTest {
    private val groupTextPayloadBits: UByte = 5u
    private val samplePayload = byteArrayOf(0x42, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x01, 0x02, 0x03)

    // MARK: - Scope key derivation

    @Test
    fun `Scope key is SHA-256 of hash-prefixed name truncated to 16 bytes`() {
        val key = TransportCodeRegionResolver.deriveScopeKey("Germany") ?: run { fail("expected key"); return }
        val expected = MessageDigest.getInstance("SHA-256").digest("#Germany".toByteArray(Charsets.UTF_8)).copyOf(16)
        assertArrayEquals(expected, key)
        assertEquals(16, key.size)
    }

    @Test
    fun `Names with and without hash prefix produce identical scope keys`() {
        val a = TransportCodeRegionResolver.deriveScopeKey("Germany") ?: run { fail("expected key"); return }
        val b = TransportCodeRegionResolver.deriveScopeKey("#Germany") ?: run { fail("expected key"); return }
        assertArrayEquals(a, b)
    }

    @Test
    fun `Whitespace around region name is trimmed before normalization`() {
        val trimmed = TransportCodeRegionResolver.deriveScopeKey("  Germany  ") ?: run { fail("expected key"); return }
        val direct = TransportCodeRegionResolver.deriveScopeKey("Germany") ?: run { fail("expected key"); return }
        assertArrayEquals(trimmed, direct)
    }

    @Test
    fun `Dollar-prefixed (private) region returns null scope key`() {
        assertNull(TransportCodeRegionResolver.deriveScopeKey("\$secret"))
        assertNull(TransportCodeRegionResolver.deriveScopeKey("\$"))
    }

    @Test
    fun `Empty or whitespace-only region name returns null`() {
        assertNull(TransportCodeRegionResolver.deriveScopeKey(""))
        assertNull(TransportCodeRegionResolver.deriveScopeKey("   "))
        assertNull(TransportCodeRegionResolver.deriveScopeKey("\t\n"))
    }

    @Test
    fun `Repeated derivation of the same name yields identical keys`() {
        val a = TransportCodeRegionResolver.deriveScopeKey("Bavaria") ?: run { fail("expected key"); return }
        val b = TransportCodeRegionResolver.deriveScopeKey("Bavaria") ?: run { fail("expected key"); return }
        assertArrayEquals(a, b)
    }

    // MARK: - Reserved-boundary rewrites

    @Test
    fun `rewriteReservedCode maps 0 to 1`() {
        assertEquals(1u.toUShort(), TransportCodeRegionResolver.rewriteReservedCode(0u))
    }

    @Test
    fun `rewriteReservedCode maps 0xFFFF to 0xFFFE`() {
        assertEquals(0xFFFEu.toUShort(), TransportCodeRegionResolver.rewriteReservedCode(0xFFFFu))
    }

    @Test
    fun `rewriteReservedCode passes through non-reserved values`() {
        assertEquals(1u.toUShort(), TransportCodeRegionResolver.rewriteReservedCode(1u))
        assertEquals(0xFFFEu.toUShort(), TransportCodeRegionResolver.rewriteReservedCode(0xFFFEu))
        assertEquals(0x1234u.toUShort(), TransportCodeRegionResolver.rewriteReservedCode(0x1234u))
        assertEquals(0x8000u.toUShort(), TransportCodeRegionResolver.rewriteReservedCode(0x8000u))
    }

    // MARK: - Transport code computation

    @Test
    fun `calcTransportCode reads first two HMAC bytes as little-endian UShort`() {
        val scopeKey = TransportCodeRegionResolver.deriveScopeKey("Germany") ?: run { fail("expected key"); return }

        var combined = byteArrayOf((groupTextPayloadBits and 0x0Fu).toByte())
        combined += samplePayload
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(scopeKey, "HmacSHA256"))
        val macBytes = mac.doFinal(combined)
        val expectedRaw = macBytes.readUInt16LE(0)
        val expected = TransportCodeRegionResolver.rewriteReservedCode(expectedRaw)

        val actual = TransportCodeRegionResolver.calcTransportCode(scopeKey, groupTextPayloadBits, samplePayload)
        assertEquals(expected, actual)
    }

    @Test
    fun `calcTransportCode masks payload type bits to low nibble`() {
        val scopeKey = TransportCodeRegionResolver.deriveScopeKey("Germany") ?: run { fail("expected key"); return }
        val masked = TransportCodeRegionResolver.calcTransportCode(scopeKey, 0x05u, samplePayload)
        val withHighBits = TransportCodeRegionResolver.calcTransportCode(scopeKey, 0xF5u, samplePayload)
        assertEquals(masked, withHighBits)
    }

    @Test
    fun `calcTransportCode is deterministic for the same inputs`() {
        val scopeKey = TransportCodeRegionResolver.deriveScopeKey("Bavaria") ?: run { fail("expected key"); return }
        val a = TransportCodeRegionResolver.calcTransportCode(scopeKey, 5u, samplePayload)
        val b = TransportCodeRegionResolver.calcTransportCode(scopeKey, 5u, samplePayload)
        assertEquals(a, b)
    }

    // MARK: - Region matching

    @Test
    fun `Round-trip - compute code then resolve back to unique region name`() {
        val regionName = "Germany"
        val scopeKey = TransportCodeRegionResolver.deriveScopeKey(regionName) ?: run { fail("expected key"); return }
        val expectedCode = TransportCodeRegionResolver.calcTransportCode(scopeKey, groupTextPayloadBits, samplePayload)

        val match = TransportCodeRegionResolver.matchRegions(
            scopeKeys = listOf(regionName to scopeKey),
            expectedTransportCode0 = expectedCode,
            payloadTypeBits = groupTextPayloadBits,
            payload = samplePayload,
        )
        assertEquals(RegionMatchResult.Unique(regionName), match)
    }

    @Test
    fun `Empty scopeKeys returns none`() {
        val match = TransportCodeRegionResolver.matchRegions(
            scopeKeys = emptyList(),
            expectedTransportCode0 = 0x1234u,
            payloadTypeBits = groupTextPayloadBits,
            payload = samplePayload,
        )
        assertEquals(RegionMatchResult.None, match)
    }

    @Test
    fun `No matching region returns none`() {
        val germany = TransportCodeRegionResolver.deriveScopeKey("Germany") ?: run { fail("expected key"); return }
        val usa = TransportCodeRegionResolver.deriveScopeKey("USA") ?: run { fail("expected key"); return }
        val actualCode = TransportCodeRegionResolver.calcTransportCode(germany, groupTextPayloadBits, samplePayload)
        val wrongCode = (actualCode + 1u).toUShort()

        val match = TransportCodeRegionResolver.matchRegions(
            scopeKeys = listOf("Germany" to germany, "USA" to usa),
            expectedTransportCode0 = wrongCode,
            payloadTypeBits = groupTextPayloadBits,
            payload = samplePayload,
        )
        assertEquals(RegionMatchResult.None, match)
    }

    @Test
    fun `Ambiguous match returns all names sorted independent of input order`() {
        val scopeKey = TransportCodeRegionResolver.deriveScopeKey("Germany") ?: run { fail("expected key"); return }
        val expectedCode = TransportCodeRegionResolver.calcTransportCode(scopeKey, groupTextPayloadBits, samplePayload)

        // Same key under two names — both verify the transport code; never first-match only.
        val forward = listOf("de-by" to scopeKey, "de-hh" to scopeKey)
        val reverse = listOf("de-hh" to scopeKey, "de-by" to scopeKey)

        val forwardMatch = TransportCodeRegionResolver.matchRegions(forward, expectedCode, groupTextPayloadBits, samplePayload)
        val reverseMatch = TransportCodeRegionResolver.matchRegions(reverse, expectedCode, groupTextPayloadBits, samplePayload)

        assertEquals(RegionMatchResult.Ambiguous(listOf("de-by", "de-hh")), forwardMatch)
        assertEquals(RegionMatchResult.Ambiguous(listOf("de-by", "de-hh")), reverseMatch)
        assertEquals(forwardMatch, reverseMatch)
    }

    @Test
    fun `Dollar-prefixed private regions never match via empty scope key`() {
        // deriveScopeKey returns null for dollar-prefixed names, so callers never put them in
        // the cache; matchRegions with an empty list is the production path.
        assertNull(TransportCodeRegionResolver.deriveScopeKey("\$secret"))
        val match = TransportCodeRegionResolver.matchRegions(
            scopeKeys = emptyList(),
            expectedTransportCode0 = 0x1234u,
            payloadTypeBits = groupTextPayloadBits,
            payload = samplePayload,
        )
        assertEquals(RegionMatchResult.None, match)
    }
}
