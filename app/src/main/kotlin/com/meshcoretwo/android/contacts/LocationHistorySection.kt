// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.rememberMapViewWithLifecycle
import com.meshcoretwo.services.persistence.NodeStatusSnapshotDto
import com.meshcoretwo.services.remotenode.validCoordinate
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
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point as GeoPoint

/**
 * The "Location" section of the telemetry history screens: a live inline non-interactive map plus a
 * "Location History" list of every valid-coordinate report, newest first. Tapping the map's expand
 * button or any row opens [LocationHistoryMapScreen] via [onOpenMap] (a route, unlike Swift's pushed
 * `navigationDestination`), carrying the tapped report's id so the full map can auto-select it (or
 * `null` from the expand button, which opens with nothing selected). Ported from
 * `LocationHistorySection.swift`, called the same way from [TelemetryHistoryScreen] (single-fix
 * preview) and [TelemetryHistoryOverviewScreen] (whole-path preview).
 *
 * Unlike Swift's radio/sensors/neighbors sections on the overview screen, this one is never wrapped
 * in a collapsible `DisclosureGroup` — matches `TelemetryHistoryOverviewView.swift`, which adds it as
 * a plain section.
 *
 * Not itself `@Composable` — the `LazyListScope.() -> Unit` a `LazyColumn` takes isn't a composable
 * context (only its `item`/`items` content lambdas are), so `preview`/`reports` are computed by the
 * caller's own `remember` (see [TelemetryHistoryScreen]/[TelemetryHistoryOverviewScreen]) and handed
 * in already built, the same split `AddHopPickerScreen.kt`'s `repeaterSection` makes.
 */
