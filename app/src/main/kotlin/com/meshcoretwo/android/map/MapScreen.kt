// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.map

import android.content.SharedPreferences
import android.graphics.PointF
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.GhostButton
import com.meshcoretwo.android.ui.components.InitialsAvatar
import com.meshcoretwo.android.ui.components.rememberLocationPermissionAction
import com.meshcoretwo.android.ui.components.rememberMapViewWithLifecycle
import com.meshcoretwo.android.ui.theme.AvatarCategory
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.location.LocationProvider
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point as GeoPoint

/**
 * Map tab — PLAN.md's Phase 5 item 5, a deliberately trimmed port of `MapView.swift`/
 * `MapViewModel.swift`: contacts/repeaters/rooms with a stored location, plus (since the
 * "Discovered nodes" series) not-yet-added nodes from the "Discover" list, as plain colored dots
 * on an [org.maplibre.android.maps.MapLibreMap] (see the project's hard constraint — MapLibre, never
 * Google Maps SDK), filterable via [MapFilterState] (favorites/discovered/type — see
 * [MapViewModel]'s class doc for what's not ported from `MapFilterState.swift`). Tapping a contact
 * marker opens a small bottom sheet with a Message/Details shortcut, mirroring `ContactDetailSheet`
 * from the iOS map; tapping a discovered-node marker opens a read-only variant with a shortcut to
 * the "Discover" list screen instead — adopting a discovered node into a contact only happens
 * there (`DiscoveryScreen`), not duplicated onto this sheet. Style is the hosted, keyless
 * `tiles.openfreemap.org/styles/liberty` (same source iOS's `MapTileURLs.openFreeMapLiberty`
 * points at) — offline map packs (`OfflineMapService`), style switching, north-lock/label toggles,
 * SNR/hop trails, and camera persistence across launches are all out of scope for this slice; see
 * [MapViewModel]'s class doc for the full list.
 *
 * Pins cluster while [MapDisplayPreferences.isClusteringEnabled] is on (port of upstream
 * `c71a54fe` "feat(map): add cluster nodes toggle"): iOS hosts that switch in its "Map options"
 * menu next to the label/north-lock/style controls, which this port doesn't have, so it lives at
 * the bottom of this screen's filter menu instead — it's a display option, so it deliberately
 * stays out of [MapFilterState] and never lights the filter button's badge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    connectionManager: ConnectionManager,
    locationProvider: LocationProvider,
    prefs: SharedPreferences,
    onOpenConversation: (UUID) -> Unit,
    onOpenContactDetail: (UUID) -> Unit,
    onOpenDiscovery: () -> Unit,
) {
    val viewModel: MapViewModel = viewModel(factory = MapViewModel.Factory(connectionManager, locationProvider))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val points = (uiState as? MapUiState.Ready)?.points.orEmpty()

    val controller = remember { MapMarkerController() }
    var hasAutoCentered by rememberSaveable { mutableStateOf(false) }
    var selectedPoint by remember { mutableStateOf<MapPoint?>(null) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var clusteringEnabled by remember { mutableStateOf(MapDisplayPreferences.isClusteringEnabled(prefs)) }
    var searchQuery by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val filteredPoints = remember(points, searchQuery) {
        if (searchQuery.isBlank()) points else points.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }
    val withLocationPermission = rememberLocationPermissionAction(
        onDenied = { message -> coroutineScope.launch { snackbarHostState.showSnackbar(message) } },
    )

    controller.onMarkerTap = { selectedPoint = it }

    LaunchedEffect(points) {
        if (!hasAutoCentered && points.isNotEmpty()) {
            controller.fitToPoints(points)
            hasAutoCentered = true
        }
    }

    LaunchedEffect(filteredPoints) {
        controller.updatePoints(filteredPoints)
    }

    LaunchedEffect(clusteringEnabled) {
        controller.setClusteringEnabled(clusteringEnabled)
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            MapLibreMapView(modifier = Modifier.fillMaxSize(), controller = controller)

            Surface(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                shape = RoundedCornerShape(19.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp,
            ) {
                Row(modifier = Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.map_search_nodes)) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            unfocusedContainerColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    IconButton(onClick = { viewModel.refresh() }) { Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.common_refresh)) }
                    Box(
                        modifier = Modifier.size(30.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { showFilterMenu = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        BadgedBox(badge = { if (filter != MAIN_MAP_DEFAULT_FILTER) Badge() }) {
                            Icon(painterResource(R.drawable.ic_filter_list), contentDescription = stringResource(R.string.common_filter), modifier = Modifier.size(15.dp))
                        }
                        MapFilterMenu(
                            expanded = showFilterMenu,
                            filter = filter,
                            clusteringEnabled = clusteringEnabled,
                            onDismiss = { showFilterMenu = false },
                            onSetFavoritesOnly = viewModel::setFavoritesOnly,
                            onSetShowDiscovered = viewModel::setShowDiscovered,
                            onSetShowChat = viewModel::setShowChat,
                            onSetShowRepeater = viewModel::setShowRepeater,
                            onSetShowRoom = viewModel::setShowRoom,
                            onSetClusteringEnabled = { enabled ->
                                clusteringEnabled = enabled
                                MapDisplayPreferences.setClusteringEnabled(prefs, enabled)
                            },
                        )
                    }
                }
            }

            FloatingActionButton(
                onClick = {
                    withLocationPermission {
                        viewModel.requestMyLocation { fix -> controller.centerOn(fix.latitude, fix.longitude) }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Icon(painterResource(R.drawable.ic_my_location), contentDescription = stringResource(R.string.path_map_my_location)) }
        }
    }

    selectedPoint?.let { point ->
        when (point.kind) {
            MapPointKind.CONTACT -> ContactMapPointSheet(
                point = point,
                onDismiss = { selectedPoint = null },
                onMessage = {
                    selectedPoint = null
                    onOpenConversation(point.pointId)
                },
                onDetails = {
                    selectedPoint = null
                    onOpenContactDetail(point.pointId)
                },
            )
            MapPointKind.DISCOVERED -> DiscoveredMapPointSheet(
                point = point,
                onDismiss = { selectedPoint = null },
                onOpenDiscovery = {
                    selectedPoint = null
                    onOpenDiscovery()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapFilterMenu(
    expanded: Boolean,
    filter: MapFilterState,
    clusteringEnabled: Boolean,
    onDismiss: () -> Unit,
    onSetFavoritesOnly: (Boolean) -> Unit,
    onSetShowDiscovered: (Boolean) -> Unit,
    onSetShowChat: (Boolean) -> Unit,
    onSetShowRepeater: (Boolean) -> Unit,
    onSetShowRoom: (Boolean) -> Unit,
    onSetClusteringEnabled: (Boolean) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.map_filter_favorites_only)) },
            leadingIcon = { checkedIcon(filter.favoritesOnly) },
            onClick = { onSetFavoritesOnly(!filter.favoritesOnly) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.map_filter_discovered)) },
            leadingIcon = { checkedIcon(filter.showDiscovered) },
            onClick = { onSetShowDiscovered(!filter.showDiscovered) },
            enabled = !filter.favoritesOnly,
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.common_contacts)) },
            leadingIcon = { checkedIcon(filter.showChat) },
            onClick = { onSetShowChat(!filter.showChat) },
            enabled = !filter.favoritesOnly,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.map_filter_repeaters)) },
            leadingIcon = { checkedIcon(filter.showRepeater) },
            onClick = { onSetShowRepeater(!filter.showRepeater) },
            enabled = !filter.favoritesOnly,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.common_rooms)) },
            leadingIcon = { checkedIcon(filter.showRoom) },
            onClick = { onSetShowRoom(!filter.showRoom) },
            enabled = !filter.favoritesOnly,
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.map_controls_cluster_nodes)) },
            leadingIcon = { checkedIcon(clusteringEnabled) },
            onClick = { onSetClusteringEnabled(!clusteringEnabled) },
        )
    }
}

@Composable
private fun checkedIcon(checked: Boolean) {
    if (checked) Icon(painterResource(R.drawable.ic_check), contentDescription = null)
}

@Composable
private fun MapPointBadge(@DrawableRes icon: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactMapPointSheet(point: MapPoint, onDismiss: () -> Unit, onMessage: () -> Unit, onDetails: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            MapPointHeader(point, subtitle = stringResource(R.string.map_point_subtitle, formatCoordinate(point.latitude, point.longitude), stringResource(point.type.lowercaseLabelRes())))
            if (point.isFavorite || point.isBlocked) {
                Spacer(modifier = Modifier.size(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (point.isFavorite) MapPointBadge(R.drawable.ic_star, stringResource(R.string.common_favorite))
                    if (point.isBlocked) MapPointBadge(R.drawable.ic_block, stringResource(R.string.common_blocked))
                }
            }
            Spacer(modifier = Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (point.canMessage) {
                    Button(onClick = onMessage, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_message)) }
                }
                GhostButton(text = stringResource(R.string.common_details), onClick = onDetails, modifier = Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveredMapPointSheet(point: MapPoint, onDismiss: () -> Unit, onOpenDiscovery: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            val subtitle = stringResource(R.string.map_point_discovered_subtitle, formatCoordinate(point.latitude, point.longitude), stringResource(point.type.lowercaseLabelRes()))
            MapPointHeader(point, subtitle = subtitle)
            point.hopCount?.let { hops ->
                Spacer(modifier = Modifier.size(6.dp))
                Text("$hops hop${if (hops == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.size(12.dp))
            GhostButton(text = stringResource(R.string.map_open_discover_list), onClick = onOpenDiscovery, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MapPointHeader(point: MapPoint, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        InitialsAvatar(name = point.name, size = 34.dp, category = AvatarCategory.fromContactType(point.type))
        Spacer(modifier = Modifier.size(10.dp))
        Column {
            Text(point.name, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** `"45.9°N, 7.6°E"`-style label, matching the Map mockup's compact sheet subtitle. */
