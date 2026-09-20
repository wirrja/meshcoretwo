// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.rememberMapViewWithLifecycle
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import java.time.Instant
import java.util.UUID
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point as GeoPoint

/**
 * Full-screen map of a node's location history: every valid-coordinate report as a tappable pin
 * (undecimated, unlike the inline preview), joined by a gap-broken trail. Tapping a pin opens a
 * bottom sheet with its report detail — a [ModalBottomSheet] standing in for Swift's
 * screen-position-anchored `.popover`, the same trim [MapScreen]'s marker tap already makes in this
 * port. Ported from `NodeLocationMapView.swift`, reached from [locationHistorySection] as Swift's
 * `.locationMapDestination` is.
 *
 * Not ported: the shared map controls toolbar (style/labels/north lock/my location) — no map in
 * this port has it yet; "Center" (this screen's `NodeLocationMapScreen`-style stand-in for "Center
 * All") re-fits the path instead. Also not ported: live user-location dot (`showsUserLocation`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationHistoryMapScreen(
    snapshots: List<NodeStatusSnapshotDto>,
    initialSelectionId: UUID?,
    onBack: () -> Unit,
) {
    // The full-screen map keeps every report as a tappable pin (no decimation), unlike the inline
    // preview where dense sprites would be noise.
    val built = remember(snapshots) { LocationPathMapBuilder.build(snapshots, decimatePins = false) }
    val controller = remember { LocationHistoryMapController() }
    var selected by remember { mutableStateOf<LocationReport?>(null) }
    var hasAutoFit by remember { mutableStateOf(false) }

    LaunchedEffect(built) {
        controller.onPointTap = { pointId -> selected = built.reports[pointId] }
        controller.update(built)
        if (!hasAutoFit && built.points.isNotEmpty()) {
            controller.fitToContent()
            hasAutoFit = true
            // Auto-select the report the row tap targeted; the expand button passes null.
            initialSelectionId?.let { id -> selected = built.reports.values.firstOrNull { it.id == id } }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_location)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
                actions = {
                    TextButton(onClick = controller::fitToContent, enabled = built.points.isNotEmpty()) { Text(stringResource(R.string.common_center)) }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            val mapView = rememberMapViewWithLifecycle()
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { mapView },
                update = { view -> view.getMapAsync(controller::attach) },
            )
        }
    }

    selected?.let { report ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            LocationReportCallout(report)
        }
    }
}

/**
 * Route-level wrapper for [LocationHistoryMapScreen] reached from [TelemetryHistoryScreen]: reuses
 * [NodeStatusHistoryViewModel] by session id, the same way [NeighborSnrChartScreen] does, rather
 * than carrying the pushing screen's already-fetched snapshots through the nav graph. A fresh
 * instance means a fresh default [HistoryTimeRange], not the pushing screen's selection — the same
 * trim the neighbor chart already makes.
 */
