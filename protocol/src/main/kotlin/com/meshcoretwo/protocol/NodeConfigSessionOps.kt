// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Composition of the session roles `NodeConfigService` needs: reading/adding contacts
 * ([ContactSessionOps]) and reading channel slots ([ChannelSessionOps]). Declared as a dedicated
 * interface (same precedent [AdvertisementSessionOps]/[RemoteNodeSessionOps]/
 * [BinaryProtocolSessionOps] each established for their own narrower slice) rather than widening
 * [MeshCoreSessionProtocol] itself or taking the full umbrella, since node-config export/import
 * touches neither messaging nor event streaming.
 */
interface NodeConfigSessionOps : ContactSessionOps, ChannelSessionOps
