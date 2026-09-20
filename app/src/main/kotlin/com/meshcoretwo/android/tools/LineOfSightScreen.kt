// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.rememberLocationPermissionAction
import com.meshcoretwo.android.ui.components.rememberMapViewWithLifecycle
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.location.LocationProvider
import com.meshcoretwo.services.location.LocationProviderError
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.rf.GeoCoordinate
import java.util.UUID
import kotlinx.coroutines.delay
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
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point as GeoPoint

private const val KEY_HAS_SEEN_REPEATER_DRAG_HINT = "los_has_seen_repeater_drag_hint"

/**
 * Line of Sight screen — the last of the 9/10/11/12/13 sub-slice series (see
 * [LineOfSightViewModel]'s class doc for the full breakdown; 9 = calc layer, 10 = view model,
 * 11 = sheet content, 12 = terrain canvas). This slice's only new code is the map (points/lines,
 * tap/long-press point placement, repeater-contact tap-to-select) and the screen chrome tying
 * everything else together — a bottom sheet, the drag-hint one-time nudge, and navigation wiring.
 *
 * Ported from `LineOfSightView.swift`, with two deliberate simplifications:
 * - **Two sheet states, not three.** iOS uses `PresentationDetent` fractions 0.25/0.5/1.0
 *   (collapsed/half/expanded), the half one only enabled transiently right after both points are
 *   set. Material3's [BottomSheetScaffold] models exactly two persistent-sheet states
 *   ([SheetValue.PartiallyExpanded]/[SheetValue.Expanded]), so the half state is folded into
 *   "expanded" — cosmetic (one less intermediate resting height), not a functional loss since all
 *   the same content is reachable either way.
 * - **Camera control lives on the screen, not the view model.** [LineOfSightViewModel] deliberately
 *   excludes `MKMapView`/camera-region state (see its class doc) since that layer didn't exist until
 *   now; this screen owns a local `shouldAutoZoomOnNextResult` flag instead of porting that field
 *   onto the view model, keeping the map-camera concern out of the already-ported, already-tested
 *   business logic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineOfSightScreen(
    connectionManager: ConnectionManager,
    locationProvider: LocationProvider,
    prefs: SharedPreferences,
    onBack: () -> Unit,
) {
    val viewModel: LineOfSightViewModel = viewModel(factory = LineOfSightViewModel.Factory(connectionManager))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val controller = remember { LosMapController() }
    val scope = rememberCoroutineScope()
    val scaffoldState = rememberBottomSheetScaffoldState()
    val snackbarHostState = remember { SnackbarHostState() }
    val locationUnavailable = stringResource(R.string.path_map_location_unavailable)
    val withLocationPermission = rememberLocationPermissionAction(
        onDenied = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
    )

    var editingPoint by remember { mutableStateOf<PointID?>(null) }
    var isResultsExpanded by remember { mutableStateOf(false) }
    var isRFSettingsExpanded by remember { mutableStateOf(false) }
    var showDragHint by remember { mutableStateOf(false) }
    var markerCenter by remember { mutableStateOf<Offset?>(null) }
    var shouldAutoZoomOnNextResult by remember { mutableStateOf(false) }
    var hasFittedInitial by rememberSaveable { mutableStateOf(false) }
    var previousRepeaterOnPath by remember { mutableStateOf(false) }

    fun expandSheet() = scope.launch { scaffoldState.bottomSheetState.expand() }
    fun collapseSheet() = scope.launch { scaffoldState.bottomSheetState.partialExpand() }

    fun handleRelocation(coordinate: GeoCoordinate, pointID: PointID) {
        when (pointID) {
            PointID.POINT_A -> viewModel.setPointA(coordinate, null)
            PointID.POINT_B -> viewModel.setPointB(coordinate, null)
            PointID.REPEATER -> viewModel.setRepeaterOffPath(coordinate)
        }
        viewModel.clearAnalysisResults()
        viewModel.setRelocatingPoint(null)
        expandSheet()
    }

    controller.onMapTap = { coordinate ->
        state.relocatingPoint?.let { handleRelocation(coordinate, it) }
    }
    controller.onMapLongPress = { coordinate ->
        val relocating = state.relocatingPoint
        if (relocating != null) {
            handleRelocation(coordinate, relocating)
        } else {
            viewModel.selectPoint(coordinate)
        }
    }
    controller.onRepeaterTap = { contactId ->
        state.repeatersWithLocation.firstOrNull { it.id == contactId }?.let(viewModel::toggleContact)
    }

    LaunchedEffect(Unit) { viewModel.loadRepeaters() }

    LaunchedEffect(state.repeatersWithLocation) {
        controller.updateRepeaters(state.repeatersWithLocation)
        if (!hasFittedInitial && state.repeatersWithLocation.isNotEmpty()) {
            controller.fitBounds(state.repeatersWithLocation.map { LatLng(it.latitude, it.longitude) })
            hasFittedInitial = true
        }
    }

    LaunchedEffect(state.pointA, state.pointB, state.repeaterPoint) {
        controller.updateMarkers(buildLosMarkers(state))
        controller.updateLines(buildLosLines(state))
    }

    LaunchedEffect(state.pointA != null, state.pointB != null) {
        if (state.pointA != null && state.pointB != null) expandSheet() else collapseSheet()
    }

    LaunchedEffect(state.repeaterPoint?.isOnPath) {
        val isNowOnPath = state.repeaterPoint?.isOnPath == true
        if (!previousRepeaterOnPath && isNowOnPath && !prefs.getBoolean(KEY_HAS_SEEN_REPEATER_DRAG_HINT, false)) {
            prefs.edit { putBoolean(KEY_HAS_SEEN_REPEATER_DRAG_HINT, true) }
            showDragHint = true
            delay(5_000)
            showDragHint = false
        }
        previousRepeaterOnPath = isNowOnPath
    }

    LaunchedEffect(state.analysisStatus) {
        val isResult = state.analysisStatus is AnalysisStatus.Result || state.analysisStatus is AnalysisStatus.RelayResult
        val pointA = state.pointA?.coordinate
        val pointB = state.pointB?.coordinate
        if (isResult && shouldAutoZoomOnNextResult && pointA != null && pointB != null) {
            shouldAutoZoomOnNextResult = false
            val coords = buildList {
                add(LatLng(pointA.latitude, pointA.longitude))
                state.repeaterPoint?.let { add(LatLng(it.coordinate.latitude, it.coordinate.longitude)) }
                add(LatLng(pointB.latitude, pointB.longitude))
            }
            controller.fitBounds(coords)
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 220.dp,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.los_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
                actions = {
                    IconButton(onClick = {
                        withLocationPermission {
                            scope.launch {
                                try {
                                    val fix = locationProvider.requestCurrentLocation()
                                    controller.centerOn(fix.latitude, fix.longitude, 15.0)
                                } catch (error: LocationProviderError) {
                                    snackbarHostState.showSnackbar(error.message ?: locationUnavailable)
                                }
                            }
                        }
                    }) { Icon(painterResource(R.drawable.ic_my_location), contentDescription = stringResource(R.string.path_map_my_location)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        sheetContent = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PointsSummarySectionView(
                    state = state,
                    viewModel = viewModel,
                    editingPoint = editingPoint,
                    onEditingPointChange = { editingPoint = it },
                    onRelocate = ::collapseSheet,
                )

                val hasResult = state.analysisStatus is AnalysisStatus.Result || state.analysisStatus is AnalysisStatus.RelayResult

                if (state.canAnalyze && !hasResult) {
                    AnalyzeButtonRow(state, viewModel) { shouldAutoZoomOnNextResult = true; expandSheet() }
                    RFSettingsSectionView(state, viewModel, isRFSettingsExpanded) { isRFSettingsExpanded = it }
                }

                when (val status = state.analysisStatus) {
                    is AnalysisStatus.Result -> {
                        AnalyzeButtonRow(state, viewModel) { shouldAutoZoomOnNextResult = true; expandSheet() }
                        ResultsCardView(status.result, isResultsExpanded) { isResultsExpanded = it }
                        TerrainProfileSectionView(state, viewModel, showDragHint, markerCenter, { markerCenter = it })
                        RFSettingsSectionView(state, viewModel, isRFSettingsExpanded) { isRFSettingsExpanded = it }
                    }
                    is AnalysisStatus.RelayResult -> {
                        AnalyzeButtonRow(state, viewModel) { shouldAutoZoomOnNextResult = true; expandSheet() }
                        RelayResultsCardView(status.result, isResultsExpanded) { isResultsExpanded = it }
                        TerrainProfileSectionView(state, viewModel, showDragHint, markerCenter, { markerCenter = it })
                        RFSettingsSectionView(state, viewModel, isRFSettingsExpanded) { isRFSettingsExpanded = it }
                    }
                    is AnalysisStatus.Error -> {
                        AnalysisErrorView(status.message.asString(), state.repeaterPoint != null) {
                            if (state.repeaterPoint != null) viewModel.analyzeWithRepeater() else viewModel.analyze()
                        }
                    }
                    AnalysisStatus.Idle -> Unit
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            LosMapView(modifier = Modifier.fillMaxSize(), controller = controller)
        }
    }
}

@Composable
private fun AnalyzeButtonRow(state: LineOfSightUiState, viewModel: LineOfSightViewModel, onAnalyzeStart: () -> Unit) {
    Button(
        onClick = {
            onAnalyzeStart()
            if (state.repeaterPoint != null) viewModel.analyzeWithRepeater() else viewModel.analyze()
        },
        enabled = !state.isAnalyzing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.isAnalyzing) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Text(stringResource(R.string.los_analyzing))
        } else {
            Text(stringResource(R.string.los_analyze))
        }
    }
}

// MARK: - Map

private const val LOS_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val LOS_LINES_SOURCE_ID = "los-lines"
private const val LOS_LINE_LAYER_ID = "los-line-layer"
private const val LOS_REPEATERS_SOURCE_ID = "los-repeaters"
private const val LOS_REPEATERS_LAYER_ID = "los-repeaters-circle"
private const val LOS_MARKERS_SOURCE_ID = "los-markers"
private const val LOS_MARKERS_CIRCLE_LAYER_ID = "los-markers-circle"
private const val LOS_MARKERS_LABEL_LAYER_ID = "los-markers-label"
private const val LOS_REPEATERS_LABELS_SOURCE_ID = "los-repeaters-labels"
private const val LOS_MARKERS_LABELS_SOURCE_ID = "los-markers-labels"
private const val LOS_REPEATERS_LABEL_LAYER_ID = "los-repeaters-label"
/**
 * A font stack the map style's glyph server actually hosts — MapLibre's default (`Open Sans
 * Regular`) 404s on OpenFreeMap, and unloaded glyphs stall every layer of the same source.
 */
