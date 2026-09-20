// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * A type-safe event filter for MeshCore events.
 *
 * Provides a convenient way to create predicates for filtering [MeshEvent] values. Use the
 * companion factory functions/properties for common filtering patterns, or create custom
 * filters using the constructor.
 *
 * Use with [EventDispatcher.subscribe]: `dispatcher.subscribe(filter::matches)`.
 */
class EventFilter(private val predicate: (MeshEvent) -> Boolean) {
    /** Tests whether an event matches this filter. */
    fun matches(event: MeshEvent): Boolean = predicate(event)

    // MARK: - Combinators

    /** Creates a filter that matches if either this filter or [other] matches. */
    infix fun or(other: EventFilter): EventFilter = EventFilter { matches(it) || other.matches(it) }

    /** Creates a filter that matches only if both this filter and [other] match. */
    infix fun and(other: EventFilter): EventFilter = EventFilter { matches(it) && other.matches(it) }

    /** A filter that matches events this filter does not match. */
    val negated: EventFilter
        get() = EventFilter { !matches(it) }

    companion object {
        // MARK: - Acknowledgement Filters

        /**
         * Matches any acknowledgement event regardless of code.
         *
         * Use for persistent listeners that must see every incoming ACK. Because the filter is
         * evaluated at dispatch time, unrelated events never enter the subscription's bounded
         * buffer and cannot evict an ACK.
         */
        val anyAcknowledgement: EventFilter
            get() = EventFilter { it is MeshEvent.Acknowledgement }

        /** Creates a filter for acknowledgement events with a specific [code]. */
        fun acknowledgement(code: ByteArray): EventFilter = EventFilter {
            it is MeshEvent.Acknowledgement && it.code.contentEquals(code)
        }

        // MARK: - Message Filters

        /** Matches any contact message receipt regardless of sender prefix. */
        val anyContactMessage: EventFilter
            get() = EventFilter { it is MeshEvent.ContactMessageReceived }

        /** Matches any channel message receipt. */
        val anyChannelMessage: EventFilter
            get() = EventFilter { it is MeshEvent.ChannelMessageReceived }

        /**
         * Creates a filter for contact messages from a specific sender.
         *
         * @param publicKeyPrefix The sender's public key prefix to match. The event's sender
         *   prefix must start with this data.
         */
        fun contactMessage(publicKeyPrefix: ByteArray): EventFilter = EventFilter {
            it is MeshEvent.ContactMessageReceived && it.message.senderPublicKeyPrefix.startsWith(publicKeyPrefix)
        }

        /** Creates a filter for channel messages on a specific [channel] index. */
        fun channelMessage(channel: UByte): EventFilter = EventFilter {
            it is MeshEvent.ChannelMessageReceived && it.message.channelIndex == channel
        }

        // MARK: - Response Filters

        /**
         * Creates a filter for status responses from a specific node.
         *
         * @param publicKeyPrefix The responder's public key prefix to match. The response's
         *   public key prefix must start with this data.
         */
        fun statusResponse(publicKeyPrefix: ByteArray): EventFilter = EventFilter {
            it is MeshEvent.StatusResponseEvent && it.response.publicKeyPrefix.startsWith(publicKeyPrefix)
        }

        /** Creates a filter for telemetry responses from a specific node ([publicKeyPrefix]). */
        fun telemetryResponse(publicKeyPrefix: ByteArray): EventFilter = EventFilter {
            it is MeshEvent.TelemetryResponseEvent && it.response.publicKeyPrefix.startsWith(publicKeyPrefix)
        }

        // MARK: - Network Event Filters

        /** Matches any rxLogData event. */
        val rxLogData: EventFilter
            get() = EventFilter { it is MeshEvent.RxLogData }

        /** Matches any advertisement regardless of sender prefix. */
        val anyAdvertisement: EventFilter
            get() = EventFilter { it is MeshEvent.Advertisement }

        /** Creates a filter for advertisement events from a specific node ([publicKeyPrefix]). */
        fun advertisement(publicKeyPrefix: ByteArray): EventFilter = EventFilter {
            it is MeshEvent.Advertisement && it.publicKey.startsWith(publicKeyPrefix)
        }

        /** Creates a filter for path update events for a specific node ([publicKeyPrefix]). */
        fun pathUpdate(publicKeyPrefix: ByteArray): EventFilter = EventFilter {
            it is MeshEvent.PathUpdate && it.publicKey.startsWith(publicKeyPrefix)
        }

        // MARK: - Event Type Filters

        /**
         * Creates a filter that matches events by type using a custom [matcher].
         *
         * Useful for filtering by event type when the associated values don't matter.
         */
        fun eventType(matcher: (MeshEvent) -> Boolean): EventFilter = EventFilter(matcher)

        /** Matches any [MeshEvent.Ok] response regardless of value. */
        val ok: EventFilter
            get() = EventFilter { it is MeshEvent.Ok }

        /** Matches any [MeshEvent.Error] response regardless of code. */
        val error: EventFilter
            get() = EventFilter { it is MeshEvent.Error }

        /** Matches [MeshEvent.NoMoreMessages] events. */
        val noMoreMessages: EventFilter
            get() = EventFilter { it is MeshEvent.NoMoreMessages }

        /** Matches [MeshEvent.MessagesWaiting] events. */
        val messagesWaiting: EventFilter
            get() = EventFilter { it is MeshEvent.MessagesWaiting }

        // MARK: - Login Filters

        /** Matches any successful login response. */
        val anyLoginSuccess: EventFilter
            get() = EventFilter { it is MeshEvent.LoginSuccess }

        /** Matches any failed login response. */
        val anyLoginFailed: EventFilter
            get() = EventFilter { it is MeshEvent.LoginFailed }
    }
}
