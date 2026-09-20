// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Composition of the session roles [com.meshcoretwo.services.advertisement.AdvertisementService]
 * needs: broadcasting/refreshing self-advertisement data ([AdvertisingSessionOps]) and listening
 * for the device's own change-notification events ([SessionEventStreaming]). Declared as a
 * dedicated interface (matching Swift's `any AdvertisingSessionOps & SessionEventStreaming`
 * existential) rather than widening [MeshCoreSessionProtocol] itself, since most of that
 * umbrella's consumers never touch advertising.
 */
interface AdvertisementSessionOps : AdvertisingSessionOps, SessionEventStreaming
