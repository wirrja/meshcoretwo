// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Port of MeshCoreSessionCommandCorrelationTests.swift.
 *
 * Unlike most of these ported files, this one relies on [startSession]'s cleared sent-data
 * history, so `sentData` counts/indices below are one lower than Swift's (no appStart frame).
 */
class MeshCoreSessionCommandCorrelationTest {
    @Test
    fun `simple commands serialize concurrent OK-ERROR waits`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val first = CoroutineScope(Dispatchers.Default).async { session.factoryReset() }
        val second = CoroutineScope(Dispatchers.Default).async { session.sendAdvertisement(true) }

        waitUntil { transport.sentData().size == 1 }
        delay(50)
        assertEquals(1, transport.sentData().size)

        transport.simulateOK()

        waitUntil { transport.sentData().size == 2 }

        transport.simulateOK()

        first.await()
        second.await()
        session.stop()
    }

    @Test
    fun `simple commands ignore OK responses with payloads`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 0.2, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val resetJob = CoroutineScope(Dispatchers.Default).async { session.factoryReset() }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateOK(7u)

        try {
            resetJob.await()
            fail("expected MeshCoreError.Timeout")
        } catch (error: MeshCoreError.Timeout) {
            // expected
        }
        session.stop()
    }

    @Test
    fun `simple commands still fail on device errors`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val commandJob = CoroutineScope(Dispatchers.Default).async {
            session.setAutoAddConfig(AutoAddConfig(bitmask = 0x1Eu, maxHops = 2u))
        }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateError(42u)

        try {
            commandJob.await()
            fail("expected deviceError")
        } catch (error: MeshCoreError.DeviceError) {
            assertEquals(42u.toUByte(), error.code)
        }
        session.stop()
    }

    @Test
    fun `session start ignores unrelated errors until selfInfo arrives`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))

        val startJob = CoroutineScope(Dispatchers.Default).launch { session.start() }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateError(99u)
        transport.simulateReceive(makeSelfInfoPacket(name = "Test"))

        startJob.join()
        assertEquals("Test", session.currentSelfInfo?.name)
        session.stop()
    }

    @Test
    fun `getBattery ignores unrelated errors while waiting for a battery response`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val batteryJob = CoroutineScope(Dispatchers.Default).async { session.getBattery() }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateError(10u)
        transport.simulateReceive(makeBatteryPacket(4018u))

        val battery = batteryJob.await()
        assertEquals(4018, battery.level)
        session.stop()
    }

    @Test
    fun `getSelfTelemetry ignores telemetry for other nodes`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val telemetryJob = CoroutineScope(Dispatchers.Default).async { session.getSelfTelemetry() }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateReceive(
            makeTelemetryPacket(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()), byteArrayOf(0x01, 0x67, 0x00, 0xFA.toByte())),
        )
        transport.simulateReceive(
            makeTelemetryPacket(ByteArray(6) { 0x01 }, byteArrayOf(0x01, 0x67, 0x00, 0xF0.toByte())),
        )

        val response = telemetryJob.await()
        assertEquals(ByteArray(6) { 0x01 }.toList(), response.publicKeyPrefix.toList())
        session.stop()
    }

    @Test
    fun `getChannel ignores responses for other channel indexes`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val channelJob = CoroutineScope(Dispatchers.Default).async { session.getChannel(3u) }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateReceive(makeChannelInfoPacket(9u, "Wrong", ByteArray(16) { 0xAA.toByte() }))
        transport.simulateReceive(makeChannelInfoPacket(3u, "Right", ByteArray(16) { 0xBB.toByte() }))

        val channel = channelJob.await()
        assertEquals(3u.toUByte(), channel.index)
        assertEquals("Right", channel.name)
        session.stop()
    }

    @Test
    fun `getContacts succeeds when slow stream keeps making progress`() = runBlocking {
        val transport = MockTransport()
        // Inactivity timeout sits well above the 70ms send cadence so a slow runner that
        // overshoots a sleep can't trip it mid-stream.
        val session = MeshCoreSession(
            transport,
            SessionConfiguration(
                defaultTimeout = 10.0,
                clientIdentifier = "MCTst",
                contactStreamInactivityTimeout = 1.0,
                contactStreamHardTimeout = 10.0,
            ),
        )
        startSession(session, transport)

        val contactsJob = CoroutineScope(Dispatchers.Default).async { session.getContacts() }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateReceive(makeContactsStartPacket(3u))
        for (index in 0 until 3) {
            delay(70)
            transport.simulateReceive(makeContactPacket(ByteArray(32) { (index + 1).toByte() }, "Node $index"))
        }
        delay(70)
        transport.simulateReceive(makeContactsEndPacket(1_704_067_200u))

        val contacts = contactsJob.await()
        assertEquals(3, contacts.size)
        session.stop()
    }

    @Test
    fun `getContactsReportingTotal surfaces the contactsStart total, not the received count`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(
            transport,
            SessionConfiguration(
                defaultTimeout = 10.0,
                clientIdentifier = "MCTst",
                contactStreamInactivityTimeout = 1.0,
                contactStreamHardTimeout = 10.0,
            ),
        )
        startSession(session, transport)

        val fetchJob = CoroutineScope(Dispatchers.Default).async { session.getContactsReportingTotal() }
        waitUntil { transport.sentData().size == 1 }

        // Header reports 3, but the stream carries only 2 contacts before end. The reported
        // total must be the header value so the prune caller can detect the truncation.
        transport.simulateReceive(makeContactsStartPacket(3u))
        for (index in 0 until 2) {
            transport.simulateReceive(makeContactPacket(ByteArray(32) { (index + 1).toByte() }, "Node $index"))
        }
        transport.simulateReceive(makeContactsEndPacket(1_704_067_200u))

        val result = fetchJob.await()
        assertEquals(2, result.contacts.size)
        assertEquals(3, result.reportedTotal)
        session.stop()
    }

    @Test
    fun `getContactsReportingTotal returns a null total when contactsStart never arrives`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(
            transport,
            SessionConfiguration(
                defaultTimeout = 10.0,
                clientIdentifier = "MCTst",
                contactStreamInactivityTimeout = 1.0,
                contactStreamHardTimeout = 10.0,
            ),
        )
        startSession(session, transport)

        val fetchJob = CoroutineScope(Dispatchers.Default).async { session.getContactsReportingTotal() }
        waitUntil { transport.sentData().size == 1 }

        // Contacts and end arrive with no start header. The total stays unknown so a prune
        // caller cannot mistake this stream for a complete snapshot.
        for (index in 0 until 2) {
            transport.simulateReceive(makeContactPacket(ByteArray(32) { (index + 1).toByte() }, "Node $index"))
        }
        transport.simulateReceive(makeContactsEndPacket(1_704_067_200u))

        val result = fetchJob.await()
        assertEquals(2, result.contacts.size)
        assertEquals(null, result.reportedTotal)
        session.stop()
    }

    @Test
    fun `getContacts times out after inactivity before contactsEnd`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(
            transport,
            SessionConfiguration(
                defaultTimeout = 10.0,
                clientIdentifier = "MCTst",
                contactStreamInactivityTimeout = 0.08,
                contactStreamHardTimeout = 1.0,
            ),
        )
        startSession(session, transport)

        val contactsJob = CoroutineScope(Dispatchers.Default).async { session.getContacts() }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateReceive(makeContactsStartPacket(1u))
        delay(140)

        try {
            contactsJob.await()
            fail("expected MeshCoreError.Timeout")
        } catch (error: MeshCoreError.Timeout) {
            // expected
        }
        session.stop()
    }

    @Test
    fun `getContact ignores responses for other public keys`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val requestedKey = ByteArray(32) { 0x11 }
        val contactJob = CoroutineScope(Dispatchers.Default).async { session.getContact(requestedKey) }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateReceive(makeContactPacket(ByteArray(32) { 0x22 }, "Wrong"))
        transport.simulateReceive(makeContactPacket(requestedKey, "Right"))

        val contact = contactJob.await()
        assertNotNull(contact)
        assertEquals(requestedKey.toList(), contact!!.publicKey.toList())
        assertEquals("Right", contact.advertisedName)
        session.stop()
    }

    @Test
    fun `exportContact ignores contact URIs for other public keys`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val requestedKey = ByteArray(32) { 0x11 }
        val otherKey = ByteArray(32) { 0x22 }

        val exportJob = CoroutineScope(Dispatchers.Default).async { session.exportContact(requestedKey) }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateReceive(makeContactURIPacket(otherKey))
        transport.simulateReceive(makeContactURIPacket(requestedKey))

        val uri = exportJob.await()
        assertTrue("exportContact must return the card for the requested key", uri.contains(requestedKey.hexString))
        assertFalse("exportContact must not return another contact's card", uri.contains(otherKey.hexString))
        session.stop()
    }

    @Test
    fun `importPrivateKey ignores OK responses with payloads`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 0.2, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val importJob = CoroutineScope(Dispatchers.Default).async { session.importPrivateKey(ByteArray(64) { 0x33 }) }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateOK(7u)

        try {
            importJob.await()
            fail("expected MeshCoreError.Timeout")
        } catch (error: MeshCoreError.Timeout) {
            // expected
        }
        session.stop()
    }

    @Test
    fun `importPrivateKey refreshes cached self info after OK`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))

        val originalPublicKey = ByteArray(32) { 0x01 }
        val restoredPublicKey = ByteArray(32) { 0x44 }

        startSession(session, transport, makeSelfInfoPacket(publicKey = originalPublicKey, name = "Temp"))
        assertEquals(originalPublicKey.toList(), session.currentSelfInfo?.publicKey?.toList())

        val importJob = CoroutineScope(Dispatchers.Default).async { session.importPrivateKey(ByteArray(64) { 0x33 }) }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateOK()

        waitUntil { transport.sentData().size == 2 }

        transport.simulateReceive(makeSelfInfoPacket(publicKey = restoredPublicKey, name = "Restored"))
        importJob.await()

        val selfInfo = session.currentSelfInfo
        assertNotNull(selfInfo)
        assertEquals(restoredPublicKey.toList(), selfInfo!!.publicKey.toList())
        assertEquals("Restored", selfInfo.name)
        session.stop()
    }

    @Test
    fun `importPrivateKey rejects a key that is not the expanded private-key length`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))

        try {
            session.importPrivateKey(ByteArray(PacketBuilder.PUBLIC_KEY_SIZE) { 0x33 })
            fail("expected invalidInput")
        } catch (error: MeshCoreError.InvalidInput) {
            // expected
        }
        assertEquals("Guard must fail before any frame is sent", 0, transport.sentData().size)
    }

    @Test
    fun `exportPrivateKey throws featureDisabled on disabled response`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val exportJob = CoroutineScope(Dispatchers.Default).async { session.exportPrivateKey() }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateReceive(byteArrayOf(ResponseCode.DISABLED.value.toByte()))

        try {
            exportJob.await()
            fail("expected featureDisabled")
        } catch (error: MeshCoreError.FeatureDisabled) {
            // expected
        }
        session.stop()
    }

    @Test
    fun `disabled responses do not break unrelated requests`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val batteryJob = CoroutineScope(Dispatchers.Default).async { session.getBattery() }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateReceive(byteArrayOf(ResponseCode.DISABLED.value.toByte()))
        transport.simulateReceive(makeBatteryPacket(4018u))

        val battery = batteryJob.await()
        assertEquals(4018, battery.level)
        session.stop()
    }

    @Test
    fun `requestStatus fails fast on device error before messageSent`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val target = ByteArray(32) { 0x31 }
        val statusJob = CoroutineScope(Dispatchers.Default).async { session.requestStatus(target) }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateError(10u)

        try {
            statusJob.await()
            fail("expected deviceError")
        } catch (error: MeshCoreError.DeviceError) {
            assertEquals(10u.toUByte(), error.code)
        }
        session.stop()
    }

    @Test
    fun `requestStatus uses dedicated status command and room layout for typed room targets`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val target = ByteArray(32) { 0x31 }
        val expectedAck = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())

        val statusJob = CoroutineScope(Dispatchers.Default).async { session.requestStatus(target, ContactType.ROOM) }
        waitUntil { transport.sentData().size == 1 }

        val sent = transport.sentData()[0]
        assertEquals(CommandCode.SEND_STATUS_REQUEST.value.toByte(), sent[0])

        transport.simulateReceive(makeMessageSentPacket(expectedAck = expectedAck))
        // Dedicated STATUS_RESPONSE push; room counters packed where repeater rxAirtime sits.
        transport.simulateReceive(
            makeStatusResponsePacket(target.prefixBytes(6), battery = 1000u, roomServerPostedCount = 17u, roomServerPostPushCount = 9u),
        )

        val status = statusJob.await()
        assertEquals(1000, status.battery)
        assertEquals(17u.toUShort(), status.roomServerPostedCount)
        assertEquals(9u.toUShort(), status.roomServerPostPushCount)
        assertEquals(0u, status.rxAirtime)
        session.stop()
    }

    @Test
    fun `requestStatus retransmits until a matching response arrives`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(
            transport,
            SessionConfiguration(
                defaultTimeout = 10.0,
                clientIdentifier = "MCTst",
                binaryRequestOverallTimeout = 2.0,
                binaryRequestRetransmitInterval = 0.05,
            ),
        )
        startSession(session, transport)

        val target = ByteArray(32) { 0x31 }
        val firstTag = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val secondTag = byteArrayOf(0x55, 0x66, 0x77, 0x88.toByte())

        val statusJob = CoroutineScope(Dispatchers.Default).async { session.requestStatus(target) }

        waitUntil { transport.sentData().size == 1 }
        transport.simulateReceive(makeMessageSentPacket(expectedAck = firstTag, timeoutMs = 500u))

        waitUntil { transport.sentData().size >= 2 }
        transport.simulateReceive(makeMessageSentPacket(expectedAck = secondTag, timeoutMs = 500u))

        // Status is routed by public-key prefix, not tag.
        transport.simulateReceive(makeStatusResponsePacket(target.prefixBytes(6), battery = 2200u))

        val status = statusJob.await()
        assertEquals(2200, status.battery)
        assertTrue(transport.sentData().count { it.isNotEmpty() && it[0] == CommandCode.SEND_STATUS_REQUEST.value.toByte() } >= 2)
        session.stop()
    }

    @Test
    fun `binary response matches only the latest retransmit tag`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(
            transport,
            SessionConfiguration(
                defaultTimeout = 10.0,
                clientIdentifier = "MCTst",
                binaryRequestOverallTimeout = 2.0,
                binaryRequestRetransmitInterval = 0.05,
            ),
        )
        startSession(session, transport)

        val target = ByteArray(32) { 0x31 }
        val firstTag = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val secondTag = byteArrayOf(0x55, 0x66, 0x77, 0x88.toByte())

        val telemetryJob = CoroutineScope(Dispatchers.Default).async { session.requestTelemetry(target) }

        waitUntil { transport.sentData().size == 1 }
        transport.simulateReceive(makeMessageSentPacket(expectedAck = firstTag, timeoutMs = 500u))

        waitUntil { transport.sentData().size >= 2 }
        transport.simulateReceive(makeMessageSentPacket(expectedAck = secondTag, timeoutMs = 500u))

        // Stale first-tag reply after retransmit must not complete the wait.
        transport.simulateReceive(makeBinaryTelemetryResponsePacket(firstTag))
        delay(80)
        assertFalse(telemetryJob.isCompleted)

        transport.simulateReceive(makeBinaryTelemetryResponsePacket(secondTag))

        val telemetry = telemetryJob.await()
        assertTrue(telemetry.dataPoints.isEmpty())
        session.stop()
    }

    @Test
    fun `requestStatus times out after the overall budget without a reply`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(
            transport,
            SessionConfiguration(
                defaultTimeout = 10.0,
                clientIdentifier = "MCTst",
                // Keep overall longer than retransmit spacing so a resend is observed.
                binaryRequestOverallTimeout = 0.5,
                binaryRequestRetransmitInterval = 0.05,
            ),
        )
        startSession(session, transport)

        val target = ByteArray(32) { 0x31 }
        val statusJob = CoroutineScope(Dispatchers.Default).async { session.requestStatus(target) }

        waitUntil { transport.sentData().size == 1 }
        // suggested 50ms x headroom 2 = 100ms between retransmits.
        transport.simulateReceive(makeMessageSentPacket(expectedAck = byteArrayOf(0x01, 0x02, 0x03, 0x04), timeoutMs = 50u))

        try {
            statusJob.await()
            fail("expected MeshCoreError.Timeout")
        } catch (error: MeshCoreError.Timeout) {
            // expected
        }
        assertTrue(transport.sentData().count { it.isNotEmpty() && it[0] == CommandCode.SEND_STATUS_REQUEST.value.toByte() } >= 2)
        session.stop()
    }

    @Test
    fun `requestTelemetry fails fast on device error before messageSent`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val target = ByteArray(32) { 0x31 }
        val telemetryJob = CoroutineScope(Dispatchers.Default).async { session.requestTelemetry(target) }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateError(11u)

        try {
            telemetryJob.await()
            fail("expected deviceError")
        } catch (error: MeshCoreError.DeviceError) {
            assertEquals(11u.toUByte(), error.code)
        }
        session.stop()
    }

    @Test
    fun `sendMessage fails fast on device error`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val messageJob = CoroutineScope(Dispatchers.Default).async {
            session.sendMessage(ByteArray(32) { 0x11 }, "hello")
        }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateError(5u)

        try {
            messageJob.await()
            fail("expected deviceError")
        } catch (error: MeshCoreError.DeviceError) {
            assertEquals(5u.toUByte(), error.code)
        }
        session.stop()
    }

    @Test
    fun `sendKeepAlive fails fast on device error`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val keepAliveJob = CoroutineScope(Dispatchers.Default).async {
            session.sendKeepAlive(ByteArray(32) { 0x22 }, 0u)
        }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateError(3u)

        try {
            keepAliveJob.await()
            fail("expected deviceError")
        } catch (error: MeshCoreError.DeviceError) {
            assertEquals(3u.toUByte(), error.code)
        }
        session.stop()
    }

    @Test
    fun `exportPrivateKey fails fast on device error`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val exportJob = CoroutineScope(Dispatchers.Default).async { session.exportPrivateKey() }
        waitUntil { transport.sentData().size == 1 }

        transport.simulateError(8u)

        try {
            exportJob.await()
            fail("expected deviceError")
        } catch (error: MeshCoreError.DeviceError) {
            assertEquals(8u.toUByte(), error.code)
        }
        session.stop()
    }

    @Test
    fun `binary request serializes behind a concurrent text command`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        // Text command acquires the unified serializer first.
        val keepAliveJob = CoroutineScope(Dispatchers.Default).async {
            session.sendKeepAlive(ByteArray(32) { 0x22 }, 0u)
        }
        waitUntil { transport.sentData().size == 1 }

        // Binary request must wait behind the text command, not run concurrently.
        val target = ByteArray(32) { 0x31 }
        val statusJob = CoroutineScope(Dispatchers.Default).async { session.requestStatus(target) }

        delay(50)
        assertEquals("binary request must not send while a text command is in flight", 1, transport.sentData().size)

        // The error belongs to the in-flight text command only.
        transport.simulateError(42u)

        try {
            keepAliveJob.await()
            fail("expected keepAlive deviceError")
        } catch (error: MeshCoreError.DeviceError) {
            assertEquals(42u.toUByte(), error.code)
        }

        // Only after the text command releases the serializer does the binary request send.
        waitUntil { transport.sentData().size == 2 }

        transport.simulateError(43u)

        try {
            statusJob.await()
            fail("expected status deviceError")
        } catch (error: MeshCoreError.DeviceError) {
            assertEquals("binary request runs as its own exchange after the text command", 43u.toUByte(), error.code)
        }

        session.stop()
    }

    @Test
    fun `binary request errors release the serializer for following requests`() = runBlocking {
        val transport = MockTransport()
        // Disable in-exchange retransmit so this serialization test is not sensitive to
        // parallel-suite scheduling of retransmit sleeps.
        val session = MeshCoreSession(
            transport,
            SessionConfiguration(
                defaultTimeout = 10.0,
                clientIdentifier = "MCTst",
                binaryRequestOverallTimeout = 2.0,
                binaryRequestRetransmitInterval = null,
            ),
        )
        startSession(session, transport)

        val firstTarget = ByteArray(32) { 0x31 }
        val secondTarget = ByteArray(32) { 0x42 }

        val statusJob = CoroutineScope(Dispatchers.Default).async { session.requestStatus(firstTarget) }
        val telemetryJob = CoroutineScope(Dispatchers.Default).async { session.requestTelemetry(secondTarget) }

        waitUntil { transport.sentData().size == 1 }
        delay(50)
        assertEquals(1, transport.sentData().size)

        transport.simulateError(12u)

        try {
            statusJob.await()
            fail("expected first binary request to fail with deviceError")
        } catch (error: MeshCoreError.DeviceError) {
            assertEquals(12u.toUByte(), error.code)
        }

        waitUntil { transport.sentData().size == 2 }

        transport.simulateError(13u)

        try {
            telemetryJob.await()
            fail("expected second binary request to fail with deviceError")
        } catch (error: MeshCoreError.DeviceError) {
            assertEquals(13u.toUByte(), error.code)
        }
        session.stop()
    }

    @Test
    fun `a response orphaned by a cancelled command is not delivered to the next command`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        // Command #1 is sent, then cancelled after its write has gone out — the radio still
        // owes a response. getBattery is a singleton matcher whose value we control, so a
        // stolen response surfaces as the wrong battery level on command #2.
        val orphanedLevel: UShort = 1111u
        val correctLevel: UShort = 2222u

        val firstBattery = CoroutineScope(Dispatchers.Default).async { session.getBattery() }
        waitUntil { transport.sentData().size == 1 }

        firstBattery.cancel()
        try {
            firstBattery.await()
        } catch (error: Throwable) {
            // expected: cancelled
        }

        // Command #2 issued after the cancellation.
        val secondBattery = CoroutineScope(Dispatchers.Default).async { session.getBattery() }

        // Give the next command time to subscribe before the orphan lands.
        delay(50)

        // The radio's late response to the cancelled command #1.
        transport.simulateReceive(makeBatteryPacket(orphanedLevel))

        waitUntil { transport.sentData().size == 2 }

        // Command #2's own response.
        transport.simulateReceive(makeBatteryPacket(correctLevel))

        val battery = secondBattery.await()
        assertEquals("command #2 must not receive command #1's orphaned response", correctLevel.toInt(), battery.level)
        session.stop()
    }

    @Test
    fun `concurrent unicast send and binary request do not share one messageSent`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        val statusTarget = ByteArray(32) { 0x31 }
        val messageTarget = ByteArray(32) { 0x11 }
        val statusAck = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val messageAck = byteArrayOf(0x11, 0x22, 0x33, 0x44)

        // Binary request goes first and owns the in-flight exchange.
        val statusJob = CoroutineScope(Dispatchers.Default).async { session.requestStatus(statusTarget, ContactType.ROOM) }
        waitUntil { transport.sentData().size == 1 }

        // Unicast send issued while the binary request is outstanding.
        val messageJob = CoroutineScope(Dispatchers.Default).async { session.sendMessage(messageTarget, "hi") }

        // Give a non-serialized sender time to also subscribe before any messageSent lands.
        delay(50)

        // The status request's own messageSent + dedicated STATUS_RESPONSE.
        transport.simulateReceive(makeMessageSentPacket(expectedAck = statusAck))
        transport.simulateReceive(
            makeStatusResponsePacket(statusTarget.prefixBytes(6), battery = 1234u, roomServerPostedCount = 5u, roomServerPostPushCount = 2u),
        )

        val status = statusJob.await()
        assertEquals(1234, status.battery)

        waitUntil { transport.sentData().size == 2 }

        // The unicast send's own messageSent.
        transport.simulateReceive(makeMessageSentPacket(expectedAck = messageAck))

        val info = messageJob.await()
        assertEquals("sendMessage must not consume the binary request's messageSent", messageAck.toList(), info.expectedAck.toList())
        session.stop()
    }

    @Test
    fun `a command cancelled while waiting on the serializer never writes`() = runBlocking {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
        startSession(session, transport)

        // Command #1 holds the serializer.
        val first = CoroutineScope(Dispatchers.Default).async { session.factoryReset() }
        waitUntil { transport.sentData().size == 1 }

        // Command #2 parks in acquire() behind command #1.
        val second = CoroutineScope(Dispatchers.Default).async { session.sendAdvertisement(true) }
        delay(50)
        assertEquals("second command must wait behind the first", 1, transport.sentData().size)

        // Cancel #2 while it is still parked, then let #1 finish so #2 acquires.
        second.cancel()
        transport.simulateOK()
        first.await()

        try {
            second.await()
            fail("expected cancellation")
        } catch (error: Throwable) {
            assertTrue(second.isCancelled)
        }
        assertEquals(
            "a command cancelled before acquiring the serializer must not commit a write",
            1,
            transport.sentData().size,
        )
        session.stop()
    }
}
