// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.device

import androidx.room.Room
import com.meshcoretwo.services.persistence.DeviceDto
import com.meshcoretwo.services.persistence.DeviceStore
import com.meshcoretwo.services.persistence.MeshCoreDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.UUID

/** Port of the one method `DeviceService.swift` has: OCV settings update. */
@RunWith(RobolectricTestRunner::class)
class DeviceServiceTest {
    private lateinit var database: MeshCoreDatabase
    private lateinit var deviceStore: DeviceStore
    private lateinit var service: DeviceService

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        deviceStore = DeviceStore(database)
        service = DeviceService(deviceStore)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun deviceDto(id: UUID) = DeviceDto(
        id = id,
        radioID = UUID.randomUUID(),
        publicKey = ByteArray(32),
        nodeName = "Radio",
        firmwareVersion = 10u,
        firmwareVersionString = "v1.16.0",
        manufacturerName = "",
        buildDate = "",
        maxContacts = 100u,
        maxChannels = 8u,
        frequency = 915_000u,
        bandwidth = 250_000u,
        spreadingFactor = 10u,
        codingRate = 5u,
        txPower = 20,
        maxTxPower = 20,
        latitude = 0.0,
        longitude = 0.0,
        blePin = 0u,
        lastConnected = Instant.now(),
        lastContactSync = 0u,
        isActive = true,
        ocvPreset = null,
        customOCVArrayString = null,
    )

    @Test
    fun `updateOCVSettings persists the preset and notifies the callback`() = runTest {
        val id = UUID.randomUUID()
        deviceStore.saveDevice(deviceDto(id))
        var notified: DeviceDto? = null
        service.setDeviceUpdateCallback { notified = it }

        service.updateOCVSettings(id, preset = "custom", customArray = "4240,4112,4029")

        val stored = deviceStore.fetchDeviceById(id)
        assertEquals("custom", stored?.ocvPreset)
        assertEquals("4240,4112,4029", stored?.customOCVArrayString)
        assertEquals("custom", notified?.ocvPreset)
    }

    @Test
    fun `updateOCVSettings throws DeviceNotFound for an unknown device`() = runTest {
        try {
            service.updateOCVSettings(UUID.randomUUID(), preset = "liIon", customArray = null)
            fail("expected DeviceServiceError.DeviceNotFound")
        } catch (error: DeviceServiceError.DeviceNotFound) {
            // expected
        }
    }

    @Test
    fun `updateOCVSettings can clear the custom array`() = runTest {
        val id = UUID.randomUUID()
        deviceStore.saveDevice(deviceDto(id).copy(ocvPreset = "custom", customOCVArrayString = "1,2,3"))

        service.updateOCVSettings(id, preset = "liIon", customArray = null)

        assertEquals("liIon", deviceStore.fetchDeviceById(id)?.ocvPreset)
        assertNull(deviceStore.fetchDeviceById(id)?.customOCVArrayString)
    }
}
