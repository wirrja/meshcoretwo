// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Port of EventDispatcherDropTests.swift and EventDispatcherFilteredSubscriptionTests.swift.
 *
 * The Swift originals drive these through `MeshCoreSession`, which isn't ported (Session
 * layer); exercised directly against [EventDispatcher] instead, which is the actual unit under
 * test in both cases.
 */
class EventDispatcherTest {
    @Test
    fun `droppedEventCount increments when a slow consumer overflows the buffer`() = runTest {
        val dispatcher = EventDispatcher()
        dispatcher.subscribeTracked(null)

        // Don't drain the flow — we want the buffer to fill.
        for (i in 0 until 200) {
            dispatcher.dispatch(MeshEvent.Advertisement(byteArrayOf((i % 256).toByte())))
        }

        // 100-slot buffer -> 100 drops expected.
        assertTrue(dispatcher.droppedEventCount >= 100)
    }

    @Test
    fun `filtered subscription receives only matching events`() = runTest {
        val dispatcher = EventDispatcher()
        val filter = EventFilter.anyAcknowledgement
        val flow = dispatcher.subscribe(filter::matches)

        dispatcher.dispatch(MeshEvent.Advertisement(byteArrayOf(0x01)))
        dispatcher.dispatch(MeshEvent.Acknowledgement(byteArrayOf(0x10, 0x20, 0x30, 0x40), 500u))
        dispatcher.dispatch(MeshEvent.Advertisement(byteArrayOf(0x02)))

        val first = flow.first()
        val ack = first as? MeshEvent.Acknowledgement ?: run {
            fail("Expected Acknowledgement, got $first")
            return@runTest
        }
        assertArrayEquals(byteArrayOf(0x10, 0x20, 0x30, 0x40), ack.code)
        assertEquals(500u, ack.tripTime)
    }

    @Test
    fun `filtered subscription survives flood of non-matching events`() = runTest {
        val dispatcher = EventDispatcher()
        val filter = EventFilter.anyAcknowledgement
        val flow = dispatcher.subscribe(filter::matches)

        // 500 non-matching events is 5x the 100-slot buffer cap — enough to overflow an
        // unfiltered subscription.
        for (i in 0 until 500) {
            dispatcher.dispatch(MeshEvent.Advertisement(byteArrayOf((i % 256).toByte())))
        }

        val expectedCode = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x12)
        dispatcher.dispatch(MeshEvent.Acknowledgement(expectedCode, 1234u))

        val event = flow.first()
        val ack = event as? MeshEvent.Acknowledgement ?: run {
            fail("filter not applied — flow yielded a non-ACK event first. Got: $event")
            return@runTest
        }
        assertArrayEquals(expectedCode, ack.code)
        assertEquals(1234u, ack.tripTime)
    }

    // MARK: - Sanity checks for lifecycle management (no direct Swift unit test file; exercised
    // indirectly through MeshCoreSession there, which isn't ported)

    @Test
    fun `subscriberCountForTest tracks active subscriptions`() {
        val dispatcher = EventDispatcher()
        assertEquals(0, dispatcher.subscriberCountForTest)

        val (id, _) = dispatcher.subscribeTracked()
        assertEquals(1, dispatcher.subscriberCountForTest)

        dispatcher.finishSubscription(id)
        assertEquals(0, dispatcher.subscriberCountForTest)
    }

    @Test
    fun `finishAllSubscriptions completes every active flow`() = runTest {
        val dispatcher = EventDispatcher()
        val (_, flowA) = dispatcher.subscribeTracked()
        val (_, flowB) = dispatcher.subscribeTracked()
        assertEquals(2, dispatcher.subscriberCountForTest)

        dispatcher.dispatch(MeshEvent.Ok(null))
        dispatcher.finishAllSubscriptions()

        // Both flows should complete (channel closed) after yielding whatever was buffered.
        assertEquals(listOf(MeshEvent.Ok(null)), flowA.toList())
        assertEquals(listOf(MeshEvent.Ok(null)), flowB.toList())
        assertEquals(0, dispatcher.subscriberCountForTest)
    }

    @Test
    fun `unfiltered subscribe forwards every dispatched event`() = runTest {
        val dispatcher = EventDispatcher()
        val flow = dispatcher.subscribe()

        dispatcher.dispatch(MeshEvent.Ok(1u))
        dispatcher.dispatch(MeshEvent.NoMoreMessages)
        dispatcher.finishAllSubscriptions()

        assertEquals(listOf(MeshEvent.Ok(1u), MeshEvent.NoMoreMessages), flow.toList())
    }
}
