// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.rememberMapViewWithLifecycle
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.settingsService
import com.meshcoretwo.services.connection.updateDevice
import com.meshcoretwo.services.location.LocationProvider
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.settings.AdvertLocationPolicy
import com.meshcoretwo.services.settings.DevicePreferenceStore
import com.meshcoretwo.services.settings.GPSSource
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point as GeoPoint

/**
 * "Set Location" map picker for the *locally connected* device, ported from `LocationPickerView
 * .forLocalDevice` — a thin wrapper around the generic [LocationPickerScreen] below that seeds it
 * from [ConnectionManager.connectedDeviceRecord] and saves straight to the radio via
 * [LocationPickerViewModel]. Reached from Settings' `LocationSection` ("Set Location" row). See
 * [LocationPickerScreen]'s doc for the map/UI itself, [RemoteNodeLocationPickerScreen] for the other
 * `LocationPickerView` call site (repeater/room identity's "Pick on Map").
 */
@Composable
fun DeviceLocationPickerScreen(
    connectionManager: ConnectionManager,
    locationProvider: LocationProvider,
    devicePreferenceStore: DevicePreferenceStore,
    onBack: () -> Unit,
) {
    val viewModel: LocationPickerViewModel = viewModel(
        factory = LocationPickerViewModel.Factory(connectionManager, devicePreferenceStore),
    )
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val initialFix = remember {
        connectionManager.connectedDeviceRecord?.let { device ->
            if (device.latitude != 0.0 || device.longitude != 0.0) LatLng(device.latitude, device.longitude) else null
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it.resolve(context))
            viewModel.clearError()
        }
    }

    LocationPickerScreen(
        initial = initialFix,
        locationProvider = locationProvider,
        isSaving = isSaving,
        snackbarHostState = snackbarHostState,
        onSave = { latLng -> scope.launch { if (viewModel.save(latLng.latitude, latLng.longitude)) onBack() } },
        onBack = onBack,
    )
}

/**
 * Generic map picker: tap-anywhere-to-drop-pin (plus a "Drop Pin" button for the current camera
 * center) stands in for MapKit's press-and-drag pin, since MapLibre's GL view has no built-in
 * draggable point annotation — the same "no Annotation plugin" trade-off `MapScreen.kt` already
 * made for its own markers. Ported from `LocationPickerView.swift`, which is itself this generic —
 * `initialCoordinate`/`onSave` closure, no radio access of its own — with [DeviceLocationPickerScreen]
 * and [RemoteNodeLocationPickerScreen] as its two `LocationPickerView.forLocalDevice`-style callers.
 *
 * Camera seeding mirrors `LocationPickerView.loadCurrentLocation()`: starts on [initial] if given,
 * otherwise tries the phone's current location via [LocationProvider] purely to center the initial
 * camera — it never auto-drops a pin there, same as Swift. Also not ported: the shared map-controls
 * toolbar (style/labels/north lock) no map in this port has yet.
 *
 * [containerColor] defaults to this codebase's usual `Color.Transparent` (the NavHost's single
 * shared `accentBackdrop()` shows through — see MainScreen.kt's doc), right for
 * [DeviceLocationPickerScreen]'s ordinary nav-pushed route. [RemoteNodeLocationPickerScreen] passes
 * an opaque color instead: it presents this as a same-destination overlay, not a push, so a
 * transparent container there let the settings screen underneath show through and its TopAppBar
 * title double up with this one's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPickerScreen(
    initial: LatLng?,
    locationProvider: LocationProvider,
    isSaving: Boolean,
    snackbarHostState: SnackbarHostState,
    onSave: (LatLng) -> Unit,
    onBack: () -> Unit,
    containerColor: Color = Color.Transparent,
) {
    var selected by remember { mutableStateOf(initial) }
    val scope = rememberCoroutineScope()
    val controller = remember { LocationPickerMapController(initial, scope, locationProvider) }

    Scaffold(
        containerColor = containerColor,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.settings_set_location)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_cancel)) }
                },
                actions = {
                    TextButton(
                        enabled = selected != null && !isSaving,
                        onClick = { selected?.let(onSave) },
                    ) { Text(stringResource(R.string.common_save)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            val mapView = rememberMapViewWithLifecycle()
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { mapView },
                update = { view -> view.getMapAsync { map -> controller.attach(map) { latLng -> selected = latLng } } },
            )
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                selected?.let { latLng ->
                    Text(
                        String.format(Locale.US, "%.6f, %.6f", latLng.latitude, latLng.longitude),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                }
                // No "Drop Pin" button: the map already places the pin on tap (see `attach`'s
                // click listener below), so a second button that instead drops it at the current
                // camera center was a redundant, confusing second way to set the same value — tap
                // a spot, then tap this, and the pin visibly jumped from where you tapped to
                // wherever the camera happened to be centered. Destructive-styled (filled, error
                // color) since it's the one remaining action here that discards a choice.
                if (selected != null) {
                    Button(
                        onClick = { selected = null; controller.clearPin() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.location_clear))
                    }
                }
            }
            if (isSaving) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

/**
 * Backs [LocationPickerScreen] with just the slice of `LocationPickerView.forLocalDevice`'s
 * `onSave` this screen needs, kept separate from [SettingsViewModel] since Compose Navigation gives
 * each route its own ViewModel instance here — there's no shared-ViewModel-across-routes precedent
 * in this port yet — rather than reaching across into that ViewModel's unrelated device/
 * notification/radio state.
 */
