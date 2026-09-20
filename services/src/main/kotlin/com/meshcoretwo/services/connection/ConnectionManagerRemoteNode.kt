// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.services.nodesnapshot.NodeSnapshotService
import com.meshcoretwo.services.remotenode.RemoteNodeService
import com.meshcoretwo.services.remotenode.RepeaterAdminService
import com.meshcoretwo.services.remotenode.RoomAdminService
import com.meshcoretwo.services.remotenode.RoomServerService

/**
 * Narrow public seam onto the per-connection [com.meshcoretwo.services.ServiceContainer] for the
 * repeater/room authentication and status UI (`app` module) — same pattern as
 * `nodeConfigService` in [ConnectionManagerNodeConfig.kt].
 */

/** The active connection's shared login/status/telemetry service, or null when disconnected. */
val ConnectionManager.remoteNodeService: RemoteNodeService?
    get() = services?.remoteNodeService

/** The active connection's repeater admin-connect service, or null when disconnected. */
val ConnectionManager.repeaterAdminService: RepeaterAdminService?
    get() = services?.repeaterAdminService

/** The active connection's room join/post service, or null when disconnected. */
val ConnectionManager.roomServerService: RoomServerService?
    get() = services?.roomServerService

/** The active connection's room admin status/telemetry/CLI service, or null when disconnected. */
val ConnectionManager.roomAdminService: RoomAdminService?
    get() = services?.roomAdminService

/** The active connection's node-snapshot history service, or null when disconnected. */
val ConnectionManager.nodeSnapshotService: NodeSnapshotService?
    get() = services?.nodeSnapshotService
