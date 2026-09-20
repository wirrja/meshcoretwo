// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.TypeConverter
import java.time.Instant
import java.util.UUID

/**
 * Room [TypeConverter]s for identifiers that recur across every persistence entity —
 * `radioID: UUID` as partition key on every table per project constraints. [UUID] has no native Room
 * column type, so it is widened to its canonical string form with no precision loss.
 *
 * Kotlin's unsigned types ([UByte]/[UInt]), used throughout the wire protocol, deliberately do
 * **not** get converters here: Room's KSP processor crashes on them
 * (`getValueClassUnderlyingProperty`: `List has more than one element`), a known category of bug
 * with Kotlin inline/value classes. Entities instead store the signed Room-native widening
 * ([Int] for [UByte], [Long] for [UInt] — both lossless) directly, converting to/from the
 * unsigned domain type only in the entity/DTO mapping (see `ContactEntity`/`ContactDto`).
 */
class Converters {
    @TypeConverter
    fun uuidToString(value: UUID): String = value.toString()

    @TypeConverter
    fun stringToUuid(value: String): UUID = UUID.fromString(value)

    @TypeConverter
    fun instantToLong(value: Instant?): Long? = value?.epochSecond

    @TypeConverter
    fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochSecond)
}
