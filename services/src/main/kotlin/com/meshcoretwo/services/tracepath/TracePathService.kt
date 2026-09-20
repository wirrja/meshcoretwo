// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.tracepath

import com.meshcoretwo.protocol.MessageSentInfo
import com.meshcoretwo.protocol.TraceInfo
import com.meshcoretwo.services.diagnostics.BinaryProtocolService
import com.meshcoretwo.services.persistence.TracePathDto
import com.meshcoretwo.services.persistence.TracePathRunDto
import com.meshcoretwo.services.persistence.TracePathStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/**
 * Trace Path's services-layer half: saved-path persistence (wrapping [TracePathStore], the Room
 * counterpart of `PersistenceStore`'s `TracePathPersisting` conformance) plus a live [TraceInfo]
 * event stream sourced from [binaryProtocolService]'s push-response plumbing.
 *
 * Deliberately thin — everything Swift's `TracePathViewModel` does with the events this exposes
 * (tag/device correlation, per-trace timeout, batch sequencing, hop-to-contact-name resolution) is
 * `app`-layer UI state, not persisted or session-owned, so it stays out of this class exactly like
 * it stays out of `PersistenceStore`/`AdvertisementService` on iOS. [traceEvents] fires for every
 * [TraceInfo] this device receives while listening, tagged or not — the future `TracePathViewModel`
 * port filters by its own pending tag, mirroring `handleTraceResponse`'s own tag check on iOS.
 *
 * [binaryProtocolService]'s event monitoring is a per-remote-session concern, not a per-connection
 * one (see that class's doc, and [com.meshcoretwo.services.ServiceContainer]'s doc on why it's
 * never started there) — [startEventMonitoring]/[stopEventMonitoring] here are thin forwards so
 * the future Trace Path screen can start listening on entry and stop on exit without reaching past
 * this service into diagnostics internals.
 */
class TracePathService(
    private val binaryProtocolService: BinaryProtocolService,
    private val store: TracePathStore,
) {
    private val _traceEvents = MutableSharedFlow<TraceInfo>(extraBufferCapacity = 8)

    /** Every [TraceInfo] this device receives while [startEventMonitoring] is active. */
    val traceEvents: SharedFlow<TraceInfo> = _traceEvents.asSharedFlow()

    init {
        binaryProtocolService.setTraceResponseHandler { info -> _traceEvents.emit(info) }
    }

    /** Starts listening for trace responses (and the other diagnostics pushes [BinaryProtocolService] handles). */
    fun startEventMonitoring() = binaryProtocolService.startEventMonitoring()

    /** Stops listening for trace responses. */
    fun stopEventMonitoring() = binaryProtocolService.stopEventMonitoring()

    /** Sends a trace-route request. Only confirms the request left the radio — see [traceEvents]'s doc for how the result arrives. */
    suspend fun sendTrace(tag: UInt, authCode: UInt, flags: UByte, path: ByteArray): MessageSentInfo =
        binaryProtocolService.sendTrace(tag, authCode, flags, path)

    // MARK: - Persistence (see TracePathStore for per-method docs)

    suspend fun fetchSavedTracePaths(radioID: UUID): List<TracePathDto> = store.fetchSavedTracePaths(radioID)

    suspend fun fetchSavedTracePath(id: UUID): TracePathDto? = store.fetchSavedTracePath(id)

    suspend fun createSavedTracePath(
        radioID: UUID,
        name: String,
        pathBytes: ByteArray,
        hashSize: Int,
        initialRun: TracePathRunDto?,
    ): TracePathDto = store.createSavedTracePath(radioID, name, pathBytes, hashSize, initialRun)

    suspend fun updateSavedTracePathName(id: UUID, name: String) = store.updateSavedTracePathName(id, name)

    suspend fun deleteSavedTracePath(id: UUID) = store.deleteSavedTracePath(id)

    suspend fun appendTracePathRun(pathID: UUID, run: TracePathRunDto) = store.appendTracePathRun(pathID, run)
}
