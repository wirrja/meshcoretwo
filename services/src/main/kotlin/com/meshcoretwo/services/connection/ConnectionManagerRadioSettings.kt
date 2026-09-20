// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.services.advertisement.AdvertisementService
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.settings.SettingsService

/**
 * Narrow public seam onto the per-connection [com.meshcoretwo.services.ServiceContainer] for
 * callers outside `services` (the onboarding Preset step, Settings' radio screen) that only need
 * radio configuration, not the whole internal [ConnectionManager.services]/[ConnectionManager.connectedDevice].
 */

/** The active connection's radio settings service, or null when disconnected. */
val ConnectionManager.settingsService: SettingsService?
    get() = services?.settingsService

/** The active connection's advert sender (zero-hop/flood self-advertisement), or null when disconnected. */
val ConnectionManager.advertisementService: AdvertisementService?
    get() = services?.advertisementService

/** The currently connected device's persisted record (radio params included), or null when disconnected. */
val ConnectionManager.connectedDeviceRecord: DeviceDto?
    get() = this.connectedDevice
