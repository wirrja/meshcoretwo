// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Configuration for device "other params" settings.
 *
 * Used by granular configuration setters to implement a read-modify-write pattern.
 */
data class OtherParamsConfig(
    var manualAddContacts: Boolean = false,
    var telemetryModeBase: UByte = 0u,
    var telemetryModeLocation: UByte = 0u,
    var telemetryModeEnvironment: UByte = 0u,
    var advertisementLocationPolicy: UByte = 0u,
    var multiAcks: UByte = 0u,
) {
    /** Creates a configuration from existing device information. */
    constructor(selfInfo: SelfInfo) : this(
        manualAddContacts = selfInfo.manualAddContacts,
        telemetryModeBase = selfInfo.telemetryModeBase,
        telemetryModeLocation = selfInfo.telemetryModeLocation,
        telemetryModeEnvironment = selfInfo.telemetryModeEnvironment,
        advertisementLocationPolicy = selfInfo.advertisementLocationPolicy,
        multiAcks = selfInfo.multiAcks,
    )
}
