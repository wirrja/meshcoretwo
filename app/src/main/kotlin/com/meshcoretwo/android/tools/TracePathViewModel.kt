// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.android.pathediting.CodeInputResult
import com.meshcoretwo.android.pathediting.HopCodeClassification
import com.meshcoretwo.android.pathediting.HopCodeParser
import com.meshcoretwo.android.pathediting.HopCodeStatus
import com.meshcoretwo.android.pathediting.HopPickerSource
import com.meshcoretwo.android.pathediting.PathHop
import com.meshcoretwo.android.pathediting.RecentHopsStore
import com.meshcoretwo.android.pathediting.RepeaterResolver
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.TraceInfo
import com.meshcoretwo.protocol.prefixBytes
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.DeviceConnectionState
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.contactService
import com.meshcoretwo.services.connection.tracePathService
import com.meshcoretwo.services.location.LocationFix
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.TracePathDto
import com.meshcoretwo.services.persistence.TracePathRunDto
import com.meshcoretwo.services.rf.GeoCoordinate
import com.meshcoretwo.services.rf.RFCalculator
import com.meshcoretwo.services.tracepath.TracePathService
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * UI state for the Trace Path builder/executor. Ported from the `@Observable` property set of
 * `TracePathViewModel.swift`, minus Swift's `discoveredRepeaters` (the "Discover" list has no
 * Android equivalent yet — see [com.meshcoretwo.android.pathediting.HopPickerSource]'s doc) and
 * the map/list-UI-only bits (view mode, per-row expansion) that belong to the not-yet-built
 * screen (PLAN.md's Trace Path subslices 4/5).
 *
 * [devicePathHashMode] mirrors Swift's `deps.connectedDevice()?.pathHashMode` live read — this
 * port snapshots it into state at construction and on every [TracePathViewModel.loadContacts]
 * call (each reconnect) rather than reading `ConnectionManager.connectedDeviceRecord` on every
 * access, since a Kotlin `StateFlow`-driven UI needs the value inside the observable state to
 * recompose correctly, unlike Swift's `@Observable` closure that re-evaluates at every property
 * read.
 */
data class TracePathUiState(
    val outboundPath: List<PathHop> = emptyList(),
    val availableRepeaters: List<ContactDto> = emptyList(),
    val availableRooms: List<ContactDto> = emptyList(),
    val autoReturnPath: Boolean = true,
    /** Recently added hop public keys, newest first. Source for the shared picker's "Recent" section. */
    val recentPublicKeys: List<ByteArray> = emptyList(),

    val isRunning: Boolean = false,
    val result: TraceResult? = null,
    /** Set to a new [UUID] only on a successful trace — a distinct signal the future screen can key a result-sheet presentation on. */
    val resultId: UUID? = null,
    val errorMessage: String? = null,
    /** Incremented on each error, for haptic-feedback triggers. */
    val errorHapticTrigger: Int = 0,

    val batchEnabled: Boolean = false,
    val batchSize: Int = 3,
    val currentTraceIndex: Int = 0,
    val completedResults: List<TraceResult> = emptyList(),

    val activeSavedPath: TracePathDto? = null,

    /** Per-trace hash size override (path_sz code 0/1/2). `null` follows the radio's configured [devicePathHashMode]. Honored by firmware v1.11+. */
    val traceHashMode: UByte? = null,
    /** Snapshot of the connected device's configured path-hash-size code — see this class's doc. */
    val devicePathHashMode: UByte = 0u,
) {
    // ByteArray fields have reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TracePathUiState) return false
        return outboundPath == other.outboundPath &&
            availableRepeaters == other.availableRepeaters &&
            availableRooms == other.availableRooms &&
            autoReturnPath == other.autoReturnPath &&
            recentPublicKeys.size == other.recentPublicKeys.size &&
            recentPublicKeys.zip(other.recentPublicKeys).all { (a, b) -> a.contentEquals(b) } &&
            isRunning == other.isRunning &&
            result == other.result &&
            resultId == other.resultId &&
            errorMessage == other.errorMessage &&
            errorHapticTrigger == other.errorHapticTrigger &&
            batchEnabled == other.batchEnabled &&
            batchSize == other.batchSize &&
            currentTraceIndex == other.currentTraceIndex &&
            completedResults == other.completedResults &&
            activeSavedPath == other.activeSavedPath &&
            traceHashMode == other.traceHashMode &&
            devicePathHashMode == other.devicePathHashMode
    }

    override fun hashCode(): Int {
        var result = outboundPath.hashCode()
        result = 31 * result + availableRepeaters.hashCode()
        result = 31 * result + availableRooms.hashCode()
        result = 31 * result + autoReturnPath.hashCode()
        result = 31 * result + recentPublicKeys.fold(1) { acc, key -> 31 * acc + key.contentHashCode() }
        result = 31 * result + isRunning.hashCode()
        result = 31 * result + (this.result?.hashCode() ?: 0)
        result = 31 * result + (resultId?.hashCode() ?: 0)
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + errorHapticTrigger
        result = 31 * result + batchEnabled.hashCode()
        result = 31 * result + batchSize
        result = 31 * result + currentTraceIndex
        result = 31 * result + completedResults.hashCode()
        result = 31 * result + (activeSavedPath?.hashCode() ?: 0)
        result = 31 * result + (traceHashMode?.hashCode() ?: 0)
        result = 31 * result + devicePathHashMode.hashCode()
        return result
    }
}

