// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android

import android.app.Application
import android.content.Context
import android.util.Log
import com.meshcoretwo.android.di.AppContainer
import com.meshcoretwo.android.ui.i18n.AppLanguageManager
import com.meshcoretwo.services.connection.activate
import com.meshcoretwo.services.connection.appDidBecomeActive
import com.meshcoretwo.services.connection.checkSyncHealth
import com.meshcoretwo.services.connection.rxLogService
import com.meshcoretwo.services.logging.DebugLogBuffer
import com.meshcoretwo.android.notifications.AppNotificationStrings
import com.meshcoretwo.services.notifications.NotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

/**
 * Application entry point — the analogue of `MC1App.swift`. Builds the process-lifetime
 * [AppContainer] and activates [AppContainer.connectionManager] once, mirroring iOS's
 * `AppState.connectionManager.activate()` call on launch.
 */
class MeshCoreTwoApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Must run before any MapView is inflated (Map tab, Phase 5 item 5). No API key needed —
        // the app only ever loads unauthenticated styles (OpenFreeMap), never Mapbox-hosted tiles.
        MapLibre.getInstance(this)
        NotificationService.defaultStringProvider = AppNotificationStrings(this)
        NotificationService.registerChannels(this)
        container = AppContainer(this)
        scope.launch { container.connectionManager.activate() }
        registerActivityLifecycleCallbacks(AppForegroundTracker(::handleEnterBackground, ::handleReturnToForeground))
    }

    /**
     * Ported from `AppState.handleEnterBackground` / `MC1App.handleScenePhaseChange`, keeping only
     * the buffer flush. Both buffers batch writes, and unlike iOS a backgrounded Android process
     * can be killed at any time with no further callback, so anything still buffered would be lost.
     *
     * Deliberately *not* ported: `ConnectionManager.appDidEnterBackground()`, which stops the BLE
     * reconnection watchdog and marks the app inactive. On iOS that's right (the OS suspends the
     * process and restores the link); this app has no foreground service, and the watchdog is what
     * brings the link back while the phone is in a pocket — which incoming notifications depend on.
     * Room keepalives are likewise left running.
     */
    private fun handleEnterBackground() {
        Log.i(TAG, "app entered background: flushing buffers")
        scope.launch {
            // The shared buffer, not the connected services': while disconnected (a failing
            // reconnect is exactly that window) there are no services, and unflushed entries
            // would die with the process.
            DebugLogBuffer.shared?.flush()
            container.connectionManager.rxLogService?.flushPendingEntries()
            Log.i(TAG, "background flush done")
        }
    }

    /**
     * Ported from `AppState.handleReturnToForeground`, minus what has no Android counterpart
     * (Live Activity, badge) or already runs on its own loop (expired-ACK sweep, battery polling).
     * Reconciles a link that went stale while backgrounded, then retries a failed sync.
     */
    private fun handleReturnToForeground() {
        Log.i(TAG, "app returned to foreground: reconciling connection")
        scope.launch {
            container.connectionManager.appDidBecomeActive()
            container.connectionManager.checkSyncHealth()
            Log.i(TAG, "foreground reconciliation done")
        }
    }

    private companion object {
        const val TAG = "MC2Lifecycle"
    }
}
