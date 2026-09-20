// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * A single execution of a [TracePathEntity], recording its outcome for the saved path's run
 * history/sparkline. Ported from `TracePathRun.swift`'s `@Model`.
 *
 * [hopsSNR] is stored as a comma-separated string rather than adding a `List<Double>` Room
 * [Converters] type-converter for this one column — no other entity needs one, and a plain string
 * column with join/split at the DTO boundary (see `TracePathDto.kt`) is the simpler option, in the
 * same spirit as [Converters]'s doc favoring the simplest workable column type over new
 * infrastructure for a single caller.
 */
@Entity(
    tableName = "trace_path_runs",
    indices = [Index(value = ["pathID", "date"])],
)
data class TracePathRunEntity(
    @PrimaryKey val id: UUID,
    /** The parent [TracePathEntity.id] — a plain column, not a relationship; see that entity's class doc. */
    val pathID: UUID,
    val date: Instant,
    val success: Boolean,
    /** Round-trip time in milliseconds (0 if failed). */
    val roundTripMs: Int,
    /** Comma-separated SNR values, one per intermediate hop (empty string for a failed run). */
    val hopsSNR: String,
)
