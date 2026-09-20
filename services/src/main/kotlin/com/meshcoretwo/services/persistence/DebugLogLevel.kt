// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

/** Severity of a persisted debug log entry. Ported from `DebugLogLevel.swift`. */
enum class DebugLogLevel(val rawValue: Int, val label: String) {
    DEBUG(0, "DEBUG"),
    INFO(1, "INFO"),
    NOTICE(2, "NOTICE"),
    WARNING(3, "WARNING"),
    ERROR(4, "ERROR"),
    FAULT(5, "FAULT"),
    ;

    companion object {
        fun fromRawValue(value: Int): DebugLogLevel? = entries.find { it.rawValue == value }
    }
}
