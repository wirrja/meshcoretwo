// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.services.persistence.DiscoveredNodeStore

/**
 * Narrow public seam onto the per-connection [com.meshcoretwo.services.ServiceContainer] for the
 * "Discover" list screen (`app` module) — same pattern as [ConnectionManagerTracePath.kt]'s
 * `tracePathService`. Exposes the persistence store directly rather than a dedicated service,
 * matching Swift: `DiscoveryViewModel` talks straight to `dataStore: DataStore` too, since there
 * is no `DiscoveryService`/`DiscoveredNodeService` on either platform.
 */

/** The active connection's Discover-list store, or null when disconnected. */
val ConnectionManager.discoveredNodeStore: DiscoveredNodeStore?
    get() = services?.discoveredNodeStore
