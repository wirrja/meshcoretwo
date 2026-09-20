// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.i18n.toUiText
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.connection.nodeConfigService
import com.meshcoretwo.services.connection.settingsService
import com.meshcoretwo.services.nodeconfig.ConfigSections
import com.meshcoretwo.services.nodeconfig.ImportStep
import com.meshcoretwo.services.nodeconfig.MeshCoreNodeConfig
import com.meshcoretwo.services.nodeconfig.NodeConfigService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * UI state for the config import screen. Ported from the `@Observable` property set of
 * `NodeConfigImportViewModel.swift`; [confirmTitle]/[applyButtonLabel]/[confirmMessage] reproduce
 * its computed properties that pick overwrite/additive/mixed copy based on which sections are
 * selected and whether the (non-destructive) preview found a channel that would overwrite an
 * existing slot ([channelsWouldOverwrite]).
 */
data class NodeConfigImportUiState(
    val importedConfig: MeshCoreNodeConfig? = null,
    val errorMessage: UiText? = null,
    val isParsing: Boolean = false,

    val sections: ConfigSections = ConfigSections(),

    val currentName: String? = null,
    val currentRadio: MeshCoreNodeConfig.RadioSettings? = null,
    val currentPosition: MeshCoreNodeConfig.PositionSettings? = null,

    val isApplying: Boolean = false,
    val applyProgress: Float = 0f,
    val applyStepDescription: UiText = UiText.Plain(""),
    val importComplete: Boolean = false,
    val showConfirmation: Boolean = false,
    val isPreparingConfirmation: Boolean = false,

    /** Set by a [NodeConfigService.previewImport] pass before the confirmation alert — see that method's doc. */
    val channelsWouldOverwrite: Boolean = false,
) {
    private val hasOverwriteSections: Boolean
        get() = sections.radioSettings || sections.nodeIdentity || sections.positionSettings ||
            sections.otherSettings || (sections.channels && channelsWouldOverwrite)

    private val hasAdditiveSections: Boolean
        get() = sections.channels || sections.contacts

    val confirmTitle: UiText
        get() = when {
            !hasOverwriteSections && hasAdditiveSections -> UiText.of(R.string.cfg_confirm_add_title)
            hasOverwriteSections && !hasAdditiveSections -> UiText.of(R.string.cfg_confirm_overwrite_title)
            else -> UiText.of(R.string.cfg_confirm_apply_title)
        }

    val applyButtonLabel: UiText
        get() = when {
            !hasOverwriteSections && hasAdditiveSections -> UiText.of(R.string.cfg_btn_add)
            hasOverwriteSections && !hasAdditiveSections -> UiText.of(R.string.cfg_btn_overwrite)
            else -> UiText.of(R.string.cfg_btn_apply)
        }

    fun confirmMessage(deviceName: String): UiText = when {
        !hasOverwriteSections && hasAdditiveSections ->
            UiText.of(R.string.cfg_msg_add, deviceName)
        hasOverwriteSections && !hasAdditiveSections ->
            UiText.of(R.string.cfg_msg_overwrite, deviceName)
        hasOverwriteSections && hasAdditiveSections ->
            UiText.of(R.string.cfg_msg_both, deviceName)
        else ->
            UiText.of(R.string.cfg_msg_merge, deviceName)
    }
}

/**
 * Backs the config import screen ([NodeConfigImportScreen]) — ported from
 * `NodeConfigImportViewModel.swift`. [contentResolver] reads the picked file
 * (`ActivityResultContracts.OpenDocument`'s [Uri], Android's counterpart to SwiftUI's
 * `.fileImporter`); there is no Android equivalent of iCloud's undownloaded-file stall that
 * motivated Swift's `Task.detached`, but the read still runs off [Dispatchers.IO] since some
 * document providers (e.g. a cloud-backed one) can block.
 *
 * [NodeConfigService.importConfig]'s progress callback is a plain (non-suspend) lambda invoked
 * synchronously, in write order, from within its single caller coroutine — unlike Swift's
 * `AsyncStream` indirection (needed there to guarantee ordered delivery across actor-isolation
 * boundaries), so [applyConfig] updates [NodeConfigImportUiState] directly from the callback with
 * no risk of out-of-order updates.
 */
/** Carries an already-localizable message across a `withContext` boundary. */
private class LocalizedError(val text: UiText) : Exception()

