// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.channels

import com.meshcoretwo.protocol.FloodScope
import com.meshcoretwo.services.persistence.ChannelFloodScope
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ported from `ChannelFloodScopeResolverTests.swift`. */
class ChannelFloodScopeResolverTest {
    @Test
    fun `inherit with device default set resolves to scope region default`() {
        val resolved = ChannelFloodScopeResolver.resolve(
            channelFloodScope = ChannelFloodScope.Inherit,
            deviceDefaultFloodScopeName = "Germany",
            supportsUnscopedFloodSend = true,
        )
        assertEquals(ResolvedFloodScope.Scope(FloodScope.Region("Germany")), resolved)
    }

    @Test
    fun `inherit with no device default resolves to scope disabled`() {
        val resolved = ChannelFloodScopeResolver.resolve(
            channelFloodScope = ChannelFloodScope.Inherit,
            deviceDefaultFloodScopeName = null,
            supportsUnscopedFloodSend = true,
        )
        assertEquals(ResolvedFloodScope.Scope(FloodScope.Disabled), resolved)
    }

    @Test
    fun `inherit with empty-string default treats it as no default`() {
        val resolved = ChannelFloodScopeResolver.resolve(
            channelFloodScope = ChannelFloodScope.Inherit,
            deviceDefaultFloodScopeName = "",
            supportsUnscopedFloodSend = true,
        )
        assertEquals(ResolvedFloodScope.Scope(FloodScope.Disabled), resolved)
    }

    @Test
    fun `allRegions on firmware v12+ resolves to unscoped (true override)`() {
        val withDefault = ChannelFloodScopeResolver.resolve(ChannelFloodScope.AllRegions, "Germany", supportsUnscopedFloodSend = true)
        val withoutDefault = ChannelFloodScopeResolver.resolve(ChannelFloodScope.AllRegions, null, supportsUnscopedFloodSend = true)
        assertEquals(ResolvedFloodScope.Unscoped, withDefault)
        assertEquals(ResolvedFloodScope.Unscoped, withoutDefault)
    }

    @Test
    fun `allRegions on older firmware falls back to scope disabled`() {
        val withDefault = ChannelFloodScopeResolver.resolve(ChannelFloodScope.AllRegions, "Germany", supportsUnscopedFloodSend = false)
        val withoutDefault = ChannelFloodScopeResolver.resolve(ChannelFloodScope.AllRegions, null, supportsUnscopedFloodSend = false)
        assertEquals(ResolvedFloodScope.Scope(FloodScope.Disabled), withDefault)
        assertEquals(ResolvedFloodScope.Scope(FloodScope.Disabled), withoutDefault)
    }

    @Test
    fun `region name resolves to that region regardless of default or capability`() {
        val withDefault = ChannelFloodScopeResolver.resolve(ChannelFloodScope.Region("France"), "Germany", supportsUnscopedFloodSend = true)
        val withoutCapability = ChannelFloodScopeResolver.resolve(ChannelFloodScope.Region("France"), null, supportsUnscopedFloodSend = false)
        assertEquals(ResolvedFloodScope.Scope(FloodScope.Region("France")), withDefault)
        assertEquals(ResolvedFloodScope.Scope(FloodScope.Region("France")), withoutCapability)
    }
}
