// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.rf.ClearanceStatus
import com.meshcoretwo.services.rf.ElevationSample
import com.meshcoretwo.services.rf.ElevationService
import com.meshcoretwo.services.rf.FresnelZoneRenderer
import com.meshcoretwo.services.rf.GeoCoordinate
import com.meshcoretwo.services.rf.OpenMeteoElevationService
import com.meshcoretwo.services.rf.PathAnalysisResult
import com.meshcoretwo.services.rf.ProfileSample
import com.meshcoretwo.services.rf.RFCalculator
import com.meshcoretwo.services.rf.RelayPathAnalysisResult
import com.meshcoretwo.services.rf.SegmentAnalysisResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/** Identifies which point is being edited. Ported from `PointID` (`LineOfSightViewModel.swift`). */
enum class PointID { POINT_A, POINT_B, REPEATER }

/** A selected endpoint for line-of-sight analysis. Ported from `SelectedPoint`. */
data class SelectedPoint(
    val id: UUID = UUID.randomUUID(),
    val coordinate: GeoCoordinate,
    val contact: ContactDto?,
    val groundElevation: Double? = null,
    val additionalHeight: Double = 7.0,
) {
    val totalHeight: Double? get() = groundElevation?.let { it + additionalHeight }
    val displayName: String get() = contact?.displayName ?: "Dropped pin"
    val isLoadingElevation: Boolean get() = groundElevation == null
}

/**
 * A repeater point for relay analysis. Ported from `RepeaterPoint`. Can be on-path (dragged along
 * the terrain-profile slider, reuses the cached A→B elevation profile) or off-path (relocated on
 * the map, needs its own fresh elevation fetch).
 */
data class RepeaterPoint(
    val coordinate: GeoCoordinate,
    val groundElevation: Double? = null,
    val additionalHeight: Double = 10.0,
    val isOnPath: Boolean = true,
    val pathFraction: Double = 0.5,
) {
    companion object {
        /**
         * Repeaters are kept away from the endpoints — the same 5%-95% window `RepeaterPoint.swift`
         * clamps to via a `pathFraction` property observer. Every call site that sets `pathFraction`
         * here routes through this instead, since a Kotlin data class has no property-observer
         * equivalent.
         */
        fun clampPathFraction(value: Double): Double = value.coerceIn(0.05, 0.95)
    }
}

/** Current status of path analysis. Ported from `AnalysisStatus`. */
sealed class AnalysisStatus {
    data object Idle : AnalysisStatus()
    data class Result(val result: PathAnalysisResult) : AnalysisStatus()
    data class RelayResult(val result: RelayPathAnalysisResult) : AnalysisStatus()
    data class Error(val message: UiText) : AnalysisStatus()
}

/**
 * UI state for the Line of Sight tool. Ported from the `@Observable` property set of
 * `LineOfSightViewModel.swift`, minus everything tied to `MKMapView`/`MapPoint`/`MapLine`
 * (camera region, stable map IDs, pin styling, per-repeater selection state) — the Android map
 * stack has no multi-pin-style system yet (see `MapViewModel.kt`'s class doc on the same gap), so
 * that rendering-prep layer is deferred to the future screen sub-slice that actually draws this
 * tool's map/terrain views, same A1/A2/B/C-style split already used for `ConnectionManager`
 * (Phase 3) and RF Calculator/ElevationService (this file's own predecessor slice).
 */
