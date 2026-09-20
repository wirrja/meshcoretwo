// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.contacts.NODE_MAP_STYLE_URL
import com.meshcoretwo.android.tools.LOSFormatters
import com.meshcoretwo.android.ui.components.FilterChipRow
import com.meshcoretwo.android.ui.components.LoadingScreen
import com.meshcoretwo.android.ui.components.rememberMapViewWithLifecycle
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.connection.discoveredNodeStore
import com.meshcoretwo.services.connection.heardRepeatsService
import com.meshcoretwo.services.connection.messageService
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.location.LocationProvider
import com.meshcoretwo.services.location.LocationProviderError
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.ContactPathHop
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.persistence.MessageDto
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
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

sealed class MessagePathMapUiState {
    data object Loading : MessagePathMapUiState()
    /** [messageService][com.meshcoretwo.services.connection.messageService] no longer has this id (deleted, or never connected). */
    data object NotFound : MessagePathMapUiState()
    data class Loaded(
        val plotted: PlottedMessagePath,
        val hopCount: Int,
        /** The message's own arrival, then each extra flood path — one entry unless the same packet reached this radio more than once. */
        val arrivals: List<PathArrivalOption> = emptyList(),
        val selectedIndex: Int = 0,
    ) : MessagePathMapUiState()
}

/**
 * One selectable arrival on the path map. The label is left to the screen ([offsetFromFirst] `null`
 * means "First"), since a ViewModel has no resources — same split as [MessagePathMapViewModel
 * .setYouLabel].
 */
data class PathArrivalOption(val id: String, val offsetFromFirst: Duration?, val hopCount: Int)

/**
 * Backs the "Path map" screen pushed from [MessageDetailsSection]'s View Path content — a trimmed
 * port of `MessagePathMapView.swift`. Owns its own fetch keyed by [messageId] (same
 * nav-arg-and-refetch shape as this port's other pushed map screens, e.g.
 * [com.meshcoretwo.android.contacts.NeighborSnrMapViewModel]) rather than threading the
 * already-loaded [MessageDto]/contacts/discovered-nodes through navigation.
 *
 * [setCurrentLocation] mirrors [com.meshcoretwo.android.contacts.NeighborSnrMapViewModel]'s
 * same-named method: opt-in, best-effort, and re-plots on every call so a hop resolved ambiguously
 * (no location fix yet) can settle once one arrives — matching Swift's ambient
 * `appState.bestAvailableLocation` feeding `RepeaterResolver.resolve`'s proximity tiebreak.
 */
