// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import java.time.Instant
import java.util.UUID

/**
 * An immutable snapshot of a saved trace path plus its run history. Ported from
 * `SavedTracePathDTO`, trimmed of its `pathHashBytes` computed property — a `ByteArray` already
 * serves both the raw-bytes and `[UInt8]`-view roles Swift needed two representations for.
 */
data class TracePathDto(
    val id: UUID,
    val radioID: UUID,
    val name: String,
    val pathBytes: ByteArray,
    val hashSize: Int,
    val createdDate: Instant,
    val runs: List<TracePathRunDto>,
) {
    /** Number of runs for this path. */
    val runCount: Int get() = runs.size

    /** Most recent run date. */
    val lastRunDate: Instant? get() = runs.maxByOrNull { it.date }?.date

    /** Average round-trip time of successful runs. */
    val averageRoundTripMs: Int?
        get() {
            val successful = runs.filter { it.success }
            if (successful.isEmpty()) return null
            return successful.sumOf { it.roundTripMs } / successful.size
        }

    /** Success rate as a percentage (0-100). An empty history reports 100, matching Swift's `guard !runs.isEmpty else { return 100 }`. */
    val successRate: Int
        get() {
            if (runs.isEmpty()) return 100
            return (runs.count { it.success } * 100) / runs.size
        }

    /** Recent RTT values for a sparkline: up to the 10 most recent successful runs, oldest first. */
    val recentRTTs: List<Int>
        get() = runs.filter { it.success }.sortedByDescending { it.date }.take(10).reversed().map { it.roundTripMs }

    // ByteArray fields have reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TracePathDto) return false
        return id == other.id &&
            radioID == other.radioID &&
            name == other.name &&
            pathBytes.contentEquals(other.pathBytes) &&
            hashSize == other.hashSize &&
            createdDate == other.createdDate &&
            runs == other.runs
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + radioID.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + pathBytes.contentHashCode()
        result = 31 * result + hashSize
        result = 31 * result + createdDate.hashCode()
        result = 31 * result + runs.hashCode()
        return result
    }
}

/** A single logged run of a [TracePathDto]. Ported from `TracePathRunDTO`. */
data class TracePathRunDto(
    val id: UUID,
    val date: Instant,
    val success: Boolean,
    val roundTripMs: Int,
    val hopsSNR: List<Double>,
)

/** Maps a persisted run row to the immutable snapshot services consume. */
fun TracePathRunEntity.toDto(): TracePathRunDto = TracePathRunDto(
    id = id,
    date = date,
    success = success,
    roundTripMs = roundTripMs,
    hopsSNR = if (hopsSNR.isEmpty()) emptyList() else hopsSNR.split(",").map { it.toDouble() },
)

/** Maps a domain run snapshot to its Room row shape under the given parent path id. */
fun TracePathRunDto.toEntity(pathID: UUID): TracePathRunEntity = TracePathRunEntity(
    id = id,
    pathID = pathID,
    date = date,
    success = success,
    roundTripMs = roundTripMs,
    hopsSNR = hopsSNR.joinToString(","),
)

/** Maps a persisted path row plus its already-fetched runs to the immutable snapshot services consume. */
fun TracePathEntity.toDto(runs: List<TracePathRunDto>): TracePathDto = TracePathDto(
    id = id,
    radioID = radioID,
    name = name,
    pathBytes = pathBytes,
    hashSize = hashSize,
    createdDate = createdDate,
    runs = runs,
)

/** Maps a domain path snapshot to its Room row shape (runs live in their own table — see [TracePathRunEntity]). */
fun TracePathDto.toEntity(): TracePathEntity = TracePathEntity(
    id = id,
    radioID = radioID,
    name = name,
    pathBytes = pathBytes,
    hashSize = hashSize,
    createdDate = createdDate,
)
