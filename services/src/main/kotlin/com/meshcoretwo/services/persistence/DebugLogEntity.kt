// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * A single persisted debug log line. Ported from `DebugLogEntry.swift`'s `@Model` (SwiftData) to
 * a Room `@Entity`. Unlike most entities in this module, there is no `radioID` partition key —
 * Swift's model has none either: the log spans the app's whole lifetime, not one device.
 * [levelRawValue] mirrors [DebugLogLevel]'s `rawValue` (see [Converters]'s doc for why enums are
 * stored as raw `Int` rather than via a Room converter).
 */
@Entity(
    tableName = "debug_log_entries",
    indices = [Index(value = ["timestamp"])],
)
data class DebugLogEntity(
    @PrimaryKey val id: UUID,
    val timestamp: Instant,
    val levelRawValue: Int,
    val subsystem: String,
    val category: String,
    val message: String,
)
