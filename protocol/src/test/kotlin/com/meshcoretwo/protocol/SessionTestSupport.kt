// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Shared wire-frame builders and session-bootstrap helpers for the `MeshCoreSession*Test` files
 * (ported from the private per-file helpers Swift duplicates across the `Session` test files;
 * Kotlin has no reason to duplicate them file by file, so they live here once).
 */

/** Polls [condition] on a real (non-virtual) clock until it's true, or fails after [timeoutMs]. */
internal suspend fun waitUntil(timeoutMs: Long = 2000, condition: suspend () -> Boolean) {
    withTimeout(timeoutMs) {
        while (!condition()) {
            delay(5)
        }
    }
}

internal fun makeSelfInfoPacket(
    name: String = "TestNode",
    publicKey: ByteArray = ByteArray(32) { 0x01 },
    radioFrequency: UInt = 915_000u,
    radioBandwidth: UInt = 125_000u,
    spreadingFactor: Byte = 7,
    codingRate: Byte = 5,
): ByteArray {
    var payload = byteArrayOf(1, 22, 22) // advType, txPower, maxTxPower
    payload += publicKey.paddedOrTruncated(32)
    payload += 0.toLittleEndianBytes() // lat (Int32)
    payload += 0.toLittleEndianBytes() // lon (Int32)
    payload += byteArrayOf(0, 0, 0, 0) // multiAcks, advLocPolicy, telemetryMode, manualAdd
    payload += radioFrequency.toLittleEndianBytes()
    payload += radioBandwidth.toLittleEndianBytes()
    payload += byteArrayOf(spreadingFactor, codingRate)
    payload += name.toByteArray(Charsets.UTF_8)
    return byteArrayOf(ResponseCode.SELF_INFO.value.toByte()) + payload
}

internal fun makeContactsStartPacket(count: UInt): ByteArray =
    byteArrayOf(ResponseCode.CONTACT_START.value.toByte()) + count.toLittleEndianBytes()

internal fun makeContactsEndPacket(lastModified: UInt): ByteArray =
    byteArrayOf(ResponseCode.CONTACT_END.value.toByte()) + lastModified.toLittleEndianBytes()

internal fun makeContactPacket(
    publicKey: ByteArray,
    name: String,
    type: UByte = 1u,
    flags: UByte = 0u,
    outPathLength: UByte = 0xFFu,
    lastAdvertisement: UInt = 0u,
    lastModified: UInt = 0u,
): ByteArray {
    var data = publicKey.copyOf()
    data += type.toByte()
    data += flags.toByte()
    data += outPathLength.toByte()
    data += ByteArray(64) // outPath
    data += name.toByteArray(Charsets.UTF_8).paddedOrTruncated(32)
    data += lastAdvertisement.toLittleEndianBytes()
    data += 0.toLittleEndianBytes() // lat
    data += 0.toLittleEndianBytes() // lon
    data += lastModified.toLittleEndianBytes()
    return byteArrayOf(ResponseCode.CONTACT.value.toByte()) + data
}

/** Advertisement push: `[ADVERTISEMENT][publicKey:32]`. */
internal fun makeAdvertisementPacket(publicKey: ByteArray): ByteArray =
    byteArrayOf(ResponseCode.ADVERTISEMENT.value.toByte()) + publicKey.paddedOrTruncated(32)

/** `[CHANNEL_INFO][index][name:31 zero-padded][secret:16]`. */
internal fun makeChannelInfoPacket(index: UByte, name: String, secret: ByteArray): ByteArray {
    val nameBytes = name.toByteArray(Charsets.UTF_8).take(31).toByteArray()
    var packet = byteArrayOf(ResponseCode.CHANNEL_INFO.value.toByte(), index.toByte())
    packet += nameBytes
    packet += 0
    if (nameBytes.size < 31) packet += ByteArray(31 - nameBytes.size)
    packet += secret
    return packet
}

/** `[BATTERY][level:2 LE]`. */
internal fun makeBatteryPacket(level: UShort): ByteArray =
    byteArrayOf(ResponseCode.BATTERY.value.toByte()) + level.toLittleEndianBytes()

