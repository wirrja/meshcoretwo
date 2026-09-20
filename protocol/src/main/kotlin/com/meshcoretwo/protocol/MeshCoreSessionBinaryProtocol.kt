// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.time.Instant

// MARK: - Status Requests

/**
 * Requests status information from a remote node via `CMD_SEND_STATUS_REQ`, using the repeater
 * status layout. For room servers, prefer the [ContactType] or [MeshContact] overloads so the
 * correct status layout is selected.
 */
internal suspend fun MeshCoreSession.requestStatusImpl(publicKey: ByteArray): StatusResponse {
    requireFullPublicKey(publicKey, "requestStatus")
    return requestResponseSerializer.withSerialization { performStatusRequest(publicKey, StatusResponse.Layout.REPEATER) }
}

/** Requests status information from a remote node, using [type] to choose the status layout. */
internal suspend fun MeshCoreSession.requestStatusTypedImpl(publicKey: ByteArray, type: ContactType): StatusResponse {
    requireFullPublicKey(publicKey, "requestStatus")
    val layout = if (type == ContactType.ROOM) StatusResponse.Layout.ROOM_SERVER else StatusResponse.Layout.REPEATER
    return requestResponseSerializer.withSerialization { performStatusRequest(publicKey, layout) }
}

/** Requests status information from a remote contact, using its contact type to select the layout. */
suspend fun MeshCoreSession.requestStatus(contact: MeshContact): StatusResponse =
    requestStatusTypedImpl(contact.publicKey, contact.type)

/** Requests status information from a [Destination] (contact or public key). */
suspend fun MeshCoreSession.requestStatus(destination: Destination): StatusResponse = when (destination) {
    is Destination.Contact -> requestStatus(destination.contact)
    is Destination.Data, is Destination.HexString -> requestStatusImpl(destination.fullPublicKey())
}

/**
 * Uses `CMD_SEND_STATUS_REQ` so firmware pushes `STATUS_RESPONSE` (0x87) matched by public-key
 * prefix. `parseFromBinaryResponse` is the tag-matched fallback.
 */
private suspend fun MeshCoreSession.performStatusRequest(publicKey: ByteArray, layout: StatusResponse.Layout): StatusResponse {
    val publicKeyPrefix = publicKey.prefixBytes(6)
    return performBinaryExchange(
        request = PacketBuilder.sendStatusRequest(publicKey),
        publicKey = publicKey,
        operation = "Status",
        matchRoutedEvent = { event ->
            val response = (event as? MeshEvent.StatusResponseEvent)?.response
            when {
                response == null || !response.publicKeyPrefix.contentEquals(publicKeyPrefix) -> null
                layout == StatusResponse.Layout.ROOM_SERVER && response.layout == StatusResponse.Layout.REPEATER ->
                    roomServerStatus(response)
                else -> response
            }
        },
    ) { payload, _ -> StatusResponseParser.parseFromBinaryResponse(payload, publicKeyPrefix, layout) }
}

/** Maps a repeater-layout status push into room-server counters when the request used the room layout. */
private fun roomServerStatus(response: StatusResponse): StatusResponse = StatusResponse(
    layout = StatusResponse.Layout.ROOM_SERVER,
    publicKeyPrefix = response.publicKeyPrefix,
    battery = response.battery,
    txQueueLength = response.txQueueLength,
    noiseFloor = response.noiseFloor,
    lastRSSI = response.lastRSSI,
    packetsReceived = response.packetsReceived,
    packetsSent = response.packetsSent,
    airtime = response.airtime,
    uptime = response.uptime,
    sentFlood = response.sentFlood,
    sentDirect = response.sentDirect,
    receivedFlood = response.receivedFlood,
    receivedDirect = response.receivedDirect,
    fullEvents = response.fullEvents,
    lastSNR = response.lastSNR,
    directDuplicates = response.directDuplicates,
    floodDuplicates = response.floodDuplicates,
    rxAirtime = 0u,
    receiveErrors = 0u,
    roomServerPostedCount = response.rxAirtime.toUShort(),
    roomServerPostPushCount = (response.rxAirtime shr 16).toUShort(),
)

// MARK: - Binary Protocol Commands

/**
 * Requests telemetry data from a remote node via `CMD_SEND_TELEMETRY_REQ`. Firmware pushes
 * `TELEMETRY_RESPONSE` (0x8B) matched by response tag; `parseFromBinaryResponse` is the
 * tag-matched fallback.
 */
