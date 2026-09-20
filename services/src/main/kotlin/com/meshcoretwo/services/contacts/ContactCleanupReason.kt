// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.contacts

/** Reason for contact cleanup (deletion or blocking). Ported from `ContactCleanupReason` (`ContactService.swift`). */
enum class ContactCleanupReason {
    DELETED,
    BLOCKED,
    UNBLOCKED,
}
