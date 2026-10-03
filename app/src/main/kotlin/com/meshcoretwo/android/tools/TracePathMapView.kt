// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import android.content.SharedPreferences
import android.graphics.PointF
import android.graphics.RectF
import android.view.Gravity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import com.meshcoretwo.android.R
import com.meshcoretwo.android.map.MapFilterState
import com.meshcoretwo.android.map.SnrLink
import com.meshcoretwo.android.map.setBaseStyle
import com.meshcoretwo.android.ui.components.rememberLocationPermissionAction
import com.meshcoretwo.android.ui.components.rememberMapViewWithLifecycle
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.location.LocationProvider
import com.meshcoretwo.services.location.LocationProviderError
import com.meshcoretwo.services.rendering.SNRQuality
import com.meshcoretwo.services.rf.GeoCoordinate
import java.util.UUID
import kotlin.math.hypot
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
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

private const val KEY_FAVORITES_ONLY = "trace_path_map_favorites_only"
private const val KEY_SHOW_DISCOVERED = "trace_path_map_show_discovered"

/** Swift seeds the Trace Path host's filter with discovered repeaters shown (`MapFilterState.seed(for: .tracePath)`). */
private val DEFAULT_TRACE_FILTER = MapFilterState(showDiscovered = true)

private fun loadFilter(prefs: SharedPreferences) = MapFilterState(
    favoritesOnly = prefs.getBoolean(KEY_FAVORITES_ONLY, DEFAULT_TRACE_FILTER.favoritesOnly),
    showDiscovered = prefs.getBoolean(KEY_SHOW_DISCOVERED, DEFAULT_TRACE_FILTER.showDiscovered),
)

private fun saveFilter(prefs: SharedPreferences, filter: MapFilterState) = prefs.edit {
    putBoolean(KEY_FAVORITES_ONLY, filter.favoritesOnly)
    putBoolean(KEY_SHOW_DISCOVERED, filter.showDiscovered)
}

/**
 * Map mode of [TracePathScreen]: build the path by tapping repeaters on the map. Ported from
 * `TracePathMapView.swift` with its `TracePathFloatingButtonsView`/`TracePathMapToolbarView`
 * satellites; the logic of `TracePathMapViewModel.swift` lives in [TracePathMapBuilder].
 *
 * Tapping a pin off the path appends it, tapping the last hop takes it back, a middle hop stays
 * (the list edits those). The path is drawn from the user's position through every located hop;
 * after a successful trace each segment is colored by the SNR its hop reported, with a
 * distance/SNR badge, and the top banner shows the hop count and total distance.
 *
 * Trimmed vs. Swift, as on the other map screens here: no style/labels/north-lock controls and no
 * haptics (a rejected middle-hop tap explains itself in a snackbar instead). Swift's save-path
 * alert is not ported — nothing in `TracePathMapView` ever shows it; saving stays on the results
 * screen. The filter (favorites, discovered nodes) is remembered like Swift's per-host
 * `@AppStorage`.
 */
