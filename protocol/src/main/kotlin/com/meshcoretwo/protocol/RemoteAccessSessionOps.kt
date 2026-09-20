// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.time.Instant

/**
 * Session operations for authenticated access to remote nodes (room servers and repeaters):
 * login, commands, keep-alive, and remote queries.
 */
interface RemoteAccessSessionOps {
    /**
     * Sends a login request to a remote node.
     *
     * @param destination The node's public key (6+ bytes).
     * @param password The authentication password.
     * @return Information about the sent message, including the expected ACK code.
     * @throws MeshCoreError if the device doesn't respond.
     */
    suspend fun sendLogin(destination: ByteArray, password: String): MessageSentInfo

    /**
     * Sends a logout request to a remote node.
     *
     * @param destination The node's public key (6+ bytes).
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun sendLogout(destination: ByteArray)

    /**
     * Sends a command message to a remote node.
     *
     * @param destination The destination public key (6+ bytes, uses first 6 as prefix).
     * @param command The command string to send.
     * @param timestamp Message timestamp. Defaults to the current time.
     * @return Information about the sent message, including the expected ACK code.
     * @throws MeshCoreError if the device doesn't respond.
     */
    suspend fun sendCommand(destination: ByteArray, command: String, timestamp: Instant = Instant.now()): MessageSentInfo

    /**
     * Sends a keep-alive request to a room server with the client's sync watermark.
     *
     * @param publicKey The full 32-byte public key of the room server.
     * @param syncSince The client's last-received message timestamp.
     * @throws MeshCoreError if the device doesn't respond.
     */
    suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: UInt): MessageSentInfo

    /**
     * Requests owner information from a repeater using the binary protocol.
     *
     * @param publicKey The full 32-byte public key of the repeater.
     * @return An [OwnerInfoResponse] containing firmware version, node name, and owner info.
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse

    /**
     * Requests status information from a remote node.
     *
     * @param publicKey The full 32-byte public key of the remote node.
     * @param type The target node type used to choose the correct firmware status layout.
     * @return A status response containing battery, uptime, and other metrics.
     * @throws MeshCoreError on timeout, device error, or unexpected response.
     */
    suspend fun requestStatus(publicKey: ByteArray, type: ContactType): StatusResponse

    /**
     * Requests telemetry data from a remote node using the binary protocol.
     *
     * @param publicKey The full 32-byte public key of the remote node.
     * @return Telemetry response containing sensor data and device status.
     * @throws MeshCoreError on timeout, device error, or unexpected response.
     */
    suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse

    /**
     * Requests the neighbor list from a remote node.
     *
     * @param publicKey The full 32-byte public key of the remote node.
     * @param count Maximum number of neighbors to return.
     * @param offset Starting offset for pagination.
     * @param orderBy Sort order (0 = by RSSI).
     * @param pubkeyPrefixLength Length of public key prefix to include.
     * @throws MeshCoreError on timeout or device error.
     */
    // No defaults here (unlike DiagnosticsSessionOps' declaration of the same method):
    // Kotlin warns (future error) when two supertypes both default the same override, since
    // it can't guarantee they agree. DiagnosticsSessionOps is this method's conceptual home.
    suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: UByte,
        offset: UShort,
        orderBy: UByte,
        pubkeyPrefixLength: UByte,
    ): NeighboursResponse

    /**
     * Returns one message at a time from the device's message queue. Call repeatedly until
     * [MessageResult.NoMoreMessages] is returned to drain the queue.
     *
     * @param timeout Optional timeout override in seconds. Uses the session's default timeout when `null`.
     * @throws MeshCoreError if the fetch fails.
     */
    // No default here (unlike MessageFetchSessionOps' declaration of the same method): Kotlin
    // warns (future error) when two supertypes both default the same override, since it can't
    // guarantee they agree. MessageFetchSessionOps is this method's conceptual home.
    suspend fun getMessage(timeout: Double?): MessageResult

    /**
     * Sends a message with automatic retry logic and optional path reset.
     *
     * @param destination The full 32-byte public key of the recipient.
     * @param text The message text to send.
     * @param timestamp The message timestamp.
     * @param maxAttempts The maximum number of total attempts to make.
     * @param floodAfter The number of failed attempts after which to reset the path to flood.
     * @param maxFloodAttempts The maximum number of attempts to make while in flood mode.
     * @param timeout The acknowledgment timeout per attempt, in seconds. If `null`, uses the
     *   suggested timeout provided by the device.
     * @return Information about the sent message if an acknowledgment was received, otherwise
     *   `null` if all attempts failed.
     * @throws MeshCoreError.InvalidInput if the destination key is not 32 bytes.
     */
    suspend fun sendMessageWithRetry(
        destination: ByteArray,
        text: String,
        timestamp: Instant = Instant.now(),
        maxAttempts: Int = 3,
        floodAfter: Int = 2,
        maxFloodAttempts: Int = 2,
        timeout: Double? = null,
    ): MessageSentInfo?

    /**
     * Initiates path discovery to a remote node.
     *
     * @param destination The node's public key (6+ bytes).
     * @return Information about the sent message, including the expected ACK code.
     * @throws MeshCoreError if the device doesn't respond.
     */
    suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo
}
