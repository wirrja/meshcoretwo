// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.rememberLocationPermissionAction
import com.meshcoretwo.android.ui.components.rememberMapViewWithLifecycle
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.location.LocationProvider
import com.meshcoretwo.services.location.LocationProviderError
import com.meshcoretwo.services.rendering.NodeNameMatchKind
import com.meshcoretwo.services.rendering.SNRQuality
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point as GeoPoint

/**
 * "View on Map" push from [RepeaterStatusScreen]'s Neighbors section — a trimmed port of
 * `NeighborSNRMapView.swift`: the repeater at center, each exact-match located neighbor pinned
 * with an SNR-colored link line and a distance/SNR badge, neighbors that can't be placed counted
 * in a top pill that opens a sheet listing them. See [NeighborSnrMapViewModel]'s class doc for
 * what's trimmed relative to Swift (no offline packs, no persisted filter/camera/style).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeighborSnrMapScreen(
    connectionManager: ConnectionManager,
    locationProvider: LocationProvider,
    sessionId: UUID,
    onBack: () -> Unit,
) {
    val viewModel: NeighborSnrMapViewModel = viewModel(factory = NeighborSnrMapViewModel.Factory(connectionManager, sessionId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val plotted = uiState.plotted

    val controller = remember { NeighborSnrMapController() }
    val scope = rememberCoroutineScope()
    var hasAutoCentered by rememberSaveable { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var showNoLocationList by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val withLocationPermission = rememberLocationPermissionAction(
        onDenied = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
    )

    LaunchedEffect(Unit) {
        try {
            viewModel.setCurrentLocation(locationProvider.requestCurrentLocation())
        } catch (error: LocationProviderError) {
            // Best-effort: the resolver still works without it, just without proximity tiebreaking.
        }
    }

    LaunchedEffect(plotted) {
        val current = plotted ?: return@LaunchedEffect
        controller.updatePoints(current.points)
        controller.updateLines(current.lines)
        controller.updateBadges(current.badges)
        if (!hasAutoCentered && current.points.isNotEmpty()) {
            controller.fitToPoints(current.points)
            hasAutoCentered = true
        }
    }

    val context = LocalContext.current
    val locationUnavailable = stringResource(R.string.path_map_location_unavailable)
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { snackbarHostState.showSnackbar(it.resolve(context)) }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.neighbors_map_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
                actions = {
                    IconButton(onClick = {
                        withLocationPermission {
                            scope.launch {
                                try {
                                    val fix = locationProvider.requestCurrentLocation()
                                    controller.centerOn(fix.latitude, fix.longitude)
                                } catch (error: LocationProviderError) {
                                    snackbarHostState.showSnackbar(error.message ?: locationUnavailable)
                                }
                            }
                        }
                    }) { Icon(painterResource(R.drawable.ic_my_location), contentDescription = stringResource(R.string.path_map_my_location)) }
                    Box {
                        IconButton(onClick = { showFilterMenu = true }) {
                            BadgedBox(badge = { if (uiState.filter.isActive) Badge() }) {
                                Icon(painterResource(R.drawable.ic_filter_list), contentDescription = stringResource(R.string.common_filter))
                            }
                        }
                        DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.map_filter_favorites_only)) },
                                leadingIcon = { checkedIcon(uiState.filter.favoritesOnly) },
                                onClick = { viewModel.setFavoritesOnly(!uiState.filter.favoritesOnly) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.map_filter_discovered)) },
                                leadingIcon = { checkedIcon(uiState.filter.showDiscovered) },
                                onClick = { viewModel.setShowDiscovered(!uiState.filter.showDiscovered) },
                                enabled = !uiState.filter.favoritesOnly,
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            NeighborSnrMapLibreView(modifier = Modifier.fillMaxSize(), controller = controller)
            if (plotted != null && plotted.unplottable.isNotEmpty()) {
                NoLocationPill(
                    count = plotted.unplottable.size,
                    onClick = { showNoLocationList = true },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                )
            }
        }
    }

    if (showNoLocationList) {
        NoLocationListSheet(unplottable = plotted?.unplottable.orEmpty(), onDismiss = { showNoLocationList = false })
    }
}

@Composable
private fun checkedIcon(checked: Boolean) {
    if (checked) Icon(painterResource(R.drawable.ic_check), contentDescription = null)
}

@Composable
private fun NoLocationPill(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .wrapContentWidth()
            .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.neighbors_not_shown, count), style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoLocationListSheet(unplottable: List<UnplottableNeighbor>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            items(unplottable) { item ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row {
                        Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
                        if (item.matchKind == NodeNameMatchKind.FALLBACK) {
                            Text(" (?)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "%.1f dB".format(Locale.US, item.neighbor.snr),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Divider()
            }
        }
    }
}

// MARK: - Map

/** OpenFreeMap liberty, shared by the node maps in this package (see PLAN.md Л3 for its attribution). */
internal const val NODE_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val LINES_SOURCE_ID = "neighbor-snr-lines"
private const val LINE_LAYER_ID = "neighbor-snr-line-layer"
private const val POINTS_SOURCE_ID = "neighbor-snr-points"
private const val POINTS_CIRCLE_LAYER_ID = "neighbor-snr-points-circle"
private const val POINTS_LABEL_LAYER_ID = "neighbor-snr-points-label"
private const val BADGES_SOURCE_ID = "neighbor-snr-badges"
private const val BADGES_LABEL_LAYER_ID = "neighbor-snr-badges-label"
private const val PROP_LABEL = "label"
private const val PROP_COLOR = "color"
private const val PROP_STROKE_COLOR = "strokeColor"
private const val PROP_TEXT = "text"

