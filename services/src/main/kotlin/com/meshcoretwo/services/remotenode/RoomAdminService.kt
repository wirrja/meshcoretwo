// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import com.meshcoretwo.protocol.ContactMessage
import com.meshcoretwo.protocol.StatusResponse
import com.meshcoretwo.protocol.TelemetryResponse
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.persistence.RemoteNodeSessionStore
import java.util.UUID

/**
 * Service for room server admin interactions: viewing status/telemetry and sending CLI commands.
 * Room authentication is handled by [RoomServerService.joinRoom]. Ported from
 * `RoomAdminService.swift`.
 *
 * Deferred: `CommandAuditLogger` (pure logging, dropped everywhere in this port — see
 * `ChannelService`'s class doc).
 */
class RoomAdminService(
    private val remoteNodeService: RemoteNodeService,
    private val sessionStore: RemoteNodeSessionStore,
) {
    private var statusResponseHandler: (suspend (StatusResponse) -> Unit)? = null
    private var telemetryResponseHandler: (suspend (TelemetryResponse) -> Unit)? = null
    private var cliResponseHandler: (suspend (ContactMessage, ContactDto) -> Unit)? = null

    fun setStatusHandler(handler: suspend (StatusResponse) -> Unit) {
        statusResponseHandler = handler
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
        telemetryResponseHandler = null
    }

    /** Invokes the status response handler, if one is registered. */
    suspend fun invokeStatusHandler(status: StatusResponse) {
        statusResponseHandler?.invoke(status)
    }

    /** Invokes the telemetry response handler, if one is registered. */
    suspend fun invokeTelemetryHandler(response: TelemetryResponse) {
        telemetryResponseHandler?.invoke(response)
    }

    /** Invokes the CLI response handler, if one is registered. */
    suspend fun invokeCLIHandler(message: ContactMessage, contact: ContactDto) {
        cliResponseHandler?.invoke(message, contact)
    }

    // MARK: - Status / Telemetry (delegated to RemoteNodeService)

    suspend fun requestStatus(sessionID: UUID, timeoutMs: Long? = null): StatusResponse = remoteNodeService.requestStatus(sessionID, timeoutMs)

    suspend fun requestTelemetry(sessionID: UUID, timeoutMs: Long? = null): TelemetryResponse = remoteNodeService.requestTelemetry(sessionID, timeoutMs)

    // MARK: - CLI Commands (delegated to RemoteNodeService)

    /** Sends a CLI command to a room server and waits for its response (admin only). */
    suspend fun sendCommand(sessionID: UUID, command: String, timeoutMs: Long = 10_000L): String =
        remoteNodeService.sendCLICommand(sessionID, command, timeoutMs)

    /** Sends a raw CLI command; the next reply is delivered verbatim (admin only). */
    suspend fun sendRawCommand(sessionID: UUID, command: String, timeoutMs: Long = 10_000L): String =
        remoteNodeService.sendRawCLICommand(sessionID, command, timeoutMs)

    // MARK: - Session Queries

    /** Fetches all room admin sessions for a device. */
    suspend fun fetchRoomAdminSessions(radioID: UUID): List<RemoteNodeSessionDto> =
        sessionStore.fetchSessions(radioID).filter { it.isRoom }

    /** Checks if a contact is a known room with an active session. */
    suspend fun getConnectedSession(publicKeyPrefix: ByteArray): RemoteNodeSessionDto? {
        val remoteSession = sessionStore.fetchSessionByPrefix(publicKeyPrefix) ?: return null
        return remoteSession.takeIf { it.isRoom && it.isConnected }
    }
}
