// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.connection

/** Outcome of a bulk contact-removal operation. Ported from `RemoveUnfavoritedResult.swift`. */
data class RemoveUnfavoritedResult(val removed: Int, val total: Int)