class NodeConfigImportViewModel(
    private val connectionManager: ConnectionManager,
    private val contentResolver: ContentResolver,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NodeConfigImportUiState())
    val uiState: StateFlow<NodeConfigImportUiState> = _uiState.asStateFlow()

    private var didApplyAnyWrite = false
    private var parseJob: Job? = null
    private var previewJob: Job? = null
    private var importJob: Job? = null

    fun parseFile(uri: Uri) {
        parseJob?.cancel()
        _uiState.update { it.copy(isParsing = true, errorMessage = null) }

        parseJob = viewModelScope.launch {
            try {
                val config = withContext(Dispatchers.IO) {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes() }?.decodeToString()
                        ?: throw LocalizedError(UiText.of(R.string.cfg_err_open_file))
                    MeshCoreNodeConfig.fromJson(JSONObject(text))
                }
                applyParseSuccess(config)
                loadCurrentDeviceState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isParsing = false, errorMessage = (e as? LocalizedError)?.text ?: e.toUiText(UiText.of(R.string.cfg_err_parse))) }
            }
        }
    }

    private fun applyParseSuccess(config: MeshCoreNodeConfig) {
        _uiState.update {
            it.copy(
                isParsing = false,
                importedConfig = config,
                errorMessage = null,
                // Auto-select only sections present in the file.
                sections = ConfigSections(
                    nodeIdentity = config.name != null || config.publicKey != null || config.privateKey != null,
                    radioSettings = config.radioSettings != null,
                    positionSettings = config.positionSettings != null && !(config.positionSettings?.isZero ?: true),
                    otherSettings = config.otherSettings != null,
                    channels = config.channels != null,
                    contacts = config.contacts != null,
                ),
            )
        }
    }

    fun updateSections(transform: ConfigSections.() -> Unit) {
        _uiState.update { it.copy(sections = it.sections.copy().apply(transform)) }
    }

    /** Best-effort: a failed read just leaves the "current" diff column blank. */
    private suspend fun loadCurrentDeviceState() {
        val settingsService = connectionManager.settingsService ?: return
        try {
            val selfInfo = settingsService.getSelfInfo()
            _uiState.update {
                it.copy(
                    currentName = selfInfo.name,
                    currentRadio = NodeConfigService.buildRadioSettings(selfInfo),
                    currentPosition = MeshCoreNodeConfig.PositionSettings(
                        latitude = selfInfo.latitude.toString(),
                        longitude = selfInfo.longitude.toString(),
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best-effort — see doc.
        }
    }

    /**
     * Runs the non-destructive planner (via [NodeConfigService.previewImport]) to classify the
     * import and reject a malformed config before the confirmation alert, mirroring Swift's
     * `prepareConfirmation`.
     */
    fun prepareConfirmation() {
        val state = _uiState.value
        if (state.isPreparingConfirmation || state.isApplying) return
        val config = state.importedConfig ?: return
        val service = connectionManager.nodeConfigService ?: return

        _uiState.update { it.copy(errorMessage = null, isPreparingConfirmation = true) }
        previewJob = viewModelScope.launch {
            try {
                val preview = service.previewImport(config, state.sections)
                _uiState.update {
                    it.copy(
                        isPreparingConfirmation = false,
                        channelsWouldOverwrite = preview.channelsOverwriteExisting,
                        errorMessage = null,
                        showConfirmation = true,
                    )
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isPreparingConfirmation = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isPreparingConfirmation = false, errorMessage = e.toUiText(UiText.of(R.string.cfg_err_preview))) }
            }
        }
    }

    fun dismissConfirmation() {
        _uiState.update { it.copy(showConfirmation = false) }
    }

    fun applyConfig() {
        val state = _uiState.value
        if (state.isApplying) return
        val config = state.importedConfig ?: return
        val service = connectionManager.nodeConfigService ?: return
        val radioID = connectionManager.connectedDeviceRecord?.radioID ?: return

        didApplyAnyWrite = false
        _uiState.update {
            it.copy(showConfirmation = false, isApplying = true, applyProgress = 0f, errorMessage = null, importComplete = false)
        }

        importJob = viewModelScope.launch {
            try {
                service.importConfig(config, state.sections, radioID) { progress ->
                    didApplyAnyWrite = true
                    _uiState.update {
                        it.copy(
                            applyProgress = progress.current.toFloat() / progress.total.coerceAtLeast(1),
                            applyStepDescription = describe(progress.step),
                        )
                    }
                }
                connectionManager.settingsService?.let { runCatching { it.refreshDeviceInfo() } }
                _uiState.update { it.copy(isApplying = false, importComplete = true) }
                delay(1_500)
                resetToFileSelection()
            } catch (e: CancellationException) {
                _uiState.update {
                    it.copy(
                        isApplying = false,
                        errorMessage = if (didApplyAnyWrite) {
                            UiText.of(R.string.cfg_cancelled_partial)
                        } else {
                            UiText.of(R.string.cfg_cancelled)
                        },
                    )
                }
            } catch (e: Exception) {
                val message: UiText = e.toUiText(UiText.of(R.string.cfg_err_import))
                _uiState.update {
                    it.copy(
                        isApplying = false,
                        errorMessage = if (didApplyAnyWrite) {
                            UiText.of(R.string.cfg_failed_partial, message)
                        } else {
                            message
                        },
                    )
                }
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    /**
     * Called when the screen goes away. An in-flight apply is left running with its progress
     * state intact — see `NodeConfigImportViewModel.handleDismissal`'s Swift doc for why a
     * destructive, explicitly-cancellable operation must not be silently aborted by navigation.
     */
    fun handleDismissal() {
        parseJob?.cancel()
        previewJob?.cancel()
        if (_uiState.value.isApplying) return
        resetToFileSelection()
    }

    private fun resetToFileSelection() {
        _uiState.value = NodeConfigImportUiState()
    }

    private fun describe(step: ImportStep): UiText = when (step) {
        ImportStep.Position -> UiText.of(R.string.cfg_step_position)
        ImportStep.OtherParameters -> UiText.of(R.string.cfg_step_other)
        ImportStep.PrivateKey -> UiText.of(R.string.cfg_step_key)
        ImportStep.NodeName -> UiText.of(R.string.cfg_step_name)
        ImportStep.RadioParameters -> UiText.of(R.string.cfg_step_radio)
        ImportStep.TxPower -> UiText.of(R.string.cfg_step_txpower)
        is ImportStep.Channel -> UiText.of(R.string.cfg_step_channel, step.name)
        is ImportStep.Contact -> UiText.of(R.string.cfg_step_contact, step.name)
    }

    class Factory(
        private val connectionManager: ConnectionManager,
        private val contentResolver: ContentResolver,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NodeConfigImportViewModel(connectionManager, contentResolver) as T
    }
}
