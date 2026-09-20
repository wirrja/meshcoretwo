// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import com.meshcoretwo.services.persistence.MessageDto

/**
 * Determines which message actions are available based on message state. Ported 1:1 from
 * `MessageActionAvailability.swift` — the gating table behind the long-press message-actions menu
 * (`MessageActionsSheet.swift`). Extracted as its own class for the same reason Swift gives:
 * testability, independent of any Compose UI.
 *
 * Every field is now consumed by [MessageActionsSheet] — [canShowRepeatDetails]/[canViewPath]
 * gate [MessageDetailsSection]'s expandable row, the last two fields to get one.
 */
data class MessageActionAvailability(
    val canReply: Boolean,
    val canCopy: Boolean,
    val canSendAgain: Boolean,
    val canBlockSender: Boolean,
    val canSendDM: Boolean,
    val canShowRepeatDetails: Boolean,
    val canViewPath: Boolean,
    val canDelete: Boolean,
    /** Whether the actions sheet shows the expandable path disclosure at all. Ported from `showsPathDetail`. */
    val showsPathDetail: Boolean = canViewPath || canShowRepeatDetails,
) {
    constructor(message: MessageDto) : this(
        canReply = !message.isOutgoing,
        canCopy = true,
        canSendAgain = message.isOutgoing,
        canBlockSender = message.hasKnownChannelSender,
        canSendDM = message.hasKnownChannelSender,
        canShowRepeatDetails = canShowRepeatDetailsOf(message),
        canViewPath = canViewPathOf(message),
        canDelete = true,
        showsPathDetail = canViewPathOf(message) || canShowRepeatDetailsOf(message) || message.hasExtraIncomingPaths,
    )
}

private fun canViewPathOf(message: MessageDto) = !message.isOutgoing && message.isFloodRouted && !(message.pathNodes?.isEmpty() ?: true)

private fun canShowRepeatDetailsOf(message: MessageDto) = message.isOutgoing && message.heardRepeats > 0

/** An incoming message the same flood packet reached again over a different path — [MessageDto.heardRepeats] counts those extras for incoming rows. */
val MessageDto.hasExtraIncomingPaths: Boolean get() = !isOutgoing && heardRepeats > 0

/** Ported from `MessageActionAvailability.init`'s inline `hasChannelSender` local. */
private val MessageDto.hasKnownChannelSender: Boolean
    get() = isChannelMessage && !isOutgoing && senderNodeName != null