private val LOS_LABEL_FONT = arrayOf("Noto Sans Regular")
private const val PROP_ID = "id"
private const val PROP_LABEL = "label"
private const val PROP_COLOR = "color"

private const val POINT_A_COLOR = "#1976D2"
private const val POINT_B_COLOR = "#2E7D32"
private const val REPEATER_COLOR = "#6A1B9A"

private data class LosMarker(val id: String, val latitude: Double, val longitude: Double, val label: String, val colorHex: String)

private fun buildLosMarkers(state: LineOfSightUiState): List<LosMarker> = buildList {
    state.pointA?.let { add(LosMarker("A", it.coordinate.latitude, it.coordinate.longitude, "A", POINT_A_COLOR)) }
    state.pointB?.let { add(LosMarker("B", it.coordinate.latitude, it.coordinate.longitude, "B", POINT_B_COLOR)) }
    state.repeaterPoint?.let { add(LosMarker("R", it.coordinate.latitude, it.coordinate.longitude, "R", REPEATER_COLOR)) }
}

private fun buildLosLines(state: LineOfSightUiState): List<List<LatLng>> {
    val a = state.pointA?.coordinate
    val b = state.pointB?.coordinate
    val r = state.repeaterPoint?.coordinate
    return when {
        a != null && r != null && b != null -> listOf(
            listOf(LatLng(a.latitude, a.longitude), LatLng(r.latitude, r.longitude)),
            listOf(LatLng(r.latitude, r.longitude), LatLng(b.latitude, b.longitude)),
        )
        a != null && b != null -> listOf(listOf(LatLng(a.latitude, a.longitude), LatLng(b.latitude, b.longitude)))
        else -> emptyList()
    }
}

