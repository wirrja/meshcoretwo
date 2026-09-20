// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.i18n

import com.meshcoretwo.android.R
import com.meshcoretwo.protocol.MeshCoreError
import com.meshcoretwo.services.backup.AppBackupError
import com.meshcoretwo.services.channels.ChannelServiceError
import com.meshcoretwo.services.advertisement.AdvertisementError
import com.meshcoretwo.services.connection.ConnectionError
import com.meshcoretwo.services.contacts.ContactServiceError
import com.meshcoretwo.services.diagnostics.BinaryProtocolError
import com.meshcoretwo.services.location.LocationProviderError
import com.meshcoretwo.services.messages.MessageServiceError
import com.meshcoretwo.services.pairing.DevicePairingError
import com.meshcoretwo.services.remotenode.RemoteNodeError
import com.meshcoretwo.services.sendqueue.ChatSendQueueServiceError
import com.meshcoretwo.services.settings.KeyGenerationService
import com.meshcoretwo.services.settings.SettingsServiceError
import com.meshcoretwo.services.transport.BleError

/**
 * User-facing text for an exception. `services` and `protocol` have no Android resources, so their
 * error messages are English; the common typed ones are mapped here to string resources and
 * everything else falls back to the raw message, then to [fallback]. Dynamic `reason` payloads
 * (platform/firmware text) stay untranslated.
 */
fun Throwable.toUiText(fallback: UiText): UiText =
    localizedOrNull() ?: message?.let(UiText::Plain) ?: fallback

