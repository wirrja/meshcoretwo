// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import java.time.Duration

/**
 * Device platform type for BLE write pacing configuration. Ported from `DevicePlatform.swift` +
 * `DevicePlatform+SyncThrottling.swift`.
 *
 * [channelSyncSkipWindow]/[channelSyncConfig] give
 * [com.meshcoretwo.services.sync.ChannelSyncConfig] a real platform-detected source now that this
 * type exists; wiring [channelSyncConfig] into `SyncCoordinator`'s resync path is still deferred
 * to the `ConnectionManager` slice, which is where platform detection from the connected device's
 * model string actually happens.
 */
enum class DevicePlatform {
    ESP32,
    NRF52,
    UNKNOWN,
    ;

    /** Recommended write pacing delay for this platform. */
    val recommendedWritePacing: Duration
        get() = when (this) {
            ESP32 -> Duration.ofMillis(60) // 60ms required by ESP32 BLE stack
            NRF52 -> Duration.ofMillis(25) // Light pacing to avoid RX queue pressure
            UNKNOWN -> Duration.ofMillis(60) // Conservative ESP32-safe default for unrecognized devices
        }

    /**
     * If channels were synced more recently than this, skip channel re-sync on resync. Only
     * enabled for ESP32 where channel re-sync wastes scarce connection time. Channel skipping is
     * a correctness tradeoff (not just performance), so it stays disabled for unknown platforms
     * until field evidence warrants it.
     */
    val channelSyncSkipWindow: Duration
        get() = when (this) {
            ESP32 -> Duration.ofSeconds(30)
            NRF52, UNKNOWN -> Duration.ZERO
        }

    companion object {
        /**
         * Detects the device platform from the model string for BLE write pacing.
         *
         * Uses specific model substrings rather than vendor prefixes, because vendors like
         * Heltec, RAK, Seeed, and Elecrow ship devices on multiple chip families. Unrecognized
         * devices fall to [UNKNOWN] (conservative 60ms pacing).
         *
         * Ordering matters: first match wins. More specific patterns must precede general ones
         * within each platform group.
         */
        fun detect(model: String): DevicePlatform {
            val rule = platformRules.firstOrNull { (substring, _) -> model.contains(substring, ignoreCase = true) }
            return rule?.second ?: UNKNOWN
        }

        private val platformRules: List<Pair<String, DevicePlatform>> = listOf(
            // ESP32 — Heltec
            "Heltec V2" to ESP32,
            "Heltec V3" to ESP32,
            "Heltec V4" to ESP32,
            "Heltec Tracker" to ESP32,
            "Heltec E290" to ESP32,
            "Heltec E213" to ESP32,
            "Heltec T190" to ESP32,
            "Heltec CT62" to ESP32,
            // ESP32 — LilyGo
            "T-Beam" to ESP32,
            "T-Deck" to ESP32,
            "T-LoRa" to ESP32,
            "TLora" to ESP32,
            // ESP32 — Seeed
            "Xiao S3 WIO" to ESP32,
            "Xiao C3" to ESP32,
            "Xiao C6" to ESP32,
            // ESP32 — RAK
            "RAK 3112" to ESP32,
            // ESP32 — M5Stack
            "Unit C6L" to ESP32,
            // ESP32 — Other
            "Station G2" to ESP32,
            "Meshadventurer" to ESP32,
            "Generic ESP32" to ESP32,
            "ThinkNode M2" to ESP32,
            "ThinkNode M5" to ESP32,
            // nRF52 — Heltec
            "MeshPocket" to NRF52,
            "Mesh Pocket" to NRF52,
            "T114" to NRF52,
            "Mesh Solar" to NRF52,
            // nRF52 — Seeed
            "Xiao-nrf52" to NRF52,
            "Xiao_nrf52" to NRF52,
            "WM1110" to NRF52,
            "Wio Tracker" to NRF52,
            "T1000-E" to NRF52,
            "SenseCap Solar" to NRF52,
            // nRF52 — RAK
            "WisMesh Tag" to NRF52,
            "RAK 4631" to NRF52,
            "RAK 3401" to NRF52,
            // nRF52 — LilyGo
            "T-Echo" to NRF52,
            // nRF52 — Elecrow
            "ThinkNode-M1" to NRF52,
            "ThinkNode M3" to NRF52,
            "ThinkNode-M6" to NRF52,
            // nRF52 — GAT562
            "GAT562" to NRF52,
            // nRF52 — Other
            "Ikoka" to NRF52,
            "ProMicro" to NRF52,
            "Minewsemi" to NRF52,
            "Meshtiny" to NRF52,
            "Keepteen" to NRF52,
            "Nano G2 Ultra" to NRF52,
        )
    }
}

/** Builds a channel sync config for a sync operation, using this platform's [DevicePlatform.channelSyncSkipWindow]. */
fun DevicePlatform.channelSyncConfig(
    lastCleanChannelSync: java.time.Instant?,
    lastAttemptedChannelSync: java.time.Instant? = null,
    usePipelinedChannelRead: Boolean = false,
) = com.meshcoretwo.services.sync.ChannelSyncConfig(
    channelSyncSkipWindow = channelSyncSkipWindow,
    lastCleanChannelSync = lastCleanChannelSync,
    lastAttemptedChannelSync = lastAttemptedChannelSync,
    usePipelinedChannelRead = usePipelinedChannelRead,
)
