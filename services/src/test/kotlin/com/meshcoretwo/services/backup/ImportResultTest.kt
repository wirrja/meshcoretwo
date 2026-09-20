// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportResultTest {
    @Test
    fun `starts at zero for every kind and every total`() {
        val result = ImportResult()

        for (kind in BackupModelKind.entries) {
            assertEquals(PerTypeCounts.ZERO, result.counts(kind))
        }
        assertEquals(0, result.totalInserted)
        assertFalse(result.hasRestoredChanges)
    }

    @Test
    fun `record accumulates per kind without affecting other kinds`() {
        val result = ImportResult()

        result.record(BackupModelKind.CONTACTS, inserted = 2, skipped = 1)
        result.record(BackupModelKind.CONTACTS, inserted = 1, dropped = 1)
        result.record(BackupModelKind.CHANNELS, merged = 1)

        assertEquals(PerTypeCounts(inserted = 3, skipped = 1, dropped = 1), result.counts(BackupModelKind.CONTACTS))
        assertEquals(PerTypeCounts(merged = 1), result.counts(BackupModelKind.CHANNELS))
        assertEquals(PerTypeCounts.ZERO, result.counts(BackupModelKind.MESSAGES))
    }

    @Test
    fun `totals sum across every kind`() {
        val result = ImportResult()
        result.record(BackupModelKind.CONTACTS, inserted = 2, merged = 1, skipped = 3, dropped = 1)
        result.record(BackupModelKind.MESSAGES, inserted = 5, merged = 2, skipped = 1, dropped = 0)

        assertEquals(7, result.totalInserted)
        assertEquals(3, result.totalMerged)
        assertEquals(4, result.totalSkipped)
        assertEquals(1, result.totalDropped)
        assertEquals(10, result.totalRestoredRecordCount)
        assertTrue(result.hasRestoredChanges)
    }

    @Test
    fun `userDefaultsRestored alone counts as a restored change`() {
        val result = ImportResult()
        result.userDefaultsRestored = true

        assertEquals(0, result.totalRestoredRecordCount)
        assertTrue(result.hasRestoredChanges)
    }
}
