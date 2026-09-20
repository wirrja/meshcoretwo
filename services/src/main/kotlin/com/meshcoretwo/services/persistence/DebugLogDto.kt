// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import java.time.Instant
import java.util.UUID

/** An immutable snapshot of a persisted (or about-to-be-persisted) debug log line. Ported from `DebugLogEntryDTO`. */
data class DebugLogEntryDto(
    val id: UUID = UUID.randomUUID(),
    val timestamp: Instant = Instant.now(),
    val level: DebugLogLevel,
    val subsystem: String,
    val category: String,
    val message: String,
)

/** Maps a persisted row to the immutable snapshot services consume, narrowing a corrupt/out-of-range raw value with the same fallback Swift's `DebugLogEntryDTO.init(from:)` uses. */
fun DebugLogEntity.toDto(): DebugLogEntryDto = DebugLogEntryDto(
    id = id,
    timestamp = timestamp,
    level = DebugLogLevel.fromRawValue(levelRawValue) ?: DebugLogLevel.INFO,
    subsystem = subsystem,
    category = category,
    message = message,
)

/** Maps a domain snapshot to the Room row shape. */
fun DebugLogEntryDto.toEntity(): DebugLogEntity = DebugLogEntity(
    id = id,
    timestamp = timestamp,
    levelRawValue = level.rawValue,
    subsystem = subsystem,
    category = category,
    message = message,
)
