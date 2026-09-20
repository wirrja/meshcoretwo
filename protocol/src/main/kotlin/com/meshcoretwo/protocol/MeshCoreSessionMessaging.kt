// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/** Guards [MeshCoreSession.inFlightGetMessage] so the check-then-launch in [getMessage] is atomic. */
private val inFlightGetMessageMutex = Mutex()

// MARK: - Messaging Commands

/**
 * Sends a text message to a contact.
 *
 * @param destination The destination public key (6+ bytes, uses first 6 as prefix).
 * @param attempt Retry attempt counter (0 for first attempt). Included in ACK hash.
 * @throws MeshCoreError.Timeout if no response. [MeshCoreError.DeviceError] on error.
 */
internal suspend fun MeshCoreSession.sendMessageImpl(
    destination: ByteArray,
    text: String,
    timestamp: Instant = Instant.now(),
    attempt: UByte = 0u,
): MessageSentInfo {
    val data = PacketBuilder.sendMessage(destination, text, timestamp, attempt)
    return sendAndWaitWithError(
        data,
        matching = { event -> (event as? MeshEvent.MessageSent)?.info },
        errorMatcher = deviceErrorMatcher,
    )
}

/** Sends a text message to a [Destination] (contact or public key). */
suspend fun MeshCoreSession.sendMessage(
    destination: Destination,
    text: String,
    timestamp: Instant = Instant.now(),
    attempt: UByte = 0u,
): MessageSentInfo = sendMessageImpl(destination.publicKey(prefixLength = 6), text, timestamp, attempt)

/**
 * Sends a message with automatic retry logic and optional path reset.
 *
 * @param destination The full 32-byte public key of the recipient. A full key is required if
 *   path reset is enabled.
 * @param maxAttempts The maximum number of total attempts to make.
 * @param floodAfter The number of failed attempts after which to reset the path to flood.
 * @param maxFloodAttempts The maximum number of attempts to make while in flood mode.
 * @param timeout The acknowledgment timeout per attempt, in seconds. If `null`, uses the
 *   suggested timeout provided by the device.
 * @return Information about the sent message if acknowledged, otherwise `null`.
 * @throws MeshCoreError.InvalidInput if the destination key is not 32 bytes.
 */
internal suspend fun MeshCoreSession.sendMessageWithRetryImpl(
    destination: ByteArray,
    text: String,
    timestamp: Instant = Instant.now(),
    maxAttempts: Int = 3,
    floodAfter: Int = 2,
    maxFloodAttempts: Int = 2,
    timeout: Double? = null,
): MessageSentInfo? {
    if (destination.size < PacketBuilder.PUBLIC_KEY_SIZE) {
        throw MeshCoreError.InvalidInput("Full ${PacketBuilder.PUBLIC_KEY_SIZE}-byte public key required for retry with path reset")
    }

    var attempts = 0
    var floodAttempts = 0
    var isFloodMode = false

    while (attempts < maxAttempts && (!isFloodMode || floodAttempts < maxFloodAttempts)) {
        if (attempts == floodAfter && !isFloodMode) {
            try {
                resetPathImpl(destination)
                isFloodMode = true
            } catch (error: Throwable) {
                // Swift logs and continues here; this module takes no logging dependency.
            }
        }

        val sentInfo = sendMessageImpl(destination.prefixBytes(6), text, timestamp, attempts.toUByte())

        val ackTimeout = timeout ?: (
            sentInfo.suggestedTimeoutMs.toDouble() / SessionConfiguration.MILLISECONDS_PER_SECOND *
                SessionConfiguration.RETRY_ACK_TIMEOUT_MULTIPLIER
            )
        val ackEvent = waitForEvent(
            predicate = { event -> (event as? MeshEvent.Acknowledgement)?.code?.contentEquals(sentInfo.expectedAck) == true },
            timeout = ackTimeout,
        )

        if (ackEvent != null) return sentInfo

        attempts++
        if (isFloodMode) floodAttempts++
    }

    return null
}

/** Sends an advertisement broadcast. */
internal suspend fun MeshCoreSession.sendAdvertisementImpl(flood: Boolean = false) {
    sendSimpleCommand(PacketBuilder.sendAdvertisement(flood))
}

/**
 * Fetches the next pending message from the device.
 *
 * Calls arriving while a fetch is in flight coalesce onto the same deferred and share its
 * outcome, so every coalesced caller's wait is bounded by the leader's timeout. The exchange
 * runs in [MeshCoreSession.sessionScope] (not a child of the caller) so a cancelled caller
 * cannot abandon it mid-wire; late responses still resolve inside the serializer instead of
 * leaking — mirroring Swift's unstructured `Task { ... }` here.
 */
internal suspend fun MeshCoreSession.getMessageImpl(timeout: Double? = null): MessageResult {
    val task = inFlightGetMessageMutex.withLock {
        inFlightGetMessage ?: sessionScope.async { performGetMessage(timeout) }.also { inFlightGetMessage = it }
    }
    return try {
        task.await()
    } finally {
        inFlightGetMessageMutex.withLock {
            if (inFlightGetMessage === task) inFlightGetMessage = null
        }
    }
}

