// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dispatches [MeshEvent]s to subscribers via [Flow].
 *
 * Manages event subscriptions and dispatches events to all active subscribers. Supports both
 * unfiltered and filtered subscriptions.
 *
 * Swift's `EventDispatcher` is an `actor`, but none of its methods actually suspend internally
 * (registration is a synchronous dictionary write) — so unlike [MockTransport], this is a plain
 * thread-safe class ([ConcurrentHashMap] + atomics) rather than a [kotlinx.coroutines.sync.Mutex]
 * wrapper, with no `suspend` modifiers to match. Subscription must stay synchronous either way:
 * a test ([finishSubscription] callers, [subscribeTracked]'s id) races a `dispatch()` against a
 * listener's `subscribe()` if registration were deferred to first collection.
 */
class EventDispatcher {
    private class Subscription(
        /** The channel used to deliver events to the subscriber's flow. */
        val channel: Channel<MeshEvent>,
        /** The optional predicate used to filter events before sending. */
        val filter: ((MeshEvent) -> Boolean)?,
    ) {
        /**
         * Approximate count of events currently buffered for this subscriber, used only to
         * detect overflow for [droppedEventCount]. [Channel]'s own `DROP_OLDEST` eviction is
         * what actually bounds memory; this shadow counter just observes it.
         */
        val bufferedCount = AtomicInteger(0)
    }

    private val subscriptions = ConcurrentHashMap<UUID, Subscription>()

    /**
     * Running count of events dropped by the 100-slot buffer when a subscriber couldn't keep
     * up. Approximate under concurrent dispatch (see [Subscription.bufferedCount]); intended for
     * diagnostics and tests, not an exact audit trail.
     */
    private val droppedCount = AtomicInteger(0)

    /** Total events dropped across all subscriptions since dispatcher creation. */
    val droppedEventCount: Int
        get() = droppedCount.get()

    /**
     * Subscribes to all events.
     *
     * Uses a bounded 100-event buffer per subscriber to prevent memory issues with
     * high-throughput events: if a subscriber processes events slower than they arrive, older
     * events may be dropped. For critical event processing (e.g. debugging with
     * [MeshEvent.ParseFailure] events), ensure the collector is fast or processes events
     * asynchronously.
     */
    fun subscribe(): Flow<MeshEvent> = subscribe(filter = null)

    /**
     * Subscribes to events matching a filter predicate.
     *
     * Only events for which [filter] returns `true` are sent to the flow. If no filter is
     * provided (`null`), all events are sent.
     */
    fun subscribe(filter: ((MeshEvent) -> Boolean)?): Flow<MeshEvent> = subscribeTracked(filter).second

    /**
     * Subscribes to events and returns the flow together with an id that can be used to finish
     * the subscription explicitly via [finishSubscription].
     *
     * Explicit finishing is useful for timeout races, where a waiting caller may otherwise
     * remain suspended on the flow after moving on.
     */
    fun subscribeTracked(filter: ((MeshEvent) -> Boolean)? = null): Pair<UUID, Flow<MeshEvent>> {
        val id = UUID.randomUUID()
        val channel = Channel<MeshEvent>(capacity = BUFFER_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val subscription = Subscription(channel = channel, filter = filter)
        subscriptions[id] = subscription

        val eventFlow = flow {
            try {
                for (event in channel) {
                    subscription.bufferedCount.decrementAndGet()
                    emit(event)
                }
            } finally {
                subscriptions.remove(id)
            }
        }

        return id to eventFlow
    }

    /**
     * Dispatches an event to all subscribers, applying filters.
     *
     * Each subscription's filter (if any) is evaluated. The event is only sent to subscribers
     * whose filter returns `true` or who have no filter.
     */
    fun dispatch(event: MeshEvent) {
        for ((_, subscription) in subscriptions) {
            if (subscription.filter?.invoke(event) == false) continue

            if (subscription.bufferedCount.get() >= BUFFER_CAPACITY) {
                droppedCount.incrementAndGet()
            } else {
                subscription.bufferedCount.incrementAndGet()
            }
            subscription.channel.trySend(event)
        }
    }

    /**
     * Finishes all active subscriptions, causing their flows to complete.
     *
     * Call this during session teardown so that any collectors of event flows exit promptly
     * instead of hanging until garbage collection.
     */
    fun finishAllSubscriptions() {
        val ids = subscriptions.keys.toList()
        for (id in ids) {
            subscriptions.remove(id)?.channel?.close()
        }
    }

    /**
     * Finishes and removes a specific subscription.
     *
     * Safe to call multiple times; unknown ids are ignored.
     */
    fun finishSubscription(id: UUID) {
        subscriptions.remove(id)?.channel?.close()
    }

    /**
     * Count of active subscriptions. For tests only.
     *
     * Used by integration tests to synchronize with a listener's [subscribe] call before
     * dispatching events — without this, a dispatch can race the subscribe and silently vanish.
     */
    internal val subscriberCountForTest: Int
        get() = subscriptions.size

    private companion object {
        const val BUFFER_CAPACITY = 100
    }
}
