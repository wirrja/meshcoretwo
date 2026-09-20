// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import com.meshcoretwo.protocol.decodePathLen
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.persistence.MessageRepeatDto

/**
 * Display-only computed properties [MessageRepeatDto] itself dropped (see that class's doc) —
 * ported here instead, next to their one reader, [RepeatRow]. Mirrors `MessageRepeat.swift`'s
 * `hashSize`/`repeaterHash`/`hopCount`/`repeaterHashFormatted`.
 */
private val MessageRepeatDto.hashSize: Int get() = decodePathLen(pathLength)?.hashSize ?: 1

/** Last repeater's public-key prefix bytes (the node we heard from), or `null` if direct. */
val MessageRepeatDto.repeaterHash: ByteArray?
    get() {
        if (pathNodes.isEmpty()) return null
        val size = hashSize
        if (pathNodes.size < size) return pathNodes
        return pathNodes.copyOfRange(pathNodes.size - size, pathNodes.size)
    }

/** Number of hops in the path (1 = direct from repeater, 2+ = multi-hop). */
val MessageRepeatDto.hopCount: Int
    get() {
        val size = hashSize
        if (size <= 0) return pathNodes.size
        return pathNodes.size / size
    }

/** [repeaterHash] formatted as uppercase hex, e.g. `"31"`/`"31A7"`. */
val MessageRepeatDto.repeaterHashFormatted: String
    get() = repeaterHash?.hexString?.uppercase() ?: "00"
