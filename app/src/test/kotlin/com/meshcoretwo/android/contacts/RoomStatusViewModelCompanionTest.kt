// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.protocol.StatusResponse
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [RoomStatusViewModel]'s Room-only display formatters (`postsReceivedDisplay`/
 * `postsPushedDisplay`) — the role-independent ones moved to `NodeStatusDisplayTest` once
 * `RepeaterStatusViewModel` became a second consumer.
 */
class RoomStatusViewModelCompanionTest {
    private fun status(
        roomServerPostedCount: UShort? = 7u,
        roomServerPostPushCount: UShort? = 3u,
    ) = StatusResponse(
        publicKeyPrefix = ByteArray(6),
        battery = 3700,
        txQueueLength = 0,
        noiseFloor = -100,
        lastRSSI = -80,
        packetsReceived = 1u,
        packetsSent = 2u,
        airtime = 30u,
        uptime = 3661u,
        sentFlood = 5u,
        sentDirect = 6u,
        receivedFlood = 7u,
        receivedDirect = 8u,
        fullEvents = 0,
        lastSNR = 5.0,
        directDuplicates = 1,
        floodDuplicates = 2,
        rxAirtime = 15u,
        roomServerPostedCount = roomServerPostedCount,
        roomServerPostPushCount = roomServerPostPushCount,
    )

    @Test
    fun `postsReceivedDisplay reads roomServerPostedCount`() {
        assertEquals("7", RoomStatusViewModel.postsReceivedDisplay(status(roomServerPostedCount = 7u)))
    }

    @Test
    fun `postsReceivedDisplay is em dash when absent`() {
        assertEquals("—", RoomStatusViewModel.postsReceivedDisplay(status(roomServerPostedCount = null)))
    }

    @Test
    fun `postsPushedDisplay reads roomServerPostPushCount`() {
        assertEquals("3", RoomStatusViewModel.postsPushedDisplay(status(roomServerPostPushCount = 3u)))
    }
}
