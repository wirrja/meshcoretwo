// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.di

import android.content.Context
import com.meshcoretwo.android.map.OfflineMapService
import com.meshcoretwo.android.ui.theme.ThemeService
import com.meshcoretwo.services.backup.AppBackupService
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.location.AndroidLocationProvider
import com.meshcoretwo.services.location.LocationProvider
import com.meshcoretwo.services.notifications.NotificationPreferences
import com.meshcoretwo.services.notifications.NotificationPreferencesStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.region.AndroidRegionGeocoder
import com.meshcoretwo.services.region.RegionResolver
import com.meshcoretwo.services.region.RegionSelectionStore
import com.meshcoretwo.services.settings.DevicePreferenceStore
import com.meshcoretwo.services.settings.StaleNodeCleanupPreferencesStore
import com.meshcoretwo.services.transport.BleMeshTransport
import com.meshcoretwo.services.transport.BleStateMachine

/**
 * Process-lifetime composition root — the analogue of `MC1App.swift` building its single
 * `AppState`. No DI framework is used anywhere in this port (see PLAN.md), so this is a plain
 * class constructed once by [com.meshcoretwo.android.MeshCoreTwoApplication] and held for the
 * life of the process.
 *
 * Deliberately distinct from [com.meshcoretwo.services.ServiceContainer], which is per-connection
 * (built after a successful [ConnectionManager] connect, torn down on disconnect) — this class
 * only holds what must survive across connect/disconnect cycles.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: MeshCoreDatabase = MeshCoreDatabase.create(appContext)

    /** Single shared preferences file, mirroring iOS's single `UserDefaults.standard`. */
    val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val stateMachine = BleStateMachine(appContext)
    private val transport = BleMeshTransport(stateMachine)

    val connectionManager: ConnectionManager = ConnectionManager(
        context = appContext,
        database = database,
        prefs = prefs,
        stateMachine = stateMachine,
        transport = transport,
    )

    /** Shared one-shot GPS fix, reused by onboarding's Region step and the Map tab's "center on me". */
    val locationProvider: LocationProvider = AndroidLocationProvider(appContext)

    /** The user's persisted region: written by onboarding's Region step and Settings' preset location. */
    val regionSelectionStore: RegionSelectionStore = RegionSelectionStore(prefs)

    /** Backs onboarding's Region step and Settings' preset location "Use my location". */
    val regionResolver: RegionResolver = RegionResolver(
        location = locationProvider,
        geocoder = AndroidRegionGeocoder(appContext),
    )

    /**
     * Backs the Settings tab's notification toggles; also read fresh by
     * [com.meshcoretwo.services.notifications.NotificationService] at notify-time.
     */
    val notificationPreferencesStore: NotificationPreferencesStore = NotificationPreferencesStore(appContext)

    /** Backs the Settings tab's stale-node auto-cleanup threshold picker (Phase 5 slice 30). */
    val staleNodeCleanupPreferencesStore: StaleNodeCleanupPreferencesStore = StaleNodeCleanupPreferencesStore(appContext)

    /** Backs the Settings tab's `LocationSettingsSection` port (auto-update-location/GPS-source preferences). */
    val devicePreferenceStore: DevicePreferenceStore = DevicePreferenceStore(appContext)

    /**
     * Backs the Settings tab's Appearance screen. Shares [NotificationPreferences.PREFS_NAME]
     * (`"app_storage"`), not [prefs] above — see `PersistenceKeys.kt`'s doc comment: the theme keys
     * belong in the `app_storage` namespace that mirrors iOS's single `UserDefaults.standard`,
     * the same file [notificationPreferencesStore] already reads/writes.
     */
    val themeService: ThemeService = ThemeService(
        appContext.getSharedPreferences(NotificationPreferences.PREFS_NAME, Context.MODE_PRIVATE),
    )

    /** Backs the Settings tab's "Offline Maps" screen — device-independent, unlike most of this container's services. */
    val offlineMapService: OfflineMapService = OfflineMapService(appContext)

    /**
     * Backs the Settings tab's "Backup & Restore" screen. Shares [database] with [connectionManager]
     * rather than living inside [com.meshcoretwo.services.ServiceContainer] — backup export/import
     * touches every radio's data regardless of which (if any) is currently connected, so it needs
     * the same process-lifetime database this container already holds, not a per-connection one.
     * Also shares [NotificationPreferences.PREFS_NAME] (`"app_storage"`) with [notificationPreferencesStore]/
     * [themeService]/[staleNodeCleanupPreferencesStore] — the namespace `BackupUserDefaults` snapshots/restores.
     */
    val appBackupService: AppBackupService = AppBackupService(
        database,
        appContext.getSharedPreferences(NotificationPreferences.PREFS_NAME, Context.MODE_PRIVATE),
    )

    private companion object {
        const val PREFS_NAME = "com.meshcoretwo.android.prefs"
    }
}
