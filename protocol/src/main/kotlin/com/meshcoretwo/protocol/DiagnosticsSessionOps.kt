// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.time.Instant

/**
 * Session operations for binary-protocol diagnostics against remote nodes: status, telemetry,
 * neighbours, ACL, MMA, traces, and path discovery.
 */
interface DiagnosticsSessionOps {
    /**
     * Requests status information from a remote node using the repeater status layout.
     *
     * @param publicKey The full 32-byte public key of the remote node.
     * @return A status response containing battery, uptime, and other metrics.
     * @throws MeshCoreError on timeout, device error, or unexpected response.
     */
    suspend fun requestStatus(publicKey: ByteArray): StatusResponse

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
    suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: UByte = 255u,
        offset: UShort = 0u,
        orderBy: UByte = 0u,
        pubkeyPrefixLength: UByte = 4u,
    ): NeighboursResponse

    /**
     * Fetches all neighbors from a remote node with automatic pagination.
     *
     * @param publicKey The full 32-byte public key of the remote node.
     * @param orderBy Sort order (0 = by RSSI).
     * @param pubkeyPrefixLength Length of public key prefix to include.
     * @throws MeshCoreError on timeout or invalid response.
     */
    suspend fun fetchAllNeighbours(
        publicKey: ByteArray,
        orderBy: UByte = 0u,
        pubkeyPrefixLength: UByte = 4u,
    ): NeighboursResponse

    /**
     * Requests Min-Max-Average (MMA) data for a time range.
     *
     * @param publicKey The full 32-byte public key of the remote node.
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun requestMMA(publicKey: ByteArray, start: Instant, end: Instant): MMAResponse

    /**
     * Requests the Access Control List (ACL) from a remote node.
     *
     * @param publicKey The full 32-byte public key of the remote node.
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun requestACL(publicKey: ByteArray): ACLResponse

    /**
     * Retrieves telemetry data from the local device.
     *
     * @throws MeshCoreError if the device doesn't respond.
     */
    suspend fun getSelfTelemetry(): TelemetryResponse

    /**
     * Initiates path discovery to a remote node.
     *
     * @param destination The node's public key (6+ bytes).
     * @return Information about the sent message, including the expected ACK code.
     * @throws MeshCoreError if the device doesn't respond.
     */
    suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo

    /**
     * Sends a trace packet through the mesh network.
     *
     * @param tag Optional trace identifier. Random value generated if `null`.
     * @param authCode Optional authentication code. Random value generated if `null`.
     * @param flags Trace flags controlling behavior.
     * @param path Initial path to follow; firmware requires at least one path byte.
     * @return Information about the sent message, including tag and auth code.
     * @throws MeshCoreError on invalid input or timeout.
     */
    suspend fun sendTrace(tag: UInt? = null, authCode: UInt? = null, flags: UByte = 0u, path: ByteArray? = null): MessageSentInfo
}
