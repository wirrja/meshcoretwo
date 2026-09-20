// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.util.UUID

/**
 * Room DAO for [DeviceEntity] — dumb CRUD only, matching [ContactDao]'s split with [DeviceStore]
 * owning upsert/activation logic. Ported from the subset of `PersistenceStore+Devices.swift`
 * this vertical slice covers.
 */
@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY lastConnected DESC")
    suspend fun fetchDevices(): List<DeviceEntity>

    // Kotlin/JVM overload resolution is by parameter type, not name, and both lookups take a
    // single UUID — distinct names avoid an unresolvable overload clash.
    @Query("SELECT * FROM devices WHERE id = :id LIMIT 1")
    suspend fun fetchDeviceById(id: UUID): DeviceEntity?

    @Query("SELECT * FROM devices WHERE radioID = :radioID LIMIT 1")
    suspend fun fetchDeviceByRadioId(radioID: UUID): DeviceEntity?

    @Query("SELECT * FROM devices WHERE publicKey = :publicKey LIMIT 1")
    suspend fun fetchDevice(publicKey: ByteArray): DeviceEntity?

    @Query("SELECT * FROM devices WHERE bleAddress = :bleAddress LIMIT 1")
    suspend fun fetchDeviceByBleAddress(bleAddress: String): DeviceEntity?

    /** A ghost row (see [DeviceEntity.isGhost]) matching [publicKey], other than [excludingId] itself. */
    @Query("SELECT * FROM devices WHERE publicKey = :publicKey AND id != :excludingId AND isGhost = 1 LIMIT 1")
    suspend fun fetchGhostByPublicKey(publicKey: ByteArray, excludingId: UUID): DeviceEntity?

    @Insert
    suspend fun insert(device: DeviceEntity)

    @Update
    suspend fun update(device: DeviceEntity)

    @Query("UPDATE devices SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE devices SET lastContactSync = :timestamp WHERE radioID = :radioID")
    suspend fun updateLastContactSync(radioID: UUID, timestamp: Long)

    @Query("DELETE FROM devices WHERE id = :id")
    suspend fun deleteById(id: UUID)
}
