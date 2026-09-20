// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.onboarding

import android.content.SharedPreferences
import androidx.core.content.edit
import com.meshcoretwo.services.connection.ConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val KEY_HAS_COMPLETED_ONBOARDING = "has_completed_onboarding"

/**
 * Onboarding completion flag, persisted to the shared prefs file. Ported from
 * `OnboardingState.swift`, trimmed to what this slice needs: `onboardingPath` isn't ported as a
 * standalone array — Compose `NavController`'s own back stack is the source of truth for the
 * flow's position instead, so the onboarding graph always starts at `welcome` (see
 * [resolveStartupCompletion] for how a returning user skips it).
 */
class OnboardingState(private val prefs: SharedPreferences) {
    private val _hasCompletedOnboarding = MutableStateFlow(
        prefs.getBoolean(KEY_HAS_COMPLETED_ONBOARDING, false),
    )
    val hasCompletedOnboarding: StateFlow<Boolean> = _hasCompletedOnboarding.asStateFlow()

    fun completeOnboarding() {
        _hasCompletedOnboarding.value = true
        prefs.edit { putBoolean(KEY_HAS_COMPLETED_ONBOARDING, true) }
    }
}

/**
 * Trimmed analogue of `OnboardingState.suggestedStartingPath`: a returning install that already
 * connected a device before (backup restore, or a process death mid-onboarding after a successful
 * pair) has nothing left to show in this slice's flow — Region/Preset, the only steps iOS would
 * still route such a user through, are deferred — so it completes onboarding immediately instead
 * of replaying Welcome/Permissions/Pair.
 */
fun OnboardingState.resolveStartupCompletion(connectionManager: ConnectionManager) {
    if (!hasCompletedOnboarding.value && connectionManager.lastConnectedDeviceID != null) {
        completeOnboarding()
    }
}
