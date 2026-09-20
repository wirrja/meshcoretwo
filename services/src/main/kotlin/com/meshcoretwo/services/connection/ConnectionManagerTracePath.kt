// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.services.tracepath.TracePathService

/**
 * Narrow public seam onto the per-connection [com.meshcoretwo.services.ServiceContainer] for the
 * Trace Path diagnostics screen (`app` module) — same pattern as
 * [ConnectionManagerDiagnostics.kt]'s `rxLogService`.
 */

/** The active connection's Trace Path service, or null when disconnected. */
val ConnectionManager.tracePathService: TracePathService?
    get() = services?.tracePathService