private fun Throwable.localizedOrNull(): UiText? = when (this) {
    is BinaryProtocolError.SessionError -> error.localizedOrNull()
    is AdvertisementError.SessionError -> error.localizedOrNull()
    is ChatSendQueueServiceError.NotConnected -> UiText.of(R.string.err_not_connected)
    is DevicePairingError -> when (this) {
        DevicePairingError.Cancelled -> UiText.of(R.string.err_pairing_cancelled)
        DevicePairingError.AlreadyInProgress -> UiText.of(R.string.err_pairing_busy)
        else -> null
    }
    is KeyGenerationService.KeyGenerationError -> when (this) {
        KeyGenerationService.KeyGenerationError.MaxAttemptsExceeded -> UiText.of(R.string.err_key_max_attempts)
        KeyGenerationService.KeyGenerationError.ReservedPrefix -> UiText.of(R.string.err_key_reserved)
        KeyGenerationService.KeyGenerationError.InvalidKey -> UiText.of(R.string.err_key_invalid)
    }
    is BleError -> when (this) {
        BleError.BluetoothUnavailable -> UiText.of(R.string.err_ble_unavailable)
        BleError.BluetoothUnauthorized -> UiText.of(R.string.err_ble_unauthorized)
        BleError.BluetoothPoweredOff -> UiText.of(R.string.err_ble_off)
        BleError.DeviceNotFound -> UiText.of(R.string.err_ble_device_not_found)
        is BleError.ConnectionFailed -> UiText.of(R.string.err_connection_failed, reason)
        BleError.ConnectionTimeout -> UiText.of(R.string.err_ble_conn_timeout)
        BleError.NotConnected -> UiText.of(R.string.err_not_connected)
        BleError.CharacteristicNotFound -> UiText.of(R.string.err_ble_characteristic)
        is BleError.WriteError -> UiText.of(R.string.err_ble_write, reason)
        BleError.OperationTimeout -> UiText.of(R.string.err_operation_timeout)
        BleError.AuthenticationFailed -> UiText.of(R.string.err_ble_auth)
        is BleError.PairingFailed -> UiText.of(R.string.err_ble_pairing, reason)
        BleError.DeviceConnectedToOtherApp -> UiText.of(R.string.err_other_app)
        else -> null
    }
    is ConnectionError -> when (this) {
        is ConnectionError.ConnectionFailed -> UiText.of(R.string.err_connection_failed, reason)
        ConnectionError.DeviceNotFound -> UiText.of(R.string.err_device_not_found)
        ConnectionError.NotConnected -> UiText.of(R.string.err_not_connected)
        is ConnectionError.InitializationFailed -> UiText.of(R.string.err_init_failed, reason)
        else -> null
    }
    is MessageServiceError -> when (this) {
        MessageServiceError.NotConnected -> UiText.of(R.string.err_not_connected)
        MessageServiceError.ContactNotFound -> UiText.of(R.string.err_contact_not_found)
        MessageServiceError.ChannelNotFound -> UiText.of(R.string.err_channel_not_found)
        is MessageServiceError.SendFailed -> UiText.of(R.string.err_send_failed, reason)
        MessageServiceError.InvalidRecipient -> UiText.of(R.string.err_invalid_recipient)
        MessageServiceError.MessageTooLong -> UiText.of(R.string.err_message_too_long)
        is MessageServiceError.SessionError -> error.localizedOrNull()
        else -> null
    }
    is ChannelServiceError -> when (this) {
        ChannelServiceError.SyncAlreadyInProgress -> UiText.of(R.string.err_channel_sync_busy)
        ChannelServiceError.InvalidChannelIndex -> UiText.of(R.string.err_channel_index)
        is ChannelServiceError.SessionError -> error.localizedOrNull()
        else -> null
    }
    is ContactServiceError -> when (this) {
        ContactServiceError.ContactNotFound -> UiText.of(R.string.err_contact_not_found)
        ContactServiceError.ContactTableFull -> UiText.of(R.string.err_contact_table_full)
        is ContactServiceError.SessionError -> error.localizedOrNull()
        else -> null
    }
    is RemoteNodeError -> when (this) {
        is RemoteNodeError.LoginFailed -> UiText.of(R.string.err_login_failed, reason)
        RemoteNodeError.InvalidResponse -> UiText.of(R.string.err_invalid_response)
        RemoteNodeError.PermissionDenied -> UiText.of(R.string.err_permission_denied)
        RemoteNodeError.Timeout -> UiText.of(R.string.err_request_timeout)
        RemoteNodeError.ContactNotFound -> UiText.of(R.string.err_contact_not_found)
        RemoteNodeError.RadioContactsFull -> UiText.of(R.string.err_contact_table_full)
        RemoteNodeError.Cancelled -> UiText.of(R.string.err_login_cancelled)
        is RemoteNodeError.SessionError -> error.localizedOrNull()
        else -> null
    }
    is AppBackupError -> when (this) {
        AppBackupError.InvalidFile -> UiText.of(R.string.err_backup_invalid)
        is AppBackupError.FileTooLarge -> UiText.of(R.string.err_backup_too_large, actualBytes / 1_048_576, maxBytes / 1_048_576)
        is AppBackupError.DecompressedTooLarge -> UiText.of(R.string.err_backup_expands, maxBytes / 1_048_576)
        is AppBackupError.UnsupportedVersion -> UiText.of(R.string.err_backup_version, found, maxSupported)
        AppBackupError.CorruptedManifest -> UiText.of(R.string.err_backup_corrupted)
        is AppBackupError.ExportFailed -> UiText.of(R.string.err_backup_export, underlying.message.orEmpty())
        is AppBackupError.ImportFailed -> UiText.of(R.string.err_backup_import, underlying.message.orEmpty())
        else -> null
    }
    is SettingsServiceError -> when (this) {
        is SettingsServiceError.SessionError -> error.localizedOrNull()
        is SettingsServiceError.VerificationFailed -> UiText.of(R.string.err_setting_not_saved, expectedValue, actualValue)
        is SettingsServiceError.DeviceGPSVerificationFailed -> UiText.of(
            R.string.err_gps_not_saved,
            UiText.of(if (expectedEnabled) R.string.settings_on else R.string.settings_off),
            UiText.of(if (actualEnabled) R.string.settings_on else R.string.settings_off),
        )
        else -> null
    }
    is LocationProviderError -> when (this) {
        LocationProviderError.NotAuthorized -> UiText.of(R.string.err_location_denied)
        LocationProviderError.NoProviderAvailable -> UiText.of(R.string.err_location_no_provider)
        LocationProviderError.Timeout -> UiText.of(R.string.err_location_timeout)
        else -> null
    }
    is MeshCoreError -> when (this) {
        MeshCoreError.Timeout -> UiText.of(R.string.err_operation_timeout)
        MeshCoreError.NotConnected -> UiText.of(R.string.err_not_connected)
        else -> null
    }
    else -> null
}