@Composable
fun NodeLocationHistoryMapScreen(
    connectionManager: ConnectionManager,
    sessionId: UUID,
    initialSelectionId: UUID?,
    onBack: () -> Unit,
) {
    val viewModel: NodeStatusHistoryViewModel = viewModel(factory = NodeStatusHistoryViewModel.Factory(connectionManager, sessionId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LocationHistoryMapScreen(uiState.timeRange.filter(uiState.snapshots), initialSelectionId, onBack)
}

/** Route-level wrapper for [LocationHistoryMapScreen] reached from [TelemetryHistoryOverviewScreen]: reuses [TelemetryHistoryOverviewViewModel] by contact id, for the same reason [NodeLocationHistoryMapScreen] reuses [NodeStatusHistoryViewModel]. */
@Composable
fun ContactLocationHistoryMapScreen(
    connectionManager: ConnectionManager,
    contactId: UUID,
    initialSelectionId: UUID?,
    onBack: () -> Unit,
) {
    val viewModel: TelemetryHistoryOverviewViewModel = viewModel(factory = TelemetryHistoryOverviewViewModel.Factory(connectionManager, contactId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LocationHistoryMapScreen(uiState.timeRange.filter(uiState.snapshots), initialSelectionId, onBack)
}

/** Popover-turned-sheet content: when the node was there, and its altitude when known. */
@Composable
private fun LocationReportCallout(report: LocationReport) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(LocationReportFormat.relativeTime(report.timestamp, Instant.now()).asString(), style = MaterialTheme.typography.titleMedium)
        val detail = report.altitude?.let { "${LocationReportFormat.absoluteTime(report.timestamp)} · ${LocationReportFormat.altitude(it)}" }
            ?: LocationReportFormat.absoluteTime(report.timestamp)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val SOURCE_POINTS = "location-history-points"
private const val SOURCE_LINES = "location-history-lines"
private const val LAYER_POINTS = "location-history-points-circle"
private const val LAYER_LINES = "location-history-lines-layer"
private const val PROP_COLOR = "color"
private const val PROP_ID = "id"

/** Owns the [MapLibreMap] for [LocationHistoryMapScreen]: point/line sources, tap routing, and bounds fitting. */
private class LocationHistoryMapController {
    var onPointTap: ((String) -> Unit)? = null

    private var map: MapLibreMap? = null
    private var attached = false
    private var pointsSource: GeoJsonSource? = null
    private var linesSource: GeoJsonSource? = null
    private var lastPoints: List<LocationMapPoint> = emptyList()
    private var pendingPath: PlottedLocationPath? = null

    /** A fit requested before [attach] ran (the map view hands its instance over asynchronously); replayed on attach. */
    private var pendingFit = false

    fun attach(map: MapLibreMap) {
        this.map = map
        if (pendingFit) {
            pendingFit = false
            fitToContent()
        }
        if (attached) return
        attached = true

        map.addOnMapClickListener { latLng ->
            val point = findPointAt(map, latLng)
            if (point != null) {
                onPointTap?.invoke(point.id)
                true
            } else {
                false
            }
        }

        map.setStyle(Style.Builder().fromUri(NODE_MAP_STYLE_URL)) { style ->
            val lines = GeoJsonSource(SOURCE_LINES, emptyList<LocationMapLine>().linesToFeatureCollection())
            style.addSource(lines)
            style.addLayer(
                LineLayer(LAYER_LINES, SOURCE_LINES).withProperties(
                    PropertyFactory.lineColor(LOCATION_TRAIL_COLOR),
                    PropertyFactory.lineWidth(3f),
                ),
            )
            linesSource = lines

            val points = GeoJsonSource(SOURCE_POINTS, emptyList<LocationMapPoint>().toFeatureCollectionWithIds())
            style.addSource(points)
            style.addLayer(
                CircleLayer(LAYER_POINTS, SOURCE_POINTS).withProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleColor(Expression.get(PROP_COLOR)),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                ),
            )
            pointsSource = points

            pendingPath?.let(::apply)
        }
    }

    fun update(path: PlottedLocationPath) {
        pendingPath = path
        apply(path)
    }

    fun fitToContent() {
        val map = map
        if (map == null) {
            pendingFit = true
            return
        }
        when {
            lastPoints.isEmpty() -> Unit
            lastPoints.size == 1 -> map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lastPoints[0].latitude, lastPoints[0].longitude), 12.0))
            else -> {
                val bounds = LatLngBounds.Builder()
                lastPoints.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 96))
            }
        }
    }

    private fun apply(path: PlottedLocationPath) {
        lastPoints = path.points
        pointsSource?.setGeoJson(path.points.toFeatureCollectionWithIds())
        linesSource?.setGeoJson(path.lines.linesToFeatureCollection())
    }

    private fun findPointAt(map: MapLibreMap, latLng: LatLng): LocationMapPoint? {
        val screenPoint = map.projection.toScreenLocation(latLng)
        val feature = map.queryRenderedFeatures(screenPoint, LAYER_POINTS).firstOrNull() ?: return null
        val id = feature.getStringProperty(PROP_ID) ?: return null
        return lastPoints.firstOrNull { it.id == id }
    }
}

private fun List<LocationMapPoint>.toFeatureCollectionWithIds(): FeatureCollection = FeatureCollection.fromFeatures(
    map { point ->
        Feature.fromGeometry(GeoPoint.fromLngLat(point.longitude, point.latitude)).apply {
            addStringProperty(PROP_ID, point.id)
            addStringProperty(PROP_COLOR, if (point.isLatest) LOCATION_HERO_COLOR else LOCATION_DOT_COLOR)
        }
    },
)
