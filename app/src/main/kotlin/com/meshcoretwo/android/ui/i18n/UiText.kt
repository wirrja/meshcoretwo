// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.i18n

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * A user-visible string that isn't resolved yet. ViewModels and services can't call
 * `stringResource`, and resolving early would freeze the text in the language that was active at
 * that moment; a [UiText] is resolved by the UI ([asString]) at render time instead.
 */
sealed interface UiText {
    /** Already-final text (user data, node names, technical strings that are never translated). */
    data class Plain(val value: String) : UiText

    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    fun resolve(context: Context): String = when (this) {
        is Plain -> value
        is Res -> context.getString(id, *args.map { it.resolveArg(context) }.toTypedArray())
        is Plural -> context.resources.getQuantityString(id, count, *args.map { it.resolveArg(context) }.toTypedArray())
    }

    @Composable
    fun asString(): String = resolve(LocalContext.current)

    companion object {
        fun of(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
            Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())
    }
}

private fun Any.resolveArg(context: Context): Any = if (this is UiText) resolve(context) else this
