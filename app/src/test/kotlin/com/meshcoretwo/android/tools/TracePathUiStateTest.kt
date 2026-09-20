// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import com.meshcoretwo.android.pathediting.PathHop
import com.meshcoretwo.services.persistence.TracePathDto
import com.meshcoretwo.services.persistence.TracePathRunDto
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TracePathUiStateTest {
    private fun hop(byte: Int) = PathHop(hashBytes = byteArrayOf(byte.toByte()))

    private fun successResult(durationMs: Int, tracedPathBytes: ByteArray = byteArrayOf(0x01), hops: List<TraceHop> = emptyList()) = TraceResult(
        hops = hops, durationMs = durationMs, success = true, errorMessage = null, tracedPathBytes = tracedPathBytes, hashSize = 1,
    )

    private fun failedResult() = TraceResult(hops = emptyList(), durationMs = 0, success = false, errorMessage = "nope", tracedPathBytes = byteArrayOf(), hashSize = 1)

    // MARK: - hashSize / effectiveTraceMode

    @Test
    fun `effectiveTraceMode falls back to devicePathHashMode when no override is set`() {
        val state = TracePathUiState(devicePathHashMode = 1u)
        assertEquals(1u.toUByte(), state.effectiveTraceMode)
        assertEquals(2, state.hashSize)
    }

    @Test
    fun `effectiveTraceMode prefers the override over devicePathHashMode`() {
        val state = TracePathUiState(devicePathHashMode = 0u, traceHashMode = 2u)
        assertEquals(2u.toUByte(), state.effectiveTraceMode)
        assertEquals(4, state.hashSize)
    }

    // MARK: - fullPathData / fullPathString

    @Test
    fun `fullPathData is empty for an empty path`() {
        assertEquals(0, TracePathUiState().fullPathData.size)
    }

    @Test
    fun `fullPathData mirrors the return path when autoReturnPath is enabled, dropping the duplicate last hop`() {
        val state = TracePathUiState(outboundPath = listOf(hop(1), hop(2), hop(3)), autoReturnPath = true)
        // outbound: 1,2,3 ; return: 2,1 (reversed, drop first which duplicates the last outbound hop)
        assertEquals(listOf<Byte>(1, 2, 3, 2, 1), state.fullPathData.toList())
    }

    @Test
    fun `fullPathData is outbound-only when autoReturnPath is disabled`() {
        val state = TracePathUiState(outboundPath = listOf(hop(1), hop(2)), autoReturnPath = false)
        assertEquals(listOf<Byte>(1, 2), state.fullPathData.toList())
    }

    @Test
    fun `fullPathString is comma-separated uppercase hex chunked by hop width`() {
        val state = TracePathUiState(outboundPath = listOf(hop(0xAB), hop(0xCD)), autoReturnPath = false)
        assertEquals("AB,CD", state.fullPathString)
    }

    // MARK: - canRunTraceWhenConnected / canSavePath

    @Test
    fun `canRunTraceWhenConnected requires a non-empty path and not already running`() {
        assertFalse(TracePathUiState().canRunTraceWhenConnected)
        assertTrue(TracePathUiState(outboundPath = listOf(hop(1))).canRunTraceWhenConnected)
        assertFalse(TracePathUiState(outboundPath = listOf(hop(1)), isRunning = true).canRunTraceWhenConnected)
    }

    @Test
    fun `canSavePath is true only when the result succeeded against the current path bytes`() {
        val path = byteArrayOf(0x01, 0x02)
        val matching = successResult(durationMs = 10, tracedPathBytes = path)
        val state = TracePathUiState(outboundPath = listOf(hop(1), hop(2)), autoReturnPath = false, result = matching)
        assertTrue(state.canSavePath)
    }

    @Test
    fun `canSavePath is false when the path changed since the trace ran`() {
        val stale = successResult(durationMs = 10, tracedPathBytes = byteArrayOf(0x09))
        val state = TracePathUiState(outboundPath = listOf(hop(1), hop(2)), autoReturnPath = false, result = stale)
        assertFalse(state.canSavePath)
    }

    @Test
    fun `canSavePath in batch mode looks at the first successful completed result`() {
        val path = byteArrayOf(0x01)
        val state = TracePathUiState(
            outboundPath = listOf(hop(1)), autoReturnPath = false, batchEnabled = true,
            completedResults = listOf(failedResult(), successResult(10, path), successResult(20, path)),
        )
        assertTrue(state.canSavePath)
    }

    // MARK: - Batch aggregates

    @Test
    fun `successfulResults and successCount filter out failures`() {
        val state = TracePathUiState(completedResults = listOf(successResult(10), failedResult(), successResult(20)))
        assertEquals(2, state.successCount)
        assertEquals(listOf(10, 20), state.successfulResults.map { it.durationMs })
    }

    @Test
    fun `averageRTT minRTT maxRTT are null with no successful results`() {
        val state = TracePathUiState(completedResults = listOf(failedResult()))
        assertNull(state.averageRTT)
        assertNull(state.minRTT)
        assertNull(state.maxRTT)
    }

    @Test
    fun `averageRTT minRTT maxRTT summarize successful durations`() {
        val state = TracePathUiState(completedResults = listOf(successResult(10), successResult(20), successResult(30)))
        assertEquals(20, state.averageRTT)
        assertEquals(10, state.minRTT)
        assertEquals(30, state.maxRTT)
    }

    @Test
    fun `isBatchInProgress and isBatchComplete track batch state`() {
        val inProgress = TracePathUiState(batchEnabled = true, batchSize = 3, currentTraceIndex = 2)
        assertTrue(inProgress.isBatchInProgress)
        assertFalse(inProgress.isBatchComplete)

        val complete = TracePathUiState(batchEnabled = true, batchSize = 2, completedResults = listOf(successResult(1), failedResult()))
        assertTrue(complete.isBatchComplete)
    }

    // MARK: - previousRun

    @Test
    fun `previousRun is the second-most-recent successful run`() {
        val now = Instant.now()
        val runs = listOf(
            TracePathRunDto(id = UUID.randomUUID(), date = now, success = true, roundTripMs = 30, hopsSNR = emptyList()),
            TracePathRunDto(id = UUID.randomUUID(), date = now.minusSeconds(10), success = true, roundTripMs = 20, hopsSNR = emptyList()),
            TracePathRunDto(id = UUID.randomUUID(), date = now.minusSeconds(20), success = false, roundTripMs = 0, hopsSNR = emptyList()),
        )
        val savedPath = TracePathDto(id = UUID.randomUUID(), radioID = UUID.randomUUID(), name = "Path", pathBytes = byteArrayOf(), hashSize = 1, createdDate = now, runs = runs)
        val state = TracePathUiState(activeSavedPath = savedPath)
        assertEquals(20, state.previousRun?.roundTripMs)
    }

    @Test
    fun `previousRun is null with fewer than two successful runs`() {
        assertNull(TracePathUiState().previousRun)
    }

    // MARK: - Distance / location fallback

    private fun locatedHop(lat: Double, lon: Double, start: Boolean = false, end: Boolean = false) =
        TraceHop(hashBytes = null, resolvedName = null, snr = 1.0, isStartNode = start, isEndNode = end, latitude = lat, longitude = lon)

    private fun unlocatedHop(start: Boolean = false, end: Boolean = false) =
        TraceHop(hashBytes = byteArrayOf(0x01), resolvedName = "Repeater", snr = 1.0, isStartNode = start, isEndNode = end, latitude = null, longitude = null)

    @Test
    fun `totalPathDistance sums the full path when every hop has a location`() {
        // Non-zero coordinates throughout: (0,0) is the "Null Island" sentinel TraceHop.hasLocation treats as absent.
        val hops = listOf(
            locatedHop(10.0, 10.0, start = true),
            locatedHop(10.0, 11.0),
            locatedHop(10.0, 12.0, end = true),
        )
        val state = TracePathUiState(result = successResult(10, hops = hops))
        assertTrue(state.totalPathDistance!! > 0)
        assertFalse(state.isDistanceUsingFallback)
    }

    @Test
    fun `totalPathDistance falls back to intermediate repeaters when device endpoints lack location`() {
        val hops = listOf(
            unlocatedHop(start = true),
            locatedHop(0.0, 1.0),
            locatedHop(0.0, 2.0),
            unlocatedHop(end = true),
        )
        val state = TracePathUiState(result = successResult(10, hops = hops))
        assertTrue(state.totalPathDistance!! > 0)
        assertTrue(state.isDistanceUsingFallback)
    }

    @Test
    fun `repeatersWithoutLocation lists intermediate hops lacking location`() {
        val hops = listOf(locatedHop(0.0, 0.0, start = true), unlocatedHop(), locatedHop(0.0, 2.0, end = true))
        val state = TracePathUiState(result = successResult(10, hops = hops))
        assertEquals(listOf("Repeater"), state.repeatersWithoutLocation)
    }
}