@Composable
fun TracePathMapContent(
    state: TracePathUiState,
    viewModel: TracePathViewModel,
    locationProvider: LocationProvider,
    prefs: SharedPreferences,
    userLocation: LocationFix?,
    onUserLocation: (LocationFix) -> Unit,
    snackbarHostState: SnackbarHostState,
    onClearPath: () -> Unit,
    onRunTrace: () -> Unit,
    onShowResults: (TraceResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = remember { TracePathMapController() }
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(loadFilter(prefs)) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var hasCentered by remember { mutableStateOf(false) }
    val content = remember(state, userLocation, filter) { TracePathMapBuilder.build(state, userLocation, filter) }

    val density = LocalDensity.current
    controller.touchRadiusPx = with(density) { 22.dp.toPx() }
    controller.fitPaddingPx = with(density) { floatArrayOf(48.dp.toPx(), 72.dp.toPx(), 72.dp.toPx(), 140.dp.toPx()) }
    controller.compassMarginPx = with(density) { 12.dp.roundToPx() }

    val middleHopMessage = stringResource(R.string.trace_map_middle_hop)
    val locationUnavailable = stringResource(R.string.path_map_location_unavailable)
    val withLocationPermission = rememberLocationPermissionAction(
        onDenied = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
    )

    controller.onPinTap = { pinId ->
        when (val action = TracePathMapBuilder.tapAction(content, pinId)) {
            is TracePathPinAction.Add -> viewModel.addNode(action.node)
            TracePathPinAction.RemoveLast -> viewModel.removeRepeater(state.outboundPath.lastIndex)
            TracePathPinAction.RejectMiddleHop -> scope.launch { snackbarHostState.showSnackbar(middleHopMessage) }
            null -> Unit
        }
    }

    // Best effort and without asking: the drawn path starts at the user, and hop resolution and
    // result distances use the fix too. The location button asks for permission.
    LaunchedEffect(Unit) {
        if (userLocation != null) return@LaunchedEffect
        try {
            onUserLocation(locationProvider.requestCurrentLocation())
        } catch (error: LocationProviderError) {
            // Without a fix the path starts at its first hop.
        }
    }

    LaunchedEffect(content, userLocation) {
        controller.update(content, userLocation)
        // Ported from `performInitialCentering`: the path when there is one, else every pin.
        if (!hasCentered) {
            val target = content.pathCoordinates.takeIf { state.outboundPath.isNotEmpty() && it.isNotEmpty() } ?: content.pinCoordinates
            if (target.isNotEmpty()) {
                controller.fit(target)
                hasCentered = true
            }
        }
    }

    val result = state.result?.takeIf { it.success }

    Box(modifier = modifier) {
        TracePathMapLibreView(modifier = Modifier.fillMaxSize(), controller = controller)

        if (result != null) {
            PathDistanceBanner(
                // Minus this device at both ends, as in Swift.
                hopCount = (result.hops.size - 2).coerceAtLeast(0),
                totalPathDistance = state.totalPathDistance,
                onClick = { onShowResults(result) },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            )
        }

        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapControlButton(R.drawable.ic_my_location, stringResource(R.string.path_map_my_location)) {
                withLocationPermission {
                    scope.launch {
                        try {
                            val fix = locationProvider.requestCurrentLocation()
                            onUserLocation(fix)
                            controller.centerOn(fix.latitude, fix.longitude)
                        } catch (error: LocationProviderError) {
                            snackbarHostState.showSnackbar(error.message ?: locationUnavailable)
                        }
                    }
                }
            }
            Box {
                MapControlButton(R.drawable.ic_filter_list, stringResource(R.string.common_filter), showBadge = filter != DEFAULT_TRACE_FILTER) {
                    showFilterMenu = true
                }
                DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.map_filter_favorites_only)) },
                        leadingIcon = { CheckedIcon(filter.favoritesOnly) },
                        onClick = {
                            filter = filter.withFavoritesOnly(!filter.favoritesOnly)
                            saveFilter(prefs, filter)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.map_filter_discovered)) },
                        leadingIcon = { CheckedIcon(filter.showDiscovered) },
                        onClick = {
                            filter = filter.withShowDiscovered(!filter.showDiscovered)
                            saveFilter(prefs, filter)
                        },
                        enabled = !filter.favoritesOnly,
                    )
                }
            }
            if (state.outboundPath.isNotEmpty()) {
                MapControlButton(R.drawable.ic_open_in_full, stringResource(R.string.trace_map_center_on_path)) {
                    controller.fit(content.pathCoordinates.ifEmpty { content.pinCoordinates })
                }
            }
        }

        // Kept above MapLibre's logo and attribution button, which sit at the bottom-left edge.
        val bottomControlsModifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 36.dp)
        if (state.outboundPath.isEmpty()) {
            Surface(
                modifier = bottomControlsModifier,
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
            ) {
                Text(
                    stringResource(R.string.trace_map_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        } else {
            // Ported from `TracePathFloatingButtonsView.swift`: clear, run, results as separate floating buttons.
            Row(
                modifier = bottomControlsModifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MapActionButton(R.drawable.ic_delete, stringResource(R.string.common_clear_path), onClick = onClearPath)
                ExtendedFloatingActionButton(
                    onClick = {
                        if (!state.isRunning) {
                            controller.fit(content.pathCoordinates.ifEmpty { content.pinCoordinates })
                            onRunTrace()
                        }
                    },
                    icon = {
                        // Keeps its colors while running instead of greying out like a disabled button.
                        if (state.isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                        } else {
                            Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = null)
                        }
                    },
                    text = {
                        Text(
                            stringResource(if (state.isRunning) R.string.trace_running else R.string.trace_map_run),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.weight(1f, fill = false),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
                if (result != null) {
                    MapActionButton(R.drawable.ic_show_chart, stringResource(R.string.trace_map_results)) { onShowResults(result) }
                }
            }
        }
    }
}

/** Hop count and total distance of the last successful trace. Ported from `PathDistanceBanner.swift`; tapping it opens the results. */
@Composable
private fun PathDistanceBanner(hopCount: Int, totalPathDistance: Double?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        val hops = pluralStringResource(R.plurals.trace_map_hops, hopCount, hopCount)
        val text = totalPathDistance?.let { "$hops • ${LOSFormatters.formatDistance(it)}" } ?: hops
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun MapControlButton(@DrawableRes icon: Int, contentDescription: String, showBadge: Boolean = false, onClick: () -> Unit) {
    SmallFloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp, pressedElevation = 2.dp),
    ) {
        BadgedBox(badge = { if (showBadge) Badge() }) {
            Icon(painterResource(icon), contentDescription = contentDescription)
        }
    }
}

/** A full-size floating button of the bottom row, styled like the location button of the main map. */
@Composable
private fun MapActionButton(@DrawableRes icon: Int, contentDescription: String, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 3.dp),
    ) {
        Icon(painterResource(icon), contentDescription = contentDescription)
    }
}

