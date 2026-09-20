// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.flow.Flow

/**
 * Interface for communicating with a MeshCore device across various physical layers.
 *
 * `MeshTransport` is the primary extensibility point for the protocol layer, letting
 * different transport mechanisms (Bluetooth Low Energy, TCP/IP, ...) plug into a session.
 *
 * ## Built-in Implementations
 * - [MockTransport]: in-memory transport for unit testing and simulation.
 *
 * Bluetooth Low Energy is a platform concern: implement this interface over the `services`
 * module's BLE stack, where pairing and reconnection policy live.
 *
 * ## Thread Safety
 *
 * Implementations should be safe to call concurrently — guard mutable state with a
 * [kotlinx.coroutines.sync.Mutex] (or serialize calls through a single-consumer command
 * channel), the same pattern used for [MockTransport] here.
 */
interface MeshTransport {
    /**
     * Establishes a connection to the MeshCore device.
     *
     * @throws MeshTransportError if the connection cannot be established or the hardware is
     *   unavailable.
     */
    suspend fun connect()

    /**
     * Terminates the connection to the device.
     *
     * Cleans up resources, closes the physical connection, and completes the [receivedData]
     * flow. Idempotent and safe to call even if already disconnected.
     */
    suspend fun disconnect()

    /**
     * Transmits raw data to the connected MeshCore device.
     *
     * @param data The raw bytes to be sent over the transport.
     * @throws MeshTransportError.NotConnected if called while the transport is disconnected.
     * @throws MeshTransportError.SendFailed if the underlying physical layer fails to transmit.
     */
    suspend fun send(data: ByteArray)

    /**
     * Transmits raw data as an unacknowledged write (ATT Write Command).
     *
     * Unlike [send] (an acknowledged ATT Write Request, one round-trip per call), this allows
     * back-to-back writes without waiting for a per-write response, which is what makes request
     * pipelining possible. The tradeoff is that there is no link-layer guarantee of delivery, so
     * callers must reconcile any unanswered requests themselves.
     *
     * The default implementation routes to [send]; only transports whose write characteristic
     * genuinely advertises write-without-response override it.
     *
     * @throws MeshTransportError The same errors as [send].
     */
    suspend fun sendWithoutResponse(data: ByteArray) {
        send(data)
    }

    /**
     * Whether [sendWithoutResponse] is backed by a real ATT Write-Command path (the write
     * characteristic advertises write-without-response).
     *
     * Defaults to `false`, making the capability opt-in: a transport that cannot issue Write
     * Commands transparently degrades to acknowledged [send].
     */
    suspend fun supportsWriteWithoutResponse(): Boolean = false

    /**
     * Whether channel-read pipelining is viable on this transport — that is, whether it can
     * issue back-to-back requests without paying a per-request stall. On BLE this requires real
     * ATT Write Commands; on a stream socket it is inherent (sends queue in the socket buffer),
     * so a TCP transport opts in directly even though it has no write characteristic.
     *
     * Defaults to [supportsWriteWithoutResponse] so BLE transports need no override.
     */
    suspend fun supportsPipelinedReads(): Boolean = supportsWriteWithoutResponse()

    /**
     * A stream of raw data received from the device.
     *
     * Each element represents a discrete chunk of data received from the physical layer. The
     * flow completes when the transport is disconnected.
     */
    val receivedData: Flow<ByteArray>

    /**
     * Whether the transport is currently connected to a device.
     *
     * Should accurately reflect the status of the underlying physical connection.
     */
    suspend fun isConnected(): Boolean
}
