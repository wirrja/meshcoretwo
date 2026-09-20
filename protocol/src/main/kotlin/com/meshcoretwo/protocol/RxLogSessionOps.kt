// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Composition of the session roles [com.meshcoretwo.services.rxlog.RxLogService] needs:
 * exporting the device private key for DM-decrypt correlation ([ConfigurationSessionOps]) and
 * listening for `rxLogData` events ([SessionEventStreaming]). Same pattern as
 * [AdvertisementSessionOps] — a dedicated interface rather than widening
 * [MeshCoreSessionProtocol] itself, since most of that umbrella's consumers never touch RX-log
 * diagnostics.
 */
interface RxLogSessionOps : ConfigurationSessionOps, SessionEventStreaming
