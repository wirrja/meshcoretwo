// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.settings

import com.meshcoretwo.protocol.AutoAddConfig
import com.meshcoretwo.protocol.FrequencyRange
import com.meshcoretwo.protocol.SelfInfo

/** Events emitted by [SettingsService] when device settings change. Ported from `SettingsEvent.swift`. */
sealed class SettingsEvent {
    data class DeviceUpdated(val selfInfo: SelfInfo) : SettingsEvent()
    data class AutoAddConfigUpdated(val config: AutoAddConfig) : SettingsEvent()
    data class ClientRepeatUpdated(val enabled: Boolean) : SettingsEvent()
    data class PathHashModeUpdated(val mode: UByte) : SettingsEvent()
    data class AllowedRepeatFreqUpdated(val ranges: List<FrequencyRange>) : SettingsEvent()
    data class DefaultFloodScopeUpdated(val name: String?) : SettingsEvent()
}