@Composable
private fun CheckedIcon(checked: Boolean) {
    if (checked) Icon(painterResource(R.drawable.ic_check), contentDescription = null)
}

// MARK: - Map

private const val LINES_SOURCE_ID = "trace-lines"
private const val LINE_DASHED_CASING_LAYER_ID = "trace-line-dashed-casing"
private const val LINE_DASHED_LAYER_ID = "trace-line-dashed"
private const val LINE_SOLID_CASING_LAYER_ID = "trace-line-solid-casing"
private const val LINE_SOLID_LAYER_ID = "trace-line-solid"
private const val USER_SOURCE_ID = "trace-user"
private const val USER_LAYER_ID = "trace-user-circle"
private const val PINS_SOURCE_ID = "trace-pins"
private const val PINS_LAYER_ID = "trace-pins-circle"
private const val PIN_LABELS_SOURCE_ID = "trace-pin-labels"
private const val PIN_NAMES_LAYER_ID = "trace-pin-names"
private const val PIN_HOPS_LAYER_ID = "trace-pin-hops"
private const val BADGES_SOURCE_ID = "trace-badges"
private const val BADGES_LAYER_ID = "trace-badges-label"

private const val PROP_ID = "id"
private const val PROP_LABEL = "label"
private const val PROP_HOP = "hop"
private const val PROP_COLOR = "color"
private const val PROP_STROKE_COLOR = "strokeColor"
private const val PROP_RADIUS = "radius"
private const val PROP_STROKE_WIDTH = "strokeWidth"
private const val PROP_DASHED = "dashed"
private const val PROP_TEXT = "text"

/** Same amber as the repeater dots of the Line of Sight map; a path member turns white with an amber ring. */
private const val REPEATER_COLOR = "#F59E0B"
private const val PIN_STROKE_COLOR = "#FFFFFF"
private const val UNTRACED_LINE_COLOR = "#8E8E93"
private const val USER_COLOR = "#007AFF"

@Composable
private fun TracePathMapLibreView(modifier: Modifier, controller: TracePathMapController) {
    val mapView = rememberMapViewWithLifecycle()
    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view -> view.getMapAsync { map -> controller.attach(map) } },
    )
}

/**
 * Owns the [MapLibreMap] of the trace path map: line/pin/badge/user GeoJSON sources, pin tap
 * routing and camera fitting. Kept separate from the other map controllers for the same reason
 * the Line of Sight map's controller gives: its own marker set and tap semantics.
 *
 * Line styles follow `MC1MapView+Layers.swift`: untraced, weak and fair links dashed with a white
 * casing, good links solid and wider. MapLibre has no data-driven `line-dasharray`, so dashed and
 * solid links are separate layers filtered on a feature flag.
 */
private class TracePathMapController {
    var onPinTap: ((UUID) -> Unit)? = null
    var touchRadiusPx: Float = 60f

    /** Camera padding left, top, right, bottom; the bottom one clears the action bar. */
    var fitPaddingPx: FloatArray = floatArrayOf(96f, 96f, 96f, 96f)
    var compassMarginPx: Int = 36

    private var map: MapLibreMap? = null
    private var attached = false
    private var pendingFit: List<GeoCoordinate>? = null
    private var linesSource: GeoJsonSource? = null
    private var userSource: GeoJsonSource? = null
    private var pinsSource: GeoJsonSource? = null
    private var pinLabelsSource: GeoJsonSource? = null
    private var badgesSource: GeoJsonSource? = null
    private var lastContent = PlottedTracePath(emptyList(), emptyList(), emptyList(), emptyList())
    private var lastUser: LocationFix? = null

