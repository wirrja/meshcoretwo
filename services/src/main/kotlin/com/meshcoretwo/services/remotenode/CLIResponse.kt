// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

/**
 * Parsed CLI response from a repeater/room server. Ported from `CLIResponse.swift`. Response
 * correlation itself is handled by the caller ([RemoteNodeService]'s pending-request tracking),
 * not by this type.
 */
sealed class CLIResponse {
    object Ok : CLIResponse()
    data class Error(val text: String) : CLIResponse()

    /** Specific case for "Error: unknown command", for defensive handling. */
    data class UnknownCommand(val text: String) : CLIResponse()
    data class Version(val text: String) : CLIResponse()
    data class DeviceTime(val text: String) : CLIResponse()
    data class Name(val text: String) : CLIResponse()
    data class Radio(val frequency: Double, val bandwidth: Double, val spreadingFactor: Int, val codingRate: Int) : CLIResponse()
    data class TxPower(val dbm: Int) : CLIResponse()
    data class RepeatMode(val enabled: Boolean) : CLIResponse()
    data class AdvertInterval(val minutes: Int) : CLIResponse()

    /** Value is in hours, not minutes. */
    data class FloodAdvertInterval(val hours: Int) : CLIResponse()
    data class FloodMax(val hops: Int) : CLIResponse()
    data class Latitude(val degrees: Double) : CLIResponse()
    data class Longitude(val degrees: Double) : CLIResponse()
    data class OwnerInfo(val text: String) : CLIResponse()
    data class Raw(val text: String) : CLIResponse()

