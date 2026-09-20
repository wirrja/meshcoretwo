// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Placeholder marking the protocol module as wired up (Gradle needs at least
 * one source file to build a module). This is the port target for the iOS
 * `MeshCore` package: PacketBuilder/PacketParser, PacketCodes, the Ed25519->
 * X25519 conversion + channel/DM crypto, the LPP encoder/decoder, MeshEvent,
 * EventDispatcher and the MeshTransport interface. See PLAN.md, Phase 1.
 */
internal object ProtocolModule {
    const val NAME = "meshcore-protocol"
}