    fun attach(map: MapLibreMap) {
        this.map = map
        pendingFit?.let { coordinates ->
            pendingFit = null
            fit(coordinates)
        }
        if (attached) return
        attached = true

        // Top-right holds the location/filter/fit buttons, so the compass moves to the top-left.
        map.uiSettings.compassGravity = Gravity.TOP or Gravity.START
        map.uiSettings.setCompassMargins(compassMarginPx, compassMarginPx, 0, 0)

        map.addOnMapClickListener { latLng -> handleTap(map, latLng) }

        map.setBaseStyle { style, labelFont ->
            val lines = GeoJsonSource(LINES_SOURCE_ID, lastContent.lines.toLineFeatureCollection())
            style.addSource(lines)
            val dashed = Expression.eq(Expression.get(PROP_DASHED), Expression.literal(true))
            val solid = Expression.eq(Expression.get(PROP_DASHED), Expression.literal(false))
            style.addLayer(
                LineLayer(LINE_DASHED_CASING_LAYER_ID, LINES_SOURCE_ID).withFilter(dashed).withProperties(
                    PropertyFactory.lineColor(PIN_STROKE_COLOR),
                    PropertyFactory.lineOpacity(0.8f),
                    PropertyFactory.lineWidth(6f),
                    PropertyFactory.lineDasharray(arrayOf(0.7f, 1.3f)),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                LineLayer(LINE_DASHED_LAYER_ID, LINES_SOURCE_ID).withFilter(dashed).withProperties(
                    PropertyFactory.lineColor(Expression.get(PROP_COLOR)),
                    PropertyFactory.lineWidth(3f),
                    PropertyFactory.lineDasharray(arrayOf(1.4f, 2.6f)),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                LineLayer(LINE_SOLID_CASING_LAYER_ID, LINES_SOURCE_ID).withFilter(solid).withProperties(
                    PropertyFactory.lineColor(PIN_STROKE_COLOR),
                    PropertyFactory.lineOpacity(0.8f),
                    PropertyFactory.lineWidth(7f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                LineLayer(LINE_SOLID_LAYER_ID, LINES_SOURCE_ID).withFilter(solid).withProperties(
                    PropertyFactory.lineColor(Expression.get(PROP_COLOR)),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            linesSource = lines

            val user = GeoJsonSource(USER_SOURCE_ID, lastUser.toUserFeatureCollection())
            style.addSource(user)
            style.addLayer(
                CircleLayer(USER_LAYER_ID, USER_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor(USER_COLOR),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor(PIN_STROKE_COLOR),
                ),
            )
            userSource = user

            val pins = GeoJsonSource(PINS_SOURCE_ID, lastContent.pins.toPinFeatureCollection())
            style.addSource(pins)
            style.addLayer(
                CircleLayer(PINS_LAYER_ID, PINS_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(Expression.get(PROP_RADIUS)),
                    PropertyFactory.circleColor(Expression.get(PROP_COLOR)),
                    PropertyFactory.circleStrokeWidth(Expression.get(PROP_STROKE_WIDTH)),
                    PropertyFactory.circleStrokeColor(Expression.get(PROP_STROKE_COLOR)),
                ),
            )
            pinsSource = pins

            // Labels get their own source: tiles with unresolved glyphs are held back per source,
            // and that must never delay the dots themselves (same as the Line of Sight map).
            val pinLabels = GeoJsonSource(PIN_LABELS_SOURCE_ID, lastContent.pins.toPinFeatureCollection())
            style.addSource(pinLabels)
            style.addLayer(
                SymbolLayer(PIN_NAMES_LAYER_ID, PIN_LABELS_SOURCE_ID).withProperties(
                    PropertyFactory.textField(Expression.get(PROP_LABEL)),
                    PropertyFactory.textFont(labelFont),
                    PropertyFactory.textSize(11f),
                    PropertyFactory.textColor("#1F2937"),
                    PropertyFactory.textHaloColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(1.5f),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
                    PropertyFactory.textOptional(true),
                ),
            )
            style.addLayer(
                SymbolLayer(PIN_HOPS_LAYER_ID, PIN_LABELS_SOURCE_ID).withFilter(Expression.has(PROP_HOP)).withProperties(
                    PropertyFactory.textField(Expression.get(PROP_HOP)),
                    PropertyFactory.textFont(labelFont),
                    PropertyFactory.textSize(12f),
                    PropertyFactory.textColor("#1F2937"),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                ),
            )
            pinLabelsSource = pinLabels

            val badges = GeoJsonSource(BADGES_SOURCE_ID, lastContent.badges.toBadgeFeatureCollection())
            style.addSource(badges)
            style.addLayer(
                SymbolLayer(BADGES_LAYER_ID, BADGES_SOURCE_ID).withProperties(
                    PropertyFactory.textField(Expression.get(PROP_TEXT)),
                    PropertyFactory.textFont(labelFont),
                    PropertyFactory.textSize(10f),
                    PropertyFactory.textColor("#374151"),
                    PropertyFactory.textHaloColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(1.2f),
                ),
            )
            badgesSource = badges
        }
    }

    fun update(content: PlottedTracePath, user: LocationFix?) {
        lastContent = content
        lastUser = user
        linesSource?.setGeoJson(content.lines.toLineFeatureCollection())
        userSource?.setGeoJson(user.toUserFeatureCollection())
        val pins = content.pins.toPinFeatureCollection()
        pinsSource?.setGeoJson(pins)
        pinLabelsSource?.setGeoJson(pins)
        badgesSource?.setGeoJson(content.badges.toBadgeFeatureCollection())
    }

    fun centerOn(latitude: Double, longitude: Double, zoom: Double = 13.0) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), zoom))
    }

    fun fit(coordinates: List<GeoCoordinate>) {
        val map = map
        if (map == null) {
            pendingFit = coordinates
            return
        }
        when {
            coordinates.isEmpty() -> Unit
            coordinates.size == 1 -> centerOn(coordinates[0].latitude, coordinates[0].longitude)
            else -> {
                val bounds = LatLngBounds.Builder()
                coordinates.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
                val (left, top, right, bottom) = fitPaddingPx.map { it.toInt() }
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), left, top, right, bottom))
            }
        }
    }

    /** Routes a tap to the nearest pin within [touchRadiusPx]; a tap elsewhere falls through to the map. */
    private fun handleTap(map: MapLibreMap, latLng: LatLng): Boolean {
        val tap = map.projection.toScreenLocation(latLng)
        val radius = touchRadiusPx
        val hits = map.queryRenderedFeatures(RectF(tap.x - radius, tap.y - radius, tap.x + radius, tap.y + radius), PINS_LAYER_ID)
        val nearest = hits.minByOrNull { feature ->
            val point = feature.geometry() as? GeoPoint ?: return@minByOrNull Float.MAX_VALUE
            val screen: PointF = map.projection.toScreenLocation(LatLng(point.latitude(), point.longitude()))
            hypot(screen.x - tap.x, screen.y - tap.y)
        } ?: return false
        val id = nearest.getStringProperty(PROP_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return false
        onPinTap?.invoke(id)
        return true
    }
}