class LocationPickerViewModel(
    private val connectionManager: ConnectionManager,
    private val devicePreferenceStore: DevicePreferenceStore,
) : ViewModel() {
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _errorMessage = MutableStateFlow<UiText?>(null)
    val errorMessage: StateFlow<UiText?> = _errorMessage.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Sets the device's manual location, mirroring `LocationPickerView.forLocalDevice`'s `onSave`:
     * [com.meshcoretwo.services.settings.SettingsService.setManualLocationVerified] already turns
     * off device GPS first when it was on, so this additionally (1) clears the local
     * "auto-update from device GPS" preference if that's what had been active, and (2) downgrades
     * an active [AdvertLocationPolicy.SHARE] (sharing the live device fix) to
     * [AdvertLocationPolicy.PREFS] (share this just-set static value instead) — a manual pin
     * shouldn't be silently overwritten by the next device GPS fix. Returns `true` on success (the
     * caller navigates back), `false` on failure (already posted to [errorMessage]).
     */
    suspend fun save(latitude: Double, longitude: Double): Boolean {
        val device = connectionManager.connectedDeviceRecord ?: return false
        val settingsService = connectionManager.settingsService ?: return false
        _isSaving.value = true
        try {
            val wasAutoUpdatingFromDevice = devicePreferenceStore.isAutoUpdateLocationEnabled(device.id) &&
                devicePreferenceStore.gpsSource(device.id) == GPSSource.DEVICE
            settingsService.setManualLocationVerified(latitude, longitude)
            if (wasAutoUpdatingFromDevice) {
                devicePreferenceStore.setAutoUpdateLocationEnabled(false, device.id)
            }
            var updated: DeviceDto = device.copy(latitude = latitude, longitude = longitude)
            if (updated.advertLocationPolicyMode == AdvertLocationPolicy.SHARE) {
                settingsService.setOtherParamsVerified(updated, advertLocationPolicy = AdvertLocationPolicy.PREFS)
                updated = updated.copy(advertLocationPolicy = AdvertLocationPolicy.PREFS.rawValue)
            }
            connectionManager.updateDevice(updated)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _errorMessage.value = e.toUiText(UiText.of(R.string.location_err_set))
            return false
        } finally {
            _isSaving.value = false
        }
    }

    class Factory(
        private val connectionManager: ConnectionManager,
        private val devicePreferenceStore: DevicePreferenceStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LocationPickerViewModel(connectionManager, devicePreferenceStore) as T
    }
}

// MARK: - Map

private const val LOCATION_PICKER_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val PICKER_SOURCE_ID = "location-picker-pin"
private const val PICKER_CIRCLE_LAYER_ID = "location-picker-pin-circle"
private const val PICKER_PIN_COLOR = "#2463EB"
private const val PICKER_PIN_STROKE_COLOR = "#FFFFFF"
private const val PICKER_FIX_ZOOM = 12.0
private const val PICKER_WORLD_ZOOM = 1.5

/** Owns the [MapLibreMap] for [LocationPickerScreen]: one movable pin, tap-to-place, "Drop Pin" at center. */
private class LocationPickerMapController(
    private val initial: LatLng?,
    private val scope: CoroutineScope,
    private val locationProvider: LocationProvider,
) {
    private var map: MapLibreMap? = null
    private var source: GeoJsonSource? = null
    private var attached = false

    fun attach(map: MapLibreMap, onTap: (LatLng) -> Unit) {
        this.map = map
        if (attached) return
        attached = true

        map.cameraPosition = CameraPosition.Builder()
            .target(initial ?: LatLng(0.0, 0.0))
            .zoom(if (initial != null) PICKER_FIX_ZOOM else PICKER_WORLD_ZOOM)
            .build()
        map.setStyle(Style.Builder().fromUri(LOCATION_PICKER_STYLE_URL)) { style ->
            val source = GeoJsonSource(PICKER_SOURCE_ID, initial?.let(::pinFeatureCollection) ?: emptyFeatureCollection())
            this.source = source
            style.addSource(source)
            style.addLayer(
                CircleLayer(PICKER_CIRCLE_LAYER_ID, PICKER_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleColor(PICKER_PIN_COLOR),
                    PropertyFactory.circleStrokeWidth(2.5f),
                    PropertyFactory.circleStrokeColor(PICKER_PIN_STROKE_COLOR),
                ),
            )
        }
        map.addOnMapClickListener { latLng ->
            setPin(latLng)
            onTap(latLng)
            true
        }

        if (initial == null) {
            scope.launch {
                try {
                    val fix = locationProvider.requestCurrentLocation()
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.latitude, fix.longitude), PICKER_WORLD_ZOOM))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // No fallback fix available (denied/timeout/no provider) — map stays on its default world view, matching Swift's own silent failure here.
                }
            }
        }
    }

    fun setPin(latLng: LatLng) {
        source?.setGeoJson(pinFeatureCollection(latLng))
    }

    fun clearPin() {
        source?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    }

    private fun pinFeatureCollection(latLng: LatLng) =
        FeatureCollection.fromFeature(Feature.fromGeometry(GeoPoint.fromLngLat(latLng.longitude, latLng.latitude)))

    private fun emptyFeatureCollection() = FeatureCollection.fromFeatures(emptyArray())
}
