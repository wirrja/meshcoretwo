// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.time.Instant

// MARK: - Signing Commands

/**
 * Begins a signing operation. After calling this, send data chunks with [signData], then
 * finalize with [signFinish].
 *
 * @return Maximum data size that can be signed in bytes.
 */
suspend fun MeshCoreSession.signStart(): Int =
    sendAndWait(PacketBuilder.signStart()) { event -> (event as? MeshEvent.SignStart)?.maxLength }

/** Sends a data chunk for signing. Must be called after [signStart] and before [signFinish]. */
suspend fun MeshCoreSession.signData(chunk: ByteArray) {
    sendSimpleCommand(PacketBuilder.signData(chunk))
}

/**
 * Finalizes signing and retrieves the signature.
 *
 * @param timeout Optional timeout override. Defaults to 3x the session's default timeout.
 */
suspend fun MeshCoreSession.signFinish(timeout: Double? = null): ByteArray {
    val effectiveTimeout = timeout ?: (configuration.defaultTimeout * 3)
    return sendAndWait(PacketBuilder.signFinish(), timeout = effectiveTimeout) { event ->
        (event as? MeshEvent.Signature)?.data
    }
}

/**
 * Signs data using the device's private key: starts signing, sends data in chunks, and
 * retrieves the signature.
 *
 * @throws MeshCoreError.DataTooLarge if data exceeds device limits.
 */
internal suspend fun MeshCoreSession.signImpl(data: ByteArray, chunkSize: Int = 120, timeout: Double? = null): ByteArray {
    val maxLength = signStart()
    if (data.size > maxLength) throw MeshCoreError.DataTooLarge(maxSize = maxLength, actualSize = data.size)

    var offset = 0
    while (offset < data.size) {
        val end = minOf(offset + chunkSize, data.size)
        signData(data.copyOfRange(offset, end))
        offset = end
    }

    return signFinish(timeout)
}

// MARK: - Control Data Commands

/** Sends control data to the mesh network for network-level operations and diagnostics. */
suspend fun MeshCoreSession.sendControlData(type: UByte, payload: ByteArray) {
    sendSimpleCommand(PacketBuilder.sendControlData(type, payload))
}

/**
 * Broadcasts a node discovery request to the mesh network.
 *
 * @param tag Optional request tag for correlation. Random non-zero value generated if `null`.
 * @return The tag used for this request (for correlating responses).
 */
suspend fun MeshCoreSession.sendNodeDiscoverRequest(
    filter: UByte,
    prefixOnly: Boolean = true,
    tag: UInt? = null,
    since: Instant? = null,
): UInt {
    val actualTag = tag ?: randomNonZeroUInt()
    val sinceTimestamp = since?.let { PacketBuilder.epochSeconds32(it) }
    val data = PacketBuilder.sendNodeDiscoverRequest(filter, prefixOnly, actualTag, sinceTimestamp)
    sendSimpleCommand(data)
    return actualTag
}

/**
 * Performs a factory reset on the device, erasing all configuration, contacts, and messages.
 * The device will reboot and return to factory defaults. This operation is irreversible.
 */
internal suspend fun MeshCoreSession.factoryResetImpl() {
    sendSimpleCommand(PacketBuilder.factoryReset())
}