/** Combined repeaters and rooms for hash resolution (hex codes, path building, etc.). Ported from `availableNodes`. */
val TracePathUiState.availableNodes: List<ContactDto> get() = availableRepeaters + availableRooms

/** The override when set, otherwise [TracePathUiState.devicePathHashMode]. Ported from `effectiveTraceMode`. */
val TracePathUiState.effectiveTraceMode: UByte get() = traceHashMode ?: devicePathHashMode

/** Trace hash size in bytes per hop (1, 2, or 4). Trace uses power-of-2 encoding, unlike the linear routing hash size. Ported from `hashSize`. */
val TracePathUiState.hashSize: Int get() = 1 shl effectiveTraceMode.toInt()

/** Full path data: outbound + optional mirrored return (minus last hop to avoid duplicate). Ported from `fullPathData`/`fullPathBytes` (one representation suffices in Kotlin). */
val TracePathUiState.fullPathData: ByteArray
    get() {
        if (outboundPath.isEmpty()) return ByteArray(0)
        val outboundBytes = outboundPath.map { it.hashBytes }
        val allBytes = if (autoReturnPath) outboundBytes + outboundBytes.reversed().drop(1) else outboundBytes
        val stream = java.io.ByteArrayOutputStream()
        allBytes.forEach { stream.write(it) }
        return stream.toByteArray()
    }

/** Comma-separated path string for display/copy, chunked by hash size. Ported from `fullPathString`. */
val TracePathUiState.fullPathString: String
    get() = chunkedHexString(fullPathData, outboundPath.firstOrNull()?.hashBytes?.size ?: hashSize)

/** Can run trace if path has at least one hop and not currently running. Ported from `canRunTraceWhenConnected`. */
val TracePathUiState.canRunTraceWhenConnected: Boolean get() = outboundPath.isNotEmpty() && !isRunning

/** Can save path if result is successful and path hasn't changed since trace ran. Ported from `canSavePath`. */
val TracePathUiState.canSavePath: Boolean
    get() {
        if (batchEnabled) {
            if (completedResults.isEmpty()) return false
            val firstSuccess = successfulResults.firstOrNull() ?: return false
            return fullPathData.contentEquals(firstSuccess.tracedPathBytes)
        }
        val current = result ?: return false
        if (!current.success) return false
        return fullPathData.contentEquals(current.tracedPathBytes)
    }

val TracePathUiState.isBatchInProgress: Boolean get() = batchEnabled && currentTraceIndex > 0 && currentTraceIndex <= batchSize
val TracePathUiState.isBatchComplete: Boolean get() = batchEnabled && completedResults.size == batchSize
val TracePathUiState.successfulResults: List<TraceResult> get() = completedResults.filter { it.success }
val TracePathUiState.successCount: Int get() = successfulResults.size

val TracePathUiState.averageRTT: Int?
    get() {
        val rtts = successfulResults.map { it.durationMs }
        if (rtts.isEmpty()) return null
        return rtts.sum() / rtts.size
    }
val TracePathUiState.minRTT: Int? get() = successfulResults.map { it.durationMs }.minOrNull()
val TracePathUiState.maxRTT: Int? get() = successfulResults.map { it.durationMs }.maxOrNull()

val TracePathUiState.isRunningSavedPath: Boolean get() = activeSavedPath != null

/** The second-most-recent successful run for comparison display. Ported from `previousRun`. */
val TracePathUiState.previousRun: TracePathRunDto?
    get() {
        val successfulRuns = activeSavedPath?.runs?.filter { it.success }?.sortedByDescending { it.date } ?: emptyList()
        return if (successfulRuns.size >= 2) successfulRuns[1] else null
    }

/**
 * Total path distance in meters, using a priority cascade: full path (including device legs) if
 * the device has location, else intermediate repeaters only, else `null`. Ported from `totalPathDistance`.
 */
val TracePathUiState.totalPathDistance: Double?
    get() {
        val current = result ?: return null
        if (!current.success || current.hops.size < 2) return null
        calculateHopDistance(current.hops)?.let { return it }
        val repeaters = current.hops.filter { !it.isStartNode && !it.isEndNode }
        return calculateHopDistance(repeaters)
    }

/** Total distance for a sequence of hops, or `null` if any lacks location. */
private fun calculateHopDistance(hops: List<TraceHop>): Double? {
    if (hops.size < 2) return null
    var totalMeters = 0.0
    for (index in 0 until hops.size - 1) {
        val current = hops[index]
        val next = hops[index + 1]
        val curLat = current.latitude
        val curLon = current.longitude
        val nextLat = next.latitude
        val nextLon = next.longitude
        if (!current.hasLocation || !next.hasLocation || curLat == null || curLon == null || nextLat == null || nextLon == null) return null
        totalMeters += RFCalculator.distance(GeoCoordinate(curLat, curLon), GeoCoordinate(nextLat, nextLon))
    }
    return totalMeters
}