private suspend fun MeshCoreSession.performGetMessage(timeout: Double?): MessageResult = requestResponseSerializer.withSerialization {
    val timeoutSeconds = timeout ?: configuration.defaultTimeout

    // Subscribe before sending, then only accept an `.error` once the getMessage frame has
    // gone out. Holding the serializer makes this the sole exchange in flight, so the only
    // error that can arrive inside the window is the device's reply to this request.
    val stream = dispatcher.subscribe { event ->
        when (event) {
            is MeshEvent.ContactMessageReceived, is MeshEvent.ChannelMessageReceived,
            is MeshEvent.ChannelDataReceived, is MeshEvent.NoMoreMessages, is MeshEvent.Error -> true
            else -> false
        }
    }

    transport.send(PacketBuilder.getMessage())

    val result = withTimeoutOrNull((timeoutSeconds * 1000).toLong()) {
        val channel = stream.produceIn(this)
        try {
            for (event in channel) {
                when (event) {
                    is MeshEvent.ContactMessageReceived -> return@withTimeoutOrNull MessageResult.ContactMessageResult(event.message)
                    is MeshEvent.ChannelMessageReceived -> return@withTimeoutOrNull MessageResult.ChannelMessageResult(event.message)
                    is MeshEvent.ChannelDataReceived -> return@withTimeoutOrNull MessageResult.ChannelDatagramResult(event.datagram)
                    is MeshEvent.NoMoreMessages -> return@withTimeoutOrNull MessageResult.NoMoreMessages
                    is MeshEvent.Error -> throw MeshCoreError.DeviceError(event.code ?: 0u)
                    else -> {}
                }
            }
            null
        } finally {
            channel.cancel()
        }
    }
    result ?: throw MeshCoreError.Timeout
}

/** Sends a command message to a remote node. */
internal suspend fun MeshCoreSession.sendCommandImpl(
    destination: ByteArray,
    command: String,
    timestamp: Instant = Instant.now(),
): MessageSentInfo = sendAndWaitWithError(
    PacketBuilder.sendCommand(destination, command, timestamp),
    matching = { event -> (event as? MeshEvent.MessageSent)?.info },
    errorMatcher = deviceErrorMatcher,
)

/** Sends a message to a channel. Broadcasts to all nodes with the same channel configuration. */
internal suspend fun MeshCoreSession.sendChannelMessageImpl(channel: UByte, text: String, timestamp: Instant = Instant.now()) {
    sendSimpleCommand(PacketBuilder.sendChannelMessage(channel, text, timestamp))
}

/**
 * Sends a binary datagram to a channel. Requires firmware v11+ (MeshCore v1.15.0+).
 *
 * @param dataType Application data-type namespace. `0x0000` is reserved and rejected by
 *   firmware; `0xFFFF` is the developer namespace.
 * @param pathLength Encoded `path_len` byte. Defaults to flood ([PacketBuilder.FLOOD_PATH_SENTINEL]).
 */
suspend fun MeshCoreSession.sendChannelData(
    channelIndex: UByte,
    dataType: UShort,
    payload: ByteArray,
    pathLength: UByte = PacketBuilder.FLOOD_PATH_SENTINEL,
    pathBytes: ByteArray = ByteArray(0),
) {
    sendSimpleCommand(PacketBuilder.sendChannelData(channelIndex, dataType, payload, pathLength, pathBytes))
}

/** Sends a login request to a remote node, authenticating with a password-protected node. */
internal suspend fun MeshCoreSession.sendLoginImpl(destination: ByteArray, password: String): MessageSentInfo =
    sendAndWaitWithError(
        PacketBuilder.sendLogin(destination, password),
        matching = { event -> (event as? MeshEvent.MessageSent)?.info },
        errorMatcher = deviceErrorMatcher,
    )

/** Sends a login request to a [Destination] (contact or public key). */
suspend fun MeshCoreSession.sendLogin(destination: Destination, password: String): MessageSentInfo =
    sendLoginImpl(destination.fullPublicKey(), password)

/** Sends a logout request to a remote node, terminating an authenticated session. */
internal suspend fun MeshCoreSession.sendLogoutImpl(destination: ByteArray) {
    sendSimpleCommand(PacketBuilder.sendLogout(destination))
}

/** Requests status information from a remote node (legacy message-based request). */
suspend fun MeshCoreSession.sendStatusRequest(destination: ByteArray): MessageSentInfo =
    sendAndWaitWithError(
        PacketBuilder.sendStatusRequest(destination),
        matching = { event -> (event as? MeshEvent.MessageSent)?.info },
        errorMatcher = deviceErrorMatcher,
    )

/** Requests telemetry data from a remote node (legacy message-based request). */
suspend fun MeshCoreSession.sendTelemetryRequest(destination: ByteArray): MessageSentInfo =
    sendAndWaitWithError(
        PacketBuilder.getSelfTelemetry(destination),
        matching = { event -> (event as? MeshEvent.MessageSent)?.info },
        errorMatcher = deviceErrorMatcher,
    )

