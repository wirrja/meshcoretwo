// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.settings

import com.meshcoretwo.protocol.AutoAddConfig

/**
 * The auto-add behavior for discovered nodes. Ported from `AutoAddMode.swift`.
 */
enum class AutoAddMode {
    /** Review all nodes in Discover before adding. */
    MANUAL,

    /** Auto-add only the types enabled in settings. */
    SELECTED_TYPES,

    /** Auto-add every discovered node. */
    ALL,
    ;

    companion object {
        private val TYPE_BITS_MASK: UByte =
            AutoAddConfig.CONTACTS_BIT or AutoAddConfig.REPEATERS_BIT or AutoAddConfig.ROOM_SERVERS_BIT or AutoAddConfig.SENSORS_BIT

        /**
         * Computes the auto-add mode from device settings. Ported from `AutoAddMode.mode(manualAddContacts:autoAddConfig:)`.
         *
         * Protocol mapping:
         * - `manualAddContacts=false` -> [ALL] (firmware auto-adds everything)
         * - `manualAddContacts=true` + no type bits -> [MANUAL] (user reviews all in Discover)
         * - `manualAddContacts=true` + type bits set -> [SELECTED_TYPES] (firmware auto-adds selected types)
         */
        fun mode(manualAddContacts: Boolean, autoAddConfig: UByte): AutoAddMode = when {
            !manualAddContacts -> ALL
            autoAddConfig and TYPE_BITS_MASK == 0u.toUByte() -> MANUAL
            else -> SELECTED_TYPES
        }
    }
}