/**
 * Owns the [MapLibreMap] instance for the Line of Sight screen: point/repeater/line GeoJSON
 * sources, tap routing (repeater-contact tap vs. plain map tap), and camera fitting. Kept separate
 * from `MapScreen.kt`'s `MapMarkerController` — different marker set (A/B/R + selectable
 * repeaters, not one uniform contact layer), different tap semantics (relocate-if-relocating vs.
 * open a details sheet), and a lines layer that tab doesn't need.
 */
private class LosMapController {
    var onMapTap: ((GeoCoordinate) -> Unit)? = null
    var onMapLongPress: ((GeoCoordinate) -> Unit)? = null
    var onRepeaterTap: ((UUID) -> Unit)? = null

    private var map: MapLibreMap? = null
    private var attached = false
    private var markersSource: GeoJsonSource? = null
    private var linesSource: GeoJsonSource? = null
    private var repeatersSource: GeoJsonSource? = null
    private var repeaterLabelsSource: GeoJsonSource? = null
    private var markerLabelsSource: GeoJsonSource? = null
    private var lastMarkers: List<LosMarker> = emptyList()
    private var lastLines: List<List<LatLng>> = emptyList()
    private var lastRepeaters: List<ContactDto> = emptyList()

    /** A [fitBounds] requested before the map finished attaching; replayed by [attach]. */
    private var pendingFit: List<LatLng>? = null

