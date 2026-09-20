// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sanity test confirming the JVM test setup works end to end. Replace with
 * real ports of MeshCoreTests (packet framing, crypto vectors, LPP encode/
 * decode round-trips) as those pieces land — see PLAN.md, Phase 1.
 */
class ProtocolModuleTest {
    @Test
    fun `module name is set`() {
        assertEquals("meshcore-protocol", ProtocolModule.NAME)
    }
}
