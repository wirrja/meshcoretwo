// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of OtherParamsSerializationTests.swift.
 *
 * Two granular setters fired concurrently must both survive. Each setter does a
 * read-modify-write of the full config across several wire exchanges, so without
 * serialization the second setter reads the pre-first-write snapshot and reverts the first
 * setter's change. With serialization the second setter reads the config the first one already
 * wrote, so the final frame on the wire carries both changes.
 */
class OtherParamsSerializationTest {
    @Test
    fun `concurrent granular setters do not revert each other`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val device = OtherParamsDevice(transport)
        val responderJob: Job = CoroutineScope(Dispatchers.Default).launch { device.run() }

        coroutineScope {
            launch { session.setManualAddContacts(true) }
            launch { session.setMultiAcks(7u) }
        }

        responderJob.cancelAndJoin()

        val finalConfig = device.lastWrittenConfig
        assertNotNull(finalConfig)
        assertTrue("manual-add change must survive the concurrent setter", finalConfig!!.manualAddContacts)
        assertEquals("multi-acks change must survive the concurrent setter", 7u.toUByte(), finalConfig.multiAcks)

        session.stop()
    }
}

/**
 * Background device emulator that tracks running other-params state. Replies OK to each
 * setOtherParams frame after recording it, and replies with a matching selfInfo to the appStart
 * that applyOtherParams issues to refresh the session's cache.
 *
 * Only [run] mutates `current`/`processed`; [lastWrittenConfig] is read from the test body only
 * after that job has been cancelled and joined, so a plain `@Volatile` is enough — no concurrent
 * writer exists once the read happens.
 */
private class OtherParamsDevice(private val transport: MockTransport) {
    private var processed = 0
    private var current = OtherParamsConfig()

    @Volatile
    var lastWrittenConfig: OtherParamsConfig? = null
        private set

    suspend fun run() {
        while (true) {
            val sent = transport.sentData()
            while (processed < sent.size) {
                val frame = sent[processed]
                processed++
                handle(frame)
            }
            delay(5)
        }
    }

    private suspend fun handle(frame: ByteArray) {
        if (frame.isEmpty()) return
        when (frame[0].toUByte()) {
            CommandCode.SET_OTHER_PARAMS.value -> {
                decodeSetOtherParams(frame)?.let {
                    current = it
                    lastWrittenConfig = it
                }
                transport.simulateOK()
            }
            CommandCode.APP_START.value -> transport.simulateReceive(makeOtherParamsSelfInfoPacket(current))
            else -> {}
        }
    }

    /** Decodes a setOtherParams command frame: [cmd][manualAdd][telemetryByte][advLoc][multiAcks]. */
    private fun decodeSetOtherParams(frame: ByteArray): OtherParamsConfig? {
        if (frame.size < 5) return null
        val telemetry = frame[2].toUByte()
        return OtherParamsConfig(
            manualAddContacts = frame[1].toInt() != 0,
            telemetryModeBase = (telemetry.toInt() and 0b11).toUByte(),
            telemetryModeLocation = ((telemetry.toInt() shr 2) and 0b11).toUByte(),
            telemetryModeEnvironment = ((telemetry.toInt() shr 4) and 0b11).toUByte(),
            advertisementLocationPolicy = frame[3].toUByte(),
            multiAcks = frame[4].toUByte(),
        )
    }
}

/** Builds a selfInfo response packet whose other-params bytes reflect [config]. */
private fun makeOtherParamsSelfInfoPacket(config: OtherParamsConfig): ByteArray {
    var payload = byteArrayOf(1, 22, 22) // advType, txPower, maxTxPower
    payload += ByteArray(32) { 0x01 } // public key
    payload += 0.toLittleEndianBytes() // lat
    payload += 0.toLittleEndianBytes() // lon
    payload += config.multiAcks.toByte()
    payload += config.advertisementLocationPolicy.toByte()
    val telemetry = ((config.telemetryModeEnvironment.toInt() and 0b11) shl 4) or
        ((config.telemetryModeLocation.toInt() and 0b11) shl 2) or
        (config.telemetryModeBase.toInt() and 0b11)
    payload += telemetry.toByte()
    payload += if (config.manualAddContacts) 1.toByte() else 0.toByte()
    payload += 869_525u.toLittleEndianBytes()
    payload += 250_000u.toLittleEndianBytes()
    payload += byteArrayOf(11, 5)
    return byteArrayOf(ResponseCode.SELF_INFO.value.toByte()) + payload
}
