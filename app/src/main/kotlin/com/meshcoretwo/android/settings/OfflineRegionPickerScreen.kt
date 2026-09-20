// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import android.graphics.PointF
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoretwo.android.R
import com.meshcoretwo.android.map.OfflineMapError
import com.meshcoretwo.android.map.OfflineMapLayer
import com.meshcoretwo.android.map.OfflineMapService
import com.meshcoretwo.android.ui.components.rememberMapViewWithLifecycle
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.connectedDeviceRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private const val REGION_PICKER_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val REGION_PICKER_MIN_ZOOM = 10
private const val REGION_PICKER_INITIAL_ZOOM = 11.0
private const val REGION_PICKER_WORLD_ZOOM = 1.5
private val SELECTION_PADDING = 40.dp
private const val LARGE_DOWNLOAD_THRESHOLD_BYTES = 500_000_000L

/**
 * "Pick Region" sheet for downloading an offline map pack, ported from `OfflineMapSettingsView.swift`'s
 * `RegionPickerSheet`. The fixed selection rectangle is a plain Compose overlay instead of Swift's
 * `RoundedRectangle` + manual `MKCoordinateRegion` span-fraction math; bounds are read directly via
 * [MapLibreMap.getProjection]`.fromScreenLocation` on the inset rectangle's screen corners, which is
 * simpler and exactly as precise since this map never rotates or tilts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineRegionPickerScreen(
    offlineMapService: OfflineMapService,
    connectionManager: ConnectionManager,
    onBack: () -> Unit,
    onDownloadStarted: () -> Unit,
) {
    var regionName by remember { mutableStateOf("") }
    var includeTopo by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val diskSpaceError = stringResource(R.string.offline_err_disk_space)
    var selectionBounds by remember { mutableStateOf<LatLngBounds?>(null) }
    var mapSizePx by remember { mutableStateOf(IntSize.Zero) }
    val isNetworkAvailable by offlineMapService.isNetworkAvailable.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val insetPx = with(density) { SELECTION_PADDING.toPx() }

    val selectedLayers = remember(includeTopo) {
        if (includeTopo) setOf(OfflineMapLayer.BASE, OfflineMapLayer.TOPO) else setOf(OfflineMapLayer.BASE)
    }
    val estimatedBytes = remember(selectionBounds, selectedLayers) {
        selectionBounds?.let { bounds ->
            selectedLayers.sumOf { layer ->
                OfflineMapService.estimatedDownloadSizeBytes(
                    south = bounds.latitudeSouth,
                    west = bounds.longitudeWest,
                    north = bounds.latitudeNorth,
                    east = bounds.longitudeEast,
                    minZoom = REGION_PICKER_MIN_ZOOM,
                    maxZoom = layer.maxDownloadZoom,
                    layer = layer,
                )
            }
        }
    }
    val availableBytes = remember { offlineMapService.availableDiskSpaceBytes() }
    val exceedsAvailableSpace = estimatedBytes != null && estimatedBytes > availableBytes

    val initialCamera = remember {
        connectionManager.connectedDeviceRecord?.let { device ->
            if (device.latitude != 0.0 || device.longitude != 0.0) LatLng(device.latitude, device.longitude) else null
        }
    }
    val controller = remember { OfflineRegionPickerMapController(initialCamera) { bounds -> selectionBounds = bounds } }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            errorMessage = null
        }
    }
    LaunchedEffect(mapSizePx) { controller.recomputeBounds() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.offmap_pick_region)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_cancel)) }
                },
                actions = {
                    TextButton(
                        enabled = regionName.isNotBlank() && !isDownloading && !exceedsAvailableSpace &&
                            isNetworkAvailable && selectionBounds != null,
                        onClick = {
                            val bounds = selectionBounds ?: return@TextButton
                            isDownloading = true
                            scope.launch {
                                try {
                                    offlineMapService.downloadRegion(regionName, bounds, selectedLayers, REGION_PICKER_MIN_ZOOM)
                                    onDownloadStarted()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: OfflineMapError) {
                                    errorMessage = if (e is OfflineMapError.InsufficientDiskSpace) diskSpaceError else e.message
                                } finally {
                                    isDownloading = false
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.offmap_download)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val mapView = rememberMapViewWithLifecycle()
                AndroidView(
                    modifier = Modifier.fillMaxSize().onSizeChanged { mapSizePx = it },
                    factory = { mapView },
                    update = { view -> view.getMapAsync { map -> controller.attach(map, insetPx) { mapSizePx } } },
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxSize()
                        .padding(SELECTION_PADDING)
                        .border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(8.dp)),
                )
                if (isDownloading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
            RegionPickerBottomCard(
                regionName = regionName,
                onRegionNameChange = { regionName = it },
                includeTopo = includeTopo,
                onIncludeTopoChange = { includeTopo = it },
                estimatedDownloadBytes = estimatedBytes,
                exceedsAvailableSpace = exceedsAvailableSpace,
                isNetworkAvailable = isNetworkAvailable,
            )
        }
    }
}

@Composable
private fun RegionPickerBottomCard(
    regionName: String,
    onRegionNameChange: (String) -> Unit,
    includeTopo: Boolean,
    onIncludeTopoChange: (Boolean) -> Unit,
    estimatedDownloadBytes: Long?,
    exceedsAvailableSpace: Boolean,
    isNetworkAvailable: Boolean,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        OutlinedTextField(
            value = regionName,
            onValueChange = onRegionNameChange,
            placeholder = { Text(stringResource(R.string.nodeadmin_region_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.offmap_include_topo))
            Switch(checked = includeTopo, onCheckedChange = onIncludeTopoChange)
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        when {
            !isNetworkAvailable -> Text(
                stringResource(R.string.offmap_no_network),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            estimatedDownloadBytes != null -> {
                val isLarge = estimatedDownloadBytes > LARGE_DOWNLOAD_THRESHOLD_BYTES
                val color = when {
                    exceedsAvailableSpace -> MaterialTheme.colorScheme.error
                    isLarge -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    stringResource(R.string.offmap_estimated_size, Formatter.formatShortFileSize(context, estimatedDownloadBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
                Text(
                    when {
                        exceedsAvailableSpace -> stringResource(R.string.offmap_exceeds)
                        isLarge -> stringResource(R.string.offmap_large)
                        else -> stringResource(R.string.offmap_pan_zoom)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
            }
            else -> Text(
                stringResource(R.string.offmap_pan_zoom),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Owns the [MapLibreMap] for [OfflineRegionPickerScreen]: free pan/zoom, no markers. */
