// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.DetailRow
import com.meshcoretwo.android.ui.components.ExpandableSectionCard
import com.meshcoretwo.android.ui.components.InitialsAvatar
import com.meshcoretwo.android.ui.components.SettingsGroupLabel
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.android.ui.theme.AvatarCategory
import kotlin.math.abs

/**
 * Device info/radio/identity/contact info/security sections shared by [RepeaterSettingsScreen] and
 * [RoomSettingsScreen] — ported from `SharedNodeSettingsViews.swift`, all bound to the shared
 * [NodeSettingsViewModel] (`settings`/`state` here, `.helper` there).
 *
 * Adapted from Swift's dense two-column Form rows (`Text(label); Spacer(); TextField(...)`) to
 * standard full-width [OutlinedTextField]s with a `label`, matching this codebase's own established
 * form convention ([app.settings.SettingsScreen] dialogs) rather than the iOS List-row idiom, which
 * has no direct Compose equivalent. Per-field loading placeholders (`SettingsLoadPlaceholder`) are
 * likewise collapsed into one section-level [SectionLoadingRow]/[ErrorRetryRow], matching how
 * [RepeaterStatusScreen]'s sections already gate on a single `isLoading`/`error` per section rather
 * than per field.
 *
 * `ExpandableSettingsSection.swift`'s auto-load-on-expand (`.onChange(of: isExpanded)`) is
 * reproduced inline in each section's `onToggle`: expanding fires `onReload` when nothing has
 * loaded yet, instead of a shared `LaunchedEffect`-based helper — simpler, since every section here
 * starts collapsed (there's no "starts pre-expanded" case on Android to also cover, unlike Swift's
 * `.task` trigger).
 *
 * Not ported: `RemoteNodeIdentitySection`'s "Pick on Map" button / `LocationPickerView` sheet — the
 * existing `LocationPickerScreen`/`LocationPickerViewModel` write straight to the *locally connected*
 * device (`connectionManager.connectedDeviceRecord`), not to an arbitrary lat/lon callback, so
 * reusing it here would need a new picker variant; deferred as a follow-up (see PLAN.md). The
 * latitude/longitude fields remain directly editable either way, which is the primary path on iOS
 * too — the map picker is a convenience shortcut for the same two fields, not separate functionality.
 */

// MARK: - Header

@Composable
fun NodeSettingsHeader(name: String, category: AvatarCategory) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        InitialsAvatar(name = name, size = 60.dp, category = category)
        Spacer(modifier = Modifier.size(8.dp))
        Text(name, style = MaterialTheme.typography.headlineSmall)
    }
}

// MARK: - Device Info

