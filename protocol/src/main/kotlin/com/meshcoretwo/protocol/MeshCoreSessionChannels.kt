// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// MARK: - Channel Commands

/** Retrieves configuration for a channel (index 0-255). */
internal suspend fun MeshCoreSession.getChannelImpl(index: UByte): ChannelInfo =
    sendAndWait(PacketBuilder.getChannel(index)) { event ->
        val info = (event as? MeshEvent.ChannelInfoEvent)?.info
        if (info != null && info.index == index) info else null
    }

/**
 * Reads multiple channels in a single bounded-window pipeline of unacknowledged requests, so the
 * per-request stall (BLE slave latency, or the WiFi per-round-trip TCP delay) is amortized across
 * the window instead of paid once per index.
 *
 * @return `received` are the channels that answered (sorted by index); `missing` are the
 *   requested indexes that went unanswered — a dropped BLE Write Command, or a TCP send the
 *   radio never replied to. The caller reconciles `missing` with serial acknowledged reads; an
 *   unanswered request is data, not a fatal error.
 * @throws MeshCoreError only on a hard send failure (e.g. disconnect mid-send); an idle stall
 *   returns the partial set rather than throwing.
 *
 * Falls back to serial [getChannel] reads when the transport does not support pipelined reads.
 */
internal suspend fun MeshCoreSession.getChannelsImpl(indices: List<UByte>): ChannelsFetchResult {
    if (indices.isEmpty()) return ChannelsFetchResult(received = emptyList(), missing = emptyList())

    if (!transport.supportsPipelinedReads()) {
        val received = indices.map { getChannelImpl(it) }
        return ChannelsFetchResult(received = received, missing = emptyList())
    }

    // One serializer slot for the whole exchange: late/orphaned channel frames are drained
    // under the held slot instead of leaking to the next command, even if the caller is
    // cancelled mid-drain.
    return requestResponseSerializer.withSerialization { runChannelReadPipeline(indices) }
}

private suspend fun MeshCoreSession.runChannelReadPipeline(indices: List<UByte>): ChannelsFetchResult {
    val requested = indices
    val requestedSet = indices.toHashSet()
    val window = maxOf(1, configuration.channelPipelineWindow)
    val idleTimeoutMs = (configuration.channelPipelineIdleTimeout * 1000).toLong()
    val hardTimeoutMs = (configuration.channelPipelineHardTimeout * 1000).toLong()
    val graceTimeoutMs = (configuration.channelPipelinePostDrainGrace * 1000).toLong()

    val (subscriptionId, events) = dispatcher.subscribeTracked()

    try {
        // Prime the window before draining so the peripheral's send queue stays non-empty and
        // it does not re-enter slave latency between responses.
        //
        // The nRF52 Nordic UART Service does not preserve ATT-write framing: its receive path
        // reads every byte currently available into one firmware frame, so adjacent Write
        // Commands delivered in the same connection event are coalesced and only the first
        // index in the blob is answered. One write is therefore not a guaranteed response —
        // coalesced-away indexes surface in `missing` and the caller reconciles them with
        // acknowledged reads. That reconcile path is load-bearing, not just a disconnect
        // fallback.
        val primeCount = minOf(window, requested.size)
        for (index in requested.take(primeCount)) {
            transport.sendWithoutResponse(PacketBuilder.getChannel(index))
        }

        val progressTracker = StreamProgressTracker()

        val received = coroutineScope {
            // Consumer: records matching responses, refills the window, and returns as soon as
            // every requested index is in (so completion is not delayed by the idle sleep) or
            // when the watchdog force-closes the subscription.
            val consumer = async {
                val channel = events.produceIn(this)
                try {
                    val collected = mutableMapOf<UByte, ChannelInfo>()
                    var nextToSend = primeCount
                    for (event in channel) {
                        val info = (event as? MeshEvent.ChannelInfoEvent)?.info ?: continue
                        if (!requestedSet.contains(info.index) || collected.containsKey(info.index)) continue
                        progressTracker.markProgress()
                        collected[info.index] = info
                        if (nextToSend < requested.size) {
                            val nextIndex = requested[nextToSend]
                            nextToSend++
                            // A refill failure (disconnect mid-drain) just stops sending; the
                            // watchdog's idle timeout then returns the partial set.
                            try {
                                transport.sendWithoutResponse(PacketBuilder.getChannel(nextIndex))
                            } catch (error: Throwable) {
                                // Ignored; see comment above.
                            }
                        }
                        if (collected.size == requested.size) break
                    }
                    collected
                } finally {
                    channel.cancel()
                }
            }

            // Watchdog: force-closes the subscription on an inactivity gap or the hard cap, which
            // makes the consumer's loop end on its own and return its (possibly partial) set —
            // there is no separate "watchdog wins" outcome to race against.
            val watchdog = launch {
                while (isActive) {
                    val before = progressTracker.snapshot()
                    if (before.elapsed.toMillis() >= hardTimeoutMs) {
                        dispatcher.finishSubscription(subscriptionId)
                        return@launch
                    }
                    val remainingHard = maxOf(1L, hardTimeoutMs - before.elapsed.toMillis())
                    delay(minOf(idleTimeoutMs, remainingHard))
                    if (!isActive) return@launch
                    val after = progressTracker.snapshot()
                    if (after.elapsed.toMillis() >= hardTimeoutMs || after.generation == before.generation) {
                        dispatcher.finishSubscription(subscriptionId)
                        return@launch
                    }
                }
            }

            val result = consumer.await()
            watchdog.cancel()
            result
        }

        val missing = requested.filter { !received.containsKey(it) }
        if (missing.isEmpty()) {
            // Every index answered. Hold the slot for the grace window with the subscription
            // still open so duplicate/straggler frames are absorbed here instead of leaking
            // into the next command.
            delay(graceTimeoutMs)
        }

        val receivedSorted = received.keys.sorted().mapNotNull { received[it] }
        return ChannelsFetchResult(received = receivedSorted, missing = missing)
    } finally {
        dispatcher.finishSubscription(subscriptionId)
    }
}

/** Configures a channel with an explicit 16-byte channel secret. */
internal suspend fun MeshCoreSession.setChannelImpl(index: UByte, name: String, secret: ByteArray) {
    sendSimpleCommand(PacketBuilder.setChannel(index, name, secret))
}

/** Configures a channel, deriving the secret from [secret]'s strategy (defaults to deriving from the name). */
suspend fun MeshCoreSession.setChannel(index: UByte, name: String, secret: ChannelSecret = ChannelSecret.DeriveFromName) {
    setChannelImpl(index, name, secret.secretData(name))
}