private fun formatCoordinate(latitude: Double, longitude: Double): String {
    val latDir = if (latitude >= 0) "N" else "S"
    val lonDir = if (longitude >= 0) "E" else "W"
    return "%.1f°%s, %.1f°%s".format(abs(latitude), latDir, abs(longitude), lonDir)
}

@StringRes
private fun ContactType.lowercaseLabelRes(): Int = when (this) {
    ContactType.CHAT -> R.string.map_type_contact_lc
    ContactType.REPEATER -> R.string.map_type_repeater_lc
    ContactType.ROOM -> R.string.map_type_room_lc
}

@Composable
private fun MapLibreMapView(modifier: Modifier, controller: MapMarkerController) {
    val mapView = rememberMapViewWithLifecycle()
    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view -> view.getMapAsync { map -> controller.attach(map) } },
    )
}

private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val SOURCE_ID = "mc1-contacts"
private const val LABELS_SOURCE_ID = "mc1-contacts-labels"
private const val CLUSTERED_SOURCE_ID = "mc1-contacts-clustered"
private const val CIRCLE_LAYER_ID = "mc1-contacts-circle"
private const val LABEL_LAYER_ID = "mc1-contacts-label"
private const val CLUSTER_CIRCLE_LAYER_ID = "mc1-cluster-circle"
private const val CLUSTER_COUNT_LAYER_ID = "mc1-cluster-count"
private const val CLUSTERED_POINT_LAYER_ID = "mc1-clustered-point-circle"
private const val CLUSTERED_LABEL_LAYER_ID = "mc1-clustered-point-label"
/**
 * A font stack the style's glyph server hosts — MapLibre's default (`Open Sans Regular`) 404s on
 * OpenFreeMap, and unloaded glyphs stall every layer of the same source (markers never draw).
 */
