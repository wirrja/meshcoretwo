// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.messages

import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.persistence.MessageDto

/**
 * Incoming-message notifications broadcast by [IncomingMessageService]. Ported from the two
 * message cases of `SyncDataEvent.swift`, trimmed to what this service itself emits —
 * `contactsChanged`/`conversationsChanged`/`roomMessageReceived`/`reactionReceived` belong to a
 * broader cross-service event bus (Swift's `SyncCoordinator.dataEvents()`) this port doesn't
 * have yet; adding those cases now, with no other service constructing them, would be
 * unconsumed infrastructure.
 */
sealed class IncomingMessageEvent {
    /** An incoming direct message was persisted for a known contact (orphan DMs with no resolved contact don't emit this). */
    data class DirectMessageReceived(val message: MessageDto, val contact: ContactDto) : IncomingMessageEvent()

    /** An incoming channel message was persisted. */
    data class ChannelMessageReceived(val message: MessageDto, val channelIndex: UByte) : IncomingMessageEvent()
}
