// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * The current connection state of a MeshCore session.
 *
 * Use this to update UI based on connection status.
 */
sealed class ConnectionState {
    /** The session is disconnected. */
    data object Disconnected : ConnectionState()

    /** The session is attempting to connect. */
    data object Connecting : ConnectionState()

    /** The session is successfully connected. */
    data object Connected : ConnectionState()

    /** The session is attempting to reconnect after a failure. */
    data class Reconnecting(val attempt: Int) : ConnectionState()

    /** The session connection failed with a specific error. */
    data class Failed(val error: MeshTransportError) : ConnectionState()
}
