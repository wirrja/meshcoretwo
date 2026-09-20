// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import java.security.MessageDigest

private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

/** Errors that can occur when resolving a [Destination]. */
sealed class DestinationError(message: String) : Exception(message) {
    /** The hex string is not valid hex. */
    class InvalidHexString(val hex: String) : DestinationError("Invalid hex string: $hex")

    /** The destination data is shorter than the requested length. */
    class InsufficientLength(val expected: Int, val actual: Int) :
        DestinationError("Expected at least $expected bytes, got $actual")
}

/**
 * Defines the destination for a message or command in the mesh network.
 *
 * A destination can be specified using raw bytes, a hex string, or a [MeshContact].
 */
sealed class Destination {
    /** Destination specified as raw bytes. */
    class Data(val data: ByteArray) : Destination() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Data && data.contentEquals(other.data))

        override fun hashCode(): Int = data.contentHashCode()
    }

    /** Destination specified as a hex string. */
    data class HexString(val hex: String) : Destination()

    /** Destination specified as a mesh contact. */
    data class Contact(val contact: MeshContact) : Destination()

    /**
     * Returns the public key prefix of the specified length for this destination.
     *
     * @param prefixLength The number of bytes to extract from the start of the public key. Defaults to 6.
     * @throws DestinationError.InsufficientLength if the data is shorter than requested.
     * @throws DestinationError.InvalidHexString if the hex string cannot be parsed.
     */
    fun publicKey(prefixLength: Int = 6): ByteArray = when (this) {
        is Data -> {
            if (data.size < prefixLength) throw DestinationError.InsufficientLength(prefixLength, data.size)
            data.copyOfRange(0, prefixLength)
        }

        is HexString -> {
            val decoded = hex.decodeHex() ?: throw DestinationError.InvalidHexString(hex)
            if (decoded.size < prefixLength) {
                throw DestinationError.InsufficientLength(prefixLength, decoded.size)
            }
            decoded.copyOfRange(0, prefixLength)
        }

        is Contact -> {
            if (contact.publicKey.size < prefixLength) {
                throw DestinationError.InsufficientLength(prefixLength, contact.publicKey.size)
            }
            contact.publicKey.copyOfRange(0, prefixLength)
        }
    }

    /**
     * Returns the full 32-byte public key for this destination.
     *
     * @throws DestinationError if the destination data is invalid or too short.
     */
    fun fullPublicKey(): ByteArray = publicKey(32)
}

/** Defines the scope for flood routing in the mesh network. */
sealed class FloodScope {
    /** Flood routing is disabled. */
    object Disabled : FloodScope()

    /** Scope based on a channel name. */
    data class ChannelName(val name: String) : FloodScope()

    /** Scope based on a raw 16-byte key. */
    class RawKey(val key: ByteArray) : FloodScope() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is RawKey && key.contentEquals(other.key))

        override fun hashCode(): Int = key.contentHashCode()
    }

    /**
     * Scope based on a public region name. The key is derived as `SHA256("#" + name).prefix(16)`,
     * matching the firmware convention for public hashtag regions.
     *
     * Region names from the region-list request can be passed directly (e.g., `"Europe"`). The
     * `#` prefix is added automatically if not present.
     */
    data class Region(val name: String) : FloodScope()

    /** Generates a 16-byte scope key from the current scope. */
    fun scopeKey(): ByteArray = when (this) {
        is Disabled -> ByteArray(16)
        is ChannelName -> sha256(name.toByteArray(Charsets.UTF_8)).copyOf(16)
        is RawKey -> key.paddedOrTruncated(16)
        is Region -> {
            val prefixed = if (name.startsWith("#")) name else "#$name"
            sha256(prefixed.toByteArray(Charsets.UTF_8)).copyOf(16)
        }
    }
}

/** Defines the secret used for channel encryption. */
sealed class ChannelSecret {
    /** An explicit 16-byte secret. */
    class Explicit(val data: ByteArray) : ChannelSecret() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Explicit && data.contentEquals(other.data))

        override fun hashCode(): Int = data.contentHashCode()
    }

    /** A secret derived from the channel name. */
    object DeriveFromName : ChannelSecret()

    /** Generates the secret data for the given channel name. */
    fun secretData(channelName: String): ByteArray = when (this) {
        is Explicit -> data.paddedOrTruncated(16)
        is DeriveFromName -> sha256(channelName.toByteArray(Charsets.UTF_8)).copyOf(16)
    }
}
