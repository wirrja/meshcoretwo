// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.protocol.sendNodeDiscoverRequest
import com.meshcoretwo.protocol.toLittleEndianBytes
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Active zero-hop node scan ("Tools → Node Discovery"). Ported from the request/listen half of
 * `NodeDiscoveryViewModel.swift`, hoisted here so the `app` layer needn't reach the internal
 * [ConnectionManager.session] (see [ConnectionManagerRegions.kt] for the same seam).
 *
 * The radio broadcasts a `DISCOVER_REQ`; only nodes that hear it directly answer, so every
 * result is a zero-hop neighbor — that is the point of the tool. Unlike the passive advert-based
 * Discover list, this measures signal quality (SNR both ways, RSSI) on demand.
 */
enum class NodeScanFilter(val filterValue: UByte) {
    REPEATERS(0x04u),
    SENSORS(0x10u),
}

/** One `DISCOVER_RESP`: who answered and how well it was heard in each direction. */
class NodeScanResponse(
    val publicKey: ByteArray,
    /** Raw node-type byte; may not map to a [ContactType] (sensors don't). */
    val nodeType: UByte,
    /** SNR of the response as heard by this radio (dB). */
    val snr: Double,
    /** SNR of our request as heard by the responder (dB). */
    val snrIn: Double,
    val rssi: Int,
)

/** How long to keep listening for `DISCOVER_RESP` after the request goes out. */
const val NODE_SCAN_DURATION_MS = 15_000L

/**
 * Sends a discovery request and emits each matching response until [NODE_SCAN_DURATION_MS] passes
 * (the flow then completes normally). Cancelling the collector stops the scan early.
 *
 * @throws IllegalStateException if no device session is live.
 */
fun ConnectionManager.scanNodes(filter: NodeScanFilter): Flow<NodeScanResponse> = flow {
    val activeSession = session ?: throw IllegalStateException("Not connected")
    // Subscribe before sending so an immediate reply can't slip past.
    val events = activeSession.events()
    val tagBytes = activeSession.sendNodeDiscoverRequest(filter = filter.filterValue, prefixOnly = false).toLittleEndianBytes()
    withTimeoutOrNull(NODE_SCAN_DURATION_MS) {
        events.collect { event ->
            if (event is MeshEvent.DiscoverResponseEvent && event.response.tag.contentEquals(tagBytes)) {
                val response = event.response
                emit(NodeScanResponse(response.publicKey, response.nodeType, response.snr, response.snrIn, response.rssi))
            }
        }
    }
}

/** Whether [nodeType] maps to a node kind that can be stored as a contact. */
fun isContactNodeType(nodeType: UByte): Boolean = ContactType.fromValue(nodeType) != null

/**
 * Adds a scanned node as a flood-routed contact, as `NodeDiscoveryViewModel.addNode` does.
 *
 * @throws ContactServiceError.ContactTableFull when the radio's contact table is full.
 * @throws IllegalStateException if not connected or [nodeType] isn't a contact type.
 */
suspend fun ConnectionManager.addScannedNode(publicKey: ByteArray, nodeType: UByte, name: String) {
    val radioID = lastConnectedRadioID ?: throw IllegalStateException("Not connected")
    val service = contactService ?: throw IllegalStateException("Not connected")
    val type = ContactType.fromValue(nodeType) ?: throw IllegalStateException("Unsupported node type")
    val now = Instant.now()
    service.addOrUpdateContact(
        radioID,
        MeshContact(
            id = publicKey.hexString,
            publicKey = publicKey,
            type = type,
            flags = ContactFlags.NONE,
            outPathLength = PacketBuilder.FLOOD_PATH_SENTINEL,
            outPath = ByteArray(0),
            advertisedName = name,
            lastAdvertisement = Instant.EPOCH,
            latitude = 0.0,
            longitude = 0.0,
            lastModified = now,
        ),
    )
}
