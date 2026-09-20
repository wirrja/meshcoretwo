// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import kotlinx.coroutines.launch

/**
 * Inline "Save Path" action: a button that expands into a name field with cancel/save. Ported from
 * `SavePathRowView.swift`; the save-success haptic trigger has no Compose equivalent wired up yet
 * (no other screen in this port uses haptics either), so it's dropped rather than stubbed.
 */
@Composable
fun SavePathRow(canSavePath: Boolean, generateName: () -> String, onSave: suspend (String) -> Boolean) {
    var showingSaveDialog by remember { mutableStateOf(false) }
    var savePathName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    if (showingSaveDialog) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            OutlinedTextField(
                value = savePathName,
                onValueChange = { savePathName = it },
                label = { Text(stringResource(R.string.trace_path_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { showingSaveDialog = false; savePathName = "" }) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    onClick = {
                        scope.launch {
                            onSave(savePathName)
                            showingSaveDialog = false
                            savePathName = ""
                        }
                    },
                    enabled = savePathName.isNotBlank() && canSavePath,
                ) { Text(stringResource(R.string.common_save)) }
            }
        }
    } else {
        TextButton(
            onClick = {
                savePathName = generateName()
                showingSaveDialog = true
            },
            enabled = canSavePath,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.trace_save_path))
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
