// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.components

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.meshcoretwo.android.ui.theme.LocalAppTheme
import org.maplibre.android.maps.MapLibreMapOptions
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
    // Read once at creation: the theme can only change on Appearance, and every map screen is
    // composed afresh when navigated to, so an open map never needs to swap its render mode.
    val nightMap = LocalAppTheme.current.style.nightMap
    val mapView = remember { if (nightMap) nightMapView(context) else MapView(context) }
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

/**
 * The Night theme's map: rendered into a TextureView (a SurfaceView composites outside the view
 * hierarchy, so it ignores a layer's color filter) and drawn through [NightMapFilter] — the map's
 * light land turns near-black, roads, water outlines, labels and markers turn red. Costs one
 * hardware layer per frame, which is why only Night pays it.
 */
private fun nightMapView(context: Context): MapView =
    MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply {
        setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(NightMapFilter) })
    }

/** Red channel = inverted luminance, dimmed; green and blue dropped. */
private const val NIGHT_MAP_BRIGHTNESS = 0.85f
private val NightMapFilter = ColorMatrix(
    floatArrayOf(
        -0.30f * NIGHT_MAP_BRIGHTNESS, -0.59f * NIGHT_MAP_BRIGHTNESS, -0.11f * NIGHT_MAP_BRIGHTNESS, 0f, 255f * NIGHT_MAP_BRIGHTNESS,
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ),
)
