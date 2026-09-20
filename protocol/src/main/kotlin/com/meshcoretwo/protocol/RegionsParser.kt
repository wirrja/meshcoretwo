// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

// MARK: - Regions Parser

/**
 * Thrown by [RegionsParser.parse] on malformed input.
 *
 * A minimal stand-in for Swift's `MeshCoreError.parseError(String)` (Session/SessionConfiguration.swift)
 * — that type is a broad, session-wide error enum not yet ported (Session layer). Once it lands,
 * this should fold into a `MeshCoreError.ParseError` case instead of staying a separate exception.
 */
class RegionsParseException(message: String) : Exception(message)

object RegionsParser {
    /**
     * Parses a region query response from binary response data.
     *
     * The response data layout (after the binary response parser strips the frame header):
     * - Offset 0-3 (4 bytes): Repeater timestamp (UInt32 LE) — skipped
     * - Offset 4+ (variable): Comma-separated UTF-8 region names
     *
     * The sender timestamp (4 bytes) is already consumed as the tag by the binary response parser.
     *
     * @param responseData The `data` field from a [MeshEvent.BinaryResponse].
     * @return A list of region name strings. Empty if no regions are configured.
     * @throws RegionsParseException if the response is too short or not valid UTF-8.
     */
    fun parse(responseData: ByteArray): List<String> {
        if (responseData.size < 4) {
            throw RegionsParseException("Region response too short (${responseData.size} bytes)")
        }
        val regionData = responseData.copyOfRange(4, responseData.size)
        val regionString = regionData.decodeUtf8Strict() ?: throw RegionsParseException("Invalid UTF-8 in region response")
        val trimmed = regionString.trimControlCharacters()
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "*" }
    }
}