fun LazyListScope.locationHistorySection(
    preview: PlottedLocationPath,
    reports: List<NodeStatusSnapshotDto>,
    onOpenMap: (UUID?) -> Unit,
) {
    item {
        ChartCard {
            Column {
                Text(stringResource(R.string.contacts_location), style = MaterialTheme.typography.titleMedium)
                if (preview.points.isEmpty()) {
                    Text(
                        stringResource(R.string.telemetry_not_captured, stringResource(R.string.contacts_location)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Box(modifier = Modifier.padding(top = 8.dp)) {
                        LocationPreviewMap(preview, onExpand = { onOpenMap(null) })
                    }
                }
            }
        }
    }

    if (reports.isNotEmpty()) {
        item {
            ChartCard {
                Column {
                    Text(stringResource(R.string.location_history_title), style = MaterialTheme.typography.titleMedium)
                    reports.forEachIndexed { index, report ->
                        LocationReportRow(
                            snapshot = report,
                            isLatest = index == 0,
                            onTap = { onOpenMap(report.id) },
                        )
                        if (index != reports.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** One row in the "Location History" list: a leading tick, a relative recency, and an absolute timestamp with coordinates. */
@Composable
internal fun LocationReportRow(snapshot: NodeStatusSnapshotDto, isLatest: Boolean, onTap: () -> Unit) {
    val coordinate = snapshot.validCoordinate
    val tickColor = if (isLatest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(tickColor))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                LocationReportFormat.relativeTime(snapshot.timestamp, Instant.now()).asString(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isLatest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(locationDetailLine(snapshot, coordinate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Jul 13, 07:41 · 37.7847, -122.4012 · 42 m". Altitude appears only when the fix carried one. */
private fun locationDetailLine(snapshot: NodeStatusSnapshotDto, coordinate: Pair<Double, Double>?): String {
    val time = LocationReportFormat.absoluteTime(snapshot.timestamp)
    if (coordinate == null) return time
    var line = "$time · ${LocationReportFormat.coordinates(coordinate.first, coordinate.second)}"
    snapshot.altitude?.let { line += " · ${LocationReportFormat.altitude(it)}" }
    return line
}

private const val PREVIEW_HEIGHT_DP = 160
private const val PREVIEW_CORNER_RADIUS_DP = 12

/** Non-interactive inline map preview: the plotted points/lines, fit to their bounds, with an expand button. */
@Composable
private fun LocationPreviewMap(path: PlottedLocationPath, onExpand: () -> Unit) {
    val controller = remember { LocationPreviewMapController() }

    LaunchedEffect(path) {
        controller.update(path)
    }

    Box(modifier = Modifier.fillMaxWidth().height(PREVIEW_HEIGHT_DP.dp).clip(RoundedCornerShape(PREVIEW_CORNER_RADIUS_DP.dp))) {
        val mapView = rememberMapViewWithLifecycle()
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { view -> view.getMapAsync(controller::attach) },
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                .clickable(onClick = onExpand)
                .padding(6.dp),
        ) {
            Icon(painterResource(R.drawable.ic_open_in_full), contentDescription = stringResource(R.string.location_expand_map), modifier = Modifier.size(16.dp))
        }
    }
}

private const val PREVIEW_SOURCE_POINTS = "location-preview-points"
private const val PREVIEW_SOURCE_LINES = "location-preview-lines"
private const val PREVIEW_LAYER_POINTS = "location-preview-points-circle"
private const val PREVIEW_LAYER_LINES = "location-preview-lines-layer"
private const val PROP_COLOR = "color"

internal const val LOCATION_HERO_COLOR = "#2463EB"
internal const val LOCATION_DOT_COLOR = "#94A3B8"
internal const val LOCATION_TRAIL_COLOR = "#60A5FA"

/** Owns the [MapLibreMap] for the non-interactive inline preview: point/line sources, fit-to-bounds, gestures disabled. */
private class LocationPreviewMapController {
    private var map: MapLibreMap? = null
    private var attached = false
    private var pointsSource: GeoJsonSource? = null
    private var linesSource: GeoJsonSource? = null
    private var pendingPath: PlottedLocationPath? = null

    fun attach(map: MapLibreMap) {
        this.map = map
        if (attached) return
        attached = true
        map.uiSettings.setAllGesturesEnabled(false)

        map.setStyle(Style.Builder().fromUri(NODE_MAP_STYLE_URL)) { style ->
            val lines = GeoJsonSource(PREVIEW_SOURCE_LINES, FeatureCollection.fromFeatures(emptyArray()))
            style.addSource(lines)
            style.addLayer(
                LineLayer(PREVIEW_LAYER_LINES, PREVIEW_SOURCE_LINES).withProperties(
                    PropertyFactory.lineColor(LOCATION_TRAIL_COLOR),
                    PropertyFactory.lineWidth(2.5f),
                ),
            )
            linesSource = lines

            val points = GeoJsonSource(PREVIEW_SOURCE_POINTS, FeatureCollection.fromFeatures(emptyArray()))
            style.addSource(points)
            style.addLayer(
                CircleLayer(PREVIEW_LAYER_POINTS, PREVIEW_SOURCE_POINTS).withProperties(
                    PropertyFactory.circleRadius(6f),
                    PropertyFactory.circleColor(Expression.get(PROP_COLOR)),
                    PropertyFactory.circleStrokeWidth(1.5f),
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

    private fun apply(path: PlottedLocationPath) {
        pointsSource?.setGeoJson(path.points.pointsToFeatureCollection())
        linesSource?.setGeoJson(path.lines.linesToFeatureCollection())
        fitToBounds(path)
    }

    private fun fitToBounds(path: PlottedLocationPath) {
        val map = map ?: return
        when {
            path.points.isEmpty() -> Unit
            path.points.size == 1 -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(path.points[0].latitude, path.points[0].longitude), 12.0))
            else -> {
                val bounds = LatLngBounds.Builder()
                path.points.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 32))
            }
        }
    }
}

internal fun List<LocationMapPoint>.pointsToFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(
    map { point ->
        Feature.fromGeometry(GeoPoint.fromLngLat(point.longitude, point.latitude)).apply {
            addStringProperty(PROP_COLOR, if (point.isLatest) LOCATION_HERO_COLOR else LOCATION_DOT_COLOR)
        }
    },
)

internal fun List<LocationMapLine>.linesToFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(
    map { line -> Feature.fromGeometry(LineString.fromLngLats(line.coordinates.map { (lat, lon) -> GeoPoint.fromLngLat(lon, lat) })) },
)
