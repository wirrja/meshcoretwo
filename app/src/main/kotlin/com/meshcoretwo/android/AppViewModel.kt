// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android

import com.meshcoretwo.services.pairing.DevicePairingError
import com.meshcoretwo.services.connection.pairNewDevice
import com.meshcoretwo.android.onboarding.PairingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.meshcoretwo.android.di.AppContainer
import com.meshcoretwo.android.map.OfflineMapService
import com.meshcoretwo.android.onboarding.OnboardingState
import com.meshcoretwo.android.onboarding.resolveStartupCompletion
import com.meshcoretwo.android.ui.theme.ThemeService
import com.meshcoretwo.services.backup.AppBackupService
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.location.LocationProvider
import com.meshcoretwo.services.notifications.NotificationPreferencesStore
import com.meshcoretwo.services.region.RegionResolver
import com.meshcoretwo.services.region.RegionSelection
import com.meshcoretwo.services.settings.DevicePreferenceStore
import com.meshcoretwo.services.settings.StaleNodeCleanupPreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Top-level ViewModel wrapping the process-lifetime [AppContainer] — the Compose analogue of
 * iOS's single `@Observable AppState`. No DI framework is used in this port (see PLAN.md), so a
 * manual [Factory] builds this from [AppContainer] instead of constructor injection.
 */
class AppViewModel(container: AppContainer) : ViewModel() {
    val connectionManager: ConnectionManager = container.connectionManager
    val onboardingState = OnboardingState(container.prefs)
    val regionResolver: RegionResolver = container.regionResolver
    val locationProvider: LocationProvider = container.locationProvider
    val notificationPreferencesStore: NotificationPreferencesStore = container.notificationPreferencesStore
    val staleNodeCleanupPreferencesStore: StaleNodeCleanupPreferencesStore = container.staleNodeCleanupPreferencesStore
    val devicePreferenceStore: DevicePreferenceStore = container.devicePreferenceStore
    val offlineMapService: OfflineMapService = container.offlineMapService
    val appBackupService: AppBackupService = container.appBackupService
    val themeService: ThemeService = container.themeService
    val prefs: SharedPreferences = container.prefs

    val regionSelectionStore = container.regionSelectionStore

    /** The persisted region, written by onboarding's Region step and Settings' preset location. Doesn't yet ride along in backup/restore (iOS's `BackupUserDefaults` equivalent is still deferred). */
    val regionSelection: StateFlow<RegionSelection?> = regionSelectionStore.selection

    fun setRegionSelection(selection: RegionSelection) = regionSelectionStore.set(selection)

    /**
     * A `meshcore://channel/add` link handed to [com.meshcoretwo.android.MainActivity] by the OS
     * (tapped in another app, e.g. a browser or messaging app) — Android's equivalent of iOS's
     * `MC1App.onOpenURL` → `ChatLinkRouter.routeExternalOpen`. Lives here rather than as
     * `Activity`-local state because it must survive the config-change recreation a `singleTask`
     * activity gets when the OS starts it with a new deep-link `Intent` while already running, and
     * because it may arrive before onboarding completes — `MainScreen` (and its consuming
     * `LaunchedEffect`) doesn't exist yet at that point, so the link waits here until it does.
     * `MainScreen` clears it via [consumePendingChannelLink] once it has navigated on it, so a
     * config change afterward doesn't replay the same join screen.
     */
    private val _pendingChannelLink = MutableStateFlow<String?>(null)
    val pendingChannelLink: StateFlow<String?> = _pendingChannelLink.asStateFlow()

    fun setPendingChannelLink(link: String) {
        _pendingChannelLink.value = link
    }

    fun consumePendingChannelLink() {
        _pendingChannelLink.value = null
    }

    /**
     * Route to open after a notification tap (see `MainActivity.handleIntent`). Held here for the
     * same reason as [pendingChannelLink]: it can arrive before `MainScreen` exists.
     */
    private val _pendingNotificationRoute = MutableStateFlow<String?>(null)
    val pendingNotificationRoute: StateFlow<String?> = _pendingNotificationRoute.asStateFlow()

    fun setPendingNotificationRoute(route: String) {
        _pendingNotificationRoute.value = route
    }

    fun consumePendingNotificationRoute() {
        _pendingNotificationRoute.value = null
    }

    private val _pairing = MutableStateFlow<PairingStatus>(PairingStatus.Idle)
    val pairing: StateFlow<PairingStatus> = _pairing.asStateFlow()

    /**
     * Onboarding's "Add device". Runs in [viewModelScope], not the Pair screen's composition scope:
     * pairing waits for a full sync, and an Activity recreation in that window used to cancel it
     * after the radio had already connected, leaving onboarding stuck on the Pair screen.
     */
    fun startPairing() {
        if (_pairing.value is PairingStatus.Pairing) return
        _pairing.value = PairingStatus.Pairing
        viewModelScope.launch {
            _pairing.value = try {
                connectionManager.pairNewDevice()
                PairingStatus.Paired
            } catch (error: CancellationException) {
                _pairing.value = PairingStatus.Idle
                throw error
            } catch (error: DevicePairingError.Cancelled) {
                PairingStatus.Idle
            } catch (error: DevicePairingError.AlreadyInProgress) {
                PairingStatus.Idle
            } catch (error: Exception) {
                PairingStatus.Failed(error)
            }
        }
    }

    /** Called by the Pair screen once it has navigated on a [PairingStatus.Paired] result. */
    fun consumePairingResult() {
        _pairing.value = PairingStatus.Idle
    }

    init {
        onboardingState.resolveStartupCompletion(connectionManager)
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
    }
}
