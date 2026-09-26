// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// MARK: - Region Requests

/**
 * Queries a repeater for its list of allowed regions.
 *
 * @param contact The repeater contact to query. Must have a full 32-byte public key.
 * @return Region name strings (e.g., `["Europe", "UK"]`). Names prefixed with `$` are private
 *   regions requiring pre-shared keys.
 * @throws MeshCoreError.Timeout if no response is received, [MeshCoreError.DeviceError] if the
 *   firmware rejects the request, or a parse error if the response is malformed.
 */
suspend fun MeshCoreSession.requestRegions(contact: MeshContact): List<String> {
    val isFloodRouted = contact.outPathLength == 0xFFu.toUByte()

    // Firmware requires isRouteDirect() for region requests. For flood-routed contacts, the
    // contact is temporarily set to zero-hop direct on the firmware and restored afterwards; the
    // restore runs NonCancellable so a caller cancelling mid-request (region discovery's "Stop")
    // can't leave the contact stuck on the zero-hop path. The setup write sits inside the same
    // try, since restoring a flood contact to flood is harmless if the setup never landed.
    return try {
        if (isFloodRouted) {
            // Route through PacketBuilder.updateContact so the raw type byte survives instead of
            // being coerced; the restore below only touches out_path_len, so any coercion here
            // would be permanent.
            val directContact = MeshContact(
                id = contact.id,
                publicKey = contact.publicKey,
                type = contact.type,
                typeRawValue = contact.typeRawValue,
                flags = contact.flags,
                outPathLength = 0u,
                outPath = ByteArray(0),
                advertisedName = contact.advertisedName,
                lastAdvertisement = contact.lastAdvertisement,
                latitude = contact.latitude,
                longitude = contact.longitude,
                lastModified = contact.lastModified,
            )
            sendSimpleCommand(PacketBuilder.updateContact(directContact))
        }
        requestResponseSerializer.withSerialization { performRegionsRequest(contact) }
    } finally {
        if (isFloodRouted) {
            withContext(NonCancellable) {
                try {
                    resetPathImpl(contact.publicKey)
                } catch (error: Throwable) {
                    // Best-effort restore; the exchange's own outcome is what the caller sees.
                }
            }
        }
    }
}

/**
 * Sends the region request and matches its response. Runs inside the request/response
 * serializer; flood-route setup and restore are handled by [requestRegions] as separate
 * exchanges.
 */
private suspend fun MeshCoreSession.performRegionsRequest(contact: MeshContact): List<String> {
    val isFloodRouted = contact.outPathLength == 0xFFu.toUByte()
    val pathLength: UByte
    val path: ByteArray
    if (isFloodRouted) {
        pathLength = 0u
        path = ByteArray(0)
    } else {
        pathLength = contact.outPathLength
        path = contact.outPath
    }

    return performBinaryExchange(
        request = PacketBuilder.sendAnonReq(contact.publicKey, AnonRequestType.REGIONS, pathLength, path),
        publicKey = contact.publicKey,
        operation = "Regions",
    ) { payload, _ -> RegionsParser.parse(payload) }
}
