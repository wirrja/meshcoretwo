// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshEventTest {
    @Test
    fun `caseName reads the Kotlin class name`() {
        assertEquals("Ok", MeshEvent.Ok(value = 5u).caseName)
        assertEquals("ContactsFull", MeshEvent.ContactsFull.caseName)
        assertEquals("Acknowledgement", MeshEvent.Acknowledgement(code = byteArrayOf(1, 2, 3, 4)).caseName)
    }

    @Test
    fun `errorCode maps a known byte and is null otherwise`() {
        val known = MeshEvent.Error(code = ErrorCode.NOT_FOUND.value)
        assertEquals(ErrorCode.NOT_FOUND, known.errorCode)

        val unknownByte = MeshEvent.Error(code = 200u)
        assertNull(unknownByte.errorCode)

        val noByte = MeshEvent.Error(code = null)
        assertNull(noByte.errorCode)

        val nonErrorEvent: MeshEvent = MeshEvent.Ok(value = null)
        assertNull(nonErrorEvent.errorCode)
    }

    @Test
    fun `attributes exposes filterable fields and is empty for events without them`() {
        val ack = MeshEvent.Acknowledgement(code = byteArrayOf(0x01, 0x02, 0x03, 0x04), tripTime = 250u)
        val attrs = ack.attributes
        assertEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04).toList(), (attrs["code"] as ByteArray).toList())
        assertEquals(250u, attrs["tripTime"])

        assertTrue(MeshEvent.NoMoreMessages.attributes.isEmpty())
    }

    @Test
    fun `TraceNode legacy single-byte constructor matches hashBytes constructor`() {
        val viaLegacy = TraceNode(hash = 0x42u, snr = 1.5)
        val viaBytes = TraceNode(hashBytes = byteArrayOf(0x42), snr = 1.5)
        assertEquals(viaBytes, viaLegacy)
        assertEquals(0x42u.toUByte(), viaLegacy.hash)

        val destination = TraceNode(hash = null, snr = 2.0)
        assertNull(destination.hashBytes)
        assertNull(destination.hash)
    }

    @Test
    fun `PathInfo matches checks the public key prefix`() {
        val info = PathInfo(
            publicKeyPrefix = byteArrayOf(0x01, 0x02, 0x03),
            outPathLength = 0u,
            outPath = ByteArray(0),
            inPathLength = 0u,
            inPath = ByteArray(0),
        )
        assertTrue(info.matches(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)))
        assertTrue(!info.matches(byteArrayOf(0x01, 0x02, 0x04)))

        val emptyPrefix = PathInfo(
            publicKeyPrefix = ByteArray(0),
            outPathLength = 0u,
            outPath = ByteArray(0),
            inPathLength = 0u,
            inPath = ByteArray(0),
        )
        assertTrue(!emptyPrefix.matches(byteArrayOf(0x01, 0x02, 0x03)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `PathInfo rejects an outPath that does not match the decoded byte length`() {
        // outPathLength 0x03 -> mode 0 (1-byte hashes), hopCount 3 -> byteLength 3, but outPath is 2 bytes.
        PathInfo(
            publicKeyPrefix = byteArrayOf(0x01),
            outPathLength = 0x03u,
            outPath = byteArrayOf(0x11, 0x22),
            inPathLength = 0u,
            inPath = ByteArray(0),
        )
    }

    @Test
    fun `NeighboursResponse collectingAllPages stops on an empty page`() = runTest {
        var calls = 0
        val response = NeighboursResponse.collectingAllPages { offset ->
            calls++
            if (offset.toInt() == 0) {
                NeighboursResponse(
                    publicKeyPrefix = byteArrayOf(0xAA.toByte()),
                    tag = byteArrayOf(0x01),
                    totalCount = 5,
                    neighbours = listOf(Neighbour(byteArrayOf(0x01), 10, 5.0)),
                )
            } else {
                NeighboursResponse(byteArrayOf(0xAA.toByte()), byteArrayOf(0x01), 5, emptyList())
            }
        }

        assertEquals(2, calls)
        assertEquals(1, response.neighbours.size)
        assertEquals(5, response.totalCount)
    }

    @Test
    fun `NeighboursResponse collectingAllPages stops once the reported total is reached`() = runTest {
        var calls = 0
        val response = NeighboursResponse.collectingAllPages { _ ->
            calls++
            NeighboursResponse(
                publicKeyPrefix = byteArrayOf(0xAA.toByte()),
                tag = byteArrayOf(0x01),
                totalCount = 2,
                neighbours = listOf(Neighbour(byteArrayOf(0x01), 1, 1.0), Neighbour(byteArrayOf(0x02), 2, 2.0)),
            )
        }

        assertEquals(1, calls)
        assertEquals(2, response.neighbours.size)
    }
}
