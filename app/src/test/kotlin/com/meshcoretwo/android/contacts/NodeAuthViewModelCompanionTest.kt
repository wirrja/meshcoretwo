// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers [NodeAuthViewModel.truncatePassword], the one piece of `NodeAuthViewModel` that doesn't need a live [com.meshcoretwo.services.connection.ConnectionManager]. */
class NodeAuthViewModelCompanionTest {
    @Test
    fun `password at the 15-character limit is unchanged`() {
        val password = "a".repeat(15)
        assertEquals(password, NodeAuthViewModel.truncatePassword(password))
    }

    @Test
    fun `password over the limit is truncated to 15 characters`() {
        val password = "a".repeat(20)
        assertEquals("a".repeat(15), NodeAuthViewModel.truncatePassword(password))
    }

    @Test
    fun `short password is unchanged`() {
        assertEquals("hunter2", NodeAuthViewModel.truncatePassword("hunter2"))
    }

    @Test
    fun `empty password is unchanged`() {
        assertEquals("", NodeAuthViewModel.truncatePassword(""))
    }
}
