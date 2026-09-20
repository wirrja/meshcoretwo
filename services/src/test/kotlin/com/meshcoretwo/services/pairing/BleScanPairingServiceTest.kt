// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.pairing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct port of `BluetoothScanPairingServiceTests.swift` — no Robolectric or Android dependency,
 * this class is pure coroutine logic (see [BleScanPairingService]'s class doc). Uses `runBlocking`
 * on a real dispatcher, not `runTest`: the continuation this class suspends on is resolved from a
 * concurrently-running real coroutine ([CoroutineScope(Dispatchers.Default)]), the same reasoning
 * `ConnectionManagerTest` documents for avoiding virtual time here.
 */
class BleScanPairingServiceTest {
    private suspend fun BleScanPairingService.awaitPresenting() =
        withTimeout(20_000) { isPresenting.first { it } }

    @Test
    fun `discoverDevice presents the picker and resolves with the selected address`() = runBlocking {
        val service = BleScanPairingService()

        val task = CoroutineScope(Dispatchers.Default).async { service.discoverDevice() }
        service.awaitPresenting()

        service.select("AA:BB:CC:DD:EE:01")

        assertEquals("AA:BB:CC:DD:EE:01", task.await())
        assertFalse(service.isPresenting.value)
    }

    @Test
    fun `cancel surfaces DevicePairingError Cancelled and lowers presentation`() = runBlocking {
        val service = BleScanPairingService()

        val task = CoroutineScope(Dispatchers.Default).async { service.discoverDevice() }
        service.awaitPresenting()

        service.cancel()

        try {
            task.await()
            throw AssertionError("expected DevicePairingError.Cancelled")
        } catch (e: DevicePairingError.Cancelled) {
            // expected
        }
        assertFalse(service.isPresenting.value)
    }

    @Test
    fun `a new discovery resolves a stranded prior discovery`() = runBlocking {
        val service = BleScanPairingService()

        val first = CoroutineScope(Dispatchers.Default).async { service.discoverDevice() }
        service.awaitPresenting()

        // Starting a second discovery must resolve the first's stranded continuation before
        // installing its own.
        val second = CoroutineScope(Dispatchers.Default).async {
            // Give the first discoverDevice() call a moment to install before this one strands it.
            delay(20)
            service.discoverDevice()
        }
        delay(50)

        try {
            first.await()
            throw AssertionError("expected DevicePairingError.Cancelled")
        } catch (e: DevicePairingError.Cancelled) {
            // expected
        }

        service.awaitPresenting()
        service.select("AA:BB:CC:DD:EE:02")
        assertEquals("AA:BB:CC:DD:EE:02", second.await())
    }

    @Test
    fun `cancelling the discovery coroutine resolves it and lowers presentation`() = runBlocking {
        val service = BleScanPairingService()

        val task = CoroutineScope(Dispatchers.Default).async { service.discoverDevice() }
        service.awaitPresenting()

        task.cancel()

        // Unlike Swift's synchronous same-actor cancellation hop (where `onCancel` resumes the
        // continuation with the custom `.cancelled` error), a coroutine cancelled via `cancel()`
        // always completes with a plain CancellationException regardless of what `resumeWith` is
        // called with inside `invokeOnCancellation` — kotlinx.coroutines owns that outcome once the
        // Job itself is cancelling. `cancel()`'s own resolution path still runs (clearing
        // `isPresenting` and the stranded continuation reference), just not observable through
        // `task.await()`'s thrown type here.
        try {
            task.await()
            throw AssertionError("expected CancellationException")
        } catch (e: CancellationException) {
            // expected
        }
        withTimeout(20_000) { isPresentingClearsEventually(service) }
    }

    private suspend fun isPresentingClearsEventually(service: BleScanPairingService) {
        while (service.isPresenting.value) delay(5)
    }

    @Test
    fun `system pairing registry operations are inert`() = runBlocking {
        val service = BleScanPairingService()

        assertFalse(service.isSessionActive)
        assertFalse(service.hasSystemPairingRegistry)
        assertEquals(0, service.registeredDeviceCount)
        assertFalse(service.supportsSystemRename)
        assertTrue(service.isDeviceConnectable("AA:BB:CC:DD:EE:01"))
        assertTrue(service.registeredDeviceInfos().isEmpty())

        // None of these should throw or have any observable effect.
        service.activate()
        service.removeDevice("AA:BB:CC:DD:EE:01")
        service.renameDevice("AA:BB:CC:DD:EE:01")
        service.clearStaleRegistrations()
    }
}
