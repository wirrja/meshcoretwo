// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.onboarding

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.meshcoretwo.android.AppViewModel

private object OnboardingRoute {
    const val WELCOME = "welcome"
    const val PERMISSIONS = "permissions"
    const val PAIR = "pair"
    const val REGION = "region"
    const val PRESET = "preset"
}

/**
 * Ported from `ContentView.swift`'s `NavigationStack(path: $onboarding.onboardingPath)`. Unlike
 * iOS, the flow's position is owned by Compose `NavController`'s own back stack rather than a
 * standalone `onboardingPath` array — this graph always starts at [OnboardingRoute.WELCOME]; a
 * returning user who should skip the flow entirely is handled one layer up, by
 * [com.meshcoretwo.android.onboarding.resolveStartupCompletion] flipping
 * `hasCompletedOnboarding` before this graph is ever composed (see [MainActivity]'s gating).
 *
 * A successful pair always advances to [OnboardingRoute.REGION] -> [OnboardingRoute.PRESET],
 * matching iOS's fixed step order; "I don't have a device yet" in [PairScreen] still completes
 * onboarding directly (there's no radio to configure a preset on).
 */
@Composable
fun OnboardingNavHost(appViewModel: AppViewModel, navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = OnboardingRoute.WELCOME) {
        composable(OnboardingRoute.WELCOME) {
            WelcomeScreen(onGetStarted = { navController.navigate(OnboardingRoute.PERMISSIONS) })
        }
        composable(OnboardingRoute.PERMISSIONS) {
            PermissionsScreen(onContinue = { navController.navigate(OnboardingRoute.PAIR) })
        }
        composable(OnboardingRoute.PAIR) {
            PairScreen(appViewModel = appViewModel, onPaired = { navController.navigate(OnboardingRoute.REGION) })
        }
        composable(OnboardingRoute.REGION) {
            RegionStepView(appViewModel = appViewModel, onContinue = { navController.navigate(OnboardingRoute.PRESET) })
        }
        composable(OnboardingRoute.PRESET) {
            PresetStepView(appViewModel = appViewModel)
        }
    }
}
