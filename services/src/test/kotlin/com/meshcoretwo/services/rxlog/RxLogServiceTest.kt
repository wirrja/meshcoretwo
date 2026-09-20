// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rxlog

import androidx.room.Room
import com.meshcoretwo.protocol.ChannelInfo
import com.meshcoretwo.protocol.ChannelsFetchResult
import com.meshcoretwo.protocol.ConnectionState
import com.meshcoretwo.protocol.ContactFlags
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.EventFilter
import com.meshcoretwo.protocol.MeshContact
import com.meshcoretwo.protocol.MeshEvent
import com.meshcoretwo.protocol.PacketBuilder
import com.meshcoretwo.protocol.ParsedRxLogData
import com.meshcoretwo.protocol.PayloadType
import com.meshcoretwo.protocol.RouteType
import com.meshcoretwo.protocol.RxLogSessionOps
import com.meshcoretwo.protocol.SelfInfo
import com.meshcoretwo.protocol.TextType
import com.meshcoretwo.protocol.TransportCodeRegionResolver
import com.meshcoretwo.protocol.encodePathLen
import com.meshcoretwo.services.persistence.ChannelStore
import com.meshcoretwo.services.persistence.ContactStore
import com.meshcoretwo.services.persistence.DecryptStatus
import com.meshcoretwo.services.persistence.DiscoveredNodeStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import com.meshcoretwo.services.persistence.MessageDirection
import com.meshcoretwo.services.persistence.MessageDto
import com.meshcoretwo.services.persistence.MessageStatus
import com.meshcoretwo.services.persistence.MessageStore
import com.meshcoretwo.services.persistence.RxLogStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class RxLogServiceTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var session: FakeRxLogSession
    private lateinit var rxLogStore: RxLogStore
    private lateinit var channelStore: ChannelStore
    private lateinit var contactStore: ContactStore
    private lateinit var discoveredNodeStore: DiscoveredNodeStore
    private lateinit var service: RxLogService
    private val radioID = UUID.randomUUID()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = FakeRxLogSession()
        rxLogStore = RxLogStore(database)
        channelStore = ChannelStore(database)
        contactStore = ContactStore(database)
        discoveredNodeStore = DiscoveredNodeStore(database)
        service = RxLogService(session, rxLogStore, channelStore, contactStore, discoveredNodeStore)
    }

    @After
    fun tearDown() = runTest {
        service.stopEventMonitoring()
        // Shut down before closing the database — a scheduled batch flush is a real-time timer
        // outside this test's coroutine scope, and one firing after close() can wedge Room's
        // process-wide lock for every other test's database. See RxLogStore.shutdown's doc.
        rxLogStore.shutdown()
        database.close()
    }

    private fun awaitUntil(timeoutMs: Long = 2000, intervalMs: Long = 10, condition: suspend () -> Boolean) = kotlinx.coroutines.runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return@runBlocking
            Thread.sleep(intervalMs)
        }
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    /** Builds a valid `[channelHash:1][MAC:2][ciphertext:N]` group-text payload, mirroring ChannelCrypto's Encrypt-then-MAC. */
    private fun encryptedChannelPayload(channelHashByte: Byte, secret: ByteArray, timestamp: UInt, txtType: Byte, text: String): ByteArray {
        val header = byteArrayOf(
            (timestamp and 0xFFu).toByte(), ((timestamp shr 8) and 0xFFu).toByte(),
            ((timestamp shr 16) and 0xFFu).toByte(), ((timestamp shr 24) and 0xFFu).toByte(),
            txtType,
        )
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val plainLength = ((header.size + textBytes.size + 15) / 16) * 16
        val plaintext = ByteArray(plainLength)
        header.copyInto(plaintext)
        textBytes.copyInto(plaintext, header.size)

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret.copyOf(16), "AES"))
        val ciphertext = cipher.doFinal(plaintext)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val fullMac = mac.doFinal(ciphertext)

        return byteArrayOf(channelHashByte) + fullMac.copyOf(2) + ciphertext
    }

    private fun parsedGroupText(payload: ByteArray, channelIndex: UByte = 0u) = ParsedRxLogData(
        snr = 5.0, rssi = -70, rawPayload = payload,
        routeType = RouteType.FLOOD, payloadType = PayloadType.GROUP_TEXT, payloadVersion = 1u,
        payloadTypeBits = 5u, transportCode = null,
        pathLength = 2u, pathNodes = listOf(0x11u, 0x22u),
        packetPayload = payload,
    )

    @Test
    fun `process persists a group-text packet as NO_MATCHING_KEY when no channel secret matches`() = runTest {
        service.startEventMonitoring(radioID)
        val payload = byteArrayOf(0x00) + ByteArray(18) // long enough, but garbage ciphertext/MAC

        service.process(parsedGroupText(payload))

        val entries = rxLogStore.fetchRecentEntriesByDecryptStatus(radioID, DecryptStatus.NO_MATCHING_KEY, Instant.EPOCH.plusSeconds(1))
        assertEquals(1, entries.size)
    }

    @Test
    fun `process decrypts and persists a group-text packet as SUCCESS when a channel secret matches`() = runTest {
        val secret = ByteArray(16) { it.toByte() }
        channelStore.saveChannel(radioID, ChannelInfo(0u, "General", secret))
        service.startEventMonitoring(radioID)
        awaitUntil { true } // let startEventMonitoring's loadSecretsFromDatabase complete
        Thread.sleep(50)

        val payload = encryptedChannelPayload(channelHashByte = 0x00, secret = secret, timestamp = 1234u, txtType = 0, text = "hi")
        service.process(parsedGroupText(payload))

        val entries = rxLogStore.fetchRecentEntriesByDecryptStatus(radioID, DecryptStatus.SUCCESS, Instant.EPOCH.plusSeconds(1))
        assertEquals(1, entries.size)
        assertEquals(0, entries[0].channelIndex?.toInt())
        assertEquals(1234u, entries[0].senderTimestamp)
    }

    @Test
    fun `process persists a payload too short for the channel format as PENDING`() = runTest {
        service.startEventMonitoring(radioID)
        val payload = byteArrayOf(0x00, 0x01, 0x02)

        service.process(parsedGroupText(payload))

        val entries = rxLogStore.fetchRecentEntriesByDecryptStatus(radioID, DecryptStatus.PENDING, Instant.EPOCH.plusSeconds(1))
        assertEquals(1, entries.size)
    }

    @Test
    fun `process persists a non-text payload as NOT_APPLICABLE`() = runTest {
        service.startEventMonitoring(radioID)
        val parsed = ParsedRxLogData(
            snr = null, rssi = null, rawPayload = ByteArray(0),
            routeType = RouteType.FLOOD, payloadType = PayloadType.ADVERT, payloadVersion = 1u,
            payloadTypeBits = 4u, transportCode = null, pathLength = 0u, pathNodes = emptyList(),
            packetPayload = ByteArray(0),
        )

        service.process(parsed)

        val entries = rxLogStore.fetchRecentEntriesByDecryptStatus(radioID, DecryptStatus.NOT_APPLICABLE, Instant.EPOCH.plusSeconds(1))
        assertEquals(1, entries.size)
    }

    @Test
    fun `updateChannels reprocesses recent NO_MATCHING_KEY entries once secrets arrive`() = runTest {
        service.startEventMonitoring(radioID)
        Thread.sleep(50) // let startEventMonitoring's loadSecretsFromDatabase settle first — see the SUCCESS test above.
        val secret = ByteArray(16) { (it * 3).toByte() }
        val payload = encryptedChannelPayload(channelHashByte = 0x00, secret = secret, timestamp = 555u, txtType = 0, text = "late")
        service.process(parsedGroupText(payload)) // arrives before the secret is known -> NO_MATCHING_KEY

        service.updateChannels(mapOf(0u.toUByte() to secret), mapOf(0u.toUByte() to "General"))

        val successEntries = rxLogStore.fetchRecentEntriesByDecryptStatus(radioID, DecryptStatus.SUCCESS, Instant.EPOCH.plusSeconds(1))
        assertEquals(1, successEntries.size)
        assertEquals(555u, successEntries[0].senderTimestamp)
    }

    @Test
    fun `process forwards the decoded plaintext to heardRepeatProcessing, transiently, on a successful decrypt`() = runTest {
        val secret = ByteArray(16) { it.toByte() }
        val heardRepeats = FakeHeardRepeatProcessing()
        val withHeardRepeats = RxLogService(session, rxLogStore, channelStore, contactStore, DiscoveredNodeStore(database), heardRepeats)
        channelStore.saveChannel(radioID, ChannelInfo(0u, "General", secret))
        withHeardRepeats.startEventMonitoring(radioID)
        Thread.sleep(50)

        val payload = encryptedChannelPayload(channelHashByte = 0x00, secret = secret, timestamp = 1234u, txtType = 0, text = "TestNode: hi")
        withHeardRepeats.process(parsedGroupText(payload))
        withHeardRepeats.stopEventMonitoring()

        assertEquals(1, heardRepeats.processed.size)
        assertEquals("TestNode: hi", heardRepeats.processed.single().decodedText)
        assertEquals(1234u, heardRepeats.processed.single().senderTimestamp)
        // Never persisted — see RxLogDto.decodedText's doc.
        val stored = rxLogStore.fetchRecentEntriesByDecryptStatus(radioID, DecryptStatus.SUCCESS, Instant.EPOCH.plusSeconds(1))
        assertEquals(null, stored.single().decodedText)
    }

    @Test
    fun `process does not forward to heardRepeatProcessing when decryption fails`() = runTest {
        val heardRepeats = FakeHeardRepeatProcessing()
        val withHeardRepeats = RxLogService(session, rxLogStore, channelStore, contactStore, DiscoveredNodeStore(database), heardRepeats)
        withHeardRepeats.startEventMonitoring(radioID)

        withHeardRepeats.process(parsedGroupText(byteArrayOf(0x00) + ByteArray(18)))
        withHeardRepeats.stopEventMonitoring()

        assertEquals(1, heardRepeats.processed.size)
        assertEquals(null, heardRepeats.processed.single().decodedText)
    }

    @Test
    fun `updateChannels reprocessing forwards the decoded plaintext to heardRepeatProcessing`() = runTest {
        val heardRepeats = FakeHeardRepeatProcessing()
        val withHeardRepeats = RxLogService(session, rxLogStore, channelStore, contactStore, DiscoveredNodeStore(database), heardRepeats)
        withHeardRepeats.startEventMonitoring(radioID)
        Thread.sleep(50) // let startEventMonitoring's loadSecretsFromDatabase settle first — see the SUCCESS test above.
        val secret = ByteArray(16) { (it * 3).toByte() }
        val payload = encryptedChannelPayload(channelHashByte = 0x00, secret = secret, timestamp = 555u, txtType = 0, text = "Node: late")
        // process()'s own unconditional forward already records one NO_MATCHING_KEY entry (decodedText null)
        // before the secret is known; the reprocess sweep below adds a second, decrypted one.
        withHeardRepeats.process(parsedGroupText(payload))

        withHeardRepeats.updateChannels(mapOf(0u.toUByte() to secret), mapOf(0u.toUByte() to "General"))
        withHeardRepeats.stopEventMonitoring()

        assertEquals(2, heardRepeats.processed.size)
        assertEquals("Node: late", heardRepeats.processed.last().decodedText)
    }

    @Test
    fun `lookupPathData finds a channel entry via the primary (channelIndex, senderTimestamp) key`() = runTest {
        val secret = ByteArray(16) { it.toByte() }
        channelStore.saveChannel(radioID, ChannelInfo(0u, "General", secret))
        service.startEventMonitoring(radioID)
        Thread.sleep(50)
        val payload = encryptedChannelPayload(channelHashByte = 0x00, secret = secret, timestamp = 9000u, txtType = 0, text = "hop test")
        service.process(parsedGroupText(payload))
        awaitUntil { rxLogStore.fetchRecentEntriesByDecryptStatus(radioID, DecryptStatus.SUCCESS, Instant.EPOCH.plusSeconds(1)).isNotEmpty() }

        val result = service.lookupPathData(radioID, channelIndex = 0u, senderTimestamp = 9000u, senderPublicKeyPrefix = null, defaultPathLength = 0u)

        assertEquals(2, result.pathLength.toInt())
        assertEquals(RouteType.FLOOD, result.routeType)
    }

    @Test
    fun `lookupPathData falls back to the DM sender-prefix match when the primary timestamp lookup misses`() = runTest {
        service.startEventMonitoring(radioID)
        val dmPayload = byteArrayOf(0x00, 0x77.toByte()) + ByteArray(20)
        val parsed = ParsedRxLogData(
            snr = null, rssi = null, rawPayload = dmPayload,
            routeType = RouteType.DIRECT, payloadType = PayloadType.TEXT_MESSAGE, payloadVersion = 1u,
            payloadTypeBits = 2u, transportCode = null, pathLength = 3u, pathNodes = listOf(0x01u),
            packetPayload = dmPayload,
        )
        service.process(parsed) // no private key configured -> DM_NO_MATCHING_KEY, no senderTimestamp

        val result = service.lookupPathData(radioID, channelIndex = null, senderTimestamp = 123456u, senderPublicKeyPrefix = byteArrayOf(0x77), defaultPathLength = 9u)

        assertEquals(3, result.pathLength.toInt())
        assertEquals(RouteType.DIRECT, result.routeType)
    }

    @Test
    fun `lookupPathData returns the default path length unchanged on a total miss`() = runTest {
        val result = service.lookupPathData(radioID, channelIndex = 0u, senderTimestamp = 42u, senderPublicKeyPrefix = null, defaultPathLength = 7u)

        assertNull(result.pathNodes)
        assertEquals(7, result.pathLength.toInt())
        assertNull(result.routeType)
    }

    @Test
    fun `stopEventMonitoring really unsubscribes from the session's event flow`() = runTest {
        service.startEventMonitoring(radioID)
        awaitUntil { session.eventsFlow.subscriptionCount.value > 0 }

        service.stopEventMonitoring()

        assertFalse(session.eventsFlow.subscriptionCount.value > 0)
    }

    @Test
    fun `entryStream rebroadcasts every processed entry to a live subscriber`() = runTest {
        service.startEventMonitoring(radioID)
        val received = mutableListOf<com.meshcoretwo.services.persistence.RxLogDto>()
        // Dispatchers.Unconfined runs the launch body eagerly up to its first suspension point
        // (inside collect), so the subscriber is registered before this call returns — same
        // "wait for a real subscriber" trick as [FakeRxLogSession]'s doc, without needing one.
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            .launch { service.entryStream().collect { received.add(it) } }

        service.process(parsedGroupText(byteArrayOf(0x00) + ByteArray(18)))

        awaitUntil { received.size == 1 }
        job.cancel()
        assertEquals(DecryptStatus.NO_MATCHING_KEY, received[0].decryptStatus)
    }

    @Test
    fun `loadExistingEntries re-decrypts persisted rows with the current channel secret`() = runTest {
        val secret = ByteArray(16) { it.toByte() }
        service.startEventMonitoring(radioID)
        // Process while the secret is unknown, so the persisted row is NO_MATCHING_KEY with no decodedText.
        val payload = encryptedChannelPayload(channelHashByte = 0x00, secret = secret, timestamp = 42u, txtType = 0, text = "reload me")
        service.process(parsedGroupText(payload))
        val stored = rxLogStore.fetchRecentEntriesByDecryptStatus(radioID, DecryptStatus.NO_MATCHING_KEY, Instant.EPOCH.plusSeconds(1))
        assertEquals(1, stored.size)
        assertNull(stored[0].decodedText)

        // A fresh service instance simulates the app's next launch: the secret is now known up front,
        // but the persisted decryptStatus/decodedText are unchanged (decodedText is never written).
        val reloaded = RxLogService(session, rxLogStore, ChannelStore(database).also {
            kotlinx.coroutines.runBlocking { it.saveChannel(radioID, ChannelInfo(0u, "General", secret)) }
        }, contactStore, DiscoveredNodeStore(database))
        reloaded.startEventMonitoring(radioID)
        awaitUntil { true } // let startEventMonitoring's loadSecretsFromDatabase complete
        Thread.sleep(50)

        val entries = reloaded.loadExistingEntries()

        assertEquals(1, entries.size)
        assertEquals("reload me", entries[0].decodedText)
        reloaded.stopEventMonitoring()
    }

    @Test
    fun `clearEntries deletes every persisted row for the active radio`() = runTest {
        service.startEventMonitoring(radioID)
        service.process(parsedGroupText(byteArrayOf(0x00) + ByteArray(18)))
        assertEquals(1, service.loadExistingEntries().size)

        service.clearEntries()

        assertTrue(service.loadExistingEntries().isEmpty())
    }

    // MARK: - Advert Inbound Hop Count

    private fun meshContact(publicKey: ByteArray, name: String = "Node") = MeshContact(
        id = publicKey.joinToString("") { "%02x".format(it) },
        publicKey = publicKey,
        type = ContactType.REPEATER,
        flags = ContactFlags.NONE,
        outPathLength = 0xFFu,
        outPath = ByteArray(0),
        advertisedName = name,
        lastAdvertisement = Instant.ofEpochSecond(900),
        latitude = 0.0,
        longitude = 0.0,
        lastModified = Instant.ofEpochSecond(1000),
    )

    /** An advert wire payload begins with the advertiser's full 32-byte public key. */
    private fun advertPayload(publicKey: ByteArray) = publicKey + byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())

    private fun parsedAdvert(pathLength: UByte, packetPayload: ByteArray, routeType: RouteType = RouteType.FLOOD) = ParsedRxLogData(
        snr = 8.0, rssi = -70, rawPayload = packetPayload,
        routeType = routeType, payloadType = PayloadType.ADVERT, payloadVersion = 0u,
        payloadTypeBits = 4u, transportCode = null,
        pathLength = pathLength, pathNodes = emptyList(),
        packetPayload = packetPayload,
    )

    /**
     * Every [DiscoveredNodeStore.setInboundHopCount] call in this section runs before the
     * corresponding Discover row exists, so its hop count is buffered (see
     * [DiscoveredNodeStore]'s pending-inbound-hops doc) — draining it via an [upsertDiscoveredNode]
     * call is how these tests observe what [RxLogService.process] actually stamped.
     */
    private suspend fun drainedInboundHopCount(publicKey: ByteArray): Int? =
        discoveredNodeStore.upsertDiscoveredNode(radioID, meshContact(publicKey)).first.inboundHopCount

    @Test
    fun `process stamps the inbound hop count for a flood-routed advert, keyed by full pubkey`() = runTest {
        val advertiserKey = ByteArray(32) { it.toByte() }
        service.startEventMonitoring(radioID)

        service.process(parsedAdvert(pathLength = encodePathLen(hashSize = 1, hopCount = 3), packetPayload = advertPayload(advertiserKey)))

        assertEquals(3, drainedInboundHopCount(advertiserKey))
    }

    @Test
    fun `a directly-heard advert stamps a hop count of zero`() = runTest {
        val advertiserKey = ByteArray(32) { it.toByte() }
        service.startEventMonitoring(radioID)

        service.process(parsedAdvert(pathLength = encodePathLen(hashSize = 1, hopCount = 0), packetPayload = advertPayload(advertiserKey)))

        assertEquals(0, drainedInboundHopCount(advertiserKey))
    }

    @Test
    fun `a truncated advert payload shorter than a public key is never stamped`() = runTest {
        val advertiserKey = ByteArray(32) { it.toByte() }
        service.startEventMonitoring(radioID)

        service.process(parsedAdvert(pathLength = encodePathLen(hashSize = 1, hopCount = 1), packetPayload = byteArrayOf(0x01, 0x02, 0x03)))

        assertNull(drainedInboundHopCount(advertiserKey))
    }

    @Test
    fun `advert timestamp is extracted from the payload and passed to the store`() = runTest {
        val advertiserKey = ByteArray(32) { it.toByte() }
        service.startEventMonitoring(radioID)
        val timestamp = 100u
        val tsBytes = byteArrayOf(
            (timestamp and 0xFFu).toByte(), ((timestamp shr 8) and 0xFFu).toByte(),
            ((timestamp shr 16) and 0xFFu).toByte(), ((timestamp shr 24) and 0xFFu).toByte(),
        )

        service.process(parsedAdvert(pathLength = encodePathLen(hashSize = 1, hopCount = 2), packetPayload = advertiserKey + tsBytes))

        val node = discoveredNodeStore.upsertDiscoveredNode(radioID, meshContact(advertiserKey)).first
        assertEquals(timestamp, node.inboundHopAdvertTimestamp)
    }

    @Test
    fun `a payload exactly 32 bytes with no timestamp passes a null advertTimestamp`() = runTest {
        val advertiserKey = ByteArray(32) { it.toByte() }
        service.startEventMonitoring(radioID)

        service.process(parsedAdvert(pathLength = encodePathLen(hashSize = 1, hopCount = 1), packetPayload = advertiserKey))

        val node = discoveredNodeStore.upsertDiscoveredNode(radioID, meshContact(advertiserKey)).first
        assertEquals(1, node.inboundHopCount)
        assertNull(node.inboundHopAdvertTimestamp)
    }

    @Test
    fun `a non-advert entry never stamps an inbound hop count`() = runTest {
        val advertiserKey = ByteArray(32) { it.toByte() }
        service.startEventMonitoring(radioID)

        service.process(parsedGroupText(advertPayload(advertiserKey)))

        assertNull(drainedInboundHopCount(advertiserKey))
    }

    @Test
    fun `a direct-routed advert is never stamped, its path length is route not hops traversed`() = runTest {
        val advertiserKey = ByteArray(32) { it.toByte() }
        service.startEventMonitoring(radioID)

        service.process(
            parsedAdvert(
                pathLength = encodePathLen(hashSize = 1, hopCount = 2),
                packetPayload = advertPayload(advertiserKey),
                routeType = RouteType.DIRECT,
            ),
        )

        assertNull(drainedInboundHopCount(advertiserKey))
    }

    /** Packs a [com.meshcoretwo.protocol.TransportCodeRegionResolver.calcTransportCode] result into the little-endian `transport_codes[0..1]` wire layout [ParsedRxLogData.transportCode] expects — the other two bytes (`transport_codes[1]`) are irrelevant to region matching, so left zeroed. */
    private fun transportCodeBytes(code0: UShort): ByteArray {
        val value = code0.toInt()
        return byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(), 0, 0)
    }

    @Test
    fun `process resolves a region for a TC_FLOOD packet against an already-known region`() = runTest {
        val regionName = "de-hh"
        val scopeKey = TransportCodeRegionResolver.deriveScopeKey(regionName)!!
        val payload = byteArrayOf(0x00) + ByteArray(18)
        val code0 = TransportCodeRegionResolver.calcTransportCode(scopeKey, payloadTypeBits = 5u, payload = payload)

        service.startEventMonitoring(radioID)
        service.updateKnownRegions(listOf(regionName))
        service.process(
            ParsedRxLogData(
                snr = null, rssi = null, rawPayload = payload,
                routeType = RouteType.TC_FLOOD, payloadType = PayloadType.GROUP_TEXT, payloadVersion = 1u,
                payloadTypeBits = 5u, transportCode = transportCodeBytes(code0),
                pathLength = 2u, pathNodes = listOf(0x11u, 0x22u),
                packetPayload = payload,
            ),
        )

        val entries = rxLogStore.fetchEntriesWithTransportCode(radioID, 10)
        assertEquals(1, entries.size)
        assertEquals(regionName, entries[0].regionScope)
        assertEquals(listOf(regionName), entries[0].regionScopeMatches)
    }

    @Test
    fun `process does not resolve a region when the packet's route type carries no transport code`() = runTest {
        val regionName = "de-hh"
        val secret = ByteArray(16) { it.toByte() }
        channelStore.saveChannel(radioID, ChannelInfo(0u, "General", secret))
        service.startEventMonitoring(radioID)
        service.updateKnownRegions(listOf(regionName))
        Thread.sleep(50)

        // Plain FLOOD (not TC_FLOOD) never carries a transport code, regardless of known regions.
        val payload = encryptedChannelPayload(channelHashByte = 0x00, secret = secret, timestamp = 42u, txtType = 0, text = "hi")
        service.process(parsedGroupText(payload))

        val entries = rxLogStore.fetchRecentEntriesByDecryptStatus(radioID, DecryptStatus.SUCCESS, Instant.EPOCH.plusSeconds(1))
        assertEquals(1, entries.size)
        assertNull(entries[0].regionScope)
    }

    @Test
    fun `updateKnownRegions reprocesses a transport-coded entry and backfills its correlated message`() = runTest {
        val regionName = "de-hh"
        val scopeKey = TransportCodeRegionResolver.deriveScopeKey(regionName)!!
        val secret = ByteArray(16) { it.toByte() }
        val payload = encryptedChannelPayload(channelHashByte = 0x00, secret = secret, timestamp = 777u, txtType = 0, text = "Node: hi")
        val code0 = TransportCodeRegionResolver.calcTransportCode(scopeKey, payloadTypeBits = 5u, payload = payload)

        val messageStore = MessageStore(database)
        val withMessages = RxLogService(session, rxLogStore, channelStore, contactStore, DiscoveredNodeStore(database), messageStore = messageStore)
        channelStore.saveChannel(radioID, ChannelInfo(0u, "General", secret))
        withMessages.startEventMonitoring(radioID)
        Thread.sleep(50) // let startEventMonitoring's loadSecretsFromDatabase settle first — see the SUCCESS test above.

        // Arrives before the region is known: decrypts fine (channel secret is already cached), but stays unresolved.
        withMessages.process(
            ParsedRxLogData(
                snr = null, rssi = null, rawPayload = payload,
                routeType = RouteType.TC_FLOOD, payloadType = PayloadType.GROUP_TEXT, payloadVersion = 1u,
                payloadTypeBits = 5u, transportCode = transportCodeBytes(code0),
                pathLength = 2u, pathNodes = listOf(0x11u, 0x22u),
                packetPayload = payload,
            ),
        )
        val beforeEntries = rxLogStore.fetchEntriesWithTransportCode(radioID, 10)
        assertEquals(1, beforeEntries.size)
        assertNull(beforeEntries[0].regionScope)
        assertEquals(777u, beforeEntries[0].senderTimestamp)

        // The message IncomingMessageService would have created for this same packet, already correlated by (channelIndex, timestamp).
        val messageID = UUID.randomUUID()
        messageStore.saveMessage(
            MessageDto(
                id = messageID,
                radioID = radioID,
                contactID = null,
                channelIndex = 0u,
                text = "hi",
                timestamp = 777u,
                createdAt = Instant.now(),
                sortDate = Instant.now(),
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                textType = TextType.PLAIN_TEXT,
                ackCode = null,
                pathLength = 2u,
                snr = null,
                pathNodes = null,
                senderKeyPrefix = null,
                senderNodeName = "Node",
                isRead = false,
                replyToID = null,
                roundTripTime = null,
                sendCount = 1,
                retryAttempt = 0,
                maxRetryAttempts = 0,
                deduplicationKey = null,
                reactionSummary = null,
                senderTimestamp = null,
                routeType = RouteType.TC_FLOOD,
                heardRepeats = 0,
            ),
        )

        withMessages.updateKnownRegions(listOf(regionName))

        val afterEntries = rxLogStore.fetchEntriesWithTransportCode(radioID, 10)
        assertEquals(regionName, afterEntries.single().regionScope)
        val updatedMessage = messageStore.fetchMessage(messageID)
        assertEquals(regionName, updatedMessage?.regionScope)
        assertEquals(listOf(regionName), updatedMessage?.regionScopeMatches)

        withMessages.stopEventMonitoring()
    }
}