private val LABEL_FONT = arrayOf("Noto Sans Regular")
private const val PROP_ID = "id"
private const val PROP_NAME = "name"
private const val PROP_COLOR = "color"
/** GeoJSON-clustering property MapLibre puts on cluster features (absent on real points). */
private const val PROP_POINT_COUNT = "point_count"
/** Same 44pt grouping distance `MC1MapView+Layers.swift` gives its clustered shape source. */
private const val CLUSTER_RADIUS = 44
private const val CLUSTER_COLOR = "#2563EB"

/**
 * Owns the [MapLibreMap] instance, its GeoJSON marker source/layers, and marker-tap routing.
 * Kept outside Compose state (a plain `remember`-held object, not a `ViewModel`) since it wraps a
 * mutable native map handle with its own attach-once lifecycle — [attach] is idempotent because
 * [AndroidView]'s `update` block re-invokes `getMapAsync` on every recomposition of
 * [MapLibreMapView], and `getMapAsync` calls back immediately once the map is already ready.
 *
 * Both marker modes are built once at style load and swapped by layer visibility ([setClusteringEnabled]):
 * a source's clustering options are fixed at construction in MapLibre, so the clustered variant needs a
 * source of its own, and rebuilding sources on every toggle would tear down and refetch tiles.
 */
private class MapMarkerController {
    var onMarkerTap: ((MapPoint) -> Unit)? = null

