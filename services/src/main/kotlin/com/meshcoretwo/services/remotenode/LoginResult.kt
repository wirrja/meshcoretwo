// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.remotenode

import com.meshcoretwo.services.persistence.RoomPermissionLevel
import java.time.Instant

/** The outcome of a [RemoteNodeService.login] attempt. Ported from `LoginResult.swift`. */
data class LoginResult(
    val success: Boolean,
    val isAdmin: Boolean,
    val aclPermissions: UByte?,
    val publicKeyPrefix: ByteArray,
    /** The remote node's RTC reading from the login response, if carried. */
    val serverTime: Instant? = null,
) {
    val permissionLevel: RoomPermissionLevel
        get() = if (isAdmin) RoomPermissionLevel.ADMIN else RoomPermissionLevel.fromRawValue(aclPermissions ?: 0u) ?: RoomPermissionLevel.GUEST

    // ByteArray has reference equality under ==, so generated equals()/hashCode() need overriding.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LoginResult) return false
        return success == other.success &&
            isAdmin == other.isAdmin &&
            aclPermissions == other.aclPermissions &&
            publicKeyPrefix.contentEquals(other.publicKeyPrefix) &&
            serverTime == other.serverTime
    }

    override fun hashCode(): Int {
        var result = success.hashCode()
        result = 31 * result + isAdmin.hashCode()
        result = 31 * result + (aclPermissions?.hashCode() ?: 0)
        result = 31 * result + publicKeyPrefix.contentHashCode()
        result = 31 * result + (serverTime?.hashCode() ?: 0)
        return result
    }
}
