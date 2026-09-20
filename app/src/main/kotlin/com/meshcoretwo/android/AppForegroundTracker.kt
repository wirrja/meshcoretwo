// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Tells the process apart from its activities: fires [onEnterBackground] when the last visible
 * activity stops and [onReturnToForeground] when one becomes visible again — the analogue of
 * SwiftUI's `scenePhase` `.background`/`.active` transitions in `MC1App.swift`. Built on
 * [Application.ActivityLifecycleCallbacks] instead of `ProcessLifecycleOwner` so no dependency
 * is added for ~30 lines.
 *
 * Two edge cases decide the shape:
 * - **Configuration change** (rotation, the QR scanner's `fullSensor`): the old activity stops
 *   before the new one starts, so the count briefly hits zero. That stop is skipped for the
 *   *transition* (not the count), so rotating never looks like a trip to the background.
 * - **Activity-to-activity hand-off** (main → scanner): the next activity starts before the
 *   previous one stops, so the count never reaches zero.
 *
 * The first start of the process is *not* reported as a return to the foreground: nothing was
 * backgrounded, and `ConnectionManager.activate()` is already running at that moment.
 */
class AppForegroundTracker(
    private val onEnterBackground: () -> Unit,
    private val onReturnToForeground: () -> Unit,
) : Application.ActivityLifecycleCallbacks {
    private var startedCount = 0
    private var isForeground = false
    private var hasBeenBackgrounded = false

    override fun onActivityStarted(activity: Activity) = activityStarted()

    override fun onActivityStopped(activity: Activity) = activityStopped(activity.isChangingConfigurations)

    internal fun activityStarted() {
        startedCount++
        if (isForeground) return
        isForeground = true
        if (hasBeenBackgrounded) onReturnToForeground()
    }

    internal fun activityStopped(isChangingConfigurations: Boolean) {
        if (startedCount > 0) startedCount--
        if (startedCount > 0 || isChangingConfigurations) return
        isForeground = false
        hasBeenBackgrounded = true
        onEnterBackground()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