/** Initiates path discovery to a remote node, triggering route rediscovery. */
internal suspend fun MeshCoreSession.sendPathDiscoveryImpl(destination: ByteArray): MessageSentInfo =
    sendAndWaitWithError(
        PacketBuilder.sendPathDiscovery(destination),
        matching = { event -> (event as? MeshEvent.MessageSent)?.info },
        errorMatcher = deviceErrorMatcher,
    )

/**
 * Sends a trace packet through the mesh network, recording the path it traverses.
 *
 * @throws MeshCoreError.InvalidInput if [path] is `null` or empty; firmware requires at least
 *   one path byte and rejects a path-less trace frame.
 */
internal suspend fun MeshCoreSession.sendTraceImpl(
    tag: UInt? = null,
    authCode: UInt? = null,
    flags: UByte = 0u,
    path: ByteArray? = null,
): MessageSentInfo {
    if (path == null || path.isEmpty()) {
        throw MeshCoreError.InvalidInput("Trace requires at least one path byte")
    }

    val actualTag = tag ?: randomNonZeroUInt()
    val actualAuth = authCode ?: randomNonZeroUInt()

    return sendAndWaitWithError(
        PacketBuilder.sendTrace(actualTag, actualAuth, flags, path),
        matching = { event -> (event as? MeshEvent.MessageSent)?.info },
        errorMatcher = deviceErrorMatcher,
    )
}

/** Sets the flood scope using a raw 32-byte scope key. */
suspend fun MeshCoreSession.setFloodScope(scopeKey: ByteArray) {
    sendSimpleCommand(PacketBuilder.setFloodScope(scopeKey))
}

/** Sets the flood scope using a [FloodScope]. */
suspend fun MeshCoreSession.setFloodScope(scope: FloodScope) {
    setFloodScope(scope.scopeKey())
}

/**
 * Forces un-scoped flood broadcasts, overriding the device's persisted default flood scope.
 *
 * Requires firmware ver 12+; older firmware rejects the command with `ERR_CODE_UNSUPPORTED_CMD`.
 */
suspend fun MeshCoreSession.setFloodScopeUnscoped() {
    sendSimpleCommand(PacketBuilder.setFloodScopeUnscoped())
}

/**
 * Persists the device's default flood scope using a raw scope key.
 *
 * Requires firmware v11+. An empty [name] clears the persisted scope; `scopeKey` is then ignored.
 */
suspend fun MeshCoreSession.setDefaultFloodScope(name: String, scopeKey: ByteArray) {
    sendSimpleCommand(PacketBuilder.setDefaultFloodScope(name, scopeKey))
}

/** Persists the device's default flood scope from a [FloodScope]. [FloodScope.Disabled] clears it. */
internal suspend fun MeshCoreSession.setDefaultFloodScopeImpl(name: String, scope: FloodScope) {
    sendSimpleCommand(PacketBuilder.setDefaultFloodScope(name, scope))
}

/**
 * Fetches the device's persisted default flood scope. Requires firmware v11+; older firmware
 * surfaces the unknown opcode as [MeshCoreError.DeviceError].
 */
internal suspend fun MeshCoreSession.getDefaultFloodScopeImpl(): DefaultFloodScope? {
    val data = PacketBuilder.getDefaultFloodScope()
    return sendAndMatch(data) { event ->
        when (event) {
            is MeshEvent.DefaultFloodScopeEvent -> ResponseDisposition.Success(event.scope)
            is MeshEvent.Error -> ResponseDisposition.Failure(MeshCoreError.DeviceError(event.code ?: 0u))
            else -> ResponseDisposition.Ignore
        }
    }
}

/** Sets the path hash mode on the device (0=1-byte, 1=2-byte, 2=3-byte hashes). */
internal suspend fun MeshCoreSession.setPathHashModeImpl(mode: UByte) {
    if (mode.toInt() > PathEncoding.MAX_PATH_HASH_MODE) {
        throw MeshCoreError.InvalidInput("Path hash mode must be 0, 1, or 2")
    }
    sendSimpleCommand(PacketBuilder.setPathHashMode(mode))
}

/**
 * Sends a keep-alive request to a room server with the client's sync watermark.
 *
 * @param syncSince The client's last-received message timestamp (little-endian on wire).
 */
internal suspend fun MeshCoreSession.sendKeepAliveImpl(publicKey: ByteArray, syncSince: UInt): MessageSentInfo {
    requireFullPublicKey(publicKey, "sendKeepAlive")
    val payload = syncSince.toLittleEndianBytes()
    val data = PacketBuilder.binaryRequest(publicKey, BinaryRequestType.KEEP_ALIVE, payload)
    return sendAndWaitWithError(
        data,
        matching = { event -> (event as? MeshEvent.MessageSent)?.info },
        errorMatcher = deviceErrorMatcher,
    )
}
