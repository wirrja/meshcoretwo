// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.flow.Flow

/**
 * Session operations for observing connection state and subscribing to device events.
 *
 * Swift declares this (and the other `*SessionOps` protocols) as `: Actor` so conformers are
 * actor-isolated; Kotlin has no equivalent protocol constraint, so it is dropped here — the
 * concrete [MeshCoreSession] is responsible for its own thread safety.
 *
 * Where Swift needed a separate `public extension` overload to give a protocol method a default
 * argument (Swift protocol requirements cannot carry default values), Kotlin interface methods
 * support default parameter values directly, so [waitForEvent] collapses both Swift overloads
 * into one declaration. This simplification is applied consistently across every `*SessionOps`
 * interface in this file group.
 */
interface SessionEventStreaming {
    /** Provides an observable connection state stream for UI binding. */
    val connectionState: Flow<ConnectionState>

    /**
     * Subscribes to all events from the device.
     *
     * Each subscriber receives all events independently.
     */
    suspend fun events(): Flow<MeshEvent>

    /**
     * Subscribes to events passing the given filter.
     *
     * Prefer this over [events] when the consumer only cares about a narrow slice of events.
     */
    suspend fun events(filter: EventFilter): Flow<MeshEvent>

    /**
     * Waits for an event matching an [EventFilter] with timeout.
     *
     * @param timeout Maximum time to wait in seconds. Uses the session's default timeout when `null`.
     * @return The matching event, or `null` if the timeout expired.
     */
    suspend fun waitForEvent(filter: EventFilter, timeout: Double? = null): MeshEvent?
}