data class LineOfSightUiState(
    val pointA: SelectedPoint? = null,
    val pointB: SelectedPoint? = null,
    val relocatingPoint: PointID? = null,
    val frequencyMHz: Double = 906.0,
    val refractionK: Double = 1.0,
    val repeatersWithLocation: List<ContactDto> = emptyList(),
    val repeaterPoint: RepeaterPoint? = null,
    val analysisStatus: AnalysisStatus = AnalysisStatus.Idle,
    val isAnalyzing: Boolean = false,
    val elevationProfile: List<ElevationSample> = emptyList(),
    val profileSamples: List<ProfileSample> = emptyList(),
    val profileSamplesRB: List<ProfileSample> = emptyList(),
    val elevationProfileAR: List<ElevationSample> = emptyList(),
    val elevationProfileRB: List<ElevationSample> = emptyList(),
    val elevationFetchFailed: Boolean = false,
) {
    val canAnalyze: Boolean get() = pointA?.groundElevation != null && pointB?.groundElevation != null

    /** Whether to show the "Add Repeater" placeholder — no repeater yet, but the direct path is marginal or worse. */
    val shouldShowRepeaterPlaceholder: Boolean
        get() {
            val result = (analysisStatus as? AnalysisStatus.Result)?.result ?: return false
            return result.clearanceStatus != ClearanceStatus.CLEAR && result.obstructionPoints.isNotEmpty()
        }

    /** Whether the repeater row should be visible: always once a repeater exists, or as a placeholder suggestion. */
    val shouldShowRepeaterRow: Boolean get() = repeaterPoint != null || shouldShowRepeaterPlaceholder

    /** Segment A→R distance in meters, null unless an off-path repeater has a relay result. */
    val segmentARDistanceMeters: Double?
        get() {
            if (repeaterPoint == null || repeaterPoint.isOnPath) return null
            return (analysisStatus as? AnalysisStatus.RelayResult)?.result?.segmentAR?.distanceMeters
        }

    /** Segment R→B distance in meters, null unless an off-path repeater has a relay result. */
    val segmentRBDistanceMeters: Double?
        get() {
            if (repeaterPoint == null || repeaterPoint.isOnPath) return null
            return (analysisStatus as? AnalysisStatus.RelayResult)?.result?.segmentRB?.distanceMeters
        }

    /**
     * The elevation profile to show in the terrain visualization: the cached A→B profile, or (for
     * an off-path repeater) the concatenated A→R→B profiles once both have loaded.
     */
    val terrainElevationProfile: List<ElevationSample>
        get() {
            val repeater = repeaterPoint
            if (repeater == null || repeater.isOnPath) return elevationProfile
            if (elevationProfileAR.isEmpty() || elevationProfileRB.isEmpty()) return elevationProfile
            // dropFirst() analogue: elevationProfileRB's first sample duplicates elevationProfileAR's last (both are R).
            return elevationProfileAR + elevationProfileRB.drop(1)
        }
}

/** Ground elevation at the repeater position: interpolated on-path, fetched off-path. Ported from the computed property of the same name. */
val LineOfSightUiState.repeaterGroundElevation: Double?
    get() {
        val repeater = repeaterPoint ?: return null
        return if (repeater.isOnPath) elevationAt(elevationProfile, repeater.pathFraction) else repeater.groundElevation
    }

/** Path fraction for repeater visualization in the terrain profile: direct for on-path, derived from segment distances for off-path. */
val LineOfSightUiState.repeaterVisualizationPathFraction: Double?
    get() {
        val repeater = repeaterPoint ?: return null
        if (repeater.isOnPath) return repeater.pathFraction
        val arLast = elevationProfileAR.lastOrNull() ?: return null
        val rbLast = elevationProfileRB.lastOrNull() ?: return null
        if (rbLast.distanceFromAMeters <= 0) return null
        return arLast.distanceFromAMeters / rbLast.distanceFromAMeters
    }

/** Interpolation indices/factor for a path fraction. Returns null when the profile has fewer than 2 samples. */
private fun interpolationIndices(profile: List<ElevationSample>, pathFraction: Double): Triple<Int, Int, Double>? {
    if (profile.size < 2) return null
    val clamped = pathFraction.coerceIn(0.0, 1.0)
    val index = clamped * (profile.size - 1)
    val lowerIndex = index.toInt()
    val upperIndex = (lowerIndex + 1).coerceAtMost(profile.size - 1)
    val t = index - lowerIndex
    return Triple(lowerIndex, upperIndex, t)
}

