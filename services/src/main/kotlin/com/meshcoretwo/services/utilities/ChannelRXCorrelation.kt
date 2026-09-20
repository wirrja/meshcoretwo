// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.utilities

import com.meshcoretwo.protocol.PayloadType
import com.meshcoretwo.services.messages.DeduplicationKey
import com.meshcoretwo.services.persistence.DecryptStatus
import com.meshcoretwo.services.persistence.RxLogDto

/**
 * Pure matcher over already-decrypted RX-log entries: which logged packets carry *this* channel
 * message. Ported from `ChannelRXCorrelation.swift` — no crypto, no store, no coroutine context,
 * so it's a plain object testable on the JVM.
 *
 * Joining on [DeduplicationKey] rather than `(channelIndex, senderTimestamp)` alone is what makes
 * extra flood copies usable: several RF packets share that pair (a repeater's copy of the same
 * message arriving over a different path), and only the decrypted `"sender: body"` tells them
 * apart from a *different* message that happens to carry the same sender timestamp.
 */
object ChannelRXCorrelation {
    /** Channel RX rows whose decrypted `"sender: body"` hashes to [deduplicationKey], oldest first. */
    fun matching(entries: List<RxLogDto>, deduplicationKey: String?): List<RxLogDto> {
        if (deduplicationKey == null) return emptyList()
        return entries
            .filter { entry -> entryKey(entry) == deduplicationKey }
            .sortedBy { it.receivedAt }
    }

    /** The content-based key [entry]'s decrypted text would produce, or `null` when it can't be keyed. */
    private fun entryKey(entry: RxLogDto): String? {
        if (entry.payloadType != PayloadType.GROUP_TEXT) return null
        if (entry.decryptStatus != DecryptStatus.SUCCESS) return null
        val decodedText = entry.decodedText ?: return null
        val channelIndex = entry.channelIndex ?: return null
        val senderTimestamp = entry.senderTimestamp ?: return null
        val (sender, body) = ChannelMessageFormat.parse(decodedText) ?: return null
        return DeduplicationKey.contentBased(
            contactID = null,
            channelIndex = channelIndex,
            senderNodeName = sender,
            timestamp = senderTimestamp,
            content = body,
        )
    }
}
