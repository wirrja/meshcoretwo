// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestTest {
    @Test
    fun `from builds a manifest matching the given actual counts`() {
        val manifest = BackupManifest.from(
            mapOf(BackupModelKind.CONTACTS to 3, BackupModelKind.CHANNELS to 1),
        )

        assertEquals(3, manifest.contactCount)
        assertEquals(1, manifest.channelCount)
        assertEquals(0, manifest.deviceCount)
        assertEquals(3, manifest.count(BackupModelKind.CONTACTS))
    }

    @Test
    fun `validate passes when every declared count matches`() {
        val actual = mapOf(BackupModelKind.CONTACTS to 3, BackupModelKind.MESSAGES to 42)
        val manifest = BackupManifest.from(actual)

        assertTrue(manifest.validate(actual))
    }

    @Test
    fun `validate fails when a count is missing or wrong`() {
        val manifest = BackupManifest.from(mapOf(BackupModelKind.CONTACTS to 3))

        assertFalse(manifest.validate(mapOf(BackupModelKind.CONTACTS to 4)))
        assertFalse(manifest.validate(emptyMap()))
    }
}