internal suspend fun MeshCoreSession.requestTelemetryImpl(publicKey: ByteArray): TelemetryResponse {
    requireFullPublicKey(publicKey, "requestTelemetry")
    // Serialize binary requests to prevent messageSent race conditions.
    return requestResponseSerializer.withSerialization { performTelemetryRequest(publicKey) }
}

private suspend fun MeshCoreSession.performTelemetryRequest(publicKey: ByteArray): TelemetryResponse {
    val publicKeyPrefix = publicKey.prefixBytes(6)
    // CMD_SEND_TELEMETRY_REQ frame: [0x27][3 reserved zeros][pubkey32]. Firmware zeros the
    // reserved mask so ~payload[1] grants full env permissions (guests stay clamped to base
    // telemetry on the repeater).
    return performBinaryExchange(
        request = PacketBuilder.getSelfTelemetry(publicKey),
        publicKey = publicKey,
        operation = "Telemetry",
        matchRoutedEvent = { event ->
            val response = (event as? MeshEvent.TelemetryResponseEvent)?.response
            if (response != null && response.publicKeyPrefix.contentEquals(publicKeyPrefix)) response else null
        },
    ) { payload, _ -> TelemetryResponseParser.parseFromBinaryResponse(payload, publicKeyPrefix) }
}

/** Requests telemetry data from a [Destination] (contact or public key). */
suspend fun MeshCoreSession.requestTelemetry(destination: Destination): TelemetryResponse =
    requestTelemetryImpl(destination.fullPublicKey())

// MARK: - Owner Info

/** Requests owner information from a repeater using the binary protocol. */
internal suspend fun MeshCoreSession.requestOwnerInfoImpl(publicKey: ByteArray): OwnerInfoResponse {
    requireFullPublicKey(publicKey, "requestOwnerInfo")
    return requestResponseSerializer.withSerialization { performOwnerInfoRequest(publicKey) }
}

private suspend fun MeshCoreSession.performOwnerInfoRequest(publicKey: ByteArray): OwnerInfoResponse =
    performBinaryExchange(
        request = PacketBuilder.binaryRequest(publicKey, BinaryRequestType.OWNER_INFO),
        publicKey = publicKey,
        operation = "Owner info",
    ) { payload, _ ->
        // Response is UTF-8: "<firmware_ver>\n<node_name>\n<owner_info>"
        val text = payload.decodeUtf8Strict() ?: ""
        val components = text.split("\n", limit = 3)
        OwnerInfoResponse(
            firmwareVersion = components.getOrElse(0) { "" },
            nodeName = components.getOrElse(1) { "" },
            ownerInfo = components.getOrElse(2) { "" },
        )
    }

/**
 * Requests Min-Max-Average (MMA) data for a time range from a remote node's aggregated sensor
 * statistics.
 */
internal suspend fun MeshCoreSession.requestMMAImpl(publicKey: ByteArray, start: Instant, end: Instant): MMAResponse {
    requireFullPublicKey(publicKey, "requestMMA")
    return requestResponseSerializer.withSerialization { performMMARequest(publicKey, start, end) }
}

private suspend fun MeshCoreSession.performMMARequest(publicKey: ByteArray, start: Instant, end: Instant): MMAResponse {
    var payload = PacketBuilder.epochSeconds32(start).toLittleEndianBytes()
    payload += PacketBuilder.epochSeconds32(end).toLittleEndianBytes()
    payload += byteArrayOf(0, 0)

    val publicKeyPrefix = publicKey.prefixBytes(6)
    return performBinaryExchange(
        request = PacketBuilder.binaryRequest(publicKey, BinaryRequestType.MMA, payload),
        publicKey = publicKey,
        operation = "MMA",
    ) { responsePayload, tag -> MMAResponse(publicKeyPrefix, tag, MMAParser.parse(responsePayload)) }
}

/**
 * Requests the Access Control List (ACL) from a remote node: the list of authorized public keys
 * for administrative access.
 */
internal suspend fun MeshCoreSession.requestACLImpl(publicKey: ByteArray): ACLResponse {
    requireFullPublicKey(publicKey, "requestACL")
    return requestResponseSerializer.withSerialization { performACLRequest(publicKey) }
}

