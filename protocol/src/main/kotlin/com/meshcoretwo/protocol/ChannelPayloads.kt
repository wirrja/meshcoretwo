// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * The device's persisted default flood scope.
 *
 * Firmware v11+ (MeshCore v1.15.0+). The default scope is applied automatically when sending
 * flood packets if no session-scoped key has been set.
 */
class DefaultFloodScope(
    /** Display name (up to 30 UTF-8 bytes on-device). */
    val name: String,
    /** The 16-byte scope key. */
    val scopeKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is DefaultFloodScope && name == other.name && scopeKey.contentEquals(other.scopeKey))

    override fun hashCode(): Int = 31 * name.hashCode() + scopeKey.contentHashCode()
}

/**
 * Configuration information for a broadcast channel.
 *
 * Channels allow broadcast messaging to all nodes sharing the same channel name and secret key.
 */
class ChannelInfo(
    /** The index of the channel configuration. */
    val index: UByte,
    /** The human-readable name of the channel. */
    val name: String,
    /** The secret key data used for channel communication. */
    val secret: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ChannelInfo && index == other.index && name == other.name && secret.contentEquals(other.secret))

    override fun hashCode(): Int {
        var result = index.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + secret.contentHashCode()
        return result
    }
}
