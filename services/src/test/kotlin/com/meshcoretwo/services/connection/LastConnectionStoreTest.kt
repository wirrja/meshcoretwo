// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/** Ported from `LastConnectionStoreTests.swift`. */
@RunWith(RobolectricTestRunner::class)
class LastConnectionStoreTest {
    private fun newStore() = LastConnectionStore(
        RuntimeEnvironment.getApplication().getSharedPreferences("last-connection-test-${UUID.randomUUID()}", Context.MODE_PRIVATE),
    )

    @Test
    fun `fields are null before anything is persisted`() {
        val store = newStore()
        assertNull(store.deviceID)
        assertNull(store.radioID)
        assertNull(store.deviceName)
        assertNull(store.disconnectDiagnostic)
        assertNull(store.bondVerifiedDeviceID)
    }

    @Test
    fun `persist then read back device identity`() {
        val store = newStore()
        val deviceID = UUID.randomUUID()
        val radioID = UUID.randomUUID()

        store.persist(deviceID = deviceID, radioID = radioID, deviceName = "My Node")

        assertEquals(deviceID, store.deviceID)
        assertEquals(radioID, store.radioID)
        assertEquals("My Node", store.deviceName)
    }

    @Test
    fun `clear for the holding device removes last-connection keys`() {
        val store = newStore()
        val deviceID = UUID.randomUUID()
        store.persist(deviceID = deviceID, radioID = UUID.randomUUID(), deviceName = "My Node")

        store.clear(deviceID)

        assertNull(store.deviceID)
        assertNull(store.radioID)
        assertNull(store.deviceName)
    }

    @Test
    fun `clear for a non-holding device is a no-op on last-connection keys`() {
        val store = newStore()
        val deviceID = UUID.randomUUID()
        store.persist(deviceID = deviceID, radioID = UUID.randomUUID(), deviceName = "My Node")

        store.clear(UUID.randomUUID())

        assertEquals(deviceID, store.deviceID)
    }

    @Test
    fun `bond verification is independent of the connection slot`() {
        val store = newStore()
        val connectedDevice = UUID.randomUUID()
        val bondedDevice = UUID.randomUUID()
        store.persist(deviceID = connectedDevice, radioID = UUID.randomUUID(), deviceName = "WiFi Node")

        store.persistBondVerification(bondedDevice)

        // Clearing the WiFi-connected device must not touch the bond slot held by a different device.
        store.clear(connectedDevice)
        assertEquals(bondedDevice, store.bondVerifiedDeviceID)
        assertNotNull(store.bondVerificationDate(bondedDevice))
    }

    @Test
    fun `clear for the bond holder removes bond keys`() {
        val store = newStore()
        val deviceID = UUID.randomUUID()
        store.persistBondVerification(deviceID)

        store.clear(deviceID)

        assertNull(store.bondVerifiedDeviceID)
        assertNull(store.bondVerificationDate(deviceID))
    }

    @Test
    fun `bondVerificationDate is null for a device that does not hold the slot`() {
        val store = newStore()
        store.persistBondVerification(UUID.randomUUID())

        assertNull(store.bondVerificationDate(UUID.randomUUID()))
    }

    @Test
    fun `disconnect diagnostic is timestamp-prefixed`() {
        val store = newStore()

        store.persistDisconnectDiagnostic("link timeout")

        val diagnostic = store.disconnectDiagnostic
        assertNotNull(diagnostic)
        assertTrue(diagnostic!!.endsWith("link timeout"))
        assertTrue(diagnostic != "link timeout")
    }
}