private fun List<TracePathMapPin>.toPinFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(
    // Path members last, so they draw on top of the other pins.
    sortedBy { it.inPath }.map { pin ->
        Feature.fromGeometry(GeoPoint.fromLngLat(pin.longitude, pin.latitude)).apply {
            addStringProperty(PROP_ID, pin.id.toString())
            addStringProperty(PROP_LABEL, pin.label)
            pin.hopIndex?.let { addStringProperty(PROP_HOP, it.toString()) }
            addStringProperty(PROP_COLOR, if (pin.inPath) PIN_STROKE_COLOR else REPEATER_COLOR)
            addStringProperty(PROP_STROKE_COLOR, if (pin.inPath) REPEATER_COLOR else PIN_STROKE_COLOR)
            addNumberProperty(PROP_RADIUS, if (pin.inPath) 11f else 7f)
            addNumberProperty(PROP_STROKE_WIDTH, if (pin.inPath) 3f else 1.5f)
        }
    },
)

private fun List<TracePathMapLine>.toLineFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(
    map { line ->
        Feature.fromGeometry(
            LineString.fromLngLats(
                listOf(GeoPoint.fromLngLat(line.fromLongitude, line.fromLatitude), GeoPoint.fromLngLat(line.toLongitude, line.toLatitude)),
            ),
        ).apply {
            val quality = line.quality
            addStringProperty(PROP_COLOR, if (quality == null) UNTRACED_LINE_COLOR else SnrLink.lineColorHex(quality))
            addBooleanProperty(PROP_DASHED, quality != SNRQuality.EXCELLENT && quality != SNRQuality.GOOD)
        }
    },
)

private fun List<TracePathMapBadge>.toBadgeFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(
    map { badge ->
        Feature.fromGeometry(GeoPoint.fromLngLat(badge.longitude, badge.latitude)).apply {
            addStringProperty(PROP_TEXT, badge.text)
        }
    },
)

private fun LocationFix?.toUserFeatureCollection(): FeatureCollection =
    FeatureCollection.fromFeatures(listOfNotNull(this?.let { Feature.fromGeometry(GeoPoint.fromLngLat(it.longitude, it.latitude)) }))
