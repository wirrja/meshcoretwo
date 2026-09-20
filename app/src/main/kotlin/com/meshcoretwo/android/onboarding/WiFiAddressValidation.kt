// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.onboarding

/**
 * Host/port validation for the WiFi connection dialog. 1:1 port of `WiFiAddressFields.swift`'s
 * `static` validation helpers (`nonisolated` in Swift since they don't touch view state — no
 * equivalent distinction needed in Kotlin).
 */
object WiFiAddressValidation {
    private const val HOSTNAME_MAX_LENGTH = 253
    private const val HOSTNAME_LABEL_MAX_LENGTH = 63
    private val HOSTNAME_LABEL_CHARS = (('a'..'z') + ('A'..'Z') + ('0'..'9') + '-').toSet()

    fun isValidHost(raw: String): Boolean {
        val host = normalizedHost(raw)
        if (host.isEmpty()) return false
        return if (looksLikeIPv4(host)) isValidIPAddress(host) else isValidHostname(host)
    }

    fun normalizedHost(raw: String): String = raw.trim()

    fun isValidIPAddress(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }

    fun isValidPort(port: String): Boolean {
        val num = port.toIntOrNull() ?: return false
        return num in 1..65535
    }

    private fun looksLikeIPv4(host: String): Boolean {
        val parts = host.split(".")
        if (parts.isEmpty()) return false
        return parts.all { part -> part.isNotEmpty() && part.all { it in '0'..'9' } }
    }

    private fun isValidHostname(host: String): Boolean {
        val candidate = if (host.endsWith(".")) host.dropLast(1) else host
        if (candidate.length !in 1..HOSTNAME_MAX_LENGTH) return false
        val labels = candidate.split(".")
        return labels.isNotEmpty() && labels.all(::isValidHostnameLabel)
    }

    private fun isValidHostnameLabel(label: String): Boolean {
        if (label.length !in 1..HOSTNAME_LABEL_MAX_LENGTH) return false
        if (!label.all { it in HOSTNAME_LABEL_CHARS }) return false
        return label.first() != '-' && label.last() != '-'
    }
}
