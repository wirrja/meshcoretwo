// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoretwo.android.onboarding.OnboardingNavHost
import com.meshcoretwo.android.ui.i18n.AppLanguageManager
import com.meshcoretwo.android.ui.theme.LocalIsDarkTheme
import com.meshcoretwo.android.ui.theme.MeshCoreTwoTheme
import com.meshcoretwo.services.notifications.NotificationService.NotificationExtras
import java.util.UUID

/**
 * Entry screen. Gates between the onboarding flow (Welcome/Permissions/Pair — see
 * `onboarding/OnboardingNavHost.kt`) and the main app (`MainScreen.kt` — chats/contacts/channels/
 * map/settings, PLAN.md's Phase 5 items 2-6; the Tools item, 7, isn't built yet), mirroring
 * `ContentView.swift`'s `if appState.onboarding.hasCompletedOnboarding`.
 *
 * `enableEdgeToEdge()` plus the status/nav bar appearance [SideEffect] below matter on
 * `targetSdk = 35`: Android 15 forces edge-to-edge regardless, so without explicitly setting
 * `isAppearanceLightStatusBars` the system bar icons stop tracking the app's light/dark theme.
 */
class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels {
        AppViewModel.Factory((application as MeshCoreTwoApplication).container)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            MeshCoreTwoTheme(themeService = appViewModel.themeService) {
                val isDark = LocalIsDarkTheme.current
                val view = LocalView.current
                SideEffect {
                    val window = window
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !isDark
                        isAppearanceLightNavigationBars = !isDark
                    }
                }

                val onboardingDone by appViewModel.onboardingState.hasCompletedOnboarding.collectAsStateWithLifecycle()
                if (onboardingDone) {
                    MainScreen(
                        connectionManager = appViewModel.connectionManager,
                        locationProvider = appViewModel.locationProvider,
                        regionSelectionStore = appViewModel.regionSelectionStore,
                        regionResolver = appViewModel.regionResolver,
                        notificationPreferencesStore = appViewModel.notificationPreferencesStore,
                        staleNodeCleanupPreferencesStore = appViewModel.staleNodeCleanupPreferencesStore,
                        devicePreferenceStore = appViewModel.devicePreferenceStore,
                        offlineMapService = appViewModel.offlineMapService,
                        appBackupService = appViewModel.appBackupService,
                        themeService = appViewModel.themeService,
                        prefs = appViewModel.prefs,
                        pendingChannelLink = appViewModel.pendingChannelLink,
                        onConsumeChannelLink = appViewModel::consumePendingChannelLink,
                        pendingNotificationRoute = appViewModel.pendingNotificationRoute,
                        onConsumeNotificationRoute = appViewModel::consumePendingNotificationRoute,
                    )
                } else {
                    OnboardingNavHost(appViewModel = appViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Android's counterpart to `MC1App.onOpenURL` — called both for the launch [Intent] (a cold
     * start from tapping a link) and, since [MainActivity] is `singleTask`, every later
     * [onNewIntent] (tapping a link while the app is already running). Only `meshcore://channel/
     * add` is registered in the manifest, so [Intent.getData] is never some other scheme here.
     */
    private fun handleIntent(intent: Intent?) {
        notificationRoute(intent)?.let { appViewModel.setPendingNotificationRoute(it) }
        if (intent?.action != Intent.ACTION_VIEW) return
        val link = intent.data?.toString() ?: return
        appViewModel.setPendingChannelLink(link)
    }

    /** Maps a notification tap [Intent] (see `NotificationService.tapPendingIntent`) to a `MainRoute`, or `null` for any other launch. */
    private fun notificationRoute(intent: Intent?): String? {
        if (intent?.hasExtra(NotificationExtras.TAP) != true) return null
        // Consume the marker so a config-change/relaunch with the same Intent doesn't replay the tap.
        intent.removeExtra(NotificationExtras.TAP)
        fun uuid(key: String) = intent.getStringExtra(key)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val contactID = uuid(NotificationExtras.CONTACT_ID)
        val channelIndex = intent.getIntExtra(NotificationExtras.CHANNEL_INDEX, -1).takeIf { it in 0..255 }
        val sessionID = uuid(NotificationExtras.SESSION_ID)
        return when (intent.getStringExtra(NotificationExtras.TYPE)) {
            "directMessage" -> contactID?.let(MainRoute::directConversation)
            "channelMessage" -> channelIndex?.let { MainRoute.channelConversation(it.toUByte()) }
            "roomMessage" -> sessionID?.let(MainRoute::roomConversation)
            "newContact" -> contactID?.let(MainRoute::contactDetail)
            "reaction" -> contactID?.let(MainRoute::directConversation)
                ?: channelIndex?.let { MainRoute.channelConversation(it.toUByte()) }
            else -> null
        }
    }
}
