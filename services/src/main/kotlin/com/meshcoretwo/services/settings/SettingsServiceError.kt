// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.settings

import com.meshcoretwo.protocol.MeshCoreError

/**
 * Errors [SettingsService] can throw. Ported from `SettingsServiceError.swift`, trimmed to the
 * cases this vertical slice's methods actually throw — `notConnected`/`sendFailed`/
 * `invalidResponse` are declared in Swift but never constructed by `SettingsService.swift` itself
 * (session-layer failures always surface as [SessionError] instead); add them if a ported method
 * needs to throw them.
 */
sealed class SettingsServiceError(message: String) : Exception(message) {
    /** The underlying session operation failed; [error] carries the specific reason. */
    data class SessionError(val error: MeshCoreError) : SettingsServiceError(error.message ?: "session error")

    /** A write succeeded but the device's read-back value didn't match what was sent. */
    data class VerificationFailed(val expectedValue: String, val actualValue: String) :
        SettingsServiceError("Setting was not saved. Expected '$expectedValue' but device reports '$actualValue'.")

    /** [SettingsService.setDeviceGPSEnabledVerified]'s read-back didn't match the requested state. */
    data class DeviceGPSVerificationFailed(val expectedEnabled: Boolean, val actualEnabled: Boolean) :
        SettingsServiceError(
            "Device GPS setting was not saved. Expected '${if (expectedEnabled) "On" else "Off"}' " +
                "but device reports '${if (actualEnabled) "On" else "Off"}'.",
        )

    /** Whether this error suggests a connection issue that might be resolved by retrying. */
    val isRetryable: Boolean
        get() = (this as? SessionError)?.error is MeshCoreError.Timeout
}