    private var map: MapLibreMap? = null
    private var attached = false
    private var pointsSource: GeoJsonSource? = null
    private var labelsSource: GeoJsonSource? = null
    private var clusteredSource: GeoJsonSource? = null
    private var lastPoints: List<MapPoint> = emptyList()
    private var clusteringEnabled = true

    /** A [fitToPoints] requested before the map finished attaching; replayed by [attach]. */
    private var pendingFit: List<MapPoint>? = null

    fun attach(map: MapLibreMap) {
        this.map = map
        pendingFit?.let { pendingFit = null; fitToPoints(it) }
        if (attached) return
        attached = true

        map.addOnMapClickListener { latLng -> handleTap(map, latLng) }

        map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
            val source = GeoJsonSource(SOURCE_ID, lastPoints.toFeatureCollection())
            style.addSource(source)
            style.addLayer(
                CircleLayer(CIRCLE_LAYER_ID, SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor(Expression.get(PROP_COLOR)),
                    PropertyFactory.circleStrokeWidth(1.5f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                ),
            )
            // Labels get their own source: a tile with unresolved glyphs is held back per source,
            // and that must never delay the dots.
            val labels = GeoJsonSource(LABELS_SOURCE_ID, lastPoints.toFeatureCollection())
            style.addSource(labels)
            labelsSource = labels
            style.addLayer(
                SymbolLayer(LABEL_LAYER_ID, LABELS_SOURCE_ID).withProperties(
                    PropertyFactory.textField(Expression.get(PROP_NAME)),
                    PropertyFactory.textFont(LABEL_FONT),
                    PropertyFactory.textSize(11f),
                    PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
                    PropertyFactory.textAnchor("top"),
                    PropertyFactory.textColor("#1F2937"),
                    PropertyFactory.textHaloColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(1.2f),
                    PropertyFactory.textAllowOverlap(false),
                ),
            )
            pointsSource = source
            addClusteredLayers(style)
            applyLayerVisibility(style)
        }
    }

    /**
     * The "Cluster nodes" half of the marker stack — a second source of the same points, this one
     * with MapLibre's GeoJSON clustering on, and the four layers it needs: the cluster bubbles and
     * their counts (`point_count`, a property only cluster features carry), plus dots and names for
     * the points no cluster swallowed. Mirrors `addClusteredPointLayers` in
     * `MC1MapView+Layers.swift`, down to its `point_count` radius steps; iOS's `systemBlue` bubble
     * becomes this port's contact-pin blue. Unlike the unclustered half these labels must sit on
     * the clustering source — only it knows which points are still loose — so the glyph-stall
     * guard noted above can't apply to them; they use the same known-good font stack.
     */
    private fun addClusteredLayers(style: Style) {
        val clustered = GeoJsonSource(
            CLUSTERED_SOURCE_ID,
            lastPoints.toFeatureCollection(),
            GeoJsonOptions().withCluster(true).withClusterRadius(CLUSTER_RADIUS),
        )
        style.addSource(clustered)
        clusteredSource = clustered

        val isCluster = Expression.has(PROP_POINT_COUNT)
        style.addLayer(
            CircleLayer(CLUSTER_CIRCLE_LAYER_ID, CLUSTERED_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(
                    Expression.step(
                        Expression.get(PROP_POINT_COUNT),
                        Expression.literal(18f),
                        Expression.stop(50, 24f),
                        Expression.stop(100, 30f),
                        Expression.stop(200, 38f),
                    ),
                ),
                PropertyFactory.circleColor(CLUSTER_COLOR),
                PropertyFactory.circleOpacity(0.85f),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeOpacity(0.8f),
            ).withFilter(isCluster),
        )
        style.addLayer(
            SymbolLayer(CLUSTER_COUNT_LAYER_ID, CLUSTERED_SOURCE_ID).withProperties(
                PropertyFactory.textField(Expression.toString(Expression.get(PROP_POINT_COUNT))),
                PropertyFactory.textFont(LABEL_FONT),
                PropertyFactory.textSize(13f),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            ).withFilter(isCluster),
        )
        style.addLayer(
            CircleLayer(CLUSTERED_POINT_LAYER_ID, CLUSTERED_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor(Expression.get(PROP_COLOR)),
                PropertyFactory.circleStrokeWidth(1.5f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
            ).withFilter(Expression.not(isCluster)),
        )
        style.addLayer(
            SymbolLayer(CLUSTERED_LABEL_LAYER_ID, CLUSTERED_SOURCE_ID).withProperties(
                PropertyFactory.textField(Expression.get(PROP_NAME)),
                PropertyFactory.textFont(LABEL_FONT),
                PropertyFactory.textSize(11f),
                PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
                PropertyFactory.textAnchor("top"),
                PropertyFactory.textColor("#1F2937"),
                PropertyFactory.textHaloColor("#FFFFFF"),
                PropertyFactory.textHaloWidth(1.2f),
                PropertyFactory.textAllowOverlap(false),
            ).withFilter(Expression.not(isCluster)),
        )
    }

    fun setClusteringEnabled(enabled: Boolean) {
        clusteringEnabled = enabled
        map?.style?.takeIf { it.isFullyLoaded }?.let(::applyLayerVisibility)
    }

    private fun applyLayerVisibility(style: Style) {
        val visibility = { visible: Boolean -> PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE) }
        listOf(CIRCLE_LAYER_ID, LABEL_LAYER_ID).forEach { id ->
            style.getLayer(id)?.setProperties(visibility(!clusteringEnabled))
        }
        listOf(CLUSTER_CIRCLE_LAYER_ID, CLUSTER_COUNT_LAYER_ID, CLUSTERED_POINT_LAYER_ID, CLUSTERED_LABEL_LAYER_ID).forEach { id ->
            style.getLayer(id)?.setProperties(visibility(clusteringEnabled))
        }
    }

