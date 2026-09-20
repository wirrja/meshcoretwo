// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.maps.MapView

/**
 * A [MapView] bound to the host lifecycle (`onCreate`/`onStart`/.../`onDestroy` forwarded, observer
 * removed on dispose) — every MapLibre screen needs this, and five of them (`MapScreen`,
 * `NeighborSnrMapScreen`, `LocationPickerScreen`, `OfflineRegionPickerScreen`, `LineOfSightScreen`,
 * plus `MessagePathMapScreen`/`NodeLocationMapScreen`/`LocationHistoryMapScreen`/
 * `LocationHistorySection` already sharing one of the five) had independently written it under a
 * different name each, so a duplicate-function-name grep never caught them. Byte-identical bodies.
 * Phase 27.
 */
@Composable
fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(Bundle())
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    return mapView
}
