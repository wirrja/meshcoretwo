// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.contacts

/** Result of a contact sync operation. Ported from `ContactSyncResult` (`ContactService.swift`). */
data class ContactSyncResult(
    val contactsReceived: Int,
    val lastSyncTimestamp: UInt,
    val isIncremental: Boolean,
)