    fun updatePoints(points: List<MapPoint>) {
        lastPoints = points
        val collection = points.toFeatureCollection()
        pointsSource?.setGeoJson(collection)
        labelsSource?.setGeoJson(collection)
        clusteredSource?.setGeoJson(collection)
    }

    fun centerOn(latitude: Double, longitude: Double, zoom: Double = 14.0) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), zoom))
    }

    fun fitToPoints(points: List<MapPoint>) {
        val map = map
        if (map == null) {
            pendingFit = points
            return
        }
        when {
            points.isEmpty() -> Unit
            points.size == 1 -> centerOn(points[0].latitude, points[0].longitude, 12.0)
            else -> {
                val bounds = LatLngBounds.Builder()
                points.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 96))
            }
        }
    }

    /**
     * Cluster bubbles first, then pins — the order `MC1MapView.handleTap` uses. A cluster tap
     * zooms past the level that breaks it apart (`getClusterExpansionZoom` + 2, iOS's
     * `zoomLevel(forExpanding:) + 2.0`) instead of opening a sheet; a pin tap reports the point.
     */
    private fun handleTap(map: MapLibreMap, latLng: LatLng): Boolean {
        val screenPoint: PointF = map.projection.toScreenLocation(latLng)
        if (clusteringEnabled) {
            val cluster = map.queryRenderedFeatures(screenPoint, CLUSTER_CIRCLE_LAYER_ID).firstOrNull()
            val source = clusteredSource
            if (cluster != null && source != null) {
                val geometry = cluster.geometry() as? GeoPoint
                val expansionZoom = source.getClusterExpansionZoom(cluster)
                if (geometry != null && expansionZoom >= 0) {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(geometry.latitude(), geometry.longitude()),
                            expansionZoom + 2.0,
                        ),
                    )
                }
                return true
            }
        }
        val point = findPointAt(map, screenPoint) ?: return false
        onMarkerTap?.invoke(point)
        return true
    }

    private fun findPointAt(map: MapLibreMap, screenPoint: PointF): MapPoint? {
        val layerID = if (clusteringEnabled) CLUSTERED_POINT_LAYER_ID else CIRCLE_LAYER_ID
        val feature = map.queryRenderedFeatures(screenPoint, layerID).firstOrNull() ?: return null
        val id = feature.getStringProperty(PROP_ID) ?: return null
        return lastPoints.firstOrNull { it.pointId.toString() == id }
    }
}

private fun List<MapPoint>.toFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(map { it.toFeature() })

private fun MapPoint.toFeature(): Feature {
    val feature = Feature.fromGeometry(GeoPoint.fromLngLat(longitude, latitude))
    feature.addStringProperty(PROP_ID, pointId.toString())
    feature.addStringProperty(PROP_NAME, name)
    feature.addStringProperty(PROP_COLOR, type.markerColorHex())
    return feature
}

private fun ContactType.markerColorHex(): String = when (this) {
    ContactType.CHAT -> "#2563EB"
    ContactType.REPEATER -> "#F59E0B"
    ContactType.ROOM -> "#9333EA"
}
