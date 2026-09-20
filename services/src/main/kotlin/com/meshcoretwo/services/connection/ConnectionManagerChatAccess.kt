// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import com.meshcoretwo.services.channels.ChannelService
import com.meshcoretwo.services.contacts.ContactService
import com.meshcoretwo.services.messages.IncomingMessageService
import com.meshcoretwo.services.messages.MessageService
import com.meshcoretwo.services.notifications.NotificationService
import com.meshcoretwo.services.reactions.ReactionService
import com.meshcoretwo.services.repeats.HeardRepeatsService

/**
 * Narrow public seam onto the per-connection [com.meshcoretwo.services.ServiceContainer] for the
 * chat list/conversation UI (`app` module) — same pattern as `settingsService`/
 * `connectedDeviceRecord` in [ConnectionManagerRadioSettings.kt]. All six are `null` when
 * disconnected; the chat screens gate on [ConnectionManager.connectionStateEvents] reaching
 * [DeviceConnectionState.READY] before reading them.
 */

/** The active connection's contact directory, or null when disconnected. */
val ConnectionManager.contactService: ContactService?
    get() = services?.contactService

/** The active connection's channel directory, or null when disconnected. */
val ConnectionManager.channelService: ChannelService?
    get() = services?.channelService

/** The active connection's message send/read surface, or null when disconnected. */
val ConnectionManager.messageService: MessageService?
    get() = services?.messageService

/** The active connection's incoming-message event stream, or null when disconnected. */
val ConnectionManager.incomingMessageService: IncomingMessageService?
    get() = services?.incomingMessageService

/** The active connection's notification/active-conversation-tracking surface, or null when disconnected. */
val ConnectionManager.notificationService: NotificationService?
    get() = services?.notificationService

/** The active connection's reaction send/persist surface, or null when disconnected. */
val ConnectionManager.reactionService: ReactionService?
    get() = services?.reactionService

/** The active connection's heard-repeats correlation surface, or null when disconnected. */
val ConnectionManager.heardRepeatsService: HeardRepeatsService?
    get() = services?.heardRepeatsService
