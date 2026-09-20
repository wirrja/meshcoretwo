// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import com.meshcoretwo.protocol.PayloadType
import com.meshcoretwo.protocol.RouteType
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.services.persistence.RxLogDto

/** Whether [RxLogDto] is a direct text message eligible for a "From → To" label. Ported from `RxLogRowView.isDirectTextMessage`. */
val RxLogDto.isDirectTextMessage: Boolean
    get() = (routeType == RouteType.DIRECT || routeType == RouteType.TC_DIRECT) && payloadType == PayloadType.TEXT_MESSAGE

/** Whether [prefix] (a local device's public-key prefix) is the leading bytes of [hashBytes]. */
private fun ByteArray.isPrefixOf(hashBytes: ByteArray): Boolean = size >= hashBytes.size && copyOfRange(0, hashBytes.size).contentEquals(hashBytes)

/**
 * Resolves a path-hop/sender hash to "You" (this device), a known contact's name, or its
 * uppercase hex. [nodeNames] is keyed by lowercase hex (see [RxLogViewModel.buildNodeNameMap]).
 * Ported from `RxLogRowView.resolveHashLabel`.
 */
fun resolveHashLabel(hashBytes: ByteArray, nodeNames: Map<String, String>, localPublicKeyPrefix: ByteArray?): String {
    if (localPublicKeyPrefix?.isPrefixOf(hashBytes) == true) return "You"
    nodeNames[hashBytes.hexString]?.let { return it }
    return hashBytes.hexString.uppercase()
}

/** Public-key prefix hashes for each path hop, chunked by [RxLogDto.pathHashSize]. Ported from `RxLogRowView.hopIdParts`. */
fun RxLogDto.hopIdParts(localPublicKeyPrefix: ByteArray?): List<String> {
    val hashSize = pathHashSize
    val parts = mutableListOf<String>()
    var start = 0
    while (start < pathNodes.size) {
        val end = minOf(start + hashSize, pathNodes.size)
        val chunk = pathNodes.copyOfRange(start, end)
        parts.add(if (localPublicKeyPrefix?.isPrefixOf(chunk) == true) "You" else chunk.hexString.uppercase())
        start = end
    }
    return parts
}

/** Target node hashes for a TRACE packet, as uppercase hex. Ported from `RxLogRowView.traceRouteIdParts`. */
fun RxLogDto.traceRouteIdParts(): List<String> = traceTargetHashes?.map { it.hexString.uppercase() } ?: emptyList()

/** Joins [parts], truncating to first 3 … last 3 when longer than 6. Ported from `RxLogRowView.truncatedJoin`. */
fun truncatedJoin(parts: List<String>, separator: String = " → "): String {
    if (parts.size <= 6) return parts.joinToString(separator)
    val first = parts.take(3).joinToString(separator)
    val last = parts.takeLast(3).joinToString(separator)
    return "$first $separator … $separator $last"
}

/** One-line path summary (collapsed row). Ported from `RxLogRowView.pathDisplayString`. */
fun RxLogDto.pathDisplayString(localPublicKeyPrefix: ByteArray?): String {
    if (pathNodes.isEmpty()) return "Direct"
    if (payloadType == PayloadType.TRACE) {
        val routeParts = traceRouteIdParts()
        if (routeParts.isNotEmpty()) return truncatedJoin(routeParts)
        return "$hopCount ${if (hopCount == 1) "hop" else "hops"}"
    }
    return truncatedJoin(hopIdParts(localPublicKeyPrefix))
}

/** Full hop list with count (expanded row). Ported from `RxLogRowView.pathDetailString`. */
fun RxLogDto.pathDetailString(localPublicKeyPrefix: ByteArray?): String {
    if (pathNodes.isEmpty()) return "Direct"
    val parts = hopIdParts(localPublicKeyPrefix)
    return "$hopCount ${if (hopCount == 1) "hop" else "hops"} [${parts.joinToString(", ")}]"
}

/** Uppercase, space-separated hex dump. Ported from `RawPayloadSection.hexString`. */
fun ByteArray.toDisplayHex(): String = joinToString(" ") { "%02X".format(it) }
