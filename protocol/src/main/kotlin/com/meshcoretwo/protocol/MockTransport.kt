// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A simulated transport for testing and local development.
 *
 * Lets code exercise session logic without requiring physical hardware or Bluetooth
 * availability. Maintains a history of sent data and provides methods to inject simulated
 * responses from the device.
 *
 * Swift's `MockTransport` is an `actor`; this mirrors that isolation with a [Mutex] guarding
 * all mutable state, rather than a command-channel actor pattern — the first data point for
 * Phase 3's actor-to-coroutine translation choice (see PLAN.md).
 */
class MockTransport : MeshTransport {
    private val mutex = Mutex()
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    private val sentDataList = mutableListOf<ByteArray>()
    private var connected = false
    private var writeWithoutResponseSupported = false

    /** 1-based index of the first send that should fail, simulating a mid-burst transport drop. */
    private var failSendsFromIndex: Int? = null

    /** A stream of raw data injected via simulation. */
    override val receivedData: Flow<ByteArray> = channel.receiveAsFlow()

    override suspend fun isConnected(): Boolean = mutex.withLock { connected }

    /** Reports the configured Write-Without-Response capability. Defaults to `false`. */
    override suspend fun supportsWriteWithoutResponse(): Boolean = mutex.withLock { writeWithoutResponseSupported }

    /**
     * Opts this transport into advertising Write-Without-Response, so a session routes through
     * its pipelined path instead of the serial fallback.
     */
    suspend fun setSupportsWriteWithoutResponse(supported: Boolean) {
        mutex.withLock { writeWithoutResponseSupported = supported }
    }

    /**
     * Makes the [index]-th (1-based) and all later sends throw, simulating a disconnect that
     * strikes after some commands have already gone out.
     */
    suspend fun failSends(index: Int) {
        mutex.withLock { failSendsFromIndex = index }
    }

    /** Sets the transport state to connected. */
    override suspend fun connect() {
        mutex.withLock { connected = true }
    }

    /** Sets the transport state to disconnected and completes the data stream. */
    override suspend fun disconnect() {
        mutex.withLock { connected = false }
        channel.close()
    }

    /**
     * Records the sent data and ensures the transport is connected.
     *
     * @throws MeshTransportError.NotConnected if the transport is not connected.
     */
    override suspend fun send(data: ByteArray) {
        mutex.withLock {
            if (!connected) throw MeshTransportError.NotConnected
            val threshold = failSendsFromIndex
            if (threshold != null && sentDataList.size + 1 >= threshold) {
                throw MeshTransportError.SendFailed("simulated send failure")
            }
            sentDataList.add(data)
        }
    }

    /** Snapshot of the history of all data packets sent through this transport. */
    suspend fun sentData(): List<ByteArray> = mutex.withLock { sentDataList.toList() }

    /** Injects raw data into the [receivedData] stream to simulate a device response. */
    fun simulateReceive(data: ByteArray) {
        channel.trySend(data)
    }

    /** Injects a successful "OK" response into the stream. */
    fun simulateOK(value: UInt? = null) {
        var data = byteArrayOf(ResponseCode.OK.value.toByte())
        if (value != null) {
            data += value.toLittleEndianBytes()
        }
        simulateReceive(data)
    }

    /** Injects an error response into the stream. */
    fun simulateError(code: UByte) {
        simulateReceive(byteArrayOf(ResponseCode.ERROR.value.toByte(), code.toByte()))
    }

    /** Empties the sent-data history. */
    suspend fun clearSentData() {
        mutex.withLock { sentDataList.clear() }
    }
}