/** Interpolates ground elevation at a path fraction (0.0 = A, 1.0 = B). Ported from `elevationAt(pathFraction:)`. */
fun elevationAt(profile: List<ElevationSample>, pathFraction: Double): Double? {
    val (lower, upper, t) = interpolationIndices(profile, pathFraction) ?: return null
    val lowerElevation = profile[lower].elevation
    val upperElevation = profile[upper].elevation
    return lowerElevation + t * (upperElevation - lowerElevation)
}

/** Interpolates a coordinate at a path fraction (0.0 = A, 1.0 = B). Ported from `coordinateAt(pathFraction:)`. */
fun coordinateAt(profile: List<ElevationSample>, pathFraction: Double): GeoCoordinate? {
    val (lower, upper, t) = interpolationIndices(profile, pathFraction) ?: return null
    val lowerCoord = profile[lower].coordinate
    val upperCoord = profile[upper].coordinate
    return GeoCoordinate(
        latitude = lowerCoord.latitude + t * (upperCoord.latitude - lowerCoord.latitude),
        longitude = lowerCoord.longitude + t * (upperCoord.longitude - lowerCoord.longitude),
    )
}

/**
 * Backs the Line of Sight tool (diagnostic item 7 in PLAN.md's Phase 5 value list; screen not yet
 * built — see this file's class doc). Ported from `LineOfSightViewModel.swift`, minus the
 * `MKMapView`/`MapPoint`/`MapLine` rendering-prep layer (deferred, see [LineOfSightUiState]'s
 * class doc).
 *
 * Takes [ConnectionManager] directly rather than Swift's `dataStoreProvider`/`radioIDProvider`
 * closures — those existed on iOS because the view model lives outside any DI graph; this port
 * follows the rest of the `app` module's convention ([RxLogViewModel], `MapViewModel`) of taking
 * the shared [ConnectionManager] and reading `contactService`/`lastConnectedRadioID` off it
 * on demand. Likewise, the connected device's frequency (kHz) is seeded once at construction
 * from [ConnectionManager.connectedDeviceRecord] instead of a separate `configure(...)` call.
 */
