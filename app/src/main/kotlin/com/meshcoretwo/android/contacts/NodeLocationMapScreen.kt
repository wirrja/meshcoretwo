// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.rememberMapViewWithLifecycle
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point as GeoPoint

/**
 * Full-screen map of a remote node's reported position — the single-fix case of
 * `NodeLocationMapView.swift`, pushed by "View on Map" in the telemetry section of
 * [RoomStatusScreen]/[RepeaterStatusScreen] as Swift's `NodeStatusRoute.locationMap` is.
 *
 * Trimmed relative to Swift: the multi-report path with its tap callouts (it belongs to the
 * location-history section, not yet ported), and the shared map controls toolbar
 * (style/labels/north lock/my location), which no map in the port has yet. "Center" re-fits the
 * pin, standing in for that toolbar's "Center All". MapLibre's attribution button stays enabled,
 * as on the port's other maps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeLocationMapScreen(latitude: Double, longitude: Double, name: String?, onBack: () -> Unit) {
    val controller = remember(latitude, longitude, name) { NodeLocationMapController(latitude, longitude, name) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.contacts_location)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
                actions = { TextButton(onClick = controller::centerOnFix) { Text(stringResource(R.string.common_center)) } },
            )
        },
    ) { padding ->
        val mapView = rememberMapViewWithLifecycle()
        AndroidView(
            modifier = Modifier.padding(padding).fillMaxSize(),
            factory = { mapView },
            update = { view -> view.getMapAsync(controller::attach) },
        )
    }
}

private const val LOCATION_SOURCE_ID = "node-location-point"
private const val LOCATION_CIRCLE_LAYER_ID = "node-location-point-circle"
private const val LOCATION_LABEL_LAYER_ID = "node-location-point-label"
private const val LOCATION_PROP_LABEL = "label"

/** Swift frames a single fix with a 0.05° span, roughly zoom 12 at mid latitudes. */
private const val SINGLE_FIX_ZOOM = 12.0
private const val PIN_COLOR = "#2463EB"
private const val PIN_STROKE_COLOR = "#FFFFFF"

/** Owns the [MapLibreMap] for [NodeLocationMapScreen]: one labeled pin, camera on the fix. */
private class NodeLocationMapController(
    private val latitude: Double,
    private val longitude: Double,
    private val label: String?,
) {
    private var map: MapLibreMap? = null
    private var attached = false

    fun attach(map: MapLibreMap) {
        this.map = map
        if (attached) return
        attached = true

        // Set before the style loads, so the first rendered frame is already on the fix.
        map.cameraPosition = CameraPosition.Builder().target(LatLng(latitude, longitude)).zoom(SINGLE_FIX_ZOOM).build()
        map.setStyle(Style.Builder().fromUri(NODE_MAP_STYLE_URL)) { style ->
            val pin = Feature.fromGeometry(GeoPoint.fromLngLat(longitude, latitude)).apply {
                label?.let { addStringProperty(LOCATION_PROP_LABEL, it) }
            }
            style.addSource(GeoJsonSource(LOCATION_SOURCE_ID, pin))
            style.addLayer(
                CircleLayer(LOCATION_CIRCLE_LAYER_ID, LOCATION_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleColor(PIN_COLOR),
                    PropertyFactory.circleStrokeWidth(2.5f),
                    PropertyFactory.circleStrokeColor(PIN_STROKE_COLOR),
                ),
            )
            style.addLayer(
                SymbolLayer(LOCATION_LABEL_LAYER_ID, LOCATION_SOURCE_ID).withProperties(
                    PropertyFactory.textField(Expression.get(LOCATION_PROP_LABEL)),
                    PropertyFactory.textSize(12f),
                    PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
                    PropertyFactory.textAnchor("top"),
                    PropertyFactory.textColor("#1F2937"),
                    PropertyFactory.textHaloColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(1.2f),
                ),
            )
        }
    }

    fun centerOnFix() {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), SINGLE_FIX_ZOOM))
    }
}
