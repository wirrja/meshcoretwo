// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class FallbackElevationServiceTest {
    private class FakeService(private val answer: Double?, private val error: Exception = ElevationServiceError.NetworkError("down")) : ElevationService {
        var calls = 0

        override suspend fun fetchElevation(coordinate: GeoCoordinate): Double {
            calls++
            return answer ?: throw error
        }

        override suspend fun fetchElevations(path: List<GeoCoordinate>): List<ElevationSample> {
            calls++
            val value = answer ?: throw error
            return path.map { ElevationSample(it, value, 0.0) }
        }
    }

    private val point = GeoCoordinate(55.75, 37.62)

    @Test
    fun `first provider answers when it works`() = runTest {
        val first = FakeService(100.0)
        val second = FakeService(200.0)
        assertEquals(100.0, FallbackElevationService(listOf(first, second)).fetchElevation(point), 0.0)
        assertEquals(0, second.calls)
    }

    @Test
    fun `falls back and then starts with the provider that worked`() = runTest {
        val blocked = FakeService(null)
        val working = FakeService(200.0)
        val service = FallbackElevationService(listOf(blocked, working))

        assertEquals(200.0, service.fetchElevation(point), 0.0)
        assertEquals(200.0, service.fetchElevations(listOf(point, point)).first().elevation, 0.0)
        assertEquals(1, blocked.calls)
        assertEquals(2, working.calls)
    }

    @Test
    fun `throws the last error when every provider fails`() = runTest {
        val last = ElevationServiceError.RateLimited
        val service = FallbackElevationService(listOf(FakeService(null), FakeService(null, last)))
        try {
            service.fetchElevation(point)
            fail("expected an error")
        } catch (error: ElevationServiceError) {
            assertSame(last, error)
        }
    }
}
