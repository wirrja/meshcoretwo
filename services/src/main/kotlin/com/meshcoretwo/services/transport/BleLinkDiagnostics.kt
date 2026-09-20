// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.transport

/**
 * One consistent snapshot of the transport's observable state, read in a single hop so a log
 * line cannot interleave values from different moments.
 *
 * Ported from `BLELinkDiagnostics.swift`.
 */
data class BleLinkDiagnostics(
    /** Bluetooth adapter state name (e.g. "STATE_ON", "STATE_OFF"). */
    val adapterState: String,
    /** The state machine's current phase. */
    val phase: BlePhaseKind,
    /** Peripheral connection state name, or null when no phase owns a device. */
    val deviceState: String?,
)
