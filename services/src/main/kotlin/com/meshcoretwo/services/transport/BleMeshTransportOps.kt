// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.transport

import com.meshcoretwo.protocol.MeshTransport

/**
 * The surface [BleMeshTransport] adds on top of the protocol layer's [MeshTransport], extracted so
 * the not-yet-hardware-testable `ConnectionManager` (`services/connection`) can depend on an
 * interface instead of the concrete class — same `*Ops` seam convention as
 * [BleStateMachineOps]/`RemoteNodeSessionOps`/etc. throughout this codebase. A test double
 * implements this directly rather than wrapping a [BleStateMachineOps] fake, since
 * `ConnectionManager` never talks to the state machine itself.
 */
interface BleMeshTransportOps : MeshTransport {
    /** Sets the MAC address of the device to connect to. */
    fun setDeviceAddress(address: String)

    fun setDisconnectionHandler(handler: (String, BleError?) -> Unit)

    /**
     * Sets a handler for auto-reconnect completion. Composes with the transport's own internal
     * data-stream capture rather than replacing it — see [BleMeshTransport]'s class doc.
     */
    fun setReconnectionHandler(handler: (String) -> Unit)

    /** Disconnects from the current device (if any) and connects to [address] instead. */
    suspend fun switchDevice(address: String)

    /**
     * Replaces the stored data stream with a fresh one from the state machine's currently
     * connected phase. Call before constructing a session over an existing link whose previous
     * session was stopped: the stop cancelled the old stream's collector, and this slot is
     * otherwise rewritten only on connect/switchDevice/reconnection completion. A declined
     * renewal (not connected) leaves the slot untouched.
     */
    suspend fun refreshDataStream()
}
