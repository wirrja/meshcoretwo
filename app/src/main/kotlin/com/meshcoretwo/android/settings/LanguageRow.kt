// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.SettingsListRow
import com.meshcoretwo.android.ui.i18n.AppLanguage
import com.meshcoretwo.android.ui.i18n.AppLanguageManager

@Composable
private fun AppLanguage.displayName(): String = nativeName ?: stringResource(R.string.language_system_default)

/** Settings → Language: a row showing the active language and a radio-list dialog. */
@Composable
fun LanguageRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppLanguageManager.current(context)) }
    var showDialog by remember { mutableStateOf(false) }

    SettingsListRow(
        title = stringResource(R.string.appearance_language),
        value = selected.displayName(),
        onClick = { showDialog = true },
        modifier = modifier,
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.appearance_language)) },
            text = {
                Column {
                    AppLanguage.entries.forEach { language ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .selectable(selected = language == selected, role = Role.RadioButton) {
                                    showDialog = false
                                    if (language != selected) {
                                        selected = language
                                        context.findActivity()?.let { AppLanguageManager.set(it, language) }
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = language == selected, onClick = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(language.displayName())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
