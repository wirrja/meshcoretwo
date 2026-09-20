// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.settings

import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.protocol.MeshCoreSession
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.protocol.requestRegions
import com.meshcoretwo.protocol.sendNodeDiscoverRequest
import com.meshcoretwo.protocol.toLittleEndianBytes
import com.meshcoretwo.services.contacts.ContactService
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.DiscoveredNodeDto
import com.meshcoretwo.services.persistence.DiscoveredNodeStore
import com.meshcoretwo.services.persistence.toMeshContact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Discovers and tracks *firmware-mesh regions* — named flood-routing scopes advertised by
 * repeaters via `DISCOVER_REQ` / 0x04 filter. Ported from `RegionDiscoveryService.swift`.
 *
 * Vocabulary note (carried over from the Swift doc): this is unrelated to
 * [com.meshcoretwo.services.region.RegionResolver]/[com.meshcoretwo.services.region.RegionSelection],
 * which use "region" to mean *user geographic location* for onboarding radio presets. The two
 * concepts share a word but no semantic overlap.
 */
object RegionDiscoveryService {
    /** Filter value for DISCOVER_REQ that requests only repeaters. */
    const val REPEATERS_FILTER: UByte = 0x04u

    /** How long to listen for DISCOVER_RESP before closing the event stream. */
    private const val LISTEN_DURATION_MS = 15_000L

    /** Firmware error code for "contact table full" responses (`ERR_CODE_TABLE_FULL`). */
    private const val TABLE_FULL_ERROR_CODE: UByte = 3u

    sealed class Outcome {
        /** The DISCOVER_REQ itself failed to send. */
        object SendFailed : Outcome()

        /** No repeaters responded to the probe, or no query targets could be built. */
        object NoRepeatersResponded : Outcome()

        /** Could not load the contact pool used to route region queries. */
        object ErrorLoadingRepeaters : Outcome()

        /**
         * Discovery completed. [newRegions] is filtered against the caller's `knownRegions` and
         * sorted. [allRepeatersTableFull] is `true` when every successful query returned
         * `ERR_CODE_TABLE_FULL` from the repeater.
         */
        data class Completed(val newRegions: List<String>, val allRepeatersTableFull: Boolean) : Outcome()
    }

    /**
     * Runs the discovery probe and aggregates regions from all responders.
     *
     * @param supportsAdHocRequest Whether the radio can query a repeater that isn't a contact
     *   (firmware v1.16+, [com.meshcoretwo.services.persistence.DeviceDto.supportsAdHocRepeaterRequest]).
     *   When `false`, only responders the user has already added as contacts are queried;
     *   anonymous requests to non-contact keys would be rejected by the local radio.
     */
    suspend fun discover(
        session: MeshCoreSession,
        contactService: ContactService,
        discoveredNodeStore: DiscoveredNodeStore?,
        radioID: UUID,
        knownRegions: List<String>,
        supportsAdHocRequest: Boolean,
    ): Outcome {
        val discoveredPubkeys: Set<String>
        try {
            val tag = session.sendNodeDiscoverRequest(filter = REPEATERS_FILTER, prefixOnly = false)
            val tagBytes = tag.toLittleEndianBytes()
            val keys = mutableSetOf<String>()
            withTimeoutOrNull(LISTEN_DURATION_MS) {
                session.events().collect { event ->
                    if (event is MeshEvent.DiscoverResponseEvent && event.response.tag.contentEquals(tagBytes)) {
                        keys += event.response.publicKey.hexString
                    }
                }
            }
            discoveredPubkeys = keys
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return Outcome.SendFailed
        }

        if (discoveredPubkeys.isEmpty()) return Outcome.NoRepeatersResponded

        val queryTargets: List<MeshContact>
        try {
            val contacts = contactService.getContacts(radioID)
            val discoveredNodes = discoveredNodeStore?.fetchDiscoveredNodes(radioID) ?: emptyList()
            queryTargets = buildRegionQueryTargets(discoveredPubkeys, contacts, discoveredNodes, supportsAdHocRequest)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return Outcome.ErrorLoadingRepeaters
        }

        if (queryTargets.isEmpty()) return Outcome.NoRepeatersResponded

        val allRegions = mutableSetOf<String>()
        var anyTableFull = false

        coroutineScope {
            val results = queryTargets.map { target ->
                async {
                    try {
                        RegionQueryOutcome.Regions(session.requestRegions(target))
                    } catch (error: MeshCoreError.DeviceError) {
                        if (error.code == TABLE_FULL_ERROR_CODE) RegionQueryOutcome.TableFull else RegionQueryOutcome.OtherFailure
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        RegionQueryOutcome.OtherFailure
                    }
                }
            }
            results.forEach { deferred ->
                when (val outcome = deferred.await()) {
                    is RegionQueryOutcome.Regions -> allRegions += outcome.regions
                    RegionQueryOutcome.TableFull -> anyTableFull = true
                    RegionQueryOutcome.OtherFailure -> {}
                }
            }
        }

        val newRegions = (allRegions - knownRegions.toSet()).sorted()
        return Outcome.Completed(newRegions = newRegions, allRepeatersTableFull = anyTableFull)
    }

    /**
     * Builds the [MeshContact] query pool used to fetch regions from each responder. Prefers
     * contact records (they carry direct routing data when available) and fills in from the
     * discovered-nodes table for responders the user has not added as contacts.
     *
     * Non-contact responders are only included when [supportsAdHocRequest] is `true`: older
     * firmware rejects an anonymous request to a key it doesn't hold as a contact, so querying
     * them would only produce spurious failures.
     */
    internal fun buildRegionQueryTargets(
        responders: Set<String>,
        contacts: List<ContactDto>,
        discoveredNodes: List<DiscoveredNodeDto>,
        supportsAdHocRequest: Boolean,
    ): List<MeshContact> {
        val byKey = linkedMapOf<String, MeshContact>()
        for (contact in contacts) {
            val hex = contact.publicKey.hexString
            if (contact.type == ContactType.REPEATER && responders.contains(hex)) {
                byKey[hex] = contact.toMeshContact()
            }
        }
        if (!supportsAdHocRequest) return byKey.values.toList()
        for (node in discoveredNodes) {
            val hex = node.publicKey.hexString
            if (node.nodeType == ContactType.REPEATER && responders.contains(hex) && !byKey.containsKey(hex)) {
                byKey[hex] = node.toMeshContact()
            }
        }
        return byKey.values.toList()
    }

    private sealed class RegionQueryOutcome {
        data class Regions(val regions: List<String>) : RegionQueryOutcome()
        object TableFull : RegionQueryOutcome()
        object OtherFailure : RegionQueryOutcome()
    }
}
