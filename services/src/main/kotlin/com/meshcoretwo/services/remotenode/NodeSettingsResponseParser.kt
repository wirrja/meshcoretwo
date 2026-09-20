// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Pure parsing of repeater and room CLI responses for the node settings screens: clock parsing,
 * owner-info wire mapping, and success/error classification. Builds on [CLIResponse] and holds no
 * state, so every function is directly unit-testable. Ported from `NodeSettingsResponseParser.swift`.
 */
object NodeSettingsResponseParser {
    // MARK: - Late Reply Recovery

    /**
     * Attribution by elimination for an out-of-band CLI reply. One command is in flight per node,
     * so a reply that no pending command claimed can only answer a command that timed out
     * unanswered. Returns the parsed value when the reply parses to a query-specific shape for
     * exactly one of the given queries; several matches are ambiguous, so nothing is returned.
     */
    fun recoveredResponse(response: String, unansweredQueries: Set<String>): Pair<String, CLIResponse>? {
        val matches = unansweredQueries.mapNotNull { query ->
            if (!CLIResponse.isStructuredQuery(query)) return@mapNotNull null
            when (val value = CLIResponse.parse(response, query)) {
                // Query-independent shapes match every query equally; not attributable.
                is CLIResponse.Raw, CLIResponse.Ok, is CLIResponse.Error, is CLIResponse.UnknownCommand, is CLIResponse.Version -> null
                else -> query to value
            }
        }
        return matches.singleOrNull()
    }

    // MARK: - Device Clock

    private val CLOCK_RESPONSE_REGEX = Regex("""(\d{1,2}:\d{2}) - (\d{1,2}/\d{1,2}/\d{4}) UTC""")
    private val CLOCK_RESPONSE_FORMATTER = DateTimeFormatter.ofPattern("HH:mm d/M/yyyy")

    /** The UTC clock shape in firmware text, including inside an `OK - clock set:` reply. */
    fun clockResponseText(text: String): String? = CLOCK_RESPONSE_REGEX.find(text)?.value

    /**
     * Parses a firmware clock response like "06:40 - 18/4/2025 UTC" into an [Instant]. Returns
     * `null` when the text doesn't carry the expected UTC clock shape.
     */
    fun utcDate(fromClockResponse: String): Instant? {
        val match = CLOCK_RESPONSE_REGEX.find(fromClockResponse) ?: return null
        val (time, date) = match.destructured
        return try {
            LocalDateTime.parse("$time $date", CLOCK_RESPONSE_FORMATTER).toInstant(ZoneOffset.UTC)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** Seconds the node's clock is ahead of [now]. Null when [response] has no clock shape. */
    fun clockDrift(response: String, relativeTo: Instant): Long? {
        val nodeDate = utcDate(response) ?: return null
        return Duration.between(relativeTo, nodeDate).seconds
    }

    // MARK: - Clock Sync

    /** Outcome of a `clock sync` command response. */
    sealed class ClockSyncOutcome {
        object Synced : ClockSyncOutcome()

        /** Firmware refused the sync because its clock is ahead of the phone's. */
        object ClockAhead : ClockSyncOutcome()

        /** Firmware reported an error; [message] is the response with the "ERR: " prefix stripped and may be empty. */
        data class Failed(val message: String) : ClockSyncOutcome()
        object Unexpected : ClockSyncOutcome()
    }

    private const val CLOCK_AHEAD_ERROR_FRAGMENT = "clock cannot go backwards"
    private const val CLI_ERROR_PREFIX = "ERR: "

    /** Classifies a `clock sync` response into a typed outcome. */
    fun classifyClockSyncResponse(response: String): ClockSyncOutcome = when (val parsed = CLIResponse.parse(response)) {
        is CLIResponse.Ok -> ClockSyncOutcome.Synced
        is CLIResponse.Error -> {
            if (parsed.text.contains(CLOCK_AHEAD_ERROR_FRAGMENT)) {
                ClockSyncOutcome.ClockAhead
            } else {
                ClockSyncOutcome.Failed(parsed.text.replace(CLI_ERROR_PREFIX, ""))
            }
        }
        else -> ClockSyncOutcome.Unexpected
    }

    // MARK: - Password

    /** Firmware echoes "password now: {pw}" on success instead of "OK". */
    private const val PASSWORD_CHANGED_PREFIX = "password now:"

    /** Whether a `password` command response indicates the change was accepted. */
    fun isPasswordChangeSuccessful(response: String): Boolean = when (val parsed = CLIResponse.parse(response)) {
        is CLIResponse.Ok -> true
        is CLIResponse.Raw -> parsed.text.startsWith(PASSWORD_CHANGED_PREFIX)
        else -> false
    }

    // MARK: - Owner Info

    /** Firmware stores owner info as a single line with "|" separating rows. */
    private const val OWNER_INFO_WIRE_SEPARATOR = "|"
    private const val OWNER_INFO_DISPLAY_SEPARATOR = "\n"

    /** Maps the wire form ("|"-separated) to the multi-line display form. */
    fun displayOwnerInfo(fromWire: String): String = fromWire.replace(OWNER_INFO_WIRE_SEPARATOR, OWNER_INFO_DISPLAY_SEPARATOR)

    /** Maps the multi-line display form back to the "|"-separated wire form. */
    fun wireOwnerInfo(fromDisplay: String): String = fromDisplay.replace(OWNER_INFO_DISPLAY_SEPARATOR, OWNER_INFO_WIRE_SEPARATOR)
}
