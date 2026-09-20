// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Defines the interface for MeshCore device communication.
 *
 * This interface abstracts the core mesh communication operations used by consuming service
 * layers, allowing them to be tested without a real device connection. It is a composition of
 * the session role interfaces in this module; consumers that touch only one or two session
 * capabilities should declare those roles directly (for example [ChannelSessionOps]) so their
 * signatures reveal what they use, while broad consumers and conformers keep this umbrella.
 */
interface MeshCoreSessionProtocol :
    SessionEventStreaming,
    MessagingSessionOps,
    ContactSessionOps,
    ChannelSessionOps,
    MessageFetchSessionOps
