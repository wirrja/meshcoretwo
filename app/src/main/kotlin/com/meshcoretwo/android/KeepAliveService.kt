// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.DeviceConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose

/**
 * Keeps the process alive while the radio link is wanted, so the BLE link and the reconnection
 * watchdog keep running with the screen off — incoming-message notifications depend on it. Shows the
 * one required ongoing notification (low importance, silent). Android-only: iOS gets this from the
 * `bluetooth-central` background mode instead, so there is no Swift counterpart.
 *
 * Uses type `connectedDevice`. No Play Services involved. The user can switch it off in
 * Settings → Notifications ([KEEP_ALIVE_PREF_KEY]).
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val notification = buildNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Foreground start refused (e.g. missing Bluetooth permission on API 34+): run without it.
            Log.w(TAG, "startForeground refused: $e")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "MC2KeepAlive"
        private const val CHANNEL_ID = "CONNECTION_KEEPALIVE"
        private const val NOTIFICATION_ID = 0x4B41
        const val KEEP_ALIVE_PREF_KEY = "keepConnectionInBackground"

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.keepalive_channel_name), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }

        private fun buildNotification(context: Context): Notification {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_keepalive)
                .setContentTitle(context.getString(R.string.keepalive_notification_title))
                .setContentText(context.getString(R.string.keepalive_notification_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        /**
         * Starts/stops the service to follow the connection and the user's toggle. Runs for the whole
         * process lifetime. A start attempted from the background can be refused by Android 12+; that
         * is caught and retried on the next state change (or when the app returns to the foreground).
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun observe(context: Context, scope: CoroutineScope, manager: ConnectionManager, prefs: SharedPreferences): Job {
            val app = context.applicationContext
            val enabled = callbackFlow {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                    if (key == KEEP_ALIVE_PREF_KEY) trySend(p.getBoolean(KEEP_ALIVE_PREF_KEY, true))
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                trySend(prefs.getBoolean(KEEP_ALIVE_PREF_KEY, true))
                awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }.distinctUntilChanged()
            return scope.launch {
                combine(enabled, manager.connectionStateEvents) { on, state -> on to state }
                    .collectLatest { (on, state) ->
                        val wanted = on && (state != DeviceConnectionState.DISCONNECTED || manager.connectionIntent.wantsConnection)
                        if (wanted) {
                            start(app)
                        } else {
                            // The state flips before the intent does on a user disconnect; look twice.
                            if (on) delay(1500)
                            val stillWanted = on && (manager.connectionState != DeviceConnectionState.DISCONNECTED || manager.connectionIntent.wantsConnection)
                            if (!stillWanted) app.stopService(Intent(app, KeepAliveService::class.java))
                        }
                    }
            }
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "start refused: $e")
            }
        }
    }
}
