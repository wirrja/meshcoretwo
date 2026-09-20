// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Composition of the session roles [com.meshcoretwo.services.remotenode.RemoteNodeService] needs:
 * login/keep-alive/remote queries ([RemoteAccessSessionOps]), listening for login-result and
 * incoming-message push events ([SessionEventStreaming]), and contact lookups plus path reset for
 * the login auto-heal flow ([ContactSessionOps]). Declared as a dedicated interface (matching
 * Swift's `any RemoteAccessSessionOps & SessionEventStreaming & ContactSessionOps` existential)
 * rather than widening [MeshCoreSessionProtocol] itself, the same precedent [AdvertisementSessionOps]/
 * [BinaryProtocolSessionOps] established. [MeshCoreSession] already implements all three
 * interfaces separately, so it automatically satisfies this composite too.
 */
interface RemoteNodeSessionOps : RemoteAccessSessionOps, SessionEventStreaming, ContactSessionOps
