// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.remoteNodeService
import com.meshcoretwo.services.connection.repeaterAdminService
import com.meshcoretwo.services.connection.roomAdminService
import com.meshcoretwo.services.remotenode.RemoteNodeError
import com.meshcoretwo.services.remotenode.RemoteOperationTimeoutPolicy
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NodeCLIUiState(
    val sessionName: String = "",
    val outputLines: List<CLIOutputLine> = emptyList(),
    val currentInput: String = "",
    val isWaitingForResponse: Boolean = false,
) {
    /** Ported from `NodeCLIViewModel.promptText` — blank while a command is in flight. */
    val promptText: String get() = if (isWaitingForResponse) "" else "@$sessionName> "

    /**
     * Touch equivalent of Swift's `tabComplete`: [CLICompletionEngine.completions] for
     * [currentInput], offered as tappable chips instead of a keyboard-Tab-cycled list. Empty while
     * a command is in flight, same gate as [promptText].
     */
    val suggestions: List<String> get() = if (isWaitingForResponse) emptyList() else CLICompletionEngine.completions(currentInput)
}

/**
 * Backs [NodeCLIScreen], a terminal for sending raw CLI commands to a connected repeater/room
 * admin session. Ported from `NodeCLIViewModel.swift`/`NodeCLIView.swift`, trimmed to the subset
 * that maps onto Android's soft-keyboard-first text input rather than Swift's hardware-keyboard-
 * shortcut-driven terminal:
 * - **Ported, touch-adapted**: [CLICompletionEngine]'s completion *logic* — the actual affordance
 *   is tappable suggestion chips ([NodeCLIUiState.suggestions]) instead of Swift's Tab-key-cycled
 *   list, applied via [applySuggestion]. Only the *presentation* is Swift-specific here, not the
 *   completion logic itself, so unlike the items below this one didn't need deferring.
 * - **Not ported**: inline "ghost text" preview (Swift's `ghostText`/`updateGhostText`) — that one
 *   genuinely is keyboard-shaped: it previews the completion inline at the text cursor as the user
 *   types, which needs cursor-position tracking a plain [androidx.compose.material3.OutlinedTextField]
 *   doesn't expose. Chips already surface the same candidates without it.
 * - **Not ported**: Up/Down command-history recall (Swift's `historyUp`/`historyDown`, hardware
 *   arrow keys) and cursor-position tracking (`pasteFromClipboard`, `onMoveLeft`/`onMoveRight`) —
 *   both assume arrow-key input Android's soft keyboard doesn't provide, and paste already works
 *   natively through [androidx.compose.foundation.text.BasicTextField]'s built-in text-selection
 *   toolbar, so there is nothing Swift-specific left to port there. [commandHistory] is still
 *   recorded (parity with Swift's model, cheap to keep, and the natural extension point for a
 *   future recall UI) even though nothing reads it back yet.
 * - **Ported as-is**: [getResponseBlock] — Swift triggers it from a long-press context menu on an
 *   output line to copy the full response block; Android's long-press-for-context-action is the
 *   same idiom (see [NodeCLIScreen]'s `CLIOutputRow`), so this one needed no adaptation at all.
 * - **Ported as-is**: local `help`/`clear` commands, the `reboot`/`reboot now` fire-and-forget
 *   special case (treats [RemoteNodeError.Timeout] as success, matching Swift's
 *   `catch RemoteNodeError.timeout`), the capped output buffer ([MAX_OUTPUT_LINES]), and
 *   [cancelCurrentCommand].
 *
 * [sendRawCommand] dispatches to [ConnectionManager.repeaterAdminService] or
 * [ConnectionManager.roomAdminService] by [isRoom] — both are thin pass-throughs to
 * `RemoteNodeService.sendRawCLICommand`, which already owns the full send/correlate/timeout
 * exchange (including its own `reboot` fire-and-forget handling at the wire level — see that
 * method's doc); this class only adds the *terminal UX* layer Swift's `NodeCLIViewModel` adds on
 * top of the same passthrough.
 */