/** `[MESSAGE_SENT][type][expectedAck:4][suggestedTimeoutMs:4 LE]`. */
internal fun makeMessageSentPacket(type: UByte = 0u, expectedAck: ByteArray, timeoutMs: UInt = 5000u): ByteArray {
    var packet = byteArrayOf(ResponseCode.MESSAGE_SENT.value.toByte(), type.toByte())
    packet += expectedAck
    packet += timeoutMs.toLittleEndianBytes()
    return packet
}

/** `[CONTACT_URI][publicKey:32][trailing card bytes...]` (trailing content is irrelevant to the echo guard). */
internal fun makeContactURIPacket(publicKey: ByteArray): ByteArray =
    byteArrayOf(ResponseCode.CONTACT_URI.value.toByte()) + publicKey + ByteArray(8) { 0xCD.toByte() }

/** Pushed telemetry response: `[TELEMETRY_RESPONSE][0x00][publicKeyPrefix:6][lppPayload...]`. */
internal fun makeTelemetryPacket(publicKeyPrefix: ByteArray, lppPayload: ByteArray): ByteArray =
    byteArrayOf(ResponseCode.TELEMETRY_RESPONSE.value.toByte(), 0x00) + publicKeyPrefix + lppPayload

/** Pushed status response (repeater layout unless [roomServer*] are non-zero, then packed into the trailing field). */
internal fun makeStatusResponsePacket(
    publicKeyPrefix: ByteArray,
    battery: UShort,
    roomServerPostedCount: UShort = 0u,
    roomServerPostPushCount: UShort = 0u,
): ByteArray {
    var packet = byteArrayOf(ResponseCode.STATUS_RESPONSE.value.toByte(), 0x00)
    packet += publicKeyPrefix
    packet += battery.toLittleEndianBytes() // battery
    packet += 0.toShort().toLittleEndianBytes() // txQueueLen
    packet += (-110).toShort().toLittleEndianBytes() // noiseFloor
    packet += (-85).toShort().toLittleEndianBytes() // lastRSSI
    packet += 100u.toLittleEndianBytes() // packetsReceived
    packet += 50u.toLittleEndianBytes() // packetsSent
    packet += 25u.toLittleEndianBytes() // airtime
    packet += 3600u.toLittleEndianBytes() // uptime
    packet += 5u.toLittleEndianBytes() // sentFlood
    packet += 10u.toLittleEndianBytes() // sentDirect
    packet += 15u.toLittleEndianBytes() // receivedFlood
    packet += 20u.toLittleEndianBytes() // receivedDirect
    packet += 0.toShort().toLittleEndianBytes() // fullEvents
    packet += 0.toShort().toLittleEndianBytes() // lastSNR
    packet += 0.toShort().toLittleEndianBytes() // directDuplicates
    packet += 0.toShort().toLittleEndianBytes() // floodDuplicates
    // Trailing 4 bytes: repeater rxAirtime, or packed room posted/postPush when requested.
    val trailing = roomServerPostedCount.toUInt() or (roomServerPostPushCount.toUInt() shl 16)
    packet += trailing.toLittleEndianBytes()
    return packet
}

/** Wire: `[BINARY_RESPONSE][requestType:1][tag:4][lppPayload...]`. */
internal fun makeBinaryTelemetryResponsePacket(tag: ByteArray, lppPayload: ByteArray = ByteArray(0)): ByteArray =
    byteArrayOf(ResponseCode.BINARY_RESPONSE.value.toByte(), 0x00) + tag + lppPayload

/**
 * Launches [MeshCoreSession.start] in the background and answers its appStart handshake with
 * [selfInfoPacket].
 */
internal suspend fun startSession(session: MeshCoreSession, transport: MockTransport, selfInfoPacket: ByteArray = makeSelfInfoPacket()) {
    val job = CoroutineScope(Dispatchers.Default).launch { session.start() }
    waitUntil { transport.sentData().isNotEmpty() }
    transport.simulateReceive(selfInfoPacket)
    job.join()
    transport.clearSentData()
}
