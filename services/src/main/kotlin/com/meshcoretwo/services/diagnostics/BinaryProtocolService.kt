// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.diagnostics

import com.meshcoretwo.protocol.ACLResponse
import com.meshcoretwo.protocol.BinaryProtocolSessionOps
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MMAResponse
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.protocol.MessageSentInfo
import com.meshcoretwo.protocol.NeighboursResponse
import com.meshcoretwo.protocol.StatusResponse
import com.meshcoretwo.protocol.TelemetryResponse
import com.meshcoretwo.protocol.TraceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * Service for binary-protocol diagnostics against remote mesh nodes: status, telemetry,
 * neighbours, ACL, MMA, path discovery, and trace requests. Ported from
 * `BinaryProtocolService.swift`.
 *
 * The Swift constructor also takes `dataStore: any PersistenceStoreProtocol`, but that property is
 * never read anywhere in `BinaryProtocolService.swift` (confirmed by grep) — dropped here rather
 * than carried forward as dead weight.
 *
 * This codebase's business services don't log (see `ChannelService`'s class doc); the `logger.*`
 * calls in [performWithPathResetOnTimeout]'s retry path are dropped for the same reason, along
 * with the `operationName` parameter that existed only to label them.
 */
class BinaryProtocolService(private val session: BinaryProtocolSessionOps) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var eventMonitorJob: Job? = null

    private var statusResponseHandler: (suspend (StatusResponse) -> Unit)? = null
    private var telemetryResponseHandler: (suspend (TelemetryResponse) -> Unit)? = null
    private var neighboursResponseHandler: (suspend (NeighboursResponse) -> Unit)? = null
    private var traceResponseHandler: (suspend (TraceInfo) -> Unit)? = null

    // MARK: - Event Handlers

    /** Sets the handler for status responses (arriving as push notifications). */
    fun setStatusResponseHandler(handler: suspend (StatusResponse) -> Unit) {
        statusResponseHandler = handler
    }

    /** Sets the handler for telemetry responses (arriving as push notifications). */
    fun setTelemetryResponseHandler(handler: suspend (TelemetryResponse) -> Unit) {
        telemetryResponseHandler = handler
    }

    /** Sets the handler for neighbours responses (arriving as push notifications). */
    fun setNeighboursResponseHandler(handler: suspend (NeighboursResponse) -> Unit) {
        neighboursResponseHandler = handler
    }

    /**
     * Sets the handler for trace-route responses. Unlike status/telemetry/neighbours, a trace
     * response is never a synchronous reply to [sendTrace] — [sendTrace] only confirms the
     * request left the radio, and the actual [TraceInfo] arrives later (potentially much later,
     * for a multi-hop flood) as this same kind of unsolicited push. Correlating a specific
     * request's tag/timeout against this handler's callbacks is the caller's job — mirroring
     * `TracePathViewModel.handleTraceResponse` on iOS, which does the same tag check itself
     * rather than pushing it down into the session/service layer.
     */
    fun setTraceResponseHandler(handler: suspend (TraceInfo) -> Unit) {
        traceResponseHandler = handler
    }

    // MARK: - Event Monitoring

    /** Starts monitoring MeshCore events for binary protocol push responses. */
    fun startEventMonitoring() {
        eventMonitorJob?.cancel()
        eventMonitorJob = scope.launch {
            session.events().collect { event -> handleEvent(event) }
        }
    }

    /** Stops monitoring events. */
    fun stopEventMonitoring() {
        eventMonitorJob?.cancel()
        eventMonitorJob = null
    }

    private suspend fun handleEvent(event: MeshEvent) {
        when (event) {
            is MeshEvent.StatusResponseEvent -> statusResponseHandler?.invoke(event.response)
            is MeshEvent.TelemetryResponseEvent -> telemetryResponseHandler?.invoke(event.response)
            is MeshEvent.NeighboursResponseEvent -> neighboursResponseHandler?.invoke(event.response)
            is MeshEvent.TraceData -> traceResponseHandler?.invoke(event.info)
            else -> {}
        }
    }

    // MARK: - Status Request

    /** Requests status from a remote node using the repeater status layout (blocking, waits for response). */
    suspend fun requestStatus(publicKey: ByteArray): StatusResponse =
        performWithPathResetOnTimeout(publicKey) { session.requestStatus(publicKey) }

    /** Requests status from a remote node, selecting the layout via [type] (blocking, waits for response). */
    suspend fun requestStatus(publicKey: ByteArray, type: ContactType): StatusResponse =
        performWithPathResetOnTimeout(publicKey) { session.requestStatus(publicKey, type) }

    // MARK: - Telemetry Request

    /** Requests telemetry from a remote node (blocking, waits for response). */
    suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse =
        performWithPathResetOnTimeout(publicKey) { session.requestTelemetry(publicKey) }

    // MARK: - Neighbours Request

    /** Requests the neighbours list from a remote node (blocking, waits for response). */
    suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: UByte = 255u,
        offset: UShort = 0u,
        orderBy: UByte = 0u,
        pubkeyPrefixLength: UByte = DEFAULT_PUBKEY_PREFIX_LENGTH,
    ): NeighboursResponse = performWithPathResetOnTimeout(publicKey) {
        session.requestNeighbours(publicKey, count, offset, orderBy, pubkeyPrefixLength)
    }

    /** Fetches all neighbours from a remote node with automatic pagination. */
    suspend fun fetchAllNeighbours(
        publicKey: ByteArray,
        orderBy: UByte = 0u,
        pubkeyPrefixLength: UByte = DEFAULT_PUBKEY_PREFIX_LENGTH,
    ): NeighboursResponse = performWithPathResetOnTimeout(publicKey) {
        session.fetchAllNeighbours(publicKey, orderBy, pubkeyPrefixLength)
    }

    // MARK: - MMA Request

    /** Requests min/max/average telemetry data from a remote node over a time range. */
    suspend fun requestMMA(publicKey: ByteArray, start: Instant, end: Instant): MMAResponse =
        performWithPathResetOnTimeout(publicKey) { session.requestMMA(publicKey, start, end) }

    // MARK: - Direct-path flood recovery

    /**
     * On mesh timeout, calls [BinaryProtocolSessionOps.resetPath] and retries once. Always
     * resets because this path has no radio-scoped contact to check for flood routing.
     */
    private suspend fun <T> performWithPathResetOnTimeout(publicKey: ByteArray, operation: suspend () -> T): T {
        try {
            return operation()
        } catch (error: MeshCoreError) {
            if (error !is MeshCoreError.Timeout) throw BinaryProtocolError.SessionError(error)

            val firstTimeout = error
            try {
                session.resetPath(publicKey)
            } catch (resetError: CancellationException) {
                throw resetError
            } catch (resetError: Exception) {
                throw BinaryProtocolError.SessionError(firstTimeout)
            }

            return try {
                operation()
            } catch (retryError: MeshCoreError) {
                throw BinaryProtocolError.SessionError(retryError)
            }
        }
    }

    // MARK: - ACL Request

    /** Requests the access control list from a remote node. */
    suspend fun requestACL(publicKey: ByteArray): ACLResponse {
        try {
            return session.requestACL(publicKey)
        } catch (error: MeshCoreError) {
            throw BinaryProtocolError.SessionError(error)
        }
    }

    // MARK: - Self Telemetry

    /** Gets telemetry from the local device. */
    suspend fun getSelfTelemetry(): TelemetryResponse {
        try {
            return session.getSelfTelemetry()
        } catch (error: MeshCoreError) {
            throw BinaryProtocolError.SessionError(error)
        }
    }

    // MARK: - Path Discovery

    /** Sends a path discovery request to a contact. */
    suspend fun sendPathDiscovery(publicKey: ByteArray): MessageSentInfo {
        try {
            return session.sendPathDiscovery(publicKey)
        } catch (error: MeshCoreError) {
            throw BinaryProtocolError.SessionError(error)
        }
    }

    // MARK: - Trace Route

    /** Sends a trace route request. */
    suspend fun sendTrace(
        tag: UInt? = null,
        authCode: UInt? = null,
        flags: UByte = 0u,
        path: ByteArray? = null,
    ): MessageSentInfo {
        try {
            return session.sendTrace(tag, authCode, flags, path)
        } catch (error: MeshCoreError) {
            throw BinaryProtocolError.SessionError(error)
        }
    }

    companion object {
        /**
         * Default pubkey-prefix length for neighbour queries — 6, distinct from
         * [com.meshcoretwo.protocol.DiagnosticsSessionOps]'s own 4-byte default, matching Swift's
         * `BinaryProtocolService.defaultPubkeyPrefixLength`.
         */
        const val DEFAULT_PUBKEY_PREFIX_LENGTH: UByte = 6u
    }
}
