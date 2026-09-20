// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import com.meshcoretwo.services.region.RegionSelection

/**
 * Pure decisions behind Settings' preset location filter. Ported from `PresetLocationPolicy.swift`,
 * with CoreLocation's authorization states collapsed to Android's granted/not-granted permission —
 * there is no `.notDetermined`/`.denied` split to observe without an Activity, so "not granted"
 * always means "ask, then fall back to the open-settings alert".
 */
object PresetLocationPolicy {
    enum class ResolveKind { APPEAR, USER_INITIATED }

    enum class UseMyLocationAction { RESOLVE, REQUEST_PERMISSION }

    /** Silently refresh from GPS when the app opens Radio settings, unless the user picked a place by hand. */
    fun shouldResolveOnAppear(granted: Boolean, source: RegionSelection.Source?): Boolean =
        granted && source != RegionSelection.Source.MANUAL

    /** An automatic resolve never overwrites a manual pick; a "Use my location" tap always does. A failed lookup keeps [current]. */
    fun committedSelection(current: RegionSelection?, result: RegionSelection?, kind: ResolveKind): RegionSelection? {
        if (result == null) return current
        return when (kind) {
            ResolveKind.APPEAR -> if (current?.source == RegionSelection.Source.MANUAL) current else result
            ResolveKind.USER_INITIATED -> result
        }
    }

    fun useMyLocationAction(granted: Boolean): UseMyLocationAction =
        if (granted) UseMyLocationAction.RESOLVE else UseMyLocationAction.REQUEST_PERMISSION

    /** Only a user-initiated lookup that found nothing is worth an alert; the silent refresh stays silent. */
    fun shouldPresentLookupMiss(kind: ResolveKind): Boolean = kind == ResolveKind.USER_INITIATED
}
