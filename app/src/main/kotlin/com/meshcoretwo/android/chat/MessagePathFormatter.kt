// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import com.meshcoretwo.services.persistence.MessageDto

/**
 * Formats a message's routing path into a display-ready string for the bubble footer chip. A path
 * longer than [MAX_NODES] collapses its middle to a tight ellipsis (`head…tail`) so the endpoints
 * stay visible. Ported from `MessagePathFormatter.swift`.
 */
object MessagePathFormatter {
    /** Maximum hop IDs shown before the middle collapses to an ellipsis. */
    const val MAX_NODES = 4

    /** `"Direct"`, `"Flood"`, the hop IDs (`"A3,7F,42"`), or a middle-collapsed list (`"A3,7F…B2,C1"`). */
    fun format(message: MessageDto): String {
        if (message.isDirectRouted) return "Direct"

        // Destination marker: a single 0xFF byte indicates a direct message.
        val pathNodes = message.pathNodes
        if (pathNodes != null && pathNodes.size == 1 && pathNodes[0] == 0xFF.toByte()) return "Direct"

        val nodes = message.pathHops.map { it.hex }
        if (nodes.isEmpty()) return "Flood"
        return truncated(nodes)
    }

    private fun truncated(nodes: List<String>): String {
        if (nodes.size <= MAX_NODES) return nodes.joinToString(",")
        val head = nodes.take(2).joinToString(",")
        val tail = nodes.takeLast(2).joinToString(",")
        return "$head…$tail"
    }
}
