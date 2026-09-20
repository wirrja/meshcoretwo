// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.map

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * "Cluster nodes" toggle from the Map tab's options menu — ported from iOS's
 * `AppStorageKey.mapClusteringEnabled` (`MapView.swift`/`MapControlsToolbar.swift`), on by default
 * like `AppStorageKey.defaultMapClusteringEnabled`. Unlike iOS this is not part of backup/restore:
 * `BackupUserDefaults` has no port here (same gap already noted for the region selection in
 * PLAN.md's Phase 39).
 */
object MapDisplayPreferences {
    private const val KEY_CLUSTERING_ENABLED = "map.clusteringEnabled"

    fun isClusteringEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_CLUSTERING_ENABLED, true)

    fun setClusteringEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit { putBoolean(KEY_CLUSTERING_ENABLED, enabled) }
    }
}
