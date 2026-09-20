// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * A saved trace-path configuration for re-use — the outbound+return hop sequence, keyed by device.
 * Ported from `SavedTracePath.swift`'s `@Model` (SwiftData) to a Room `@Entity`. Runs are a
 * separate table ([TracePathRunEntity]), following [MessageEntity]/[MessageRepeatEntity]'s
 * precedent of a plain foreign-key column instead of a Room `@Relationship`/cascade delete — see
 * that pair's class docs for why (nothing in this port deletes with cascade semantics via Room
 * itself yet; [TracePathStore.deleteSavedTracePath] deletes both tables explicitly instead).
 */
@Entity(
    tableName = "saved_trace_paths",
    indices = [Index(value = ["radioID"])],
)
data class TracePathEntity(
    @PrimaryKey val id: UUID,
    /** The device this path belongs to — partition key, not the volatile BLE address. */
    val radioID: UUID,
    val name: String,
    /** The full path bytes (outbound + return), one chunk of [hashSize] bytes per hop. */
    val pathBytes: ByteArray,
    /** Bytes per hop hash when the path was saved (1, 2, or 4). */
    val hashSize: Int,
    val createdDate: Instant,
) {
    // ByteArray fields have reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TracePathEntity) return false
        return id == other.id &&
            radioID == other.radioID &&
            name == other.name &&
            pathBytes.contentEquals(other.pathBytes) &&
            hashSize == other.hashSize &&
            createdDate == other.createdDate
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + radioID.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + pathBytes.contentHashCode()
        result = 31 * result + hashSize
        result = 31 * result + createdDate.hashCode()
        return result
    }
}
