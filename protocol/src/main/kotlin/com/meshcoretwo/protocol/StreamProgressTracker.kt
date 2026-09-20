// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks progress of a streamed read (the contact notification stream or the windowed
 * channel-read pipeline) so a watchdog can distinguish "still streaming" from "idle" by comparing
 * the generation across an inactivity gap.
 *
 * Swift's `StreamProgressTracker` is its own `actor` (separate from [MeshCoreSession]) because
 * multiple concurrently running tasks touch it. Like [EventDispatcher], neither [markProgress]
 * nor [snapshot] does genuine suspend-worthy work, so this is a plain thread-safe class (an
 * [AtomicInteger]) rather than a [kotlinx.coroutines.sync.Mutex] wrapper.
 */
class StreamProgressTracker {
    data class Snapshot(val generation: Int, val elapsed: Duration)

    private val generationCounter = AtomicInteger(0)
    private val startedAt: Instant = Instant.now()

    fun markProgress() {
        generationCounter.incrementAndGet()
    }

    fun snapshot(): Snapshot = Snapshot(
        generation = generationCounter.get(),
        elapsed = Duration.between(startedAt, Instant.now()),
    )
}