private suspend fun MeshCoreSession.performACLRequest(publicKey: ByteArray): ACLResponse {
    val payload = byteArrayOf(0, 0)
    val publicKeyPrefix = publicKey.prefixBytes(6)
    return performBinaryExchange(
        request = PacketBuilder.binaryRequest(publicKey, BinaryRequestType.ACL, payload),
        publicKey = publicKey,
        operation = "ACL",
    ) { responsePayload, tag -> ACLResponse(publicKeyPrefix, tag, ACLParser.parse(responsePayload)) }
}

/**
 * Requests the neighbor list from a remote node: nodes it can directly communicate with.
 *
 * @param orderBy Sort order (0 = by RSSI).
 */
internal suspend fun MeshCoreSession.requestNeighboursImpl(
    publicKey: ByteArray,
    count: UByte = 255u,
    offset: UShort = 0u,
    orderBy: UByte = 0u,
    pubkeyPrefixLength: UByte = 4u,
): NeighboursResponse {
    requireFullPublicKey(publicKey, "requestNeighbours")
    return requestResponseSerializer.withSerialization {
        performNeighboursRequest(publicKey, count, offset, orderBy, pubkeyPrefixLength)
    }
}

private suspend fun MeshCoreSession.performNeighboursRequest(
    publicKey: ByteArray,
    count: UByte,
    offset: UShort,
    orderBy: UByte,
    pubkeyPrefixLength: UByte,
): NeighboursResponse {
    var payload = byteArrayOf(0) // version
    payload += count.toByte()
    payload += offset.toLittleEndianBytes()
    payload += orderBy.toByte()
    payload += pubkeyPrefixLength.toByte()
    payload += randomNonZeroUInt().toLittleEndianBytes()

    val publicKeyPrefix = publicKey.prefixBytes(6)
    val prefixLength = pubkeyPrefixLength.toInt()
    return performBinaryExchange(
        request = PacketBuilder.binaryRequest(publicKey, BinaryRequestType.NEIGHBOURS, payload),
        publicKey = publicKey,
        operation = "Neighbours",
    ) { responsePayload, tag ->
        NeighboursParser.parse(responsePayload, publicKeyPrefix, tag, prefixLength)
    }
}

/**
 * Fetches all neighbors from a remote node with automatic pagination, making multiple requests
 * if necessary to retrieve the complete neighbor list.
 */
internal suspend fun MeshCoreSession.fetchAllNeighboursImpl(
    publicKey: ByteArray,
    orderBy: UByte = 0u,
    pubkeyPrefixLength: UByte = 4u,
): NeighboursResponse = NeighboursResponse.collectingAllPages { offset ->
    if (offset > 0u) delay(NeighboursResponse.INTER_PAGE_DELAY)
    requestNeighboursImpl(publicKey, count = 255u, offset = offset, orderBy = orderBy, pubkeyPrefixLength = pubkeyPrefixLength)
}

// MARK: - Binary Exchange

/**
 * Live retransmit spacing shared from `messageSent` into the resend loop. The floor starts at
 * the config minimum and rises to `suggestedTimeoutMs * binaryRetransmitRTTHeadroom` once
 * firmware reports one, so multi-hop waits cover a full return trip before another copy.
 *
 * Swift's version hand-rolls a continuation queue so a raw `CheckedContinuation` (which must be
 * resumed exactly once or leak/crash) is always resumed on cancellation. Kotlin's
 * [CompletableDeferred] needs no such handling: an awaiting coroutine that gets cancelled simply
 * stops waiting, so cancellation is left to propagate normally here.
 */
private class BinaryExchangeCadence(minimumSeconds: Double) {
    @Volatile private var seconds = minimumSeconds
    private val suggested = CompletableDeferred<Unit>()

    fun applySuggestedTimeoutMs(ms: UInt) {
        val suggestedSeconds = ms.toDouble() / SessionConfiguration.MILLISECONDS_PER_SECOND
        val withHeadroom = suggestedSeconds * SessionConfiguration.BINARY_RETRANSMIT_RTT_HEADROOM
        if (withHeadroom > seconds) seconds = withHeadroom
        suggested.complete(Unit)
    }

    suspend fun waitForInterval(): Double {
        if (!suggested.isCompleted) suggested.await()
        return seconds
    }

