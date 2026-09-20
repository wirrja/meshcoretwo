// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Composition of every session role the services-layer composition root
 * ([com.meshcoretwo.services.ServiceContainer]) needs from a single value: every capability
 * [MeshCoreSession] implements, gathered behind one type. Same precedent [RemoteNodeSessionOps]/
 * [BinaryProtocolSessionOps]/[AdvertisementSessionOps]/[RxLogSessionOps]/[NodeConfigSessionOps]
 * each established for their own narrower slice — a dedicated interface rather than widening
 * [MeshCoreSessionProtocol] itself, since most of that umbrella's consumers touch only one or two
 * session capabilities.
 *
 * [NodeConfigSessionOps] adds no new abstract members here — [MeshCoreSessionProtocol] already
 * includes both [ContactSessionOps] and [ChannelSessionOps] — it is listed purely for nominal
 * conformance, the same reasoning [MeshCoreSession]'s class doc gives for why it separately
 * declared [RemoteNodeSessionOps]/[BinaryProtocolSessionOps] before those were folded in here:
 * Kotlin does not infer interface conformance structurally, so
 * [com.meshcoretwo.services.nodeconfig.NodeConfigService] could not accept a value typed
 * [FullMeshCoreSessionOps] as its [NodeConfigSessionOps]-typed `session` parameter without this.
 *
 * [MeshCoreSession] declares conformance to this interface directly (see its class doc for why
 * that declaration is load-bearing, not redundant, under Kotlin's nominal typing).
 */
interface FullMeshCoreSessionOps :
    MeshCoreSessionProtocol,
    ConfigurationSessionOps,
    AdvertisingSessionOps,
    AdvertisementSessionOps,
    RxLogSessionOps,
    DiagnosticsSessionOps,
    RemoteAccessSessionOps,
    RemoteNodeSessionOps,
    BinaryProtocolSessionOps,
    NodeConfigSessionOps
