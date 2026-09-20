// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AppForegroundTrackerTest {
    private var backgrounds = 0
    private var foregrounds = 0
    private val tracker = AppForegroundTracker({ backgrounds++ }, { foregrounds++ })

    @Test
    fun `the first start is not a return to the foreground`() {
        tracker.activityStarted()

        assertEquals(0, foregrounds)
        assertEquals(0, backgrounds)
    }

    @Test
    fun `stopping the last activity backgrounds and starting one returns`() {
        tracker.activityStarted()
        tracker.activityStopped(isChangingConfigurations = false)
        tracker.activityStarted()

        assertEquals(1, backgrounds)
        assertEquals(1, foregrounds)
    }

    @Test
    fun `rotation is not a trip to the background`() {
        tracker.activityStarted()
        tracker.activityStopped(isChangingConfigurations = true)
        tracker.activityStarted()

        assertEquals(0, backgrounds)
        assertEquals(0, foregrounds)
    }

    @Test
    fun `a rotation does not leave the count inflated`() {
        tracker.activityStarted()
        tracker.activityStopped(isChangingConfigurations = true)
        tracker.activityStarted()
        tracker.activityStopped(isChangingConfigurations = false)

        assertEquals(1, backgrounds)
    }

    @Test
    fun `handing off to a second activity never reaches the background`() {
        tracker.activityStarted()
        tracker.activityStarted() // scanner starts before main stops
        tracker.activityStopped(isChangingConfigurations = false)

        assertEquals(0, backgrounds)

        tracker.activityStopped(isChangingConfigurations = false)

        assertEquals(1, backgrounds)
    }

    @Test
    fun `an unbalanced stop does not go negative`() {
        tracker.activityStopped(isChangingConfigurations = false)
        tracker.activityStarted()

        assertEquals(1, foregrounds)
    }
}
