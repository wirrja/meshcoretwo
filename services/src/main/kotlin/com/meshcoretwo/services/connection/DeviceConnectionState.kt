// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

/**
 * App-facing connection state for the mesh device. Ported from `DeviceConnectionState.swift`.
 *
 * Adds the post-link `SYNCING`/`READY` distinction the transport layer has no concept of.
 * Distinct from the transport-link state [com.meshcoretwo.protocol]'s session publishes; the
 * not-yet-ported `ConnectionManager` translates transport events into the rung modeled here.
 *
 * `ChatSendQueueService.observeConnectionState` is the first consumer. `ConnectionIntent`,
 * `TransportType`, `DisconnectReason`, `DevicePlatform`, and `LastConnectionStore` are now also
 * ported (self-contained connection-infrastructure types, no BLE/`ConnectionManager` dependency)
 * — see their own class docs. Driving this enum's transitions from a real transport is still the
 * `ConnectionManager` vertical slice's job, not yet started.
 */
enum class DeviceConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    SYNCING,
    READY,
    ;

    /**
     * True when session and services are available and the transport is alive. Used by internal
     * infrastructure (resync loop, health checks, heartbeat). UI code should check [READY] to gate
     * user interactions.
     */
    val isOperational: Boolean get() = this == SYNCING || this == READY

    /** True when a transport link is established (session may or may not be synced). */
    val isConnected: Boolean get() = this == CONNECTED || this == SYNCING || this == READY

    /**
     * True only once initial sync has cleared, so the chat send queue may drain without
     * contending with sync's contact/channel/message reads on the radio's link. [CONNECTED] (link
     * up, sync not yet run) deliberately excludes the queue from the vulnerable window.
     */
    val canDrainSendQueue: Boolean get() = this == READY
}
