// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives quick-reply, mark-as-read, and tap `PendingIntent`s posted by [NotificationService] and
 * forwards them to [NotificationService.activeInstance]. Has no Swift counterpart — on iOS these
 * all land on `NotificationService` directly via `UNUserNotificationCenterDelegate`, since
 * `UNUserNotificationCenter.current().delegate` is inherently one system-level pointer. A
 * `BroadcastReceiver` is instantiated fresh by the OS per broadcast and has no constructor
 * injection, so it needs an explicit hook back to the live service instance; see
 * [NotificationService]'s class doc.
 *
 * Registered in `AndroidManifest.xml` with `android:exported="false"` — only this app's own
 * `PendingIntent`s (explicit component, not implicit intent-filter matching) can trigger it.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = NotificationService.activeInstance ?: return
        // Action handling is asynchronous (suspend callbacks); goAsync() keeps the receiver's
        // process alive long enough for the launched coroutine to run to completion.
        val pendingResult = goAsync()
        service.dispatchAction(intent, onComplete = { pendingResult.finish() })
    }
}