@Composable
internal fun NodeDeviceInfoSection(state: NodeSettingsUiState, settings: NodeSettingsViewModel, onReload: () -> Unit) {
    ExpandableSectionCard(
        title = stringResource(R.string.nodeadmin_device_info),
        expanded = state.isDeviceInfoExpanded,
        isLoading = state.isLoadingDeviceInfo,
        onToggle = {
            val expand = !state.isDeviceInfoExpanded
            settings.setDeviceInfoExpanded(expand)
            if (expand && !state.deviceInfoLoaded && !state.isLoadingDeviceInfo) onReload()
        },
        onReload = if (state.deviceInfoLoaded) onReload else null,
    ) {
        when {
            state.isLoadingDeviceInfo && !state.deviceInfoLoaded -> SectionLoadingRow()
            state.deviceInfoError && !state.deviceInfoLoaded -> ErrorRetryRow(onRetry = onReload)
            state.deviceInfoLoaded -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("Firmware", state.firmwareVersion ?: EM_DASH)
                DetailRow(stringResource(R.string.nodeadmin_device_time), state.deviceTime ?: EM_DASH)
                val drift = state.clockDrift
                if (drift != null && abs(drift) >= CLOCK_DRIFT_WARNING_THRESHOLD_SECONDS) {
                    Text(clockDriftWarningText(drift), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private const val CLOCK_DRIFT_WARNING_THRESHOLD_SECONDS = 300L

@Composable
private fun clockDriftWarningText(driftSeconds: Long): String {
    val duration = formatDurationShort(abs(driftSeconds))
    return stringResource(if (driftSeconds > 0) R.string.nodeadmin_clock_ahead else R.string.nodeadmin_clock_behind, duration)
}

private fun formatDurationShort(totalSeconds: Long): String {
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

// MARK: - Radio Parameters

@Composable
internal fun NodeRadioSettingsSection(
    state: NodeSettingsUiState,
    settings: NodeSettingsViewModel,
    onReload: () -> Unit,
    onApply: () -> Unit,
    @StringRes restartWarning: Int = R.string.nodeadmin_restart_device,
) {
    ExpandableSectionCard(
        title = stringResource(R.string.nodeadmin_radio_parameters),
        expanded = state.isRadioExpanded,
        isLoading = state.isLoadingRadio,
        onToggle = {
            val expand = !state.isRadioExpanded
            settings.setRadioExpanded(expand)
            if (expand && !state.radioLoaded && !state.isLoadingRadio) onReload()
        },
        onReload = if (state.radioLoaded) onReload else null,
    ) {
        when {
            state.isLoadingRadio && !state.radioLoaded -> SectionLoadingRow()
            state.radioError && !state.radioLoaded -> ErrorRetryRow(onRetry = onReload)
            state.radioLoaded -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.radioSettingsModified) {
                    Text(stringResource(restartWarning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                DoubleFieldRow("Frequency (MHz)", state.frequency, settings::setFrequency)
                DoubleFieldRow("Bandwidth (kHz)", state.bandwidth, settings::setBandwidth)
                IntFieldRow("Spreading Factor", state.spreadingFactor, settings::setSpreadingFactor)
                IntFieldRow("Coding Rate", state.codingRate, settings::setCodingRate)
                AsyncApplyButton(
                    stringResource(R.string.nodeadmin_apply_radio),
                    isLoading = state.isApplying,
                    showSuccess = false,
                    enabled = state.radioSettingsModified,
                    onClick = onApply,
                )
            }
        }
    }
}

// MARK: - Identity & Location

@Composable
internal fun NodeIdentitySection(
    state: NodeSettingsUiState,
    settings: NodeSettingsViewModel,
    onReload: () -> Unit,
    onApply: () -> Unit,
    onPickLocation: () -> Unit,
) {
    ExpandableSectionCard(
        title = stringResource(R.string.nodeadmin_identity_location),
        expanded = state.isIdentityExpanded,
        isLoading = state.isLoadingIdentity,
        onToggle = {
            val expand = !state.isIdentityExpanded
            settings.setIdentityExpanded(expand)
            if (expand && !state.identityLoaded && !state.isLoadingIdentity) onReload()
        },
        onReload = if (state.identityLoaded) onReload else null,
    ) {
        when {
            state.isLoadingIdentity && !state.identityLoaded -> SectionLoadingRow()
            state.identityError && !state.identityLoaded -> ErrorRetryRow(onRetry = onReload)
            state.identityLoaded -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.name ?: "",
                    onValueChange = settings::setName,
                    label = { Text(stringResource(R.string.contacts_name)) },
                    singleLine = true,
                    isError = state.nameError != null,
                    supportingText = state.nameError?.let { message -> { Text(message.asString()) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                DoubleFieldRow(
                    stringResource(R.string.nodeadmin_latitude),
                    state.latitude,
                    settings::setLatitude,
                    error = state.latitudeError,
                    loadKey = state.originalLatitude to state.identityFieldsResyncToken,
                )
                DoubleFieldRow(
                    stringResource(R.string.nodeadmin_longitude),
                    state.longitude,
                    settings::setLongitude,
                    error = state.longitudeError,
                    loadKey = state.originalLongitude to state.identityFieldsResyncToken,
                )
                OutlinedButton(onClick = onPickLocation, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.ic_map), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.nodeadmin_pick_on_map))
                }
                AsyncApplyButton(
                    stringResource(R.string.nodeadmin_apply_identity),
                    isLoading = state.isApplying,
                    showSuccess = state.identityApplySuccess,
                    enabled = state.identitySettingsModified,
                    onClick = onApply,
                )
            }
        }
    }
}

// MARK: - Contact Info

@Composable
internal fun NodeContactInfoSection(state: NodeSettingsUiState, settings: NodeSettingsViewModel, onReload: () -> Unit, onApply: () -> Unit) {
    ExpandableSectionCard(
        title = stringResource(R.string.nodeadmin_contact_info),
        expanded = state.isContactInfoExpanded,
        isLoading = state.isLoadingContactInfo,
        onToggle = {
            val expand = !state.isContactInfoExpanded
            settings.setContactInfoExpanded(expand)
            if (expand && !state.contactInfoLoaded && !state.isLoadingContactInfo) onReload()
        },
        onReload = if (state.contactInfoLoaded) onReload else null,
    ) {
        when {
            state.isLoadingContactInfo && !state.contactInfoLoaded -> SectionLoadingRow()
            state.contactInfoError && !state.contactInfoLoaded -> ErrorRetryRow(onRetry = onReload)
            state.contactInfoLoaded -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = state.ownerInfo ?: "",
                    onValueChange = settings::setOwnerInfo,
                    label = { Text(stringResource(R.string.nodeadmin_contact_info)) },
                    placeholder = { Text(stringResource(R.string.nodeadmin_contact_hint)) },
                    minLines = 3,
                    maxLines = 6,
                    isError = state.isOwnerInfoTooLong,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${state.ownerInfoCharCount}/${NodeSettingsViewModel.OWNER_INFO_MAX_LENGTH}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.isOwnerInfoTooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.errorMessage != null) {
                    Text(state.errorMessage.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.size(4.dp))
                AsyncApplyButton(
                    stringResource(R.string.nodeadmin_apply_contact),
                    isLoading = state.isApplying,
                    showSuccess = state.contactInfoApplySuccess,
                    enabled = state.contactInfoSettingsModified && !state.isOwnerInfoTooLong,
                    onClick = onApply,
                )
            }
        }
    }
}

// MARK: - Security

@Composable
internal fun NodeSecuritySection(state: NodeSettingsUiState, settings: NodeSettingsViewModel, onApply: () -> Unit) {
    ExpandableSectionCard(
        title = stringResource(R.string.nodeadmin_security),
        expanded = state.isSecurityExpanded,
        isLoading = false,
        onToggle = { settings.setSecurityExpanded(!state.isSecurityExpanded) },
        onReload = null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.newPassword,
                onValueChange = settings::setNewPassword,
                label = { Text(stringResource(R.string.nodeadmin_new_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.confirmPassword,
                onValueChange = settings::setConfirmPassword,
                label = { Text(stringResource(R.string.nodeadmin_confirm_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            AsyncApplyButton(
                stringResource(R.string.nodeadmin_change_password),
                isLoading = state.isApplying,
                showSuccess = state.changePasswordSuccess,
                enabled = state.newPassword.isNotEmpty() && state.newPassword == state.confirmPassword,
                onClick = onApply,
            )
        }
    }
}

// MARK: - Device Actions

@Composable
internal fun NodeActionsSection(
    state: NodeSettingsUiState,
    onForceAdvert: () -> Unit,
    onSyncTime: () -> Unit,
    onReboot: () -> Unit,
    @StringRes rebootConfirmTitle: Int = R.string.nodeadmin_reboot_device_title,
    @StringRes rebootMessage: Int = R.string.nodeadmin_reboot_device_msg,
) {
    var showRebootConfirmation by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsGroupLabel(stringResource(R.string.nodeadmin_device_actions))
        ActionRow(label = stringResource(R.string.nodeadmin_send_advert), isBusy = state.isSendingAdvert, enabled = !state.isSendingAdvert, onClick = onForceAdvert)
        ActionRow(label = stringResource(R.string.nodeadmin_sync_time), isBusy = state.isApplying, enabled = !state.isApplying, onClick = onSyncTime)
        ActionRow(
            label = stringResource(R.string.nodeadmin_reboot_device),
            isBusy = state.isRebooting,
            enabled = !state.isRebooting,
            labelColor = MaterialTheme.colorScheme.error,
            onClick = { showRebootConfirmation = true },
        )
        if (state.errorMessage != null) {
            Spacer(modifier = Modifier.size(8.dp))
            Text(state.errorMessage.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showRebootConfirmation) {
        AlertDialog(
            onDismissRequest = { showRebootConfirmation = false },
            title = { Text(stringResource(rebootConfirmTitle)) },
            text = { Text(stringResource(rebootMessage)) },
            confirmButton = {
                TextButton(onClick = { showRebootConfirmation = false; onReboot() }) {
                    Text(stringResource(R.string.nodeadmin_reboot), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showRebootConfirmation = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun ActionRow(label: String, isBusy: Boolean, enabled: Boolean, onClick: () -> Unit, labelColor: Color = Color.Unspecified) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = labelColor)
        if (isBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}

// MARK: - Shared Field Rows

/**
 * [loadKey] should be the field's *loaded baseline* (e.g. `NodeSettingsUiState.originalLatitude`),
 * not [value] itself — [remember] has no way to tell "the user typed a character" apart from "the
 * async fetch just landed" other than by being keyed on something that only changes for the latter.
 * Keying on [value] directly seemed simpler but re-derives `text` from `value?.toString()` on every
 * keystroke too, fighting whatever the user is mid-typing (e.g. "-3" briefly parses to `-3.0`, then
 * immediately gets stomped back to the string `"-3.0"`). Keying on nothing (the bug this replaced)
 * is worse: when this row first composes before its value has loaded (a real sequence for
 * [NodeIdentitySection] — see [NodeSettingsUiState.identityLoaded]'s doc), the remembered `text`
 * locks onto `""` and never updates even after the real value arrives, since recomposition alone
 * doesn't re-run an unkeyed `remember` initializer — the field then reads as permanently empty.
 */
@Composable
internal fun DoubleFieldRow(label: String, value: Double?, onValueChange: (Double?) -> Unit, error: UiText? = null, loadKey: Any? = value) {
    var text by remember(loadKey) { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { input -> text = input; onValueChange(input.toDoubleOrNull()) },
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { message -> { Text(message.asString()) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun IntFieldRow(label: String, value: Int?, onValueChange: (Int?) -> Unit, error: UiText? = null) {
    var text by remember { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { input -> text = input; onValueChange(input.toIntOrNull()) },
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { message -> { Text(message.asString()) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

// MARK: - Shared Async/Loading Helpers

@Composable
internal fun AsyncApplyButton(text: String, isLoading: Boolean, showSuccess: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !isLoading && !showSuccess, modifier = Modifier.fillMaxWidth()) {
        when {
            isLoading -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            showSuccess -> Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp))
            else -> Text(text)
        }
    }
}

@Composable
internal fun SectionLoadingRow() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun ErrorRetryRow(onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.nodeadmin_failed_load), color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.size(8.dp))
        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.nodeadmin_try_again)) }
    }
}

private const val EM_DASH = "—"
