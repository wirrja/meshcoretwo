// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Session operations for broadcasting self-advertisements and refreshing advertised identity
 * data.
 */
interface AdvertisingSessionOps {
    /**
     * Sends an advertisement broadcast.
     *
     * @param flood If `true`, the advertisement is broadcast using flood routing.
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun sendAdvertisement(flood: Boolean = false)

    /**
     * Sets the device's advertised name.
     *
     * @param name The name to advertise (max 32 bytes UTF-8).
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun setName(name: String)

    /**
     * Sets the device's GPS coordinates.
     *
     * @param latitude Latitude in degrees (-90 to 90).
     * @param longitude Longitude in degrees (-180 to 180).
     * @throws MeshCoreError on timeout or device error.
     */
    suspend fun setCoordinates(latitude: Double, longitude: Double)

    /**
     * Fetches a single contact from the device by public key.
     *
     * @param publicKey The full 32-byte public key of the contact.
     * @return The contact if found, or `null` if no contact exists with that key.
     * @throws MeshCoreError if the query fails.
     */
    suspend fun getContact(publicKey: ByteArray): MeshContact?
}
