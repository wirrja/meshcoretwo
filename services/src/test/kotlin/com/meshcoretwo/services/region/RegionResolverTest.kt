// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.region

import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.location.LocationProvider
import com.meshcoretwo.services.location.LocationProviderError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ported from `RegionResolverTests.swift`, extended with success-path coverage the Swift suite
 * couldn't exercise (its `LocationService` isn't abstracted behind a protocol — see that file's
 * comment); [FakeLocationProvider] here makes that straightforward.
 */
class RegionResolverTest {
    private class FakeLocationProvider(
        override val isAuthorized: Boolean = true,
        private val fix: LocationFix? = LocationFix(34.05, -118.24),
    ) : LocationProvider {
        override suspend fun requestCurrentLocation(timeoutMs: Long): LocationFix =
            fix ?: throw LocationProviderError.RequestFailed("no fix")
    }

    private class FakeRegionGeocoder(private val result: GeocodeResult?) : RegionGeocoder {
        var callCount = 0
            private set

        override suspend fun reverseGeocode(latitude: Double, longitude: Double): GeocodeResult? {
            callCount++
            return result
        }
    }

    @Test
    fun `null countryCode returns null`() = runTest {
        val resolver = RegionResolver(FakeLocationProvider(), FakeRegionGeocoder(GeocodeResult(null, null, null)))
        assertNull(resolver.resolve())
    }

    @Test
    fun `unauthorized location returns null`() = runTest {
        val resolver = RegionResolver(FakeLocationProvider(isAuthorized = false), FakeRegionGeocoder(null))
        assertNull(resolver.resolve())
    }

    @Test
    fun `resolves a plain country match`() = runTest {
        val resolver = RegionResolver(FakeLocationProvider(), FakeRegionGeocoder(GeocodeResult("DE", null, null)))
        val region = resolver.resolve()
        assertEquals("DE", region?.countryCode)
        assertEquals(RegionSelection.Source.LOCATION, region?.source)
    }

    @Test
    fun `resolves administrative area to its ISO 3166-2 code`() = runTest {
        val resolver = RegionResolver(FakeLocationProvider(), FakeRegionGeocoder(GeocodeResult("US", "California", null)))
        val region = resolver.resolve()
        assertEquals("US-CA", region?.administrativeAreaCode)
    }

    @Test
    fun `resolves county key and strips the county suffix`() = runTest {
        val geocoder = FakeRegionGeocoder(GeocodeResult("US", "California", "Los Angeles County"))
        val resolver = RegionResolver(FakeLocationProvider(), geocoder)
        val region = resolver.resolve()
        assertEquals("los angeles", region?.countyKey)
    }

    @Test
    fun `caches a fresh result and skips a second geocode`() = runTest {
        val geocoder = FakeRegionGeocoder(GeocodeResult("DE", null, null))
        val resolver = RegionResolver(FakeLocationProvider(), geocoder)
        resolver.resolve()
        resolver.resolve()
        assertEquals(1, geocoder.callCount)
    }
}