class MessagePathMapViewModel(
    private val connectionManager: ConnectionManager,
    private val messageId: UUID,
) : ViewModel() {
    private val _uiState = MutableStateFlow<MessagePathMapUiState>(MessagePathMapUiState.Loading)
    val uiState: StateFlow<MessagePathMapUiState> = _uiState.asStateFlow()

    private var message: MessageDto? = null
    private var contacts: List<ContactDto> = emptyList()
    private var discoveredNodes: List<DiscoveredNodeDto> = emptyList()
    private var currentLocation: LocationFix? = null
    private var youLabel: String = "You"
    private var arrivals: List<ArrivalPath> = emptyList()
    private var selectedIndex: Int = 0

    /** An arrival's hops plus what the UI needs to label it; the message's own path is index 0. */
    private data class ArrivalPath(val option: PathArrivalOption, val hops: List<ContactPathHop>)

    init {
        viewModelScope.launch { load() }
    }

    /** The localized "You" for the receiver pin, supplied by the screen (a ViewModel has no resources). */
    fun setYouLabel(label: String) {
        if (label == youLabel) return
        youLabel = label
        rebuildPlotted()
    }

    fun setCurrentLocation(fix: LocationFix?) {
        currentLocation = fix
        rebuildPlotted()
    }

    /** Re-plots the map for another arrival of the same message. Out-of-range indices are ignored rather than clamped — the only caller is the chip row built from [arrivals]. */
    fun selectArrival(index: Int) {
        if (index == selectedIndex || index !in arrivals.indices) return
        selectedIndex = index
        rebuildPlotted()
    }

    private suspend fun load() {
        val message = connectionManager.messageService?.getMessage(messageId)
        if (message == null) {
            _uiState.value = MessagePathMapUiState.NotFound
            return
        }
        this.message = message

        val radioID = connectionManager.lastConnectedRadioID
        contacts = radioID?.let { connectionManager.contactService?.getContacts(it) }.orEmpty()
        discoveredNodes = radioID?.let { connectionManager.discoveredNodeStore?.fetchDiscoveredNodes(it) }.orEmpty()
        arrivals = buildArrivals(message)
        rebuildPlotted()
    }

    /**
     * The message's own path first, then one entry per extra flood copy, oldest first — the same
     * order [MessageDetailsSection]'s arrival list uses. Extras are only fetched for an incoming
     * message that has some ([MessageDto.hasExtraIncomingPaths]), so an ordinary message costs no
     * extra query.
     */
    private suspend fun buildArrivals(message: MessageDto): List<ArrivalPath> {
        val own = ArrivalPath(PathArrivalOption(id = message.id.toString(), offsetFromFirst = null, hopCount = message.pathHops.size), message.pathHops)
        if (!message.hasExtraIncomingPaths) return listOf(own)
        val extras = connectionManager.heardRepeatsService?.refreshRepeats(message.id).orEmpty().sortedBy { it.receivedAt }
        return listOf(own) + extras.map { extra ->
            ArrivalPath(
                PathArrivalOption(
                    id = extra.id.toString(),
                    offsetFromFirst = Duration.between(message.createdAt, extra.receivedAt),
                    hopCount = extra.arrivalHopCount,
                ),
                extra.pathHops,
            )
        }
    }

    private fun rebuildPlotted() {
        val message = message ?: return
        val selfDevice = connectionManager.connectedDeviceRecord
        val selected = arrivals.getOrNull(selectedIndex)
        val hops = selected?.hops ?: message.pathHops
        val plotted = MessagePathMapBuilder.build(
            message = message,
            contacts = contacts,
            discoveredNodes = discoveredNodes,
            selfDevice = selfDevice,
            receiverName = selfDevice?.nodeName ?: youLabel,
            userLocation = currentLocation,
            hops = hops,
        )
        _uiState.value = MessagePathMapUiState.Loaded(plotted, hops.size, arrivals.map { it.option }, selectedIndex)
    }

    class Factory(
        private val connectionManager: ConnectionManager,
        private val messageId: UUID,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MessagePathMapViewModel(connectionManager, messageId) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagePathMapScreen(
    connectionManager: ConnectionManager,
    locationProvider: LocationProvider,
    messageId: UUID,
    onBack: () -> Unit,
) {
    val viewModel: MessagePathMapViewModel = viewModel(factory = MessagePathMapViewModel.Factory(connectionManager, messageId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val loaded = uiState as? MessagePathMapUiState.Loaded
    val plotted = loaded?.plotted
    val arrivals = loaded?.arrivals.orEmpty()
    val selectedArrival = arrivals.getOrNull(loaded?.selectedIndex ?: 0)

    val controller = remember { MessagePathMapController() }
    val scope = rememberCoroutineScope()
    var hasAutoCentered by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val locationUnavailable = stringResource(R.string.path_map_location_unavailable)
    val youLabel = stringResource(R.string.common_you)
    LaunchedEffect(youLabel) { viewModel.setYouLabel(youLabel) }

    LaunchedEffect(Unit) {
        try {
            viewModel.setCurrentLocation(locationProvider.requestCurrentLocation())
        } catch (error: LocationProviderError) {
            // Best-effort: pins still resolve without it, just without proximity tiebreaking.
        }
    }

    // Re-fits on the first plot and on every route switch: a different arrival is a different set
    // of pins, so keeping the old camera would leave the new route partly off-screen.
    var fittedArrivalId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(plotted, selectedArrival?.id) {
        val current = plotted ?: return@LaunchedEffect
        controller.update(current)
        val arrivalChanged = selectedArrival?.id != fittedArrivalId
        if ((!hasAutoCentered || arrivalChanged) && current.showsPathMap) {
            controller.fitToPoints(current.points)
            hasAutoCentered = true
            fittedArrivalId = selectedArrival?.id
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.takeIf { it.plotted.showsPathMap }?.let { pathBannerText(it.hopCount, it.plotted.totalDistanceMeters) } ?: stringResource(R.string.chat_path_map)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            try {
                                val fix = locationProvider.requestCurrentLocation()
                                viewModel.setCurrentLocation(fix)
                                controller.centerOn(fix.latitude, fix.longitude)
                            } catch (error: LocationProviderError) {
                                snackbarHostState.showSnackbar(error.message ?: locationUnavailable)
                            }
                        }
                    }) { Icon(painterResource(R.drawable.ic_my_location), contentDescription = stringResource(R.string.path_map_my_location)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (arrivals.size > 1) {
                // One chip per arrival of the same message — tapping one re-plots that route.
                val firstLabel = stringResource(R.string.chat_path_arrival_first)
                FilterChipRow(
                    items = arrivals,
                    selected = selectedArrival ?: arrivals.first(),
                    onSelect = { viewModel.selectArrival(arrivals.indexOf(it)) },
                    label = { arrival -> arrival.offsetFromFirst?.let(::formatArrivalOffset) ?: firstLabel },
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState is MessagePathMapUiState.Loading -> LoadingScreen()
                    uiState is MessagePathMapUiState.NotFound || plotted?.showsPathMap != true -> {
                        // Hops exist but none could be placed: distinct from "no path data at all".
                        val unplaceable = plotted?.let { it.hopCount > 0 } == true
                        Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                            Text(stringResource(if (unplaceable) R.string.path_map_cant_place else R.string.path_map_unavailable), style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (unplaceable) {
                                    stringResource(R.string.path_map_cant_place_desc)
                                } else {
                                    stringResource(R.string.path_map_no_data)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> MessagePathMapLibreView(modifier = Modifier.fillMaxSize(), controller = controller)
                }
            }
        }
    }
}

/** "3 hops · 120 m", or without a distance segment until at least two nodes resolve. Ported from `PathDistanceBanner.swift`. */
private fun pathBannerText(hopCount: Int, totalDistanceMeters: Double?): String {
    val hopsText = if (hopCount == 1) "1 hop" else "$hopCount hops"
    return totalDistanceMeters?.let { "$hopsText · ${LOSFormatters.formatDistance(it)}" } ?: hopsText
}

// MARK: - Map

private const val LINE_SOURCE_ID = "message-path-line"
private const val LINE_CASING_LAYER_ID = "message-path-line-casing"
private const val LINE_LAYER_ID = "message-path-line-layer"
private const val POINTS_SOURCE_ID = "message-path-points"
private const val POINTS_CIRCLE_LAYER_ID = "message-path-points-circle"
private const val POINTS_LABEL_LAYER_ID = "message-path-points-label"
private const val PROP_LABEL = "label"
private const val PROP_COLOR = "color"
private const val PROP_STROKE_COLOR = "strokeColor"

/** Solid blue with a white casing, no per-segment SNR grading — ported from `MC1MapView+Layers.swift`'s `.messagePath` line style, visually distinct from [com.meshcoretwo.android.contacts.NeighborSnrMapBuilder]'s SNR-colored lines. */
private const val LINE_COLOR = "#007AFF"
private const val LINE_CASING_COLOR = "#FFFFFF"

/** Pin fill colors by role. Not a port of Swift's `PinSpriteRenderer` sprites (unported here, same as [com.meshcoretwo.android.contacts.LocationPathMapBuilder]'s doc) — a fixed flat color per role stands in. */
private fun MessagePathMapPointRole.fillColorHex(): String = when (this) {
    MessagePathMapPointRole.SENDER -> "#2E7D32"
    MessagePathMapPointRole.HOP -> "#F59E0B"
    MessagePathMapPointRole.RECEIVER -> "#1565C0"
}

@Composable
private fun MessagePathMapLibreView(modifier: Modifier, controller: MessagePathMapController) {
    val mapView = rememberMapViewWithLifecycle()
    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view -> view.getMapAsync { map -> controller.attach(map) } },
    )
}

/**
 * Owns the [MapLibreMap] instance for the message-path map: point/line GeoJSON sources and camera
 * fitting. Kept separate from [com.meshcoretwo.android.contacts.NeighborSnrMapController] — same
 * reasoning as that class's doc, a different marker/line set and no tap routing.
 */
private class MessagePathMapController {
    private var map: MapLibreMap? = null
    private var attached = false
    private var pointsSource: GeoJsonSource? = null
    private var lineSource: GeoJsonSource? = null
    private var lastPlotted: PlottedMessagePath = PlottedMessagePath(emptyList(), emptyList(), null)

    /** A fit requested before [attach] ran (the map view hands its instance over asynchronously); replayed on attach. */
    private var pendingFit: List<MessagePathMapPoint>? = null

    fun attach(map: MapLibreMap) {
        this.map = map
        pendingFit?.let { points ->
            pendingFit = null
            fitToPoints(points)
        }
        if (attached) return
        attached = true

        map.setStyle(Style.Builder().fromUri(NODE_MAP_STYLE_URL)) { style ->
            val lines = GeoJsonSource(LINE_SOURCE_ID, lastPlotted.lineCoordinates.toLineFeatureCollection())
            style.addSource(lines)
            style.addLayer(
                LineLayer(LINE_CASING_LAYER_ID, LINE_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor(LINE_CASING_COLOR),
                    PropertyFactory.lineWidth(6f),
                    PropertyFactory.lineOpacity(0.85f),
                ),
            )
            style.addLayer(
                LineLayer(LINE_LAYER_ID, LINE_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor(LINE_COLOR),
                    PropertyFactory.lineWidth(3f),
                ),
            )
            lineSource = lines

            val points = GeoJsonSource(POINTS_SOURCE_ID, lastPlotted.points.toPointFeatureCollection())
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

    fun update(plotted: PlottedMessagePath) {
        lastPlotted = plotted
        pointsSource?.setGeoJson(plotted.points.toPointFeatureCollection())
        lineSource?.setGeoJson(plotted.lineCoordinates.toLineFeatureCollection())
    }

    fun centerOn(latitude: Double, longitude: Double, zoom: Double = 14.0) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), zoom))
    }

    fun fitToPoints(points: List<MessagePathMapPoint>) {
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

private fun List<MessagePathMapPoint>.toPointFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(
    map { point ->
        Feature.fromGeometry(GeoPoint.fromLngLat(point.longitude, point.latitude)).apply {
            addStringProperty(PROP_LABEL, point.label)
            addStringProperty(PROP_COLOR, point.role.fillColorHex())
            addStringProperty(PROP_STROKE_COLOR, "#FFFFFF")
        }
    },
)

private fun List<Pair<Double, Double>>.toLineFeatureCollection(): FeatureCollection =
    if (size < 2) {
        FeatureCollection.fromFeatures(emptyList())
    } else {
        FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(LineString.fromLngLats(map { (lat, lon) -> GeoPoint.fromLngLat(lon, lat) }))))
    }
