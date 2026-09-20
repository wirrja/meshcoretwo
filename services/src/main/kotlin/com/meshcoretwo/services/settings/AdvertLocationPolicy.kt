// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.settings

/** Location inclusion policy for advertisements. Ported from `AdvertLocationPolicy.swift`. */
enum class AdvertLocationPolicy(val rawValue: UByte) {
    NONE(0u),
    SHARE(1u),
    PREFS(2u),
    ;

    val isEnabled: Boolean get() = this != NONE

    companion object {
        fun fromRawValue(rawValue: UByte): AdvertLocationPolicy? = entries.find { it.rawValue == rawValue }
    }
}
