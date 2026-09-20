// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Errors that can occur during WiFi transport operations.
 *
 * Ported from `WiFiTransportError` (`WiFiTransport.swift`). Kept distinct from
 * [MeshTransportError] exactly as the Swift original is — [WiFiTransport] throws this type
 * directly rather than mapping to [MeshTransportError] at the boundary (unlike
 * `com.meshcoretwo.services.transport.BleMeshTransport`'s mapping of `BleError`): Swift's
 * `WiFiTransport` does the same, and callers (`MeshCoreSession`) already tolerate a
 * non-[MeshTransportError] failure by normalizing it into [MeshTransportError.ConnectionFailed].
 */
sealed class WiFiTransportError(message: String) : Exception(message) {
    data class ConnectionFailed(val reason: String) : WiFiTransportError("Connection failed: $reason")
    object ConnectionTimeout : WiFiTransportError("Connection timed out")
    object NotConnected : WiFiTransportError("Not connected")
    data class SendFailed(val reason: String) : WiFiTransportError("Send failed: $reason")
    object SendTimeout : WiFiTransportError("Send timed out")
    object NotConfigured : WiFiTransportError("Connection not configured")
    /** Not thrown by [WiFiTransport] itself — validated by the `services`-layer caller before [setConnectionInfo]. */
    object InvalidHost : WiFiTransportError("Invalid host")
    /** Not thrown by [WiFiTransport] itself — validated by the `services`-layer caller before [setConnectionInfo]. */
    object InvalidPort : WiFiTransportError("Invalid port")
}

/**
 * TCP transport for connecting to MeshCore devices over WiFi. Ported from `WiFiTransport.swift`.
 *
 * Configure connection info before calling [connect]:
 * ```kotlin
 * val transport = WiFiTransport()
 * transport.setConnectionInfo(host = "192.168.1.50", port = 5000)
 * transport.connect()
 * ```
 *
 * Swift is an `actor`; this mirrors that isolation with a [Mutex] guarding `socket`/`connected`/
 * the configured host and port, the same pattern as [MockTransport].
 *
 * **`Socket.connect(address, timeoutMs)` replaces Swift's hand-rolled timeout race.** Swift has
 * to race `performConnection` against a `Task.sleep` in a task group because `NWConnection` has
 * no built-in connect timeout; `java.net.Socket.connect` takes one directly, so no manual race is
 * needed here. Cancellation responsiveness (so a cancelled `connect()` doesn't stay parked for the
 * full timeout) instead comes from [suspendCancellableCoroutine]'s `invokeOnCancellation` closing
 * the socket, which unblocks the blocking call on its [Dispatchers.IO] thread — the same
 * "cancellation closes the resource" idiom used for BLE connects in `services`.
 *
 * **No cancellable write timeout.** Unlike `connect`, `java.net.Socket`/`OutputStream.write` has
 * no built-in write timeout and no non-destructive way to interrupt a blocked write (closing the
 * socket would tear down the whole connection, unlike Swift's per-call `NWConnection.send`
 * cancellation). [send]'s [WRITE_TIMEOUT_MS] is therefore a soft [withTimeout]: on expiry the
 * suspended caller sees [WiFiTransportError.SendTimeout], but the underlying blocked write is not
 * forcibly interrupted — accepted since no test exercises an actually-stalled write, and a stuck
 * write almost always indicates a socket that will separately fail (and get cleaned up) via the
 * receive loop.
 */
class WiFiTransport : MeshTransport {
    private val mutex = Mutex()
    private var socket: Socket? = null
    private var connected = false
    private var configuredHost: String? = null
    private var configuredPort: Int? = null
    private val frameDecoder = WiFiFrameDecoder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiveJob: Job? = null

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)

    /** Lets [disconnect] abort a concurrently in-flight [connect] (Kotlin has no actor reentrancy to rely on). */
    @Volatile
    private var inFlightSocket: Socket? = null

    @Volatile
    private var disconnectionHandler: ((Throwable?) -> Unit)? = null

    override val receivedData: Flow<ByteArray> = channel.receiveAsFlow()

    override suspend fun isConnected(): Boolean = mutex.withLock { connected }

    /**
     * TCP streams pipeline natively: back-to-back sends queue in the socket buffer and the radio
     * drains them at its loop cadence, so windowed channel reads avoid the per-round-trip stall
     * that serial reads pay over WiFi. There is no ATT write characteristic, so
     * [supportsWriteWithoutResponse] stays `false` and [sendWithoutResponse] routes to [send].
     */
    override suspend fun supportsPipelinedReads(): Boolean = true

    /** Configures the connection target. Must be called before [connect]. */
    suspend fun setConnectionInfo(host: String, port: Int) {
        mutex.withLock {
            configuredHost = host
            configuredPort = port
        }
    }

    /** Sets a handler to be called when the connection is unexpectedly lost. */
    fun setDisconnectionHandler(handler: (Throwable?) -> Unit) {
        disconnectionHandler = handler
    }

    /** Clears the disconnection handler (call before a user-initiated disconnect). */
    fun clearDisconnectionHandler() {
        disconnectionHandler = null
    }

    /** Returns the configured connection info, if set. */
    suspend fun connectionInfo(): Pair<String, Int>? = mutex.withLock {
        val host = configuredHost ?: return@withLock null
        val port = configuredPort ?: return@withLock null
        host to port
    }

    /** Establishes a TCP connection to the configured host and port. Call [setConnectionInfo] first. */
    override suspend fun connect() {
        val (host, port) = mutex.withLock {
            // Idempotent: skip if already connected. Without this guard, a caller re-entering
            // connect() would orphan the TCP connection already established.
            if (connected) return
            val configuredHost = configuredHost ?: throw WiFiTransportError.NotConfigured
            val configuredPort = configuredPort ?: throw WiFiTransportError.NotConfigured
            configuredHost to configuredPort
        }

        val newSocket = Socket()
        inFlightSocket = newSocket
        try {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { runCatching { newSocket.close() } }
                    try {
                        // Disable Nagle's algorithm: the companion protocol is small-frame
                        // request-response, and Nagle interacting with the peer's delayed-ACK
                        // injects a fixed per-round-trip stall.
                        newSocket.tcpNoDelay = true
                        newSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS.toInt())
                        continuation.resume(Unit)
                    } catch (error: SocketTimeoutException) {
                        continuation.resumeWithException(WiFiTransportError.ConnectionTimeout)
                    } catch (error: IOException) {
                        continuation.resumeWithException(WiFiTransportError.ConnectionFailed(error.message ?: error.toString()))
                    }
                }
            }
        } finally {
            inFlightSocket = null
        }

        mutex.withLock {
            socket = newSocket
            connected = true
        }
        startReceiving(newSocket)
    }

    override suspend fun disconnect() {
        disconnectionHandler = null // Clear BEFORE tearing down to prevent a spurious callback.
        inFlightSocket?.close() // Unparks a concurrently in-flight connect(), if any.
        teardown(notify = false, error = null)
    }

    override suspend fun send(data: ByteArray) {
        val activeSocket = mutex.withLock { if (connected) socket else null } ?: throw WiFiTransportError.NotConnected
        val frame = WiFiFrameCodec.encode(data)
        try {
            withTimeout(WRITE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val out = activeSocket.getOutputStream()
                    out.write(frame)
                    out.flush()
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw WiFiTransportError.SendTimeout
        } catch (error: IOException) {
            throw WiFiTransportError.SendFailed(error.message ?: error.toString())
        }
    }

    private fun startReceiving(activeSocket: Socket) {
        receiveJob = scope.launch {
            try {
                val input = activeSocket.getInputStream()
                val buffer = ByteArray(65536)
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) {
                        teardown(notify = true, error = null)
                        return@launch
                    }
                    val frames = mutex.withLock { frameDecoder.decode(buffer.copyOf(bytesRead)) }
                    for (frame in frames) channel.trySend(frame)
                }
            } catch (error: IOException) {
                teardown(notify = true, error = error)
            }
        }
    }

    /**
     * Tears down the connection, whether user-initiated ([disconnect]) or observed by the
     * receive loop (peer close or a socket error) — mirrors Swift's `handleReceiveTermination`,
     * guarded on [connected] so both paths racing (e.g. `disconnect()` closing the socket while
     * the receive loop is mid-read) notify at most once.
     */
    private suspend fun teardown(notify: Boolean, error: Throwable?) {
        val socketToClose = mutex.withLock {
            if (!connected) return
            connected = false
            val current = socket
            socket = null
            frameDecoder.reset()
            current
        }
        receiveJob?.cancel()
        receiveJob = null
        runCatching { socketToClose?.close() }
        channel.close()
        if (notify) disconnectionHandler?.invoke(error)
    }

    companion object {
        /** Connection timeout duration. */
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** Write timeout duration. */
        const val WRITE_TIMEOUT_MS = 5_000L
    }
}
