// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.theme.LocalMeshExtendedColors
import com.meshcoretwo.services.connection.ConnectionManager
import java.util.UUID

/**
 * Terminal for sending raw CLI commands to a connected repeater/room admin session. Ported from
 * `NodeCLIView.swift` — see [NodeCLIViewModel]'s class doc for what's deliberately not ported
 * (ghost text, arrow-key history recall).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeCLIScreen(
    connectionManager: ConnectionManager,
    sessionId: UUID,
    isRoom: Boolean,
    onBack: () -> Unit,
) {
    val viewModel: NodeCLIViewModel = viewModel(factory = NodeCLIViewModel.Factory(connectionManager, sessionId, isRoom))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(uiState.outputLines.size) {
        if (uiState.outputLines.isNotEmpty()) listState.animateScrollToItem(uiState.outputLines.size - 1)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(if (uiState.sessionName.isEmpty()) "CLI" else "${uiState.sessionName} CLI") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
                actions = {
                    IconButton(onClick = viewModel::clearOutput) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_clear))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(uiState.outputLines, key = { it.id }) { line ->
                    CLIOutputRow(line, onCopy = { clipboard.setText(AnnotatedString(viewModel.getResponseBlock(line))) })
                }
            }

            if (uiState.suggestions.isNotEmpty() && !uiState.isWaitingForResponse) {
                CLISuggestionRow(uiState.suggestions, onTap = viewModel::applySuggestion)
            }

            CLIInputBar(
                promptText = uiState.promptText,
                currentInput = uiState.currentInput,
                isWaitingForResponse = uiState.isWaitingForResponse,
                onInputChange = viewModel::updateCurrentInput,
                onSubmit = viewModel::executeCommand,
                onCancel = viewModel::cancelCurrentCommand,
            )
        }
    }
}

/**
 * Long-press copies the full response block ([NodeCLIViewModel.getResponseBlock]) to the
 * clipboard — Android's long-press-for-context-action, the same idiom as Swift's `contextMenu`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CLIOutputRow(line: CLIOutputLine, onCopy: () -> Unit) {
    val extended = LocalMeshExtendedColors.current
    val color = when (line.type) {
        CLIOutputType.COMMAND -> MaterialTheme.colorScheme.onSurfaceVariant
        CLIOutputType.SUCCESS -> extended.success
        CLIOutputType.ERROR -> extended.warning
        CLIOutputType.RESPONSE -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = line.text,
        color = color,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onCopy),
    )
}

/**
 * Touch equivalent of Swift's Tab-cycled suggestion list: [CLICompletionEngine]'s candidates for
 * the current input, as a horizontally scrollable row of tap-to-complete chips.
 */
@Composable
private fun CLISuggestionRow(suggestions: List<String>, onTap: (String) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(suggestions, key = { it }) { suggestion ->
            SuggestionChip(
                onClick = { onTap(suggestion) },
                label = { Text(suggestion, fontFamily = FontFamily.Monospace) },
            )
        }
    }
}

@Composable
private fun CLIInputBar(
    promptText: String,
    currentInput: String,
    isWaitingForResponse: Boolean,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = currentInput,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            enabled = !isWaitingForResponse,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            label = { Text(promptText.ifEmpty { "@…> " }, fontFamily = FontFamily.Monospace) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        )
        if (isWaitingForResponse) {
            Box(
                modifier = Modifier.size(48.dp).clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else {
            IconButton(onClick = onSubmit) {
                Icon(painterResource(R.drawable.ic_send), contentDescription = stringResource(R.string.chat_send))
            }
        }
    }
}
