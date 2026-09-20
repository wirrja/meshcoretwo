// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Composition of the session roles [com.meshcoretwo.services.diagnostics.BinaryProtocolService]
 * needs: binary-protocol diagnostics requests ([DiagnosticsSessionOps]), listening for push
 * responses ([SessionEventStreaming]), and path reset on mesh timeout ([ContactSessionOps]).
 * Declared as a dedicated interface (matching Swift's `any DiagnosticsSessionOps &
 * SessionEventStreaming & ContactSessionOps` existential) rather than widening
 * [MeshCoreSessionProtocol] itself, the same precedent [AdvertisementSessionOps] established.
 */
interface BinaryProtocolSessionOps : DiagnosticsSessionOps, SessionEventStreaming, ContactSessionOps
