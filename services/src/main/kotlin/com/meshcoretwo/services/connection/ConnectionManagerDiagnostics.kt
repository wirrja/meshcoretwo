// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.services.rxlog.RxLogService

/**
 * Narrow public seam onto the per-connection [com.meshcoretwo.services.ServiceContainer] for the
 * diagnostics/tools screens (`app` module) — same pattern as `contactService`/`messageService` in
 * [ConnectionManagerChatAccess.kt].
 */

/** The active connection's RX-log processor, or null when disconnected. */
val ConnectionManager.rxLogService: RxLogService?
    get() = services?.rxLogService
