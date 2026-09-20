// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.rf

/** Clearance status at the worst point along a path. Ported from `ClearanceStatus.swift`. */
enum class ClearanceStatus {
    CLEAR,
    MARGINAL,
    PARTIAL_OBSTRUCTION,
    BLOCKED,
}