    fun currentSeconds(): Double = seconds
}

/**
 * Sends [request] and resolves the first matching [MeshEvent.BinaryResponse] or
 * [matchRoutedEvent] hit. Retransmits the same frame until a reply arrives or
 * `binaryRequestOverallTimeout` elapses (`null` retransmit interval disables resends). Companion
 * firmware keeps one pending tag per request class and replaces it on every send, so only the
 * latest `messageSent` tag matches [MeshEvent.BinaryResponse]. Routed matchers (status) ignore
 * tags. Spacing is `max(floor, suggestedTimeoutMs * binaryRetransmitRTTHeadroom)`.
 *
 * Unlike Swift's task group (which races three signal-producing tasks and treats a task's
 * `.idle` outcome as "keep waiting"), the retransmit loop here is a pure side-effecting
 * background job that never influences which of [consumer]/timeout wins — so only those two
 * race via [select], and a consumer whose event stream ends without a match suspends forever
 * ([awaitCancellation]) rather than resolving, correctly leaving the timeout task as the only
 * path to resolution in that case.
 *
 * @param matchRoutedEvent Optional matcher for a response that arrives as an already-routed
 *   typed event instead of a raw [MeshEvent.BinaryResponse].
 * @param parseResponse Parses the matched [MeshEvent.BinaryResponse] payload (with its tag).
 *   Returning `null` surfaces [MeshCoreError.ParseError] with the payload size.
 */
internal suspend fun <Response> MeshCoreSession.performBinaryExchange(
    request: ByteArray,
    publicKey: ByteArray,
    operation: String,
    matchRoutedEvent: ((MeshEvent) -> Response?)? = null,
    parseResponse: (payload: ByteArray, tag: ByteArray) -> Response?,
): Response {
    val overallTimeout = configuration.binaryRequestOverallTimeout
    val retransmitFloor = configuration.binaryRequestRetransmitInterval
    val cadence = BinaryExchangeCadence(minimumSeconds = retransmitFloor ?: 0.0)

    // Subscribe before sending to avoid the race where the response arrives before the
    // consumer is listening.
    val events = dispatcher.subscribe()
    transport.send(request)

    return coroutineScope {
        val consumer = async {
            val channel = events.produceIn(this)
            try {
                // Firmware replaces the single pending tag on each send; track only the latest.
                var expectedTag: ByteArray? = null
                var acceptedMessageSent = false

                for (event in channel) {
                    when (event) {
                        is MeshEvent.MessageSent -> {
                            expectedTag = event.info.expectedAck
                            acceptedMessageSent = true
                            cadence.applySuggestedTimeoutMs(event.info.suggestedTimeoutMs)
                        }

                        is MeshEvent.Error -> {
                            // Device errors after a live messageSent must not abort the wait
                            // (a retransmit can fail while an earlier attempt is still
                            // answerable).
                            if (!acceptedMessageSent) throw MeshCoreError.DeviceError(event.code ?: 0u)
                        }

                        is MeshEvent.BinaryResponse -> {
                            if (!event.tag.contentEquals(expectedTag)) continue
                            val response = parseResponse(event.data, event.tag)
                                ?: throw MeshCoreError.ParseError("$operation binary response unparseable (${event.data.size} bytes)")
                            return@async response
                        }

                        else -> {
                            val routed = matchRoutedEvent?.invoke(event)
                            if (routed != null) return@async routed
                        }
                    }
                }
                // Stream ended (e.g. session torn down) without a match; only the timeout
                // task can resolve the exchange from here.
                awaitCancellation()
            } finally {
                channel.cancel()
            }
        }

        val timeoutTask = async {
            delay((overallTimeout * 1000).toLong())
            throw MeshCoreError.Timeout
        }

        val retransmitJob = if (retransmitFloor != null) {
            launch {
                while (isActive) {
                    delay((cadence.waitForInterval() * 1000).toLong())
                    if (!isActive) break
                    try {
                        transport.send(request)
                    } catch (error: Throwable) {
                        // Swift logs and continues; this module takes no logging dependency.
                    }
                }
            }
        } else {
            null
        }

        try {
            select<Response> {
                consumer.onAwait { it }
                timeoutTask.onAwait { it }
            }
        } finally {
            consumer.cancel()
            timeoutTask.cancel()
            retransmitJob?.cancel()
        }
    }
}
