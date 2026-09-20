// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import com.meshcoretwo.protocol.ContactMessage
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.NeighboursResponse
import com.meshcoretwo.protocol.OwnerInfoResponse
import com.meshcoretwo.protocol.RemoteAccessSessionOps
import com.meshcoretwo.protocol.StatusResponse
import com.meshcoretwo.protocol.TelemetryResponse
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionStore
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Service for repeater admin interactions: connecting as admin, viewing status/telemetry/
 * neighbours, and sending CLI commands. Ported from `RepeaterAdminService.swift`.
 *
 * Takes its own [RemoteAccessSessionOps] reference (not [remoteNodeService]'s private one) for
 * [requestNeighbors] — `RemoteNodeService` never exposed a neighbours passthrough, matching
 * Swift's own `RepeaterAdminService`, which calls `session.requestNeighbours` directly rather
 * than through `remoteNodeService`. Both constructor params are expected to wrap the same
 * underlying session in the composition root, the same as Swift's `init` taking both.
 *
 * Deferred: `CommandAuditLogger` (pure logging, dropped everywhere in this port — see
 * `ChannelService`'s class doc).
 */
class RepeaterAdminService(
    private val session: RemoteAccessSessionOps,
    private val remoteNodeService: RemoteNodeService,
    private val sessionStore: RemoteNodeSessionStore,
) {
    // MARK: - Handlers

    private var statusResponseHandler: (suspend (StatusResponse) -> Unit)? = null
    private var neighboursResponseHandler: (suspend (NeighboursResponse) -> Unit)? = null
    private var telemetryResponseHandler: (suspend (TelemetryResponse) -> Unit)? = null
    private var cliResponseHandler: (suspend (ContactMessage, ContactDto) -> Unit)? = null

    fun setStatusHandler(handler: suspend (StatusResponse) -> Unit) {
        statusResponseHandler = handler
    }

    fun setNeighboursHandler(handler: suspend (NeighboursResponse) -> Unit) {
        neighboursResponseHandler = handler
    }

    fun setTelemetryHandler(handler: suspend (TelemetryResponse) -> Unit) {
        telemetryResponseHandler = handler
    }

    fun setCLIHandler(handler: suspend (ContactMessage, ContactDto) -> Unit) {
        cliResponseHandler = handler
    }

    /** Clears all handlers (call when the view disappears). */
    fun clearHandlers() {
        statusResponseHandler = null
        neighboursResponseHandler = null
        telemetryResponseHandler = null
        cliResponseHandler = null
    }

    /**
     * Clears only the status-surface handlers so the merged admin surface can tear down its
     * status segment without dropping the settings VM's CLI handler on the shared per-connection
     * service.
     */
    fun clearStatusHandlers() {
        statusResponseHandler = null
        neighboursResponseHandler = null
        telemetryResponseHandler = null
    }

    /** Invokes the status response handler, if one is registered. */
    suspend fun invokeStatusHandler(status: StatusResponse) {
        statusResponseHandler?.invoke(status)
    }

    /** Invokes the neighbours response handler, if one is registered. */
    suspend fun invokeNeighboursHandler(response: NeighboursResponse) {
        neighboursResponseHandler?.invoke(response)
    }

    /** Invokes the telemetry response handler, if one is registered. */
    suspend fun invokeTelemetryHandler(response: TelemetryResponse) {
        telemetryResponseHandler?.invoke(response)
    }

    /** Invokes the CLI response handler, if one is registered. */
    suspend fun invokeCLIHandler(message: ContactMessage, contact: ContactDto) {
        cliResponseHandler?.invoke(message, contact)
    }

    // MARK: - Admin Connection

    /** Connects to a repeater as admin by creating a session and authenticating. */
    suspend fun connectAsAdmin(
        radioID: UUID,
        contact: ContactDto,
        password: String?,
        rememberPassword: Boolean = true,
        pathLength: UByte = 0u,
        onTimeoutKnown: (suspend (Int) -> Unit)? = null,
    ): RemoteNodeSessionDto {
        val remoteSession = remoteNodeService.createSession(radioID, contact)

        remoteNodeService.login(remoteSession.id, password, pathLength, onTimeoutKnown)

        // Store the password only after a successful login.
        if (password != null && rememberPassword) remoteNodeService.storePassword(password, contact.publicKey)

        return sessionStore.fetchSession(remoteSession.id) ?: throw RemoteNodeError.SessionNotFound
    }

    /** Disconnects from a repeater by sending logout and removing the session. */
    suspend fun disconnect(sessionID: UUID, publicKey: ByteArray) {
        remoteNodeService.logout(sessionID)
        remoteNodeService.removeSession(sessionID, publicKey)
    }

    // MARK: - Neighbours (repeater-specific)

    /** Requests the neighbours list from a repeater. */
    suspend fun requestNeighbors(
        sessionID: UUID,
        count: UByte = 20u,
        offset: UShort = 0u,
        orderBy: NeighborSortOrder = NeighborSortOrder.NEWEST_FIRST,
        pubkeyPrefixLength: UByte = DEFAULT_PUBKEY_PREFIX_LENGTH,
        timeoutMs: Long? = null,
    ): NeighboursResponse {
        val remoteSession = sessionStore.fetchSession(sessionID)?.takeIf { it.isRepeater }
            ?: throw RemoteNodeError.SessionNotFound

        return try {
            withTimeout(timeoutMs ?: RemoteOperationTimeoutPolicy.BINARY_MAXIMUM_MS) {
                session.requestNeighbours(remoteSession.publicKey, count, offset, orderBy.rawValue, pubkeyPrefixLength)
            }
        } catch (error: TimeoutCancellationException) {
            throw RemoteNodeError.Timeout
        } catch (error: MeshCoreError) {
            if (error is MeshCoreError.Timeout) throw RemoteNodeError.Timeout
            throw RemoteNodeError.SessionError(error)
        }
    }

    /**
     * Fetches all neighbours with automatic pagination. Paginates over the per-page request so
     * each round-trip keeps its own timeout ceiling — a single node response is capped to one
     * radio frame regardless.
     */
    suspend fun fetchAllNeighbors(
        sessionID: UUID,
        orderBy: NeighborSortOrder = NeighborSortOrder.NEWEST_FIRST,
        pubkeyPrefixLength: UByte = DEFAULT_PUBKEY_PREFIX_LENGTH,
        timeoutMs: Long? = null,
    ): NeighboursResponse = NeighboursResponse.collectingAllPages { offset ->
        if (offset > 0.toUShort()) delay(NeighboursResponse.INTER_PAGE_DELAY)
        requestNeighbors(sessionID, NEIGHBOR_PAGE_SIZE, offset, orderBy, pubkeyPrefixLength, timeoutMs)
    }

    // MARK: - Status / Telemetry / Owner Info (delegated to RemoteNodeService)

    suspend fun requestStatus(sessionID: UUID, timeoutMs: Long? = null): StatusResponse = remoteNodeService.requestStatus(sessionID, timeoutMs)

    suspend fun requestTelemetry(sessionID: UUID, timeoutMs: Long? = null): TelemetryResponse = remoteNodeService.requestTelemetry(sessionID, timeoutMs)

    suspend fun requestOwnerInfo(sessionID: UUID, timeoutMs: Long? = null): OwnerInfoResponse = remoteNodeService.requestOwnerInfo(sessionID, timeoutMs)

    // MARK: - CLI Commands (delegated to RemoteNodeService)

    /** Sends a CLI command to a repeater and waits for its response (admin only). */
    suspend fun sendCommand(sessionID: UUID, command: String, timeoutMs: Long = 10_000L): String =
        remoteNodeService.sendCLICommand(sessionID, command, timeoutMs)

    /** Sends a raw CLI command; the next reply is delivered verbatim (admin only). */
    suspend fun sendRawCommand(sessionID: UUID, command: String, timeoutMs: Long = 10_000L): String =
        remoteNodeService.sendRawCLICommand(sessionID, command, timeoutMs)

    // MARK: - Session Queries

    /** Fetches all repeater sessions for a device. */
    suspend fun fetchRepeaterSessions(radioID: UUID): List<RemoteNodeSessionDto> =
        sessionStore.fetchSessions(radioID).filter { it.isRepeater }

    /** Checks if a contact is a known repeater with an active session. */
    suspend fun getConnectedSession(publicKeyPrefix: ByteArray): RemoteNodeSessionDto? {
        val remoteSession = sessionStore.fetchSessionByPrefix(publicKeyPrefix) ?: return null
        return remoteSession.takeIf { it.isRepeater && it.isConnected }
    }

    companion object {
        /** Default pubkey-prefix length for neighbour queries. */
        const val DEFAULT_PUBKEY_PREFIX_LENGTH: UByte = 6u

        /** Per-request neighbour count used while paginating — a node caps each response to one radio frame regardless. */
        private const val NEIGHBOR_PAGE_SIZE: UByte = 255u
    }
}
