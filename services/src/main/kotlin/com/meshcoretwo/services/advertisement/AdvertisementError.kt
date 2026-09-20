// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.advertisement

import com.meshcoretwo.protocol.MeshCoreError

/** Errors [AdvertisementService]'s outbound facade can throw. */
sealed class AdvertisementError(message: String) : Exception(message) {
    /** The underlying session operation failed; [error] carries the specific reason. */
    data class SessionError(val error: MeshCoreError) : AdvertisementError(error.message ?: "session error")
}
