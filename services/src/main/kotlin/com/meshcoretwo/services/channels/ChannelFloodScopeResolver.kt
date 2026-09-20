// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.channels

import com.meshcoretwo.protocol.FloodScope
import com.meshcoretwo.services.persistence.ChannelFloodScope

/**
 * The flood-scope action [ChannelFloodScopeResolver] resolves to for a conversation. Ported from
 * `ResolvedFloodScope.swift`. Distinguishes a true un-scoped override (firmware sub-command 1,
 * sets `send_unscoped`) from a concrete [FloodScope] push (sub-command 0) — a zero-key
 * [FloodScope.Disabled] resets the session scope and lets the device fall back to its persisted
 * default, so it cannot stand in for an explicit "all regions" override on firmware that supports
 * one.
 */
sealed class ResolvedFloodScope {
    /** Force un-scoped flood broadcasts, overriding the device default. Push via
     * `MeshCoreSession.setFloodScopeUnscoped()`. Requires firmware v12+. */
    object Unscoped : ResolvedFloodScope()

    /** Push a concrete [FloodScope] via `MeshCoreSession.setFloodScope(FloodScope)`. */
    data class Scope(val scope: FloodScope) : ResolvedFloodScope()
}

/**
 * Resolves the [ResolvedFloodScope] to push to the radio for a given conversation, combining the
 * per-channel [ChannelFloodScope] preference with the device-wide default flood scope name and the
 * device's un-scoped-send capability. Ported from `ChannelFloodScopeResolver.swift`.
 *
 * - [ChannelFloodScope.AllRegions] + device supports un-scoped send -> [ResolvedFloodScope.Unscoped]
 *   (true override of the device default via firmware sub-command 1; requires firmware v12+)
 * - [ChannelFloodScope.AllRegions] + device does not support un-scoped send ->
 *   `Scope(FloodScope.Disabled)` (best-effort fallback: a zero-key reset cannot override the
 *   default on older firmware, so the radio still floods on its configured default)
 * - [ChannelFloodScope.Inherit] + device default set -> `Scope(FloodScope.Region(default))` (radio
 *   floods on the default)
 * - [ChannelFloodScope.Inherit] + no device default -> `Scope(FloodScope.Disabled)` (no scope filter)
 * - [ChannelFloodScope.Region] -> `Scope(FloodScope.Region(name))` (explicit per-channel override)
 */
object ChannelFloodScopeResolver {
    fun resolve(
        channelFloodScope: ChannelFloodScope,
        deviceDefaultFloodScopeName: String?,
        supportsUnscopedFloodSend: Boolean,
    ): ResolvedFloodScope = when (channelFloodScope) {
        is ChannelFloodScope.Inherit -> {
            val name = deviceDefaultFloodScopeName
            if (!name.isNullOrEmpty()) {
                ResolvedFloodScope.Scope(FloodScope.Region(name))
            } else {
                ResolvedFloodScope.Scope(FloodScope.Disabled)
            }
        }
        is ChannelFloodScope.AllRegions ->
            if (supportsUnscopedFloodSend) ResolvedFloodScope.Unscoped else ResolvedFloodScope.Scope(FloodScope.Disabled)
        is ChannelFloodScope.Region -> ResolvedFloodScope.Scope(FloodScope.Region(channelFloodScope.name))
    }
}