/** Names of intermediate repeaters that lack location data. Ported from `repeatersWithoutLocation`. */
val TracePathUiState.repeatersWithoutLocation: List<String>
    get() {
        val current = result ?: return emptyList()
        return current.hops
            .filter { !it.isStartNode && !it.isEndNode && !it.hasLocation }
            .map { it.resolvedName ?: it.hashDisplayString ?: "Unknown" }
    }

/** Whether the distance calculation used the intermediate-only fallback (device has no location). Ported from `isDistanceUsingFallback`. */
val TracePathUiState.isDistanceUsingFallback: Boolean
    get() {
        val current = result ?: return false
        if (!current.success || totalPathDistance == null) return false
        val startNode = current.hops.firstOrNull() ?: return false
        val endNode = current.hops.lastOrNull() ?: return false
        return !startNode.hasLocation || !endNode.hasLocation
    }

/**
 * Backs the Trace Path tool (diagnostic item 7 in PLAN.md's Phase 5 value list; screen not yet
 * built — subslices 4/5 remain). Ported from `TracePathViewModel.swift`
 * (`MC1/Views/Tools/TracePath`), minus the map/list-UI state noted on [TracePathUiState]'s doc.
 *
 * Takes [ConnectionManager] directly rather than Swift's closure-based `Dependencies` struct —
 * same convention as [LineOfSightViewModel]/[RxLogViewModel] (`contactService`/`tracePathService`
 * read live off it on demand). [currentLocation] stands in for Swift's `AppState.bestAvailableLocation`
 * — a continuously-updated app-wide location cache Android's port doesn't have yet (see
 * [LineOfSightViewModel]'s own gap); the future screen calls [setCurrentLocation] the same way
 * [LineOfSightScreen] drives its own location fetch.
 *
 * [startListening]/[stopListening] additionally start/stop [ConnectionManager.tracePathService]'s
 * event monitoring (`BinaryProtocolService.startEventMonitoring`), which is *not* part of
 * `ServiceContainer`'s automatic event-monitoring set (see [TracePathService]'s class doc) —
 * without this, [TracePathService.traceEvents] never emits, unlike Swift where the session's
 * event stream is always being drained by `AdvertisementService`.
 *
 * Device-ID correlation from Swift's `handleTraceResponse` (`pendingDeviceID` vs. the response's
 * `radioID`) is dropped: [TracePathService.traceEvents] emits a bare `TraceInfo` with no device
 * tag (unlike iOS's `AdvertisementService` event, which is
 * multi-device), because each device's `ServiceContainer`/`TracePathService` is torn down and
 * rebuilt on every connect/switch — subscription is already scoped to exactly one device by
 * [startListening]/[stopListening]'s own call sites. Tag correlation ([pendingTag]) still guards
 * against a stale/duplicate response.
 */