private const val CENTER_FILL_COLOR = "#FFFFFF"
private const val CENTER_STROKE_COLOR = "#6A1B9A"
private const val NEIGHBOR_COLOR = "#F59E0B"
private const val NEIGHBOR_STROKE_COLOR = "#FFFFFF"

/**
 * Hex line/casing color per [SNRQuality] — the light-theme values of `ui.theme`'s success/caution
 * /danger tokens (same ones `TraceResultHopRow.kt`'s `signalColor` uses), fixed rather than read
 * from [LocalMeshExtendedColors][com.meshcoretwo.android.ui.theme.LocalMeshExtendedColors]: this
 * builds a MapLibre style JSON once per `attach()`, not a `@Composable`, and the map isn't
 * re-styled on theme change (same reasoning as this file's other fixed map colors below).
 */
private fun SNRQuality.lineColorHex(): String = when (this) {
    SNRQuality.EXCELLENT, SNRQuality.GOOD -> "#1B6D24"
    SNRQuality.FAIR -> "#835400"
    SNRQuality.POOR -> "#B91D20"
    SNRQuality.UNKNOWN -> "#9E9E9E"
}

@Composable
private fun NeighborSnrMapLibreView(modifier: Modifier, controller: NeighborSnrMapController) {
    val mapView = rememberMapViewWithLifecycle()
    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view -> view.getMapAsync { map -> controller.attach(map) } },
    )
}

/**
 * Owns the [MapLibreMap] instance for the neighbor SNR map: point/line/badge GeoJSON sources and
 * camera fitting. Kept separate from `MapScreen.kt`'s and [com.meshcoretwo.android.tools.LosMapController]'s
 * controllers, same reasoning as the latter's doc — a different marker/line set and no tap routing.
 */
private class NeighborSnrMapController {
    private var map: MapLibreMap? = null
    private var attached = false

    /** A fit requested before [attach] ran (the map view hands its instance over asynchronously); replayed on attach. */
    private var pendingFit: List<SnrMapPoint>? = null
    private var pointsSource: GeoJsonSource? = null
    private var linesSource: GeoJsonSource? = null
    private var badgesSource: GeoJsonSource? = null
    private var lastPoints: List<SnrMapPoint> = emptyList()
    private var lastLines: List<SnrMapLine> = emptyList()
    private var lastBadges: List<SnrMapBadge> = emptyList()

