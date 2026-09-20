// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.protocol.setFloodScope
import com.meshcoretwo.protocol.setFloodScopeUnscoped
import com.meshcoretwo.services.channels.ChannelFloodScopeResolver
import com.meshcoretwo.services.channels.ResolvedFloodScope
import com.meshcoretwo.services.persistence.ChannelFloodScope

/**
 * Pushes the session-scoped flood key to match [floodScope]'s effective resolution against the
 * connected device's default flood-scope name/un-scoped-send capability — the radio-facing half of
 * a per-channel flood-scope change. No-ops (returns without throwing) if there's no live session,
 * matching Swift's `guard let session = ... else { return }`. Ported from the device/session
 * lookups in `ChatViewModel+Channels.syncFloodScope` and the push half of
 * `ChannelInfoSheet.selectFloodScope`, hoisted here so `app`-layer callers (the conversation screen
 * and the channel-info screen) don't need direct access to the internal [ConnectionManager.session].
 *
 * Callers decide how to handle a thrown [com.meshcoretwo.protocol.MeshCoreError] — both current call
 * sites catch-and-log/ignore, mirroring Swift's `try?`/`catch { logger.error(...) }` at each site
 * rather than swallowing it here.
 */
suspend fun ConnectionManager.pushChannelFloodScope(floodScope: ChannelFloodScope) {
    val activeSession = session ?: return
    val device = connectedDevice
    when (val resolved = ChannelFloodScopeResolver.resolve(floodScope, device?.defaultFloodScopeName, device?.supportsUnscopedFloodSend ?: false)) {
        is ResolvedFloodScope.Unscoped -> activeSession.setFloodScopeUnscoped()
        is ResolvedFloodScope.Scope -> activeSession.setFloodScope(resolved.scope)
    }
}