class LineOfSightViewModel(
    private val connectionManager: ConnectionManager,
    private val elevationService: ElevationService = OpenMeteoElevationService(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        LineOfSightUiState(
            frequencyMHz = connectionManager.connectedDeviceRecord?.frequency
                ?.let { it.toDouble() / 1000.0 }
                ?: 906.0,
        ),
    )
    val uiState: StateFlow<LineOfSightUiState> = _uiState.asStateFlow()

    private var analysisJob: Job? = null
    private var pointAElevationJob: Job? = null
    private var pointBElevationJob: Job? = null
    private var repeaterElevationJob: Job? = null

    // MARK: - Frequency

    /** Live-edits the frequency field; call [commitFrequencyChange] once editing settles to re-run analysis. */
    fun setFrequencyMHz(value: Double) {
        _uiState.update { it.copy(frequencyMHz = value) }
    }

    /** Commits a frequency change and re-runs analysis against the cached elevation profile. */
    fun commitFrequencyChange() {
        reanalyzeWithCachedProfileIfNeeded()
    }

    /** Sets the refraction k-factor, auto-triggering re-analysis (mirrors the Swift `didSet`). */
    fun setRefractionK(value: Double) {
        if (_uiState.value.refractionK == value) return
        _uiState.update { it.copy(refractionK = value) }
        reanalyzeWithCachedProfileIfNeeded()
    }

    /**
     * Parses a user-entered frequency string into a positive MHz value. Accepts both "." and ","
     * as the decimal separator so comma-decimal locales round-trip, parsed with a fixed separator
     * rather than the current locale's.
     */
    fun parseFrequency(text: String): Double? {
        val normalized = text.replace(',', '.')
        val value = normalized.toDoubleOrNull() ?: return null
        return value.takeIf { it > 0 }
    }

    /** Renders a frequency value for editing using a locale-stable format that round-trips through [parseFrequency]. */
    fun formatFrequencyForEditing(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)

    // MARK: - Load Repeaters

    /** Loads repeater contacts with a stored location, for the repeater-relocation UI. */
    fun loadRepeaters() {
        viewModelScope.launch {
            val radioID = connectionManager.lastConnectedRadioID ?: return@launch
            val contacts = connectionManager.contactService?.getContacts(radioID) ?: return@launch
            _uiState.update {
                it.copy(repeatersWithLocation = contacts.filter { c -> c.hasLocation && c.type == ContactType.REPEATER })
            }
        }
    }

    // MARK: - Point Selection

    /** Auto-assigns a coordinate to A if empty, then B if A exists, else replaces B. */
    fun selectPoint(coordinate: GeoCoordinate, contact: ContactDto? = null) {
        when {
            _uiState.value.pointA == null -> setPointA(coordinate, contact)
            _uiState.value.pointB == null -> setPointB(coordinate, contact)
            else -> setPointB(coordinate, contact)
        }
    }

    fun setPointA(coordinate: GeoCoordinate, contact: ContactDto? = null) {
        pointAElevationJob?.cancel()
        invalidateAnalysis()

        _uiState.update { it.copy(pointA = SelectedPoint(coordinate = coordinate, contact = contact, groundElevation = null)) }

        pointAElevationJob = viewModelScope.launch { fetchElevationForPointA() }
    }

    fun setPointB(coordinate: GeoCoordinate, contact: ContactDto? = null) {
        val pointA = _uiState.value.pointA
        if (pointA != null && pointA.coordinate.latitude == coordinate.latitude && pointA.coordinate.longitude == coordinate.longitude) {
            return
        }

        pointBElevationJob?.cancel()
        invalidateAnalysis()

        _uiState.update { it.copy(pointB = SelectedPoint(coordinate = coordinate, contact = contact, groundElevation = null)) }

        pointBElevationJob = viewModelScope.launch { fetchElevationForPointB() }
    }

    /** Toggles a contact as a selected point: clears it if already A or B, otherwise auto-assigns. */
    fun toggleContact(contact: ContactDto) {
        val coordinate = GeoCoordinate(contact.latitude, contact.longitude)
        val state = _uiState.value

        when (contact.id) {
            state.pointA?.contact?.id -> clearPointA()
            state.pointB?.contact?.id -> clearPointB()
            else -> selectPoint(coordinate, contact)
        }
    }

    fun setRelocatingPoint(point: PointID?) {
        _uiState.update { it.copy(relocatingPoint = point) }
    }

    // MARK: - Height Adjustment

    fun updateAdditionalHeight(point: PointID, meters: Double) {
        val clamped = meters.coerceAtLeast(0.0)

        when (point) {
            PointID.POINT_A -> {
                if (_uiState.value.pointA == null) return
                _uiState.update { it.copy(pointA = it.pointA?.copy(additionalHeight = clamped)) }
            }
            PointID.POINT_B -> {
                if (_uiState.value.pointB == null) return
                _uiState.update { it.copy(pointB = it.pointB?.copy(additionalHeight = clamped)) }
            }
            PointID.REPEATER -> {
                updateRepeaterHeight(clamped)
                return
            }
        }

        invalidateAnalysis()
    }

    // MARK: - Clear

    fun clear() {
        pointAElevationJob?.cancel()
        pointBElevationJob?.cancel()
        analysisJob?.cancel()
        repeaterElevationJob?.cancel()
        pointAElevationJob = null
        pointBElevationJob = null
        analysisJob = null
        repeaterElevationJob = null

        _uiState.update {
            it.copy(
                pointA = null,
                pointB = null,
                repeaterPoint = null,
                elevationFetchFailed = false,
                isAnalyzing = false,
                analysisStatus = AnalysisStatus.Idle,
                elevationProfile = emptyList(),
                elevationProfileAR = emptyList(),
                elevationProfileRB = emptyList(),
            )
        }
    }

    fun clearPointA() {
        pointAElevationJob?.cancel()
        pointAElevationJob = null
        _uiState.update { it.copy(pointA = null, repeaterPoint = null) }
        invalidateAnalysis()
    }

    fun clearPointB() {
        pointBElevationJob?.cancel()
        pointBElevationJob = null
        _uiState.update { it.copy(pointB = null, repeaterPoint = null) }
        invalidateAnalysis()
    }

    // MARK: - Repeater

    /** Adds a repeater at the worst obstruction point of the current direct-path result. */
    fun addRepeater() {
        val result = (_uiState.value.analysisStatus as? AnalysisStatus.Result)?.result ?: return
        val worst = result.worstObstructionPoint ?: return
        val pathFraction = worst.distanceFromAMeters / result.distanceMeters

        val profile = _uiState.value.elevationProfile
        val coordinate = coordinateAt(profile, pathFraction) ?: return
        val elevation = elevationAt(profile, pathFraction) ?: return

        _uiState.update {
            it.copy(
                repeaterPoint = RepeaterPoint(
                    coordinate = coordinate,
                    groundElevation = elevation,
                    additionalHeight = 10.0,
                    isOnPath = true,
                    pathFraction = RepeaterPoint.clampPathFraction(pathFraction),
                ),
            )
        }
    }

    /** Moves an on-path repeater along the A→B path, deriving coordinate/elevation from the cached profile. */
    fun updateRepeaterPosition(pathFraction: Double) {
        val repeater = _uiState.value.repeaterPoint ?: return
        if (!repeater.isOnPath) return

        val clamped = RepeaterPoint.clampPathFraction(pathFraction)
        val profile = _uiState.value.elevationProfile
        val coordinate = coordinateAt(profile, clamped) ?: repeater.coordinate
        val elevation = elevationAt(profile, clamped) ?: repeater.groundElevation

        _uiState.update {
            it.copy(repeaterPoint = repeater.copy(pathFraction = clamped, coordinate = coordinate, groundElevation = elevation))
        }
    }

    fun updateRepeaterHeight(meters: Double) {
        if (_uiState.value.repeaterPoint == null) return
        _uiState.update { it.copy(repeaterPoint = it.repeaterPoint?.copy(additionalHeight = meters.coerceAtLeast(0.0))) }
    }

    /** Relocates the repeater off the A→B path (e.g. dragged on the map); triggers a fresh elevation fetch. */
    fun setRepeaterOffPath(coordinate: GeoCoordinate) {
        val existingHeight = _uiState.value.repeaterPoint?.additionalHeight ?: 10.0

        _uiState.update {
            it.copy(
                repeaterPoint = RepeaterPoint(
                    coordinate = coordinate,
                    groundElevation = null,
                    additionalHeight = existingHeight,
                    isOnPath = false,
                    pathFraction = 0.5,
                ),
                elevationProfileAR = emptyList(),
                elevationProfileRB = emptyList(),
            )
        }

        repeaterElevationJob?.cancel()
        repeaterElevationJob = viewModelScope.launch {
            try {
                val elevation = elevationService.fetchElevation(coordinate)
                _uiState.update { it.copy(repeaterPoint = it.repeaterPoint?.copy(groundElevation = elevation)) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Off-path repeater elevation stays null; the terrain profile falls back to the cached A-B profile.
            }
        }
    }

    /** Removes the repeater and reverts to single-path analysis. */
    fun clearRepeater() {
        // Cancel any in-flight off-path fetch so it can't write a stale coordinate's elevation
        // into a repeater added after this clear.
        repeaterElevationJob?.cancel()
        repeaterElevationJob = null

        _uiState.update { it.copy(repeaterPoint = null) }
        reanalyzeWithCachedProfileIfNeeded()
    }

    /** Analyzes the path with the current repeater position (on-path from cache, off-path via fresh fetch). */
    fun analyzeWithRepeater() {
        val state = _uiState.value
        val repeater = state.repeaterPoint ?: return
        val pointA = state.pointA ?: return
        val pointB = state.pointB ?: return

        when {
            repeater.isOnPath -> analyzeWithRepeaterOnPath()
            state.elevationProfileAR.isNotEmpty() && state.elevationProfileRB.isNotEmpty() -> applyRelayAnalysis(
                profileAR = state.elevationProfileAR,
                profileRB = state.elevationProfileRB,
                pointAHeight = pointA.additionalHeight,
                repeaterHeight = repeater.additionalHeight,
                pointBHeight = pointB.additionalHeight,
                frequencyMHz = state.frequencyMHz,
                refractionK = state.refractionK,
            )
            else -> {
                analysisJob?.cancel()
                analysisJob = viewModelScope.launch { analyzeWithRepeaterOffPath() }
            }
        }
    }

    private fun analyzeWithRepeaterOnPath() {
        val state = _uiState.value
        val repeater = state.repeaterPoint ?: return
        val pointA = state.pointA ?: return
        val pointB = state.pointB ?: return
        val profile = state.elevationProfile
        if (profile.size < 2) return

        val splitIndex = (repeater.pathFraction * (profile.size - 1)).toInt()
        if (splitIndex <= 0 || splitIndex >= profile.size - 1) return

        applyRelayAnalysis(
            profileAR = profile.subList(0, splitIndex + 1),
            profileRB = profile.subList(splitIndex, profile.size),
            pointAHeight = pointA.additionalHeight,
            repeaterHeight = repeater.additionalHeight,
            pointBHeight = pointB.additionalHeight,
            frequencyMHz = state.frequencyMHz,
            refractionK = state.refractionK,
        )
    }

    private suspend fun analyzeWithRepeaterOffPath() {
        val state = _uiState.value
        val repeater = state.repeaterPoint ?: return
        val pointA = state.pointA ?: return
        val pointB = state.pointB ?: return

        _uiState.update { it.copy(isAnalyzing = true) }

        try {
            val pointACoord = pointA.coordinate
            val repeaterCoord = repeater.coordinate
            val pointBCoord = pointB.coordinate

            val distanceAR = RFCalculator.distance(pointACoord, repeaterCoord)
            val sampleCoordsAR = OpenMeteoElevationService.sampleCoordinates(
                from = pointACoord,
                to = repeaterCoord,
                sampleCount = OpenMeteoElevationService.optimalSampleCount(distanceAR),
            )
            val distanceRB = RFCalculator.distance(repeaterCoord, pointBCoord)
            val sampleCoordsRB = OpenMeteoElevationService.sampleCoordinates(
                from = repeaterCoord,
                to = pointBCoord,
                sampleCount = OpenMeteoElevationService.optimalSampleCount(distanceRB),
            )

            val (profileAR, profileRB) = coroutineScope {
                val arDeferred = async { elevationService.fetchElevations(sampleCoordsAR) }
                val rbDeferred = async { elevationService.fetchElevations(sampleCoordsRB) }
                arDeferred.await() to rbDeferred.await()
            }

            // Offset the R→B profile's distances to continue from the A→R endpoint —
            // fetchElevations() returns distances relative to its own segment's start, not global A.
            val profileRBAdjusted = profileRB.map { it.copy(distanceFromAMeters = it.distanceFromAMeters + distanceAR) }

            applyRelayAnalysis(
                profileAR = profileAR,
                profileRB = profileRBAdjusted,
                pointAHeight = pointA.additionalHeight,
                repeaterHeight = repeater.additionalHeight,
                pointBHeight = pointB.additionalHeight,
                frequencyMHz = state.frequencyMHz,
                refractionK = state.refractionK,
            )

            _uiState.update { it.copy(elevationProfileAR = profileAR, elevationProfileRB = profileRBAdjusted, isAnalyzing = false) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _uiState.update { it.copy(isAnalyzing = false, analysisStatus = AnalysisStatus.Error(error.toUiText(UiText.of(R.string.los_err_offpath)))) }
        }
    }

    /** Shared relay analysis: analyzes both segments and updates the profile samples / analysis status. */
    private fun applyRelayAnalysis(
        profileAR: List<ElevationSample>,
        profileRB: List<ElevationSample>,
        pointAHeight: Double,
        repeaterHeight: Double,
        pointBHeight: Double,
        frequencyMHz: Double,
        refractionK: Double,
    ) {
        val arResult = RFCalculator.analyzePathSegment(profileAR, pointAHeight, repeaterHeight, frequencyMHz, refractionK)
        val rbResult = RFCalculator.analyzePathSegment(profileRB, repeaterHeight, pointBHeight, frequencyMHz, refractionK)

        val relayResult = RelayPathAnalysisResult(
            segmentAR = SegmentAnalysisResult("A", "R", arResult.clearanceStatus, arResult.distanceMeters, arResult.worstClearancePercent),
            segmentRB = SegmentAnalysisResult("R", "B", rbResult.clearanceStatus, rbResult.distanceMeters, rbResult.worstClearancePercent),
        )

        val samplesAR = FresnelZoneRenderer.buildProfileSamples(profileAR, pointAHeight, repeaterHeight, frequencyMHz, refractionK)
        val samplesRB = FresnelZoneRenderer.buildProfileSamples(profileRB, repeaterHeight, pointBHeight, frequencyMHz, refractionK)

        _uiState.update {
            it.copy(profileSamples = samplesAR, profileSamplesRB = samplesRB, analysisStatus = AnalysisStatus.RelayResult(relayResult))
        }
    }

    // MARK: - Analysis

    /** Clears analysis results without clearing the selected points. */
    fun clearAnalysisResults() {
        _uiState.update { it.copy(isAnalyzing = false, analysisStatus = AnalysisStatus.Idle) }
    }

    fun analyze() {
        val state = _uiState.value
        val pointA = state.pointA
        val pointB = state.pointB
        if (pointA == null || pointB == null || pointA.groundElevation == null || pointB.groundElevation == null) return

        analysisJob?.cancel()
        _uiState.update { it.copy(isAnalyzing = true) }

        val pointACoord = pointA.coordinate
        val pointBCoord = pointB.coordinate
        val pointAHeight = pointA.additionalHeight
        val pointBHeight = pointB.additionalHeight
        val freq = state.frequencyMHz
        val k = state.refractionK

        analysisJob = viewModelScope.launch {
            try {
                val distance = RFCalculator.distance(pointACoord, pointBCoord)
                val sampleCoordinates = OpenMeteoElevationService.sampleCoordinates(
                    from = pointACoord,
                    to = pointBCoord,
                    sampleCount = OpenMeteoElevationService.optimalSampleCount(distance),
                )
                val profile = elevationService.fetchElevations(sampleCoordinates)

                val result = withContext(Dispatchers.Default) {
                    RFCalculator.analyzePath(profile, pointAHeight, pointBHeight, freq, k)
                }
                val samples = FresnelZoneRenderer.buildProfileSamples(profile, pointAHeight, pointBHeight, freq, k)

                _uiState.update {
                    it.copy(
                        elevationProfile = profile,
                        profileSamples = samples,
                        profileSamplesRB = emptyList(),
                        isAnalyzing = false,
                        analysisStatus = AnalysisStatus.Result(result),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(isAnalyzing = false, analysisStatus = AnalysisStatus.Error(error.toUiText(UiText.of(R.string.los_err_analysis)))) }
            }
        }
    }

    // MARK: - Private

    /** Invalidates analysis results only, preserving the cached elevation profile. Use when RF settings change. */
    private fun invalidateAnalysisOnly() {
        analysisJob?.cancel()
        analysisJob = null
        _uiState.update { it.copy(isAnalyzing = false, analysisStatus = AnalysisStatus.Idle) }
    }

    /** Invalidates analysis and clears the cached elevation profile. Use when points change (needs new elevation data). */
    private fun invalidateAnalysis() {
        invalidateAnalysisOnly()
        repeaterElevationJob?.cancel()
        repeaterElevationJob = null
        _uiState.update {
            it.copy(
                elevationProfile = emptyList(),
                elevationProfileAR = emptyList(),
                elevationProfileRB = emptyList(),
                profileSamples = emptyList(),
                profileSamplesRB = emptyList(),
                elevationFetchFailed = false,
                repeaterPoint = null,
            )
        }
    }

    /** Re-runs analysis using the cached elevation profile when RF settings (frequency/k-factor) change. */
    private fun reanalyzeWithCachedProfileIfNeeded() {
        val state = _uiState.value
        val pointA = state.pointA
        val pointB = state.pointB
        if (state.elevationProfile.isEmpty() || pointA == null || pointB == null || pointA.groundElevation == null || pointB.groundElevation == null) {
            _uiState.update { it.copy(isAnalyzing = false) }
            return
        }

        if (state.repeaterPoint != null) {
            _uiState.update { it.copy(isAnalyzing = false) }
            analyzeWithRepeater()
            return
        }

        analysisJob?.cancel()
        _uiState.update { it.copy(isAnalyzing = true) }

        val profile = state.elevationProfile
        val pointAHeight = pointA.additionalHeight
        val pointBHeight = pointB.additionalHeight
        val freq = state.frequencyMHz
        val k = state.refractionK

        analysisJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { RFCalculator.analyzePath(profile, pointAHeight, pointBHeight, freq, k) }
                val samples = FresnelZoneRenderer.buildProfileSamples(profile, pointAHeight, pointBHeight, freq, k)
                _uiState.update {
                    it.copy(profileSamples = samples, profileSamplesRB = emptyList(), analysisStatus = AnalysisStatus.Result(result))
                }
            } finally {
                _uiState.update { it.copy(isAnalyzing = false) }
            }
        }
    }

    private suspend fun fetchElevationForPointA() {
        val coordinate = _uiState.value.pointA?.coordinate ?: return
        try {
            val elevation = elevationService.fetchElevation(coordinate)
            _uiState.update { it.copy(pointA = it.pointA?.copy(groundElevation = elevation)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Sea-level fallback so analysis can proceed even when the terrain API is unreachable.
            _uiState.update { it.copy(pointA = it.pointA?.copy(groundElevation = 0.0), elevationFetchFailed = true) }
        }
    }

    private suspend fun fetchElevationForPointB() {
        val coordinate = _uiState.value.pointB?.coordinate ?: return
        try {
            val elevation = elevationService.fetchElevation(coordinate)
            _uiState.update { it.copy(pointB = it.pointB?.copy(groundElevation = elevation)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _uiState.update { it.copy(pointB = it.pointB?.copy(groundElevation = 0.0), elevationFetchFailed = true) }
        }
    }

    class Factory(
        private val connectionManager: ConnectionManager,
        private val elevationService: ElevationService = OpenMeteoElevationService(),
        private val preselectedContact: ContactDto? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val viewModel = LineOfSightViewModel(connectionManager, elevationService)
            if (preselectedContact != null && preselectedContact.hasLocation) {
                viewModel.setPointA(GeoCoordinate(preselectedContact.latitude, preselectedContact.longitude), preselectedContact)
            }
            return viewModel as T
        }
    }
}