class TracePathViewModel(
    private val connectionManager: ConnectionManager,
    private val recents: RecentHopsStore,
) : ViewModel(), HopPickerSource {
    private val _uiState = MutableStateFlow(
        TracePathUiState(devicePathHashMode = connectionManager.connectedDeviceRecord?.pathHashMode ?: 0u),
    )
    val uiState: StateFlow<TracePathUiState> = _uiState.asStateFlow()

    private var allContacts: List<ContactDto> = emptyList()
    private var currentRadioID: UUID? = null
    private var currentLocation: LocationFix? = null

    private var errorAutoClearJob: Job? = null
    private var traceTask: Job? = null
    private var traceEventsJob: Job? = null

    private var pendingTag: UInt? = null
    private var traceStartTime: Instant? = null
    private var pendingPathHash: ByteArray? = null

    private var batchCancelled = false

    /** Resumed by [handleTraceResponse] (or the timeout job) so [executeSingleTrace] can await the response, mirroring Swift's `CheckedContinuation`. */
    private var traceContinuation: CompletableDeferred<Unit>? = null

    init {
        viewModelScope.launch {
            connectionManager.connectionStateEvents.collect { state ->
                if (state == DeviceConnectionState.READY) {
                    val radioID = connectionManager.lastConnectedRadioID
                    if (radioID != null) {
                        loadContacts(radioID)
                        startListening()
                    }
                } else {
                    stopListening()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectionManager.tracePathService?.stopEventMonitoring()
    }

    // MARK: - HopPickerSource

    override val availableRepeaters: List<ContactDto> get() = _uiState.value.availableRepeaters
    override val availableRooms: List<ContactDto> get() = _uiState.value.availableRooms
    override val recentPublicKeys: List<ByteArray> get() = _uiState.value.recentPublicKeys
    override val currentHopCount: Int get() = _uiState.value.outboundPath.size

    /** Trace paths are uncapped; `null` tells the shared picker not to gate adds. */
    override val hopLimit: Int? get() = null

    override fun appendHop(node: ContactDto) {
        addNode(node)
        recordRecent(node.publicKey)
    }

    override fun addCodes(input: String): CodeInputResult = addRepeatersFromCodes(input)

    override fun classifyCodes(input: String): List<HopCodeClassification> {
        val state = _uiState.value
        val existing = state.outboundPath.map { it.hashHex }.toSet()
        return HopCodeParser.classify(
            input = input,
            hashSize = state.hashSize,
            existingHashes = existing,
            remainingCapacity = null,
        ) { hash -> resolveNode(hash)?.let { it.publicKey to it.resolvableName } }
    }

    /**
     * When a pasted bulk entry's codes all share one valid trace width the radio can honor and
     * differs from the active width, switch to it so the codes parse instead of failing as
     * invalid. Ported from `adoptHashSize(forPastedCodes:)`.
     */
    override fun adoptHashSize(forPastedCodes: String) {
        val device = connectionManager.connectedDeviceRecord ?: return
        if (!device.supportsTraceHashSizeOverride) return
        val mode = inferredTraceHashMode(forPastedCodes) ?: return
        if (mode == _uiState.value.effectiveTraceMode) return
        setTraceHashMode(mode)
    }

    fun recordRecent(publicKey: ByteArray) {
        val radioID = currentRadioID ?: return
        _uiState.update { it.copy(recentPublicKeys = recents.record(publicKey, it.recentPublicKeys, radioID)) }
    }

    // MARK: - Location

    /** Stands in for Swift's `AppState.bestAvailableLocation` — see this class's doc. */
    fun setCurrentLocation(fix: LocationFix?) {
        currentLocation = fix
    }

    // MARK: - Event Subscription

    /** Starts listening for trace responses on the current connection's [ConnectionManager.tracePathService]. See this class's doc for why this also starts event monitoring. */
    fun startListening() {
        traceEventsJob?.cancel()
        val tracePathService = connectionManager.tracePathService ?: return
        tracePathService.startEventMonitoring()
        traceEventsJob = viewModelScope.launch {
            tracePathService.traceEvents.collect { info -> handleTraceResponse(info) }
        }
    }

    /** Stops listening for trace responses. */
    fun stopListening() {
        traceEventsJob?.cancel()
        traceEventsJob = null
        connectionManager.tracePathService?.stopEventMonitoring()
    }

    // MARK: - Error Handling

    fun setError(message: String) {
        errorAutoClearJob?.cancel()
        _uiState.update { it.copy(errorMessage = message, errorHapticTrigger = it.errorHapticTrigger + 1) }
        errorAutoClearJob = viewModelScope.launch {
            delay(ERROR_AUTO_CLEAR_DELAY_MS)
            _uiState.update { it.copy(errorMessage = null) }
        }
    }

    fun clearError() {
        errorAutoClearJob?.cancel()
        errorAutoClearJob = null
        _uiState.update { it.copy(errorMessage = null) }
    }

    // MARK: - Hash Resolution

    /** Resolve hash bytes to the best matching contact name. Ported from `resolveHashToName`. */
    fun resolveHashToName(hashBytes: ByteArray): String? = resolveNode(hashBytes)?.resolvableName

    private fun resolveNode(hashBytes: ByteArray): ContactDto? =
        RepeaterResolver.bestMatch(hashBytes, _uiState.value.availableNodes, currentLocation)

    private fun resolveNode(hop: PathHop): ContactDto? =
        RepeaterResolver.bestMatch(hop, _uiState.value.availableNodes, currentLocation)

    // MARK: - Data Loading

    /** Loads contacts for name resolution and available repeaters/rooms. Ported from `loadContacts(radioID:)`. */
    suspend fun loadContacts(radioID: UUID) {
        currentRadioID = radioID
        val recentKeys = recents.load(radioID)
        val device = connectionManager.connectedDeviceRecord
        val dropOverride = device?.supportsTraceHashSizeOverride != true
        _uiState.update { state ->
            state.copy(
                recentPublicKeys = recentKeys,
                traceHashMode = if (dropOverride) null else state.traceHashMode,
                devicePathHashMode = device?.pathHashMode ?: 0u,
            )
        }

        val contactService = connectionManager.contactService
        if (contactService == null) {
            allContacts = emptyList()
            _uiState.update { it.copy(availableRepeaters = emptyList(), availableRooms = emptyList()) }
            return
        }
        val contacts = contactService.getContacts(radioID)
        allContacts = contacts
        _uiState.update {
            it.copy(
                availableRepeaters = contacts.filter { c -> c.type == ContactType.REPEATER },
                availableRooms = contacts.filter { c -> c.type == ContactType.ROOM },
            )
        }
    }

    // MARK: - Path Manipulation

    /** Adds a node to the outbound path. Ported from `addNode`. */
    fun addNode(node: ContactDto) {
        clearError()
        val hashBytes = node.publicKey.prefixBytes(_uiState.value.hashSize)
        val hop = PathHop(hashBytes = hashBytes, publicKey = node.publicKey, resolvedName = node.resolvableName)
        _uiState.update { it.copy(outboundPath = it.outboundPath + hop, activeSavedPath = null, result = null) }
        pendingPathHash = null
    }

    /**
     * Applies a per-trace hash size override and rebuilds every hop to a uniform width. Ported
     * from `setTraceHashMode`; `ByteArray.copyOf(size)` already truncates-or-zero-pads in one
     * step, replacing Swift's separate prefix + append-zeros.
     */
    fun setTraceHashMode(mode: UByte) {
        clearError()
        val size = 1 shl mode.toInt()
        _uiState.update { state ->
            val newPath = state.outboundPath.map { hop ->
                val source = hop.publicKey ?: hop.hashBytes
                hop.copy(hashBytes = source.copyOf(size))
            }
            state.copy(traceHashMode = mode, outboundPath = newPath, activeSavedPath = null, result = null)
        }
        pendingPathHash = null
    }

    /** Parses comma-separated hex codes and adds matching repeaters to the path. Ported from `addRepeatersFromCodes`. */
    fun addRepeatersFromCodes(input: String): CodeInputResult {
        val result = CodeInputResult()
        for (entry in classifyCodes(input)) {
            when (val status = entry.status) {
                is HopCodeStatus.WillAdd -> {
                    _uiState.update { it.copy(outboundPath = it.outboundPath + status.hop) }
                    status.hop.publicKey?.let { recordRecent(it) }
                    result.added.add(entry.code)
                }
                HopCodeStatus.AlreadyInPath -> result.alreadyInPath.add(entry.code)
                HopCodeStatus.NotFound -> result.notFound.add(entry.code)
                HopCodeStatus.InvalidFormat -> result.invalidFormat.add(entry.code)
                HopCodeStatus.PathFull -> error("trace paths are uncapped, so classifyCodes never yields PathFull")
            }
        }
        if (result.added.isNotEmpty()) {
            _uiState.update { it.copy(activeSavedPath = null, result = null) }
            clearError()
        }
        return result
    }

    /** Removes a repeater from the path. Ported from `removeRepeater`. */
    fun removeRepeater(index: Int) {
        clearError()
        val path = _uiState.value.outboundPath
        if (index !in path.indices) return
        _uiState.update {
            it.copy(outboundPath = it.outboundPath.toMutableList().apply { removeAt(index) }, activeSavedPath = null, result = null)
        }
        pendingPathHash = null
    }

    /**
     * Moves a repeater within the path. Ported from `moveRepeater`, trimmed to a single
     * from/to index — Swift's `IndexSet` supports a SwiftUI `List`'s general (possibly
     * multi-element) `.onMove`, but a Compose drag-reorder gesture moves one item at a time.
     */
    fun moveHop(from: Int, to: Int) {
        clearError()
        _uiState.update { state ->
            val path = state.outboundPath
            if (from !in path.indices) return@update state
            val mutable = path.toMutableList()
            val item = mutable.removeAt(from)
            val target = to.coerceIn(0, mutable.size)
            mutable.add(target, item)
            state.copy(outboundPath = mutable, activeSavedPath = null, result = null)
        }
        pendingPathHash = null
    }

    /** Generates a default name from the path (e.g., "Tower → ... → Ridge"). Ported from `generatePathName`. */
    fun generatePathName(): String {
        val state = _uiState.value
        val names = state.outboundPath.mapNotNull { it.resolvedName }
        return when (names.size) {
            0 -> "Path ${state.fullPathString.take(8)}"
            1 -> names[0]
            2 -> "${names[0]} → ${names[1]}"
            else -> "${names[0]} → ... → ${names.last()}"
        }
    }

    private fun extractHopsSNR(result: TraceResult): List<Double> =
        result.hops.filter { !it.isStartNode && !it.isEndNode }.map { it.snr }

    /** Saves the current path (or, in batch mode, every completed result) under [name]. Ported from `savePath`. */
    suspend fun savePath(name: String): Boolean {
        val radioID = connectionManager.connectedDeviceRecord?.radioID ?: return false
        val tracePathService = connectionManager.tracePathService ?: return false
        val state = _uiState.value

        if (state.batchEnabled && state.completedResults.isNotEmpty()) {
            val firstSuccess = state.successfulResults.firstOrNull() ?: return false
            val initialRun = TracePathRunDto(
                id = UUID.randomUUID(), date = Instant.now(), success = true,
                roundTripMs = firstSuccess.durationMs, hopsSNR = extractHopsSNR(firstSuccess),
            )
            return try {
                val savedPath = tracePathService.createSavedTracePath(radioID, name, firstSuccess.tracedPathBytes, state.hashSize, initialRun)
                for ((index, batchResult) in state.completedResults.withIndex()) {
                    if (batchResult.id == firstSuccess.id) continue
                    val run = TracePathRunDto(
                        id = UUID.randomUUID(), date = Instant.now().plusSeconds(index.toLong()), success = batchResult.success,
                        roundTripMs = batchResult.durationMs, hopsSNR = extractHopsSNR(batchResult),
                    )
                    tracePathService.appendTracePathRun(savedPath.id, run)
                }
                tracePathService.fetchSavedTracePath(savedPath.id)?.let { updated -> _uiState.update { it.copy(activeSavedPath = updated) } }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }

        val current = state.result
        if (current == null || !current.success) return false
        val initialRun = TracePathRunDto(
            id = UUID.randomUUID(), date = Instant.now(), success = true,
            roundTripMs = current.durationMs, hopsSNR = extractHopsSNR(current),
        )
        return try {
            val savedPath = tracePathService.createSavedTracePath(radioID, name, current.tracedPathBytes, state.hashSize, initialRun)
            _uiState.update { it.copy(activeSavedPath = savedPath) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Loads a saved path into the builder. Ported from `loadSavedPath`. */
    fun loadSavedPath(savedPath: TracePathDto) {
        val fullPath = savedPath.pathBytes
        val size = savedPath.hashSize
        if (fullPath.isEmpty()) {
            _uiState.update { it.copy(outboundPath = emptyList(), result = null, activeSavedPath = savedPath) }
            pendingPathHash = null
            return
        }

        val device = connectionManager.connectedDeviceRecord
        val newTraceHashMode = if (device?.supportsTraceHashSizeOverride == true) size.countTrailingZeroBits().toUByte() else null

        val totalHops = fullPath.size / size
        val outboundHopCount = (totalHops + 1) / 2
        val outboundByteCount = outboundHopCount * size

        val hops = mutableListOf<PathHop>()
        var start = 0
        while (start < minOf(outboundByteCount, fullPath.size)) {
            val end = minOf(start + size, fullPath.size)
            val hashBytes = fullPath.copyOfRange(start, end)
            val match = resolveNode(hashBytes)
            hops.add(PathHop(hashBytes = hashBytes, publicKey = match?.publicKey, resolvedName = match?.resolvableName))
            start += size
        }

        _uiState.update { it.copy(outboundPath = hops, result = null, traceHashMode = newTraceHashMode, activeSavedPath = savedPath) }
        pendingPathHash = null
    }

    /** Clears the path (resets to empty state). Ported from `clearPath`. */
    fun clearPath() {
        clearError()
        _uiState.update { it.copy(activeSavedPath = null, outboundPath = emptyList(), result = null, traceHashMode = null) }
        pendingPathHash = null
    }

    /** Clears the active saved-path reference if it matches the deleted path. Ported from `handleSavedPathDeleted`. */
    fun handleSavedPathDeleted(id: UUID) {
        if (_uiState.value.activeSavedPath?.id != id) return
        _uiState.update { it.copy(activeSavedPath = null) }
    }

    /** Finds a saved path matching the current path bytes; returns the most recently used match if multiple exist. Ported from `findMatchingSavedPath`. */
    private suspend fun findMatchingSavedPath(): TracePathDto? {
        val radioID = connectionManager.connectedDeviceRecord?.radioID ?: return null
        val tracePathService = connectionManager.tracePathService ?: return null
        val pathBytes = _uiState.value.fullPathData
        if (pathBytes.isEmpty()) return null
        return try {
            val savedPaths = tracePathService.fetchSavedTracePaths(radioID)
            val matches = savedPaths.filter { it.pathBytes.contentEquals(pathBytes) }
            matches.maxByOrNull { path -> path.runs.maxOfOrNull { it.date } ?: Instant.MIN }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // MARK: - Batch Configuration

    fun setBatchEnabled(enabled: Boolean) {
        _uiState.update { it.copy(batchEnabled = enabled) }
        if (!enabled) clearBatchState()
    }

    fun setBatchSize(size: Int) {
        _uiState.update { it.copy(batchSize = size) }
    }

    fun setAutoReturnPath(enabled: Boolean) {
        _uiState.update { it.copy(autoReturnPath = enabled) }
    }

    /** Clears batch execution state. Ported from `clearBatchState`. */
    fun clearBatchState() {
        _uiState.update { it.copy(currentTraceIndex = 0, completedResults = emptyList()) }
    }

    /**
     * Aggregate stats for a hop at the given index (0 = start node, 1+ = intermediate/end).
     * `null` for the start node (index 0), which has no received SNR. Ported from `hopStats(at:)`.
     */
    fun hopStats(index: Int): Triple<Double, Double, Double>? {
        if (index <= 0) return null
        val snrValues = _uiState.value.successfulResults.mapNotNull { result ->
            if (index >= result.hops.size) return@mapNotNull null
            val hop = result.hops[index]
            if (hop.isStartNode) return@mapNotNull null
            hop.snr
        }
        if (snrValues.isEmpty()) return null
        return Triple(snrValues.sum() / snrValues.size, snrValues.min(), snrValues.max())
    }

    /** SNR for a hop from the most recent successful result. Ported from `latestHopSNR(at:)`. */
    fun latestHopSNR(index: Int): Double? {
        val latest = _uiState.value.successfulResults.lastOrNull() ?: return null
        if (index >= latest.hops.size) return null
        return latest.hops[index].snr
    }

    // MARK: - Trace Execution

    /** Executes the trace and waits for a response. Ported from `runTrace`. */
    suspend fun runTrace() {
        val tracePathService = connectionManager.tracePathService ?: return
        if (_uiState.value.outboundPath.isEmpty()) return

        traceTask?.cancel()
        _uiState.update { it.copy(resultId = null) }
        clearError()

        if (_uiState.value.activeSavedPath == null) {
            findMatchingSavedPath()?.let { matched -> _uiState.update { it.copy(activeSavedPath = matched) } }
        }

        _uiState.update { it.copy(isRunning = true, result = null) }

        val pathData = _uiState.value.fullPathData
        pendingPathHash = pathData
        val tag = Random.nextInt().toUInt()
        pendingTag = tag
        traceStartTime = Instant.now()

        val timeoutSeconds: Double
        try {
            val sentInfo = tracePathService.sendTrace(tag, 0u, _uiState.value.effectiveTraceMode, pathData)
            timeoutSeconds = FirmwareSuggestedTimeout.sanitizedSeconds(sentInfo.suggestedTimeoutMs, FirmwareSuggestedTimeout.Profile.FLOOD)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(TracePathStrings.ERROR_SEND_FAILED)
            pendingPathHash = null
            recordFailedRun()
            _uiState.update { it.copy(isRunning = false) }
            pendingTag = null
            return
        }

        traceTask = viewModelScope.launch {
            delay((timeoutSeconds * 1000).toLong())
            if (pendingTag == tag) {
                setError(TracePathStrings.ERROR_NO_RESPONSE)
                pendingPathHash = null
                recordFailedRun()
                _uiState.update { it.copy(isRunning = false) }
                pendingTag = null
            }
        }
    }

    /** Executes multiple traces in batch mode. Ported from `runBatchTrace`. */
    suspend fun runBatchTrace() {
        if (!_uiState.value.batchEnabled) {
            runTrace()
            return
        }

        clearBatchState()
        batchCancelled = false
        _uiState.update { it.copy(resultId = null) }
        clearError()

        val tracePathService = connectionManager.tracePathService ?: return
        if (_uiState.value.outboundPath.isEmpty()) return

        if (_uiState.value.activeSavedPath == null) {
            findMatchingSavedPath()?.let { matched -> _uiState.update { it.copy(activeSavedPath = matched) } }
        }

        _uiState.update { it.copy(isRunning = true, result = null) }

        for (traceIndex in 1.._uiState.value.batchSize) {
            if (batchCancelled) break
            _uiState.update { it.copy(currentTraceIndex = traceIndex) }

            executeSingleTrace(tracePathService)

            val latestResult = _uiState.value.completedResults.lastOrNull()
            if (latestResult != null && latestResult.success) {
                if (_uiState.value.successCount == 1) {
                    _uiState.update { it.copy(result = latestResult, resultId = UUID.randomUUID()) }
                } else {
                    _uiState.update { it.copy(result = latestResult) }
                }
            }

            if (traceIndex < _uiState.value.batchSize) {
                if (batchCancelled) break
                delay(INTER_TRACE_BUFFER_MS)
                if (batchCancelled) break
            }
        }

        _uiState.update { it.copy(isRunning = false, currentTraceIndex = 0) }

        if (_uiState.value.isBatchComplete && _uiState.value.successCount == 0) {
            setError(TracePathStrings.errorAllFailed(_uiState.value.batchSize))
        }
    }

    /** Executes a single trace within a batch, storing the result in `completedResults`. Ported from `executeSingleTrace`. */
    private suspend fun executeSingleTrace(tracePathService: TracePathService) {
        val pathData = _uiState.value.fullPathData
        pendingPathHash = pathData
        val tag = Random.nextInt().toUInt()
        pendingTag = tag
        traceStartTime = Instant.now()

        val timeoutSeconds: Double
        try {
            val sentInfo = tracePathService.sendTrace(tag, 0u, _uiState.value.effectiveTraceMode, pathData)
            timeoutSeconds = FirmwareSuggestedTimeout.sanitizedSeconds(sentInfo.suggestedTimeoutMs, FirmwareSuggestedTimeout.Profile.FLOOD)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failedResult = TraceResult.sendFailed(TracePathStrings.ERROR_SEND_FAILED, pendingPathHash ?: ByteArray(0), _uiState.value.hashSize)
            _uiState.update { it.copy(completedResults = it.completedResults + failedResult) }
            recordFailedRun()
            pendingPathHash = null
            pendingTag = null
            return
        }

        val deferred = CompletableDeferred<Unit>()
        traceContinuation = deferred

        traceTask = viewModelScope.launch {
            delay((timeoutSeconds * 1000).toLong())
            if (traceContinuation != null && pendingTag == tag) {
                val timeoutResult = TraceResult.timeout(pendingPathHash ?: ByteArray(0), _uiState.value.hashSize)
                _uiState.update { it.copy(completedResults = it.completedResults + timeoutResult) }
                recordFailedRun()
                pendingPathHash = null
                pendingTag = null
                traceContinuation?.complete(Unit)
                traceContinuation = null
            }
        }

        deferred.await()
    }

    /** Records a failed run for the active saved path, fire-and-forget. Ported from `recordFailedRun`. */
    private fun recordFailedRun() {
        val savedPath = _uiState.value.activeSavedPath ?: return
        val tracePathService = connectionManager.tracePathService ?: return
        val failedRun = TracePathRunDto(id = UUID.randomUUID(), date = Instant.now(), success = false, roundTripMs = 0, hopsSNR = emptyList())
        viewModelScope.launch {
            try {
                tracePathService.appendTracePathRun(savedPath.id, failedRun)
                tracePathService.fetchSavedTracePath(savedPath.id)?.let { updated -> _uiState.update { it.copy(activeSavedPath = updated) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort — matches Swift's logged-and-swallowed failure.
            }
        }
    }

    /** Cancels any running batch trace. Ported from `cancelBatchTrace`. */
    fun cancelBatchTrace() {
        batchCancelled = true
        traceTask?.cancel()
        traceTask = null
        traceContinuation?.complete(Unit)
        traceContinuation = null
        _uiState.update { it.copy(isRunning = false, currentTraceIndex = 0) }
        pendingTag = null
        pendingPathHash = null
    }

    /** Handles a trace response from the event stream. Ported from `handleTraceResponse`. */
    private fun handleTraceResponse(traceInfo: TraceInfo) {
        if (traceInfo.tag != pendingTag) return

        traceTask?.cancel()
        traceTask = null

        val durationMs = traceStartTime?.let { java.time.Duration.between(it, Instant.now()).toMillis().toInt() } ?: 0

        val state = _uiState.value
        val deviceName = connectionManager.connectedDeviceRecord?.nodeName ?: TracePathStrings.MY_DEVICE
        val path = traceInfo.path
        val deviceLat = currentLocation?.latitude
        val deviceLon = currentLocation?.longitude

        val hops = mutableListOf<TraceHop>()
        hops.add(TraceHop(hashBytes = null, resolvedName = deviceName, snr = 0.0, isStartNode = true, isEndNode = false, latitude = deviceLat, longitude = deviceLon))

        for (node in path) {
            val bytes = node.hashBytes ?: continue
            val matchingHop = state.outboundPath.firstOrNull { it.hashBytes.contentEquals(bytes) }
            val match = matchingHop?.let { resolveNode(it) } ?: resolveNode(bytes)

            val resolvedName: String?
            var latitude: Double? = null
            var longitude: Double? = null
            if (match != null) {
                resolvedName = match.resolvableName
                if (match.hasLocation) {
                    latitude = match.latitude
                    longitude = match.longitude
                }
            } else {
                resolvedName = matchingHop?.resolvedName
            }

            hops.add(TraceHop(hashBytes = bytes, resolvedName = resolvedName, snr = node.snr, isStartNode = false, isEndNode = false, latitude = latitude, longitude = longitude))
        }

        val endSnr = path.lastOrNull()?.snr ?: 0.0
        hops.add(TraceHop(hashBytes = null, resolvedName = deviceName, snr = endSnr, isStartNode = false, isEndNode = true, latitude = deviceLat, longitude = deviceLon))

        val newResult = TraceResult(
            hops = hops, durationMs = durationMs, success = true, errorMessage = null,
            tracedPathBytes = pendingPathHash ?: ByteArray(0), hashSize = state.hashSize,
        )

        if (state.batchEnabled) {
            _uiState.update { it.copy(completedResults = it.completedResults + newResult) }
        } else {
            _uiState.update { it.copy(result = newResult, resultId = UUID.randomUUID(), isRunning = false) }
        }

        traceContinuation?.let { continuation ->
            traceContinuation = null
            traceTask?.cancel()
            continuation.complete(Unit)
        }

        pendingPathHash = null
        pendingTag = null
        traceStartTime = null

        val savedPath = state.activeSavedPath
        val tracePathService = connectionManager.tracePathService
        if (savedPath != null && tracePathService != null) {
            val hopsSNR = hops.filter { !it.isStartNode && !it.isEndNode }.map { it.snr }
            val runDto = TracePathRunDto(id = UUID.randomUUID(), date = Instant.now(), success = true, roundTripMs = durationMs, hopsSNR = hopsSNR)
            viewModelScope.launch {
                try {
                    tracePathService.appendTracePathRun(savedPath.id, runDto)
                    tracePathService.fetchSavedTracePath(savedPath.id)?.let { updated -> _uiState.update { it.copy(activeSavedPath = updated) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best-effort — matches Swift's logged-and-swallowed failure.
                }
            }
        }
    }

    class Factory(
        private val connectionManager: ConnectionManager,
        private val prefs: SharedPreferences,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TracePathViewModel(connectionManager, RecentHopsStore(prefs)) as T
    }

    companion object {
        private const val ERROR_AUTO_CLEAR_DELAY_MS = 4_000L

        /** Buffer between consecutive batch traces to avoid network flooding. */
        private const val INTER_TRACE_BUFFER_MS = 500L

        /** Trace hop widths the firmware accepts, in bytes (power-of-2 encoding). */
        private val VALID_TRACE_HASH_SIZES = setOf(1, 2, 4)

        /**
         * The single trace hash mode (0/1/2 for 1/2/4 bytes) implied by a comma-separated bulk
         * paste, or `null` when the codes are empty, non-hex, odd-length, mixed-width, or not a
         * valid power-of-2 trace width. Ported from `inferredTraceHashMode(from:)`.
         */
        fun inferredTraceHashMode(input: String): UByte? {
            val tokens = input.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null

            var width: Int? = null
            for (token in tokens) {
                if (token.length % 2 != 0 || !token.all { Character.digit(it, 16) >= 0 }) return null
                val bytes = token.length / 2
                if (width != null && width != bytes) return null
                width = bytes
            }
            val resolvedWidth = width ?: return null
            if (resolvedWidth !in VALID_TRACE_HASH_SIZES) return null
            return resolvedWidth.countTrailingZeroBits().toUByte()
        }
    }
}