/** Records every [com.meshcoretwo.services.persistence.RxLogDto] handed to it, verbatim — including its transient [com.meshcoretwo.services.persistence.RxLogDto.decodedText]. */
private class FakeHeardRepeatProcessing : HeardRepeatProcessing {
    val processed = mutableListOf<com.meshcoretwo.services.persistence.RxLogDto>()

    override suspend fun processForRepeats(entry: com.meshcoretwo.services.persistence.RxLogDto): Int? {
        processed.add(entry)
        return null
    }
}

/** Hand-written [RxLogSessionOps] test double — see [AdvertisementServiceTest]'s class doc for the "wait for a real subscriber" trick this reuses. */
private class FakeRxLogSession : RxLogSessionOps {
    val eventsFlow = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 16)

    override val connectionState: Flow<ConnectionState> get() = error("not used by this test")
    override suspend fun events(): Flow<MeshEvent> = eventsFlow
    override suspend fun events(filter: EventFilter): Flow<MeshEvent> = eventsFlow.filter { filter.matches(it) }
    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? = error("not used by this test")

    override suspend fun sendAppStart(): SelfInfo = error("not used by this test")
    override suspend fun queryDevice() = error("not used by this test")
    override suspend fun getBattery() = error("not used by this test")
    override suspend fun getTime(): Instant = error("not used by this test")
    override suspend fun setTime(date: Instant) = error("not used by this test")
    override suspend fun setName(name: String) = error("not used by this test")
    override suspend fun setCoordinates(latitude: Double, longitude: Double) = error("not used by this test")
    override suspend fun setTxPower(power: Byte) = error("not used by this test")
    override suspend fun setRadio(frequency: Double, bandwidth: Double, spreadingFactor: UByte, codingRate: UByte, clientRepeat: Boolean?) = error("not used by this test")
    override suspend fun getRepeatFreq() = error("not used by this test")
    override suspend fun setOtherParams(
        manualAddContacts: Boolean,
        telemetryModeEnvironment: UByte,
        telemetryModeLocation: UByte,
        telemetryModeBase: UByte,
        advertisementLocationPolicy: UByte,
        multiAcks: UByte?,
    ) = error("not used by this test")
    override suspend fun setDevicePin(pin: UInt) = error("not used by this test")
    override suspend fun getAutoAddConfig() = error("not used by this test")
    override suspend fun setAutoAddConfig(config: com.meshcoretwo.protocol.AutoAddConfig) = error("not used by this test")
    override suspend fun setPathHashMode(mode: UByte) = error("not used by this test")
    override suspend fun setDefaultFloodScope(name: String, scope: com.meshcoretwo.protocol.FloodScope) = error("not used by this test")
    override suspend fun getDefaultFloodScope() = error("not used by this test")
    override suspend fun reboot() = error("not used by this test")
    override suspend fun factoryReset() = error("not used by this test")
    override suspend fun getStatsCore() = error("not used by this test")
    override suspend fun getStatsRadio() = error("not used by this test")
    override suspend fun getStatsPackets() = error("not used by this test")
    override suspend fun getCustomVars() = error("not used by this test")
    override suspend fun setCustomVar(key: String, value: String) = error("not used by this test")
    override suspend fun exportPrivateKey(): ByteArray = error("not used by this test")
    override suspend fun importPrivateKey(key: ByteArray) = error("not used by this test")
    override suspend fun sign(data: ByteArray, chunkSize: Int, timeout: Double?) = error("not used by this test")
}
