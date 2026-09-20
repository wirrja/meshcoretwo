// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.services.persistence.ContactDto
import com.meshcoretwo.services.remotenode.RemoteNodeError
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun contact(outPathLength: UByte, outPath: ByteArray): ContactDto =
    ContactDto(
        id = UUID.randomUUID(),
        radioID = UUID.randomUUID(),
        publicKey = ByteArray(32) { 0x42 },
        name = "Tower",
        typeRawValue = ContactType.REPEATER.value,
        flags = 0u,
        outPathLength = outPathLength,
        outPath = outPath,
        lastAdvertTimestamp = 0u,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0u,
        lastHeardTimestamp = 0u,
        nickname = null,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        lastMessageDate = null,
        unreadCount = 0,
        unreadMentionCount = 0,
        ocvPreset = null,
        customOCVArrayString = null,
        avatarImageData = null,
    )

/**
 * Covers [NodeLoginRouting]: which `path_len` each login attempt from `NodeAuthScreen` uses, when
 * the radio's stored path gets reset, and the one-shot flood retry after a timeout. Swift has no
 * tests for this half of `NodeAuthenticationSheet.authenticate()`, so the cases follow its branches.
 */
class NodeLoginRoutingTest {
    private val flood = PacketBuilder.FLOOD_PATH_SENTINEL

    /** A stored two-hop route (1-byte hashes). */
    private val storedRoute = contact(outPathLength = 2u, outPath = byteArrayOf(0xA3.toByte(), 0x7F))
    private val floodRouted = contact(outPathLength = PacketBuilder.FLOOD_PATH_SENTINEL, outPath = ByteArray(0))

    private val routing = NodeLoginRouting()
    private var resetCount = 0
    private var floodRetryCount = 0
    private val attemptedPathLengths = mutableListOf<UByte>()

    private suspend fun attempt(
        contact: ContactDto,
        useFloodRouting: Boolean,
        reply: (pathLength: UByte) -> String = { "session" },
    ): String = routing.login(
        contact = contact,
        useFloodRouting = useFloodRouting,
        resetPath = { resetCount++ },
        onFloodRetry = { floodRetryCount++ },
        performLogin = { pathLength ->
            attemptedPathLengths.add(pathLength)
            reply(pathLength)
        },
    )

    @Test
    fun `stored route with the toggle off logs in over the stored path length`() = runBlocking {
        assertEquals("session", attempt(storedRoute, useFloodRouting = false))

        assertEquals(listOf<UByte>(2u), attemptedPathLengths)
        assertEquals(0, resetCount)
        assertFalse(routing.didResetPath)
    }

    @Test
    fun `toggle on resets the stored path and logs in via flood`() = runBlocking {
        attempt(storedRoute, useFloodRouting = true)

        assertEquals(listOf(flood), attemptedPathLengths)
        assertEquals(1, resetCount)
        assertTrue(routing.didResetPath)
    }

    @Test
    fun `a second flood attempt skips the reset`() = runBlocking {
        attempt(storedRoute, useFloodRouting = true)
        attempt(storedRoute, useFloodRouting = true)

        assertEquals(listOf(flood, flood), attemptedPathLengths)
        assertEquals(1, resetCount)
    }

    @Test
    fun `after a reset, turning the toggle back off still floods`() = runBlocking {
        // The radio's path is gone even though the screen's contact snapshot still shows the route.
        attempt(storedRoute, useFloodRouting = true)
        attempt(storedRoute, useFloodRouting = false)

        assertEquals(listOf(flood, flood), attemptedPathLengths)
        assertEquals(1, resetCount)
    }

    @Test
    fun `a flood-routed contact floods without a reset`() = runBlocking {
        attempt(floodRouted, useFloodRouting = true)
        attempt(floodRouted, useFloodRouting = false)

        assertEquals(listOf(flood, flood), attemptedPathLengths)
        assertEquals(0, resetCount)
        assertFalse(routing.didResetPath)
    }

    @Test
    fun `a timeout over the stored route resets the path and retries via flood`() = runBlocking {
        val result = attempt(storedRoute, useFloodRouting = false) { pathLength ->
            if (pathLength != flood) throw RemoteNodeError.Timeout
            "flooded session"
        }

        assertEquals("flooded session", result)
        assertEquals(listOf(2u.toUByte(), flood), attemptedPathLengths)
        assertEquals(1, resetCount)
        assertEquals(1, floodRetryCount)
        assertTrue(routing.didResetPath)
    }

    @Test
    fun `a timeout over flood is not retried`() = runBlocking {
        try {
            attempt(storedRoute, useFloodRouting = true) { throw RemoteNodeError.Timeout }
            fail("expected RemoteNodeError.Timeout")
        } catch (error: RemoteNodeError.Timeout) {
            // expected
        }

        assertEquals(listOf(flood), attemptedPathLengths)
        assertEquals(0, floodRetryCount)
    }

    @Test
    fun `a timeout on the flood retry propagates`() = runBlocking {
        try {
            attempt(storedRoute, useFloodRouting = false) { throw RemoteNodeError.Timeout }
            fail("expected RemoteNodeError.Timeout")
        } catch (error: RemoteNodeError.Timeout) {
            // expected
        }

        assertEquals(listOf(2u.toUByte(), flood), attemptedPathLengths)
        assertEquals(1, resetCount)
        assertEquals(1, floodRetryCount)
    }

    @Test
    fun `a non-timeout failure over the stored route is not retried`() = runBlocking {
        try {
            attempt(storedRoute, useFloodRouting = false) { throw RemoteNodeError.SessionNotFound }
            fail("expected RemoteNodeError.SessionNotFound")
        } catch (error: RemoteNodeError.SessionNotFound) {
            // expected
        }

        assertEquals(listOf<UByte>(2u), attemptedPathLengths)
        assertEquals(0, resetCount)
        assertEquals(0, floodRetryCount)
    }

    @Test
    fun `a failed reset aborts before any login attempt`() = runBlocking {
        try {
            routing.login(
                contact = storedRoute,
                useFloodRouting = true,
                resetPath = { throw IllegalStateException("radio unreachable") },
                onFloodRetry = { floodRetryCount++ },
                performLogin = { pathLength -> attemptedPathLengths.add(pathLength) },
            )
            fail("expected the reset failure to propagate")
        } catch (error: IllegalStateException) {
            // expected
        }

        assertTrue(attemptedPathLengths.isEmpty())
        assertFalse(routing.didResetPath)
    }
}
