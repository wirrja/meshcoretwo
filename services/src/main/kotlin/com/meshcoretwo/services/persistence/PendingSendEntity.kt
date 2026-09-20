// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * A persistent record of an envelope queued for send. Survives process death so
 * [com.meshcoretwo.services.sendqueue.SendQueue] resumes draining after the app restarts. Ported
 * from `PendingSend.swift`'s `@Model` (SwiftData) to a Room `@Entity`.
 *
 * Rows are scoped to a single radio via [radioID] so reconnecting to a different radio does not
 * attempt to send envelopes whose messages live in a different radio's data partition.
 *
 * [sequence] is a per-radio monotonic counter assigned by
 * [com.meshcoretwo.services.persistence.PendingSendStore.insertPendingSendAssigningSequence] at
 * enqueue time, so drain order matches the original enqueue order across process death.
 *
 * As with the other entities in this module, [channelIndex] (`UByte`) and [messageTimestamp]
 * (`UInt`) are stored as [Int]/[Long] — Room's KSP processor cannot handle Kotlin unsigned types
 * as column types (see [Converters]'s doc).
 *
 * [attemptCount] is non-null here (defaulting to `0`), unlike Swift's nullable `Int?` — Swift's
 * `nil` distinguishes a pre-migration row from a fresh one so `PersistenceStore.warmUp()` can
 * purge legacy rows on connect. No app build has ever shipped from this port, so there is no
 * legacy data to distinguish; every row starts current-build.
 */
@Entity(
    tableName = "pending_sends",
    indices = [
        Index(value = ["radioID", "sequence"]),
        Index(value = ["messageID"]),
    ],
)
data class PendingSendEntity(
    @PrimaryKey val id: UUID,
    /** The device this pending send belongs to — partition key, not the volatile BLE address. */
    val radioID: UUID,
    val messageID: UUID,
    /** Discriminator: 0 = DM, 1 = channel. See [PendingSendKind]. */
    val kindRawValue: Int,
    val contactID: UUID?,
    val channelIndex: Int?,
    val isResend: Boolean,
    val messageText: String,
    val messageTimestamp: Long,
    val localNodeName: String?,
    val sequence: Int,
    val enqueuedAt: Instant,
    /** Number of drain attempts progressed past the `hasPendingSend` gate. See [PendingSendDto.attemptCount]. */
    val attemptCount: Int,
)
