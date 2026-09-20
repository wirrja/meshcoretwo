// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.protocol.MockTransport
import com.meshcoretwo.protocol.ResponseCode
import com.meshcoretwo.protocol.toLittleEndianBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Minimal wire-frame builders for driving a real [com.meshcoretwo.protocol.MeshCoreSession]'s
 * appStart/queryDevice handshake through a [MockTransport], so `ConnectionManagerTest` can
 * exercise the actual connect path rather than faking the session itself (which `ConnectionManager`
 * doesn't allow injecting — it constructs `MeshCoreSession` directly, matching Swift). Trimmed
 * duplicate of `protocol`'s own `SessionTestSupport.kt` (that file's helpers are `internal` to the
 * `protocol` module and unreachable from here) — only what a bare start()+queryDevice() handshake
 * needs, not the full response-packet catalog.
 */

/** Polls [condition] on a real (non-virtual) clock until it's true, or fails after [timeoutMs]. */
internal suspend fun waitUntilSent(mock: MockTransport, minCount: Int = 1, timeoutMs: Long = 10_000) {
    withTimeout(timeoutMs) {
        while (mock.sentData().size < minCount) {
            delay(5)
        }
    }
}

/** `SELF_INFO` response wire format — see `protocol`'s `SelfInfoParser`'s doc for the byte layout. */
internal fun makeSelfInfoPacket(
    name: String = "TestNode",
    publicKey: ByteArray = ByteArray(32) { 0x01 },
    radioFrequency: UInt = 915_000u,
    radioBandwidth: UInt = 125_000u,
): ByteArray {
    var payload = byteArrayOf(1, 22, 22) // advType, txPower, maxTxPower
    payload += publicKey
    payload += 0.toLittleEndianBytes() // lat (Int32)
    payload += 0.toLittleEndianBytes() // lon (Int32)
    payload += byteArrayOf(0, 0, 0, 0) // multiAcks, advLocPolicy, telemetryMode, manualAdd
    payload += radioFrequency.toLittleEndianBytes()
    payload += radioBandwidth.toLittleEndianBytes()
    payload += byteArrayOf(7, 5) // spreadingFactor, codingRate
    payload += name.toByteArray(Charsets.UTF_8)
    return byteArrayOf(ResponseCode.SELF_INFO.value.toByte()) + payload
}

/** `DEVICE_INFO` (v3+ layout) response wire format — see `protocol`'s `DeviceInfoParser`'s doc. */
internal fun makeDeviceInfoPacket(
    firmwareVersion: UByte = 10u,
    maxContactsHalved: UByte = 100u,
    maxChannels: UByte = 8u,
    blePin: UInt = 123456u,
    firmwareBuild: String = "build1",
    model: String = "Heltec V3",
    version: String = "1.0.0",
): ByteArray {
    var payload = byteArrayOf(firmwareVersion.toByte(), maxContactsHalved.toByte(), maxChannels.toByte())
    payload += blePin.toLittleEndianBytes()
    payload += firmwareBuild.toByteArray(Charsets.UTF_8).copyOf(12)
    payload += model.toByteArray(Charsets.UTF_8).copyOf(40)
    payload += version.toByteArray(Charsets.UTF_8).copyOf(20)
    return byteArrayOf(ResponseCode.DEVICE_INFO.value.toByte()) + payload
}

/**
 * Runs [block] in the background and answers the next appStart + queryDevice handshake the
 * session under test issues over [mock], in order. Callers must ensure [block] triggers exactly
 * one connect attempt (e.g. a single `ConnectionManager.connect(...)` call).
 */
internal suspend fun answerConnectHandshake(mock: MockTransport, selfInfo: ByteArray = makeSelfInfoPacket(), deviceInfo: ByteArray = makeDeviceInfoPacket()) {
    waitUntilSent(mock, minCount = 1)
    mock.simulateReceive(selfInfo)
    waitUntilSent(mock, minCount = 2)
    mock.simulateReceive(deviceInfo)
}

/** Launches [block] on a background dispatcher and answers the handshake concurrently, joining both. */
internal suspend fun runConnectAndAnswerHandshake(mock: MockTransport, block: suspend () -> Unit) {
    val job = CoroutineScope(Dispatchers.Default).launch { block() }
    answerConnectHandshake(mock)
    job.join()
}