    companion object {
        /**
         * Canonical query strings. [parse]'s query hints and [STRUCTURED_QUERIES] both build on
         * these so a new query can't join one and drift from the other.
         */
        private const val VERSION_QUERY = "ver"
        private const val NAME_QUERY = "get name"
        private const val OWNER_INFO_QUERY = "get owner.info"
        private const val CLOCK_QUERY = "clock"
        private const val RADIO_QUERY = "get radio"
        private const val TX_POWER_QUERY = "get tx"
        private const val REPEAT_MODE_QUERY = "get repeat"
        private const val ADVERT_INTERVAL_QUERY = "get advert.interval"
        private const val FLOOD_ADVERT_INTERVAL_QUERY = "get flood.advert.interval"
        private const val FLOOD_MAX_QUERY = "get flood.max"
        private const val LATITUDE_QUERY = "get lat"
        private const val LONGITUDE_QUERY = "get lon"

        /**
         * Queries whose replies have a machine-checkable shape. Free-form gets and set/action
         * commands are absent because their success replies are arbitrary text: firmware answers
         * `password` with "password now:", not "OK", and letsmesh builds answer `ver` with
         * "1.11.0-letsmesh.net-dev-... (Build: ...)" that no shape check covers.
         */
        private val STRUCTURED_QUERIES = setOf(
            RADIO_QUERY, TX_POWER_QUERY, REPEAT_MODE_QUERY, ADVERT_INTERVAL_QUERY,
            FLOOD_ADVERT_INTERVAL_QUERY, FLOOD_MAX_QUERY, LATITUDE_QUERY, LONGITUDE_QUERY, CLOCK_QUERY,
        )

        private val MAX_TX_POWER_REGEX = Regex("""max=(-?\d+)""")
        private val LEADING_TX_POWER_REGEX = Regex("""^(-?\d+)(?:dBm|\s|$)""")
        private val ECHO_PREFIX_REGEX = Regex("""^[0-9A-F]{2}\|$""")

        /** Parses a CLI response text into a structured type. */
        fun parse(text: String, query: String? = null): CLIResponse {
            var trimmed = text.trim()

            // Strip MeshCore CLI prompt prefix if present — firmware prepends "> " to all CLI
            // command responses.
            if (trimmed.startsWith("> ")) {
                trimmed = trimmed.substring(2)
            } else if (trimmed == ">") {
                trimmed = ""
            }

            // Success responses: "OK" or "OK - clock set: ..." etc.
            if (trimmed == "OK" || trimmed.startsWith("OK - ")) return Ok

            if (trimmed.lowercase().startsWith("error") || trimmed.startsWith("ERR:")) {
                return if (trimmed.lowercase().contains("unknown command")) UnknownCommand(trimmed) else Error(trimmed)
            }

            // Firmware version: "MeshCore v1.10.0 (2025-04-18)" or "v1.11.0 (2025-04-18)". Some
            // firmware builds omit the "MeshCore " prefix.
            if (trimmed.startsWith("MeshCore v") || (trimmed.startsWith("v") && trimmed.contains("("))) return Version(trimmed)

            // Use the query hint to match version replies with no standard prefix.
            if (query == VERSION_QUERY) return Version(trimmed)

            // Freeform text fields: any remaining text is the value.
            if (query == NAME_QUERY) return Name(trimmed)
            if (query == OWNER_INFO_QUERY) return OwnerInfo(trimmed)

            // Clock response: "06:40 - 18/4/2025 UTC". Gated on the query because the ":" + "/"
            // shape also appears in names and owner info like "Contact: KD7ABC / 145.230".
            if (query == CLOCK_QUERY && (trimmed.contains("UTC") || (trimmed.contains(":") && trimmed.contains("/")))) {
                return DeviceTime(trimmed)
            }

            // Radio params: "915.000,250.0,10,5" (freq,bw,sf,cr). Gated on the query to
            // disambiguate from other comma-separated values.
            if (query == RADIO_QUERY) {
                val parts = trimmed.split(",").map { it.trim() }
                if (parts.size >= 4) {
                    val freq = parts[0].toDoubleOrNull()
                    val bandwidth = parts[1].toDoubleOrNull()
                    val spreadingFactor = parts[2].toIntOrNull()
                    val codingRate = parts[3].toIntOrNull()
                    if (freq != null && bandwidth != null && spreadingFactor != null && codingRate != null) {
                        return Radio(freq, bandwidth, spreadingFactor, codingRate)
                    }
                }
            }

            // TX power in dBm, optionally annotated with ZephCore power-control state.
            if (query == TX_POWER_QUERY) {
                parseTxPowerDbm(trimmed)?.let { return TxPower(it) }
            }

            // Repeat mode: "on" or "off".
            if (query == REPEAT_MODE_QUERY) {
                when (trimmed.lowercase()) {
                    "on" -> return RepeatMode(true)
                    "off" -> return RepeatMode(false)
                }
            }

            // Advert interval: integer minutes.
            if (query == ADVERT_INTERVAL_QUERY) trimmed.toIntOrNull()?.let { return AdvertInterval(it) }

            // Flood advert interval: integer hours.
            if (query == FLOOD_ADVERT_INTERVAL_QUERY) trimmed.toIntOrNull()?.let { return FloodAdvertInterval(it) }

            // Flood max: integer hops.
            if (query == FLOOD_MAX_QUERY) trimmed.toIntOrNull()?.let { return FloodMax(it) }

            // Latitude/longitude: decimal degrees.
            if (query == LATITUDE_QUERY) trimmed.toDoubleOrNull()?.let { return Latitude(it) }
            if (query == LONGITUDE_QUERY) trimmed.toDoubleOrNull()?.let { return Longitude(it) }

            return Raw(trimmed)
        }

        /**
         * Reads the configured TX power in dBm from a `get tx` reply. Stock firmware sends a bare
         * integer ("22"); ZephCore appends Adaptive Power Control state ("22dBm (apc=off)"), and
         * while APC is active the leading number is the reduced live power whereas the `max=`
         * ceiling is what `set tx` writes back, so `max=` wins when present.
         */
        private fun parseTxPowerDbm(text: String): Int? {
            MAX_TX_POWER_REGEX.find(text)?.let { return it.groupValues[1].toInt() }
            // Accept only a whole leading integer ("22", "22dBm (apc=off)"); a digit run followed
            // by "." or "," is some other value, such as the leading frequency of a radio CSV,
            // never a power.
            LEADING_TX_POWER_REGEX.find(text)?.let { return it.groupValues[1].toInt() }
            return null
        }

        /** Whether [query]'s reply has a machine-checkable shape. */
        fun isStructuredQuery(query: String): Boolean = STRUCTURED_QUERIES.contains(query)

        /**
         * Whether [response] is plausible for the given pending [query]. Replies to structured
         * gets must parse to their typed case (or an error); everything else matches any text.
         */
        fun isPlausibleResponse(response: String, query: String): Boolean {
            if (!STRUCTURED_QUERIES.contains(query)) return true
            return parse(response, query) !is Raw
        }

        // MARK: - Wire Prefix Echo

        /**
         * Separator of the optional CLI wire prefix. Repeater and room firmware reflect a leading
         * "XX|" from the command back at the start of the reply, giving the otherwise tagless CLI
         * channel a correlation token.
         */
        const val ECHO_PREFIX_SEPARATOR = '|'
        private const val ECHO_PREFIX_LENGTH = 3

        /**
         * Splits an echoed wire prefix off a reply as (prefix, body). Returns `null` when the
         * reply carries none. Only two uppercase hex digits plus the separator qualify, matching
         * the prefixes this app generates, so ordinary reply text can't be mistaken for a prefix.
         */
        fun splitEchoedPrefix(text: String): Pair<String, String>? {
            if (text.length <= ECHO_PREFIX_LENGTH) return null
            val candidate = text.substring(0, ECHO_PREFIX_LENGTH)
            if (!ECHO_PREFIX_REGEX.matches(candidate)) return null
            return candidate to text.substring(ECHO_PREFIX_LENGTH)
        }
    }
}
