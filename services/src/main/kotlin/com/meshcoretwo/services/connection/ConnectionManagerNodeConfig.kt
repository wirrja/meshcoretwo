// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.services.nodeconfig.NodeConfigService

/**
 * Narrow public seam onto the per-connection [com.meshcoretwo.services.ServiceContainer] for the
 * config export/import UI (`app` module) — same pattern as `settingsService`/`connectedDeviceRecord`
 * in [ConnectionManagerRadioSettings.kt].
 */

/** The active connection's config export/import service, or null when disconnected. */
val ConnectionManager.nodeConfigService: NodeConfigService?
    get() = services?.nodeConfigService
