// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import java.util.UUID

/** Which conversation a [ConversationViewModel]/[ChatConversationScreen] is showing. */
sealed class ConversationTarget {
    data class Direct(val contactId: UUID) : ConversationTarget()
    data class Channel(val index: UByte) : ConversationTarget()
}
