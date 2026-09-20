// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Port of EventFilterAnyAcknowledgementTests.swift and EventFilterFactoryTests.swift. */
class EventFilterTest {
    @Test
    fun `anyAcknowledgement matches acknowledgement regardless of code`() {
        val filter = EventFilter.anyAcknowledgement

        val a = MeshEvent.Acknowledgement(code = byteArrayOf(0x01, 0x02, 0x03, 0x04), tripTime = 100u)
        val b = MeshEvent.Acknowledgement(code = byteArrayOf(0xFF.toByte(), 0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte()), tripTime = null)

        assertTrue(filter.matches(a))
        assertTrue(filter.matches(b))
    }

    @Test
    fun `anyAcknowledgement does not match non-acknowledgement events`() {
        val filter = EventFilter.anyAcknowledgement

        assertFalse(filter.matches(MeshEvent.Ok(null)))
        assertFalse(filter.matches(MeshEvent.Error(42u)))
        assertFalse(filter.matches(MeshEvent.Advertisement(byteArrayOf(0xAA.toByte()))))
    }

    // MARK: - rxLogData

    @Test
    fun `rxLogData matches rxLogData and rejects unrelated events`() {
        val filter = EventFilter.rxLogData
        val log = ParsedRxLogData(
            snr = 6.0, rssi = -72, rawPayload = byteArrayOf(0x00),
            routeType = RouteType.FLOOD, payloadType = PayloadType.TEXT_MESSAGE, payloadVersion = 0u,
            payloadTypeBits = 2u,
            transportCode = null, pathLength = 0u, pathNodes = emptyList(),
            packetPayload = ByteArray(0),
        )

        assertTrue(filter.matches(MeshEvent.RxLogData(log)))
        assertFalse(filter.matches(MeshEvent.Advertisement(byteArrayOf(0xAA.toByte()))))
        assertFalse(filter.matches(MeshEvent.Ok(null)))
    }

    // MARK: - anyAdvertisement

    @Test
    fun `anyAdvertisement matches advertisement and rejects unrelated events`() {
        val filter = EventFilter.anyAdvertisement

        assertTrue(filter.matches(MeshEvent.Advertisement(byteArrayOf(0xAA.toByte(), 0xBB.toByte()))))
        assertTrue(filter.matches(MeshEvent.Advertisement(byteArrayOf(0xFF.toByte()))))
        assertFalse(filter.matches(MeshEvent.Ok(null)))
        assertFalse(filter.matches(MeshEvent.Error(1u)))
    }

    // MARK: - anyContactMessage

    @Test
    fun `anyContactMessage matches contactMessageReceived and rejects unrelated events`() {
        val filter = EventFilter.anyContactMessage
        val msg = ContactMessage(
            senderPublicKeyPrefix = byteArrayOf(0x01, 0x02),
            pathLength = 0u,
            textType = 0u,
            senderTimestamp = Instant.now(),
            signature = null,
            text = "hello",
            snr = null,
        )

        assertTrue(filter.matches(MeshEvent.ContactMessageReceived(msg)))
        assertFalse(filter.matches(MeshEvent.Advertisement(byteArrayOf(0xAA.toByte()))))
        assertFalse(filter.matches(MeshEvent.Ok(null)))
    }

    // MARK: - anyChannelMessage

    @Test
    fun `anyChannelMessage matches channelMessageReceived and rejects unrelated events`() {
        val filter = EventFilter.anyChannelMessage
        val msg = ChannelMessage(
            channelIndex = 3u,
            pathLength = 0u,
            textType = 0u,
            senderTimestamp = Instant.now(),
            text = "hi",
            snr = null,
        )

        assertTrue(filter.matches(MeshEvent.ChannelMessageReceived(msg)))
        assertFalse(filter.matches(MeshEvent.Advertisement(byteArrayOf(0xAA.toByte()))))
        assertFalse(filter.matches(MeshEvent.NoMoreMessages))
    }

    // MARK: - anyLoginSuccess

    @Test
    fun `anyLoginSuccess matches loginSuccess and rejects unrelated events`() {
        val filter = EventFilter.anyLoginSuccess
        val info = LoginInfo(
            permissions = 0x01u,
            isAdmin = false,
            publicKeyPrefix = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x01, 0x02),
        )

        assertTrue(filter.matches(MeshEvent.LoginSuccess(info)))
        assertFalse(filter.matches(MeshEvent.LoginFailed(null)))
        assertFalse(filter.matches(MeshEvent.Ok(null)))
    }

    // MARK: - anyLoginFailed

    @Test
    fun `anyLoginFailed matches loginFailed and rejects unrelated events`() {
        val filter = EventFilter.anyLoginFailed
        val info = LoginInfo(
            permissions = 0x00u,
            isAdmin = false,
            publicKeyPrefix = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x01, 0x02),
        )

        assertTrue(filter.matches(MeshEvent.LoginFailed(byteArrayOf(0xAA.toByte(), 0xBB.toByte()))))
        assertTrue(filter.matches(MeshEvent.LoginFailed(null)))
        assertFalse(filter.matches(MeshEvent.LoginSuccess(info)))
        assertFalse(filter.matches(MeshEvent.Ok(null)))
    }
}
