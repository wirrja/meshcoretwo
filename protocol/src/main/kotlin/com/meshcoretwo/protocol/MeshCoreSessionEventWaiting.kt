// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

/** Wraps a value that may itself be `null`, distinguishing "a value" from "no value" in [sendAndMatch]. */
private class Box<T>(val value: T)

/** Command/response matching outcome for [sendAndMatch]. */
internal sealed class ResponseDisposition<out T> {
    data class Success<T>(val value: T) : ResponseDisposition<T>()
    data class Failure(val error: MeshCoreError) : ResponseDisposition<Nothing>()
    object Ignore : ResponseDisposition<Nothing>()
}

/** Standard error matcher that converts `.error` events into [MeshCoreError.DeviceError]. */
internal val deviceErrorMatcher: (MeshEvent) -> MeshCoreError? = { event ->
    if (event is MeshEvent.Error) MeshCoreError.DeviceError(event.code ?: 0u) else null
}

/** A random 32-bit value excluding zero, matching Swift's `UInt32.random(in: 1...UInt32.max)`. */
internal fun randomNonZeroUInt(): UInt {
    var candidate: UInt
    do {
        candidate = kotlin.random.Random.nextInt().toUInt()
    } while (candidate == 0u)
    return candidate
}

/**
 * Waits for a specific event type with optional filtering.
 *
 * Prefer [MeshCoreSession.waitForEvent] (the [EventFilter] overload) for command/response
 * patterns to avoid race conditions.
 *
 * @param timeout Maximum time to wait in seconds. Uses `configuration.defaultTimeout` if `null`.
 * @return The matching event, or `null` if timeout occurred.
 */
suspend fun MeshCoreSession.waitForEvent(predicate: (MeshEvent) -> Boolean, timeout: Double? = null): MeshEvent? {
    val effectiveTimeout = timeout ?: configuration.defaultTimeout
    val (subscriptionId, events) = dispatcher.subscribeTracked()
    return try {
        withTimeoutOrNull((effectiveTimeout * 1000).toLong()) { events.firstOrNull(predicate) }
    } finally {
        dispatcher.finishSubscription(subscriptionId)
    }
}

/**
 * Sends a command and waits for a matching response.
 *
 * Subscribes to events before sending the command to avoid race conditions. Events that do not
 * satisfy [matching] are ignored until a matching response arrives or the timeout expires.
 *
 * @throws MeshCoreError.Timeout if no matching event is received within the timeout.
 */
internal suspend fun <T> MeshCoreSession.sendAndWait(
    data: ByteArray,
    timeout: Double? = null,
    matching: (MeshEvent) -> T?,
): T = sendAndMatch(data, timeout) { event ->
    matching(event)?.let { ResponseDisposition.Success(it) } ?: ResponseDisposition.Ignore
}

/**
 * Sends a command and waits for either a success response or error.
 *
 * @param errorMatcher Optional matcher for request-specific error events. Errors that do not
 *   match are ignored so unrelated commands cannot fail the active request.
 * @throws MeshCoreError A matched error from [errorMatcher], or [MeshCoreError.Timeout].
 */
internal suspend fun <T> MeshCoreSession.sendAndWaitWithError(
    data: ByteArray,
    matching: (MeshEvent) -> T?,
    errorMatcher: ((MeshEvent) -> MeshCoreError?)? = null,
    timeout: Double? = null,
): T = sendAndMatch(data, timeout) { event ->
    val error = errorMatcher?.invoke(event)
    if (error != null) {
        ResponseDisposition.Failure(error)
    } else {
        matching(event)?.let { ResponseDisposition.Success(it) } ?: ResponseDisposition.Ignore
    }
}

/**
 * Sends [data] and resolves the first event for which [matcher] returns a disposition other than
 * [ResponseDisposition.Ignore], serialized against every other exchange via
 * [MeshCoreSession.requestResponseSerializer].
 */
internal suspend fun <T> MeshCoreSession.sendAndMatch(
    data: ByteArray,
    timeout: Double? = null,
    matcher: (MeshEvent) -> ResponseDisposition<T>,
): T = requestResponseSerializer.withSerialization {
    val effectiveTimeout = timeout ?: configuration.defaultTimeout

    // Subscribe before sending to avoid a race condition, then ignore all non-matching
    // events until this request sees its own response.
    val (subscriptionId, events) = dispatcher.subscribeTracked()
    try {
        transport.send(data)

        // Boxed so a legitimate Success(null) — e.g. getContact's "not found" or
        // getDefaultFloodScope's "not configured" — survives mapNotNull instead of being
        // conflated with Ignore and silently discarded (which previously made those calls
        // hang until MeshCoreError.Timeout instead of returning null promptly).
        val box = withTimeoutOrNull((effectiveTimeout * 1000).toLong()) {
            events.mapNotNull { event ->
                when (val disposition = matcher(event)) {
                    is ResponseDisposition.Success -> Box(disposition.value)
                    is ResponseDisposition.Failure -> throw disposition.error
                    ResponseDisposition.Ignore -> null
                }
            }.firstOrNull()
        }
        if (box == null) throw MeshCoreError.Timeout
        box.value
    } finally {
        dispatcher.finishSubscription(subscriptionId)
    }
}

// MARK: - Command Helpers

/** Sends a command and waits for an "OK" response from the device. */
internal suspend fun MeshCoreSession.sendSimpleCommand(data: ByteArray) {
    sendAndWaitWithError<Boolean>(
        data,
        matching = { event -> if (event is MeshEvent.Ok && event.value == null) true else null },
        errorMatcher = deviceErrorMatcher,
    )
}

internal fun requireFullPublicKey(publicKey: ByteArray, operation: String) {
    if (publicKey.size != PacketBuilder.PUBLIC_KEY_SIZE) {
        throw MeshCoreError.InvalidInput("Full ${PacketBuilder.PUBLIC_KEY_SIZE}-byte public key required for $operation")
    }
}
