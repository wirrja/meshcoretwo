// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import java.util.UUID

/** Ported from `CLIOutputLine.swift`'s `CLIOutputType` — color is applied by the screen, not stored here. */
enum class CLIOutputType {
    COMMAND,
    SUCCESS,
    ERROR,
    RESPONSE,
}

/** A single rendered line of [NodeCLIViewModel]'s terminal output. Ported from `CLIOutputLine.swift`. */
data class CLIOutputLine(
    val id: UUID = UUID.randomUUID(),
    val text: String,
    val type: CLIOutputType,
)
