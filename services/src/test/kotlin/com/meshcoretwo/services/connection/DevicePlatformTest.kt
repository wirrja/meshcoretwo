// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Ported from `DevicePlatformTests.swift` + `DevicePlatformSyncThrottlingTests.swift`. */
class DevicePlatformTest {
    @Test
    fun `detects ESP32 models`() {
        assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("Heltec V3"))
        assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("LilyGo T-Beam"))
        assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("Generic ESP32"))
    }

    @Test
    fun `detects nRF52 models`() {
        assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("Heltec Mesh Pocket"))
        assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("LilyGo T-Echo"))
        assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("RAK 4631"))
    }

    @Test
    fun `detection is case-insensitive`() {
        assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("heltec v3"))
    }

    @Test
    fun `unrecognized model falls back to unknown`() {
        assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.detect("Some Weird Radio 9000"))
    }

    @Test
    fun `matches a substring within a longer model string`() {
        assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("Heltec T114 v1 (rev B)"))
    }

    @Test
    fun `write pacing durations match Swift constants`() {
        assertEquals(Duration.ofMillis(60), DevicePlatform.ESP32.recommendedWritePacing)
        assertEquals(Duration.ofMillis(25), DevicePlatform.NRF52.recommendedWritePacing)
        assertEquals(Duration.ofMillis(60), DevicePlatform.UNKNOWN.recommendedWritePacing)
    }

    @Test
    fun `only ESP32 has a channel sync skip window`() {
        assertEquals(Duration.ofSeconds(30), DevicePlatform.ESP32.channelSyncSkipWindow)
        assertEquals(Duration.ZERO, DevicePlatform.NRF52.channelSyncSkipWindow)
        assertEquals(Duration.ZERO, DevicePlatform.UNKNOWN.channelSyncSkipWindow)
    }

    @Test
    fun `channelSyncConfig carries the platform skip window and timestamps`() {
        val lastClean = Instant.parse("2026-01-01T00:00:00Z")
        val lastAttempted = Instant.parse("2026-01-02T00:00:00Z")

        val config = DevicePlatform.ESP32.channelSyncConfig(
            lastCleanChannelSync = lastClean,
            lastAttemptedChannelSync = lastAttempted,
            usePipelinedChannelRead = true,
        )

        assertEquals(Duration.ofSeconds(30), config.channelSyncSkipWindow)
        assertEquals(lastClean, config.lastCleanChannelSync)
        assertEquals(lastAttempted, config.lastAttemptedChannelSync)
        assertEquals(true, config.usePipelinedChannelRead)
    }
}