    fun attach(map: MapLibreMap) {
        this.map = map
        pendingFit?.let { pendingFit = null; fitBounds(it) }
        if (attached) return
        attached = true

        map.addOnMapClickListener { latLng ->
            val hitRepeater = queryRepeaterAt(map, latLng)
            if (hitRepeater != null) {
                onRepeaterTap?.invoke(hitRepeater)
            } else {
                onMapTap?.invoke(GeoCoordinate(latLng.latitude, latLng.longitude))
            }
            true
        }
        map.addOnMapLongClickListener { latLng ->
            onMapLongPress?.invoke(GeoCoordinate(latLng.latitude, latLng.longitude))
            true
        }

        map.setStyle(Style.Builder().fromUri(LOS_MAP_STYLE_URL)) { style ->
            val lines = GeoJsonSource(LOS_LINES_SOURCE_ID, lastLines.toLineFeatureCollection())
            style.addSource(lines)
            style.addLayer(
                LineLayer(LOS_LINE_LAYER_ID, LOS_LINES_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor("#607D8B"),
                    PropertyFactory.lineWidth(3f),
                ),
            )
            linesSource = lines

            val repeaters = GeoJsonSource(LOS_REPEATERS_SOURCE_ID, lastRepeaters.toRepeaterFeatureCollection())
            style.addSource(repeaters)
            style.addLayer(
                CircleLayer(LOS_REPEATERS_LAYER_ID, LOS_REPEATERS_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(6f),
                    PropertyFactory.circleColor("#F59E0B"),
                    PropertyFactory.circleStrokeWidth(1.5f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                ),
            )
            // Label layers get their own sources: tiles with unresolved glyphs are held back per
            // source, and that must never delay the dots themselves.
            val repeaterLabels = GeoJsonSource(LOS_REPEATERS_LABELS_SOURCE_ID, lastRepeaters.toRepeaterFeatureCollection())
            style.addSource(repeaterLabels)
            repeaterLabelsSource = repeaterLabels
            style.addLayer(
                SymbolLayer(LOS_REPEATERS_LABEL_LAYER_ID, LOS_REPEATERS_LABELS_SOURCE_ID).withProperties(
                    PropertyFactory.textField(Expression.get(PROP_LABEL)),
                    PropertyFactory.textFont(LOS_LABEL_FONT),
                    PropertyFactory.textSize(11f),
                    PropertyFactory.textColor("#1F2937"),
                    PropertyFactory.textHaloColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(1.5f),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textOffset(arrayOf(0f, 0.8f)),
                    PropertyFactory.textOptional(true),
                ),
            )
            repeatersSource = repeaters

            val markers = GeoJsonSource(LOS_MARKERS_SOURCE_ID, lastMarkers.toMarkerFeatureCollection())
            style.addSource(markers)
            style.addLayer(
                CircleLayer(LOS_MARKERS_CIRCLE_LAYER_ID, LOS_MARKERS_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(11f),
                    PropertyFactory.circleColor(Expression.get(PROP_COLOR)),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                ),
            )
            val markerLabels = GeoJsonSource(LOS_MARKERS_LABELS_SOURCE_ID, lastMarkers.toMarkerFeatureCollection())
            style.addSource(markerLabels)
            markerLabelsSource = markerLabels
            style.addLayer(
                SymbolLayer(LOS_MARKERS_LABEL_LAYER_ID, LOS_MARKERS_LABELS_SOURCE_ID).withProperties(
                    PropertyFactory.textField(Expression.get(PROP_LABEL)),
                    PropertyFactory.textFont(LOS_LABEL_FONT),
                    PropertyFactory.textSize(12f),
                    PropertyFactory.textColor("#FFFFFF"),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                ),
            )
            markersSource = markers
        }
    }

    fun updateMarkers(markers: List<LosMarker>) {
        lastMarkers = markers
        val collection = markers.toMarkerFeatureCollection()
        markersSource?.setGeoJson(collection)
        markerLabelsSource?.setGeoJson(collection)
    }

    fun updateLines(lines: List<List<LatLng>>) {
        lastLines = lines
        linesSource?.setGeoJson(lines.toLineFeatureCollection())
    }

    fun updateRepeaters(repeaters: List<ContactDto>) {
        lastRepeaters = repeaters
        val collection = repeaters.toRepeaterFeatureCollection()
        repeatersSource?.setGeoJson(collection)
        repeaterLabelsSource?.setGeoJson(collection)
    }

    fun centerOn(latitude: Double, longitude: Double, zoom: Double = 15.0) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), zoom))
    }

    fun fitBounds(coordinates: List<LatLng>) {
        val map = map
        if (map == null) {
            pendingFit = coordinates
            return
        }
        when {
            coordinates.isEmpty() -> Unit
            coordinates.size == 1 -> centerOn(coordinates[0].latitude, coordinates[0].longitude, 14.0)
            else -> {
                val bounds = LatLngBounds.Builder()
                coordinates.forEach { bounds.include(it) }
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 96))
            }
        }
    }

    private fun queryRepeaterAt(map: MapLibreMap, latLng: LatLng): UUID? {
        val screenPoint = map.projection.toScreenLocation(latLng)
        val feature = map.queryRenderedFeatures(screenPoint, LOS_REPEATERS_LAYER_ID).firstOrNull() ?: return null
        val id = feature.getStringProperty(PROP_ID) ?: return null
        return lastRepeaters.firstOrNull { it.id.toString() == id }?.id
    }
}

private fun List<LosMarker>.toMarkerFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(
    map { marker ->
        Feature.fromGeometry(GeoPoint.fromLngLat(marker.longitude, marker.latitude)).apply {
            addStringProperty(PROP_ID, marker.id)
            addStringProperty(PROP_LABEL, marker.label)
            addStringProperty(PROP_COLOR, marker.colorHex)
        }
    },
)

private fun List<ContactDto>.toRepeaterFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(
    map { contact ->
        Feature.fromGeometry(GeoPoint.fromLngLat(contact.longitude, contact.latitude)).apply {
            addStringProperty(PROP_ID, contact.id.toString())
            addStringProperty(PROP_LABEL, contact.displayName)
        }
    },
)

private fun List<List<LatLng>>.toLineFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(
    map { segment -> Feature.fromGeometry(LineString.fromLngLats(segment.map { GeoPoint.fromLngLat(it.longitude, it.latitude) })) },
)

@Composable
private fun LosMapView(modifier: Modifier, controller: LosMapController) {
    val mapView = rememberMapViewWithLifecycle()
    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view -> view.getMapAsync { map -> controller.attach(map) } },
    )
}