    fun attach(map: MapLibreMap) {
        this.map = map
        pendingFit?.let { points ->
            pendingFit = null
            fitToPoints(points)
        }
        if (attached) return
        attached = true

        map.setStyle(Style.Builder().fromUri(NODE_MAP_STYLE_URL)) { style ->
            val lines = GeoJsonSource(LINES_SOURCE_ID, lastLines.toLineFeatureCollection())
            style.addSource(lines)
            style.addLayer(
                LineLayer(LINE_LAYER_ID, LINES_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor(Expression.get(PROP_COLOR)),
                    PropertyFactory.lineWidth(3f),
                ),
            )
            linesSource = lines

            val badges = GeoJsonSource(BADGES_SOURCE_ID, lastBadges.toBadgeFeatureCollection())
            style.addSource(badges)
            style.addLayer(
                SymbolLayer(BADGES_LABEL_LAYER_ID, BADGES_SOURCE_ID).withProperties(
                    PropertyFactory.textField(Expression.get(PROP_TEXT)),
                    PropertyFactory.textSize(10f),
                    PropertyFactory.textColor("#374151"),
                    PropertyFactory.textHaloColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(1.2f),
                    PropertyFactory.textAllowOverlap(false),
                ),
            )
            badgesSource = badges

            val points = GeoJsonSource(POINTS_SOURCE_ID, lastPoints.toPointFeatureCollection())
            style.addSource(points)
            style.addLayer(
                CircleLayer(POINTS_CIRCLE_LAYER_ID, POINTS_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleColor(Expression.get(PROP_COLOR)),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor(Expression.get(PROP_STROKE_COLOR)),
                ),
            )
            style.addLayer(
                SymbolLayer(POINTS_LABEL_LAYER_ID, POINTS_SOURCE_ID).withProperties(
                    PropertyFactory.textField(Expression.get(PROP_LABEL)),
                    PropertyFactory.textSize(11f),
                    PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
                    PropertyFactory.textAnchor("top"),
                    PropertyFactory.textColor("#1F2937"),
                    PropertyFactory.textHaloColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(1.2f),
                    PropertyFactory.textAllowOverlap(false),
                ),
            )
            pointsSource = points
        }
    }

    fun updatePoints(points: List<SnrMapPoint>) {
        lastPoints = points
        pointsSource?.setGeoJson(points.toPointFeatureCollection())
    }

    fun updateLines(lines: List<SnrMapLine>) {
        lastLines = lines
        linesSource?.setGeoJson(lines.toLineFeatureCollection())
    }

    fun updateBadges(badges: List<SnrMapBadge>) {
        lastBadges = badges
        badgesSource?.setGeoJson(badges.toBadgeFeatureCollection())
    }

    fun centerOn(latitude: Double, longitude: Double, zoom: Double = 14.0) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), zoom))
    }

    fun fitToPoints(points: List<SnrMapPoint>) {
        val map = map
        if (map == null) {
            pendingFit = points
            return
        }
        when {
            points.isEmpty() -> Unit
            points.size == 1 -> map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points[0].latitude, points[0].longitude), 12.0))
            else -> {
                val bounds = LatLngBounds.Builder()
                points.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 96))
            }
        }
    }
}

private fun List<SnrMapPoint>.toPointFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(
    map { point ->
        Feature.fromGeometry(GeoPoint.fromLngLat(point.longitude, point.latitude)).apply {
            addStringProperty(PROP_LABEL, point.label)
            if (point.role == SnrMapPointRole.CENTER) {
                addStringProperty(PROP_COLOR, CENTER_FILL_COLOR)
                addStringProperty(PROP_STROKE_COLOR, CENTER_STROKE_COLOR)
            } else {
                addStringProperty(PROP_COLOR, NEIGHBOR_COLOR)
                addStringProperty(PROP_STROKE_COLOR, NEIGHBOR_STROKE_COLOR)
            }
        }
    },
)

private fun List<SnrMapLine>.toLineFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(
    map { line ->
        Feature.fromGeometry(
            LineString.fromLngLats(listOf(GeoPoint.fromLngLat(line.fromLongitude, line.fromLatitude), GeoPoint.fromLngLat(line.toLongitude, line.toLatitude))),
        ).apply {
            addStringProperty(PROP_COLOR, line.quality.lineColorHex())
        }
    },
)

private fun List<SnrMapBadge>.toBadgeFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(
    map { badge ->
        Feature.fromGeometry(GeoPoint.fromLngLat(badge.longitude, badge.latitude)).apply {
            addStringProperty(PROP_TEXT, badge.text)
        }
    },
)