private class OfflineRegionPickerMapController(
    private val initialCamera: LatLng?,
    private val onBoundsChanged: (LatLngBounds?) -> Unit,
) {
    private var map: MapLibreMap? = null
    private var insetPx: Float = 0f
    private var sizeProvider: () -> IntSize = { IntSize.Zero }
    private var attachedListener = false

    fun attach(map: MapLibreMap, insetPx: Float, sizeProvider: () -> IntSize) {
        this.map = map
        this.insetPx = insetPx
        this.sizeProvider = sizeProvider
        if (attachedListener) {
            recomputeBounds()
            return
        }
        attachedListener = true

        map.cameraPosition = CameraPosition.Builder()
            .target(initialCamera ?: LatLng(0.0, 0.0))
            .zoom(if (initialCamera != null) REGION_PICKER_INITIAL_ZOOM else REGION_PICKER_WORLD_ZOOM)
            .build()
        map.setStyle(Style.Builder().fromUri(REGION_PICKER_STYLE_URL))
        map.addOnCameraIdleListener { recomputeBounds() }
        recomputeBounds()
    }

    fun recomputeBounds() {
        val map = map ?: return
        val size = sizeProvider()
        if (size.width <= 0 || size.height <= 0) {
            onBoundsChanged(null)
            return
        }
        val projection = map.projection
        val topLeft = projection.fromScreenLocation(PointF(insetPx, insetPx))
        val bottomRight = projection.fromScreenLocation(PointF(size.width - insetPx, size.height - insetPx))
        onBoundsChanged(LatLngBounds.Builder().include(topLeft).include(bottomRight).build())
    }
}