class NodeCLIViewModel(
    private val connectionManager: ConnectionManager,
    private val sessionId: UUID,
    private val isRoom: Boolean,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NodeCLIUiState())
    val uiState: StateFlow<NodeCLIUiState> = _uiState.asStateFlow()

    /** Not yet read by any UI — see class doc's "Not ported" section. */
    private val commandHistory = mutableListOf<String>()
    private var currentCommandJob: Job? = null

    init {
        viewModelScope.launch {
            val name = connectionManager.remoteNodeService?.fetchSession(sessionId)?.name ?: ""
            _uiState.update { it.copy(sessionName = name) }
            appendOutput("Connected to $name", CLIOutputType.RESPONSE)
            appendOutput("Type 'help' for available commands.", CLIOutputType.RESPONSE)
            appendOutput("", CLIOutputType.RESPONSE)
        }
    }

    fun updateCurrentInput(text: String) {
        _uiState.update { it.copy(currentInput = text) }
    }

    /** Tapping a suggestion chip. Ported from `NodeCLIViewModel.applyCompletion`. */
    fun applySuggestion(suggestion: String) {
        _uiState.update { it.copy(currentInput = applyCompletion(it.currentInput, suggestion)) }
    }

    /**
     * Long-pressing an output line, for the screen's "copy full response" affordance. Ported from
     * `NodeCLIViewModel.getResponseBlock`.
     */
    fun getResponseBlock(line: CLIOutputLine): String = responseBlock(_uiState.value.outputLines, line)

    /** Ported from `NodeCLIViewModel.executeCommand`. */
    fun executeCommand() {
        if (_uiState.value.isWaitingForResponse) return
        val command = _uiState.value.currentInput
        val trimmed = command.trim()
        val promptPrefix = _uiState.value.promptText.trim()

        if (trimmed.isEmpty()) {
            appendOutput(promptPrefix, CLIOutputType.COMMAND)
            return
        }

        addToHistory(trimmed)
        appendOutput("$promptPrefix $trimmed", CLIOutputType.COMMAND)
        _uiState.update { it.copy(currentInput = "") }

        val (cmd, args) = splitCommand(trimmed)
        currentCommandJob = viewModelScope.launch { handleCommand(cmd, args, trimmed) }
    }

    /** Ported from `NodeCLIViewModel.cancelCurrentCommand`. */
    fun cancelCurrentCommand() {
        currentCommandJob?.cancel()
        currentCommandJob = null
        if (_uiState.value.isWaitingForResponse) {
            _uiState.update { it.copy(isWaitingForResponse = false) }
            appendOutput("Command cancelled", CLIOutputType.ERROR)
        }
    }

    private suspend fun handleCommand(cmd: String, args: String, raw: String) {
        when {
            cmd == "help" -> showHelp()
            cmd == "clear" && args.isEmpty() -> clearOutput()
            else -> sendCommand(raw)
        }
    }

    /** Also reachable from the screen's "Clear" toolbar action, mirroring `onClear`'s shortcut in `NodeCLIView.swift`. */
    fun clearOutput() {
        _uiState.update { it.copy(outputLines = emptyList()) }
    }

    /** Ported from `NodeCLIViewModel.sendCommand`, including its reboot fire-and-forget special case. */
    private suspend fun sendCommand(command: String) {
        _uiState.update { it.copy(isWaitingForResponse = true) }
        try {
            if (isRebootCommand(command)) {
                try {
                    sendRawCommand(command, RemoteOperationTimeoutPolicy.FIRE_AND_FORGET_CLI_MS)
                    appendOutput("Reboot command sent", CLIOutputType.SUCCESS)
                } catch (error: RemoteNodeError.Timeout) {
                    appendOutput("Reboot command sent", CLIOutputType.SUCCESS)
                }
                return
            }

            val response = sendRawCommand(command, DEFAULT_COMMAND_TIMEOUT_MS)
            appendOutput(response, CLIOutputType.RESPONSE)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            appendOutput(error.message ?: "Error", CLIOutputType.ERROR)
        } finally {
            _uiState.update { it.copy(isWaitingForResponse = false) }
        }
    }

    private suspend fun sendRawCommand(command: String, timeoutMs: Long): String =
        if (isRoom) {
            (connectionManager.roomAdminService ?: throw RemoteNodeError.SessionNotFound)
                .sendRawCommand(sessionId, command, timeoutMs)
        } else {
            (connectionManager.repeaterAdminService ?: throw RemoteNodeError.SessionNotFound)
                .sendRawCommand(sessionId, command, timeoutMs)
        }

    private fun showHelp() {
        HELP_LINES.forEach { appendOutput(it, CLIOutputType.RESPONSE) }
    }

    private fun addToHistory(command: String) {
        commandHistory.add(command)
        if (commandHistory.size > MAX_HISTORY_ENTRIES) commandHistory.removeAt(0)
    }

    /** Ported from `NodeCLIViewModel.appendOutput`, capped at [MAX_OUTPUT_LINES]. */
    private fun appendOutput(text: String, type: CLIOutputType) {
        _uiState.update { state ->
            val lines = state.outputLines + CLIOutputLine(text = text, type = type)
            state.copy(outputLines = if (lines.size > MAX_OUTPUT_LINES) lines.takeLast(MAX_OUTPUT_LINES) else lines)
        }
    }

    class Factory(
        private val connectionManager: ConnectionManager,
        private val sessionId: UUID,
        private val isRoom: Boolean,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NodeCLIViewModel(connectionManager, sessionId, isRoom) as T
    }

    companion object {
        private const val MAX_OUTPUT_LINES = 1000
        private const val MAX_HISTORY_ENTRIES = 100
        private const val DEFAULT_COMMAND_TIMEOUT_MS = 10_000L

        private val HELP_LINES = listOf(
            "Available commands:",
            "  help\n    Show this help",
            "  clear\n    Clear the terminal",
            "  clear stats\n    Reset node statistics",
            "  reboot\n    Restart this node",
            "Any other input is sent to the node.",
        )

        /** Ported from `NodeCLIViewModel.sendCommand`'s inline reboot check. */
        fun isRebootCommand(command: String): Boolean {
            val normalized = command.trim().lowercase()
            return normalized == "reboot" || normalized == "reboot now"
        }

        /** Ported from `NodeCLIViewModel.executeCommand`'s inline split. */
        fun splitCommand(trimmed: String): Pair<String, String> {
            val parts = trimmed.split(" ", limit = 2)
            return parts[0].lowercase() to parts.getOrElse(1) { "" }
        }

        /**
         * Ported from `NodeCLIViewModel.applyCompletion`: replaces the last whitespace-delimited
         * token of [currentInput] with [suggestion] (or the whole input, for a single-word command),
         * always leaving a trailing space so the next keystroke starts a fresh argument.
         */
        fun applyCompletion(currentInput: String, suggestion: String): String {
            val parts = currentInput.split(" ")
            return if (parts.size <= 1) "$suggestion " else (parts.dropLast(1) + suggestion).joinToString(" ") + " "
        }

        /**
         * Ported from `NodeCLIViewModel.getResponseBlock(containing:)`. For a command line, strips
         * the `"@session> "` prompt prefix. For a response/success/error line, walks outward to the
         * nearest surrounding command lines and joins the block, stripping each line's echoed
         * `"> "` wire-response prefix (see `CLIResponse.splitEchoedPrefix`, unrelated to the prompt
         * above but coincidentally the same two characters). Falls back to the bare line text if it
         * can no longer be found in [outputLines] (e.g. scrolled out past [MAX_OUTPUT_LINES]).
         */
        fun responseBlock(outputLines: List<CLIOutputLine>, line: CLIOutputLine): String {
            val index = outputLines.indexOfFirst { it.id == line.id }
            if (index == -1) return line.text

            if (line.type == CLIOutputType.COMMAND) {
                val markerIndex = line.text.indexOf("> ")
                return if (markerIndex >= 0) line.text.substring(markerIndex + 2) else line.text
            }

            var startIndex = index
            while (startIndex > 0 && outputLines[startIndex - 1].type != CLIOutputType.COMMAND) startIndex--
            var endIndex = index
            while (endIndex < outputLines.size - 1 && outputLines[endIndex + 1].type != CLIOutputType.COMMAND) endIndex++

            return outputLines.subList(startIndex, endIndex + 1).joinToString("\n") {
                if (it.text.startsWith("> ")) it.text.substring(2) else it.text
            }
        }
    }
}
