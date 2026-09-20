// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.contacts

import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.i18n.UiText
import com.meshcoretwo.services.persistence.RemoteNodeRole
import com.meshcoretwo.services.persistence.RemoteNodeSessionDto
import com.meshcoretwo.services.remotenode.RemoteNodeError
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun fakeSession() = RemoteNodeSessionDto(
    id = UUID.randomUUID(),
    radioID = UUID.randomUUID(),
    publicKey = ByteArray(6) { it.toByte() },
    name = "TestNode",
    role = RemoteNodeRole.REPEATER,
)

/** Records every command sent through [NodeSettingsViewModel.configure]'s closures and answers from a map. */
private class CommandRecorder {
    val sentCommands = mutableListOf<String>()
    var responses: Map<String, String> = emptyMap()
    var timeoutCommands: Set<String> = emptySet()

    suspend fun send(id: UUID, command: String, timeoutMs: Long): String {
        sentCommands.add(command)
        if (command in timeoutCommands) throw RemoteNodeError.Timeout
        return responses[command] ?: "OK"
    }
}

private fun configuredViewModel(recorder: CommandRecorder, session: RemoteNodeSessionDto = fakeSession()): NodeSettingsViewModel {
    val viewModel = NodeSettingsViewModel()
    viewModel.configure(session = session, sendCommand = recorder::send, sendRawCommand = recorder::send)
    return viewModel
}

/** Covers [NodeSettingsViewModel], the shared repeater/room settings logic ported from `NodeSettingsViewModel.swift`. */
class NodeSettingsViewModelTest {
    // MARK: - validateIdentityFields

    @Test
    fun `validateIdentityFields accepts in-range coordinates and a short name`() {
        val result = NodeSettingsViewModel.validateIdentityFields("Node", 45.0, -122.0)
        assertFalse(result.hasErrors)
    }

    @Test
    fun `validateIdentityFields skips null fields instead of flagging them`() {
        val result = NodeSettingsViewModel.validateIdentityFields(null, null, null)
        assertFalse(result.hasErrors)
    }

    @Test
    fun `validateIdentityFields accepts inclusive latitude and longitude boundaries`() {
        assertFalse(NodeSettingsViewModel.validateIdentityFields(null, -90.0, null).hasErrors)
        assertFalse(NodeSettingsViewModel.validateIdentityFields(null, 90.0, null).hasErrors)
        assertFalse(NodeSettingsViewModel.validateIdentityFields(null, null, -180.0).hasErrors)
        assertFalse(NodeSettingsViewModel.validateIdentityFields(null, null, 180.0).hasErrors)
    }

    @Test
    fun `validateIdentityFields rejects out-of-range latitude and longitude`() {
        assertTrue(NodeSettingsViewModel.validateIdentityFields(null, 90.0001, null).latitude != null)
        assertTrue(NodeSettingsViewModel.validateIdentityFields(null, -91.0, null).latitude != null)
        assertTrue(NodeSettingsViewModel.validateIdentityFields(null, null, 180.0001).longitude != null)
        assertTrue(NodeSettingsViewModel.validateIdentityFields(null, null, -181.0).longitude != null)
    }

    @Test
    fun `validateIdentityFields rejects non-finite coordinates`() {
        assertTrue(NodeSettingsViewModel.validateIdentityFields(null, Double.NaN, null).latitude != null)
        assertTrue(NodeSettingsViewModel.validateIdentityFields(null, Double.POSITIVE_INFINITY, null).latitude != null)
        assertTrue(NodeSettingsViewModel.validateIdentityFields(null, null, Double.NEGATIVE_INFINITY).longitude != null)
    }

    @Test
    fun `validateIdentityFields name byte cap counts UTF-8 bytes not characters`() {
        val at31Bytes = "1234567890123456789012345678901"
        assertEquals(31, at31Bytes.toByteArray(Charsets.UTF_8).size)
        assertFalse(NodeSettingsViewModel.validateIdentityFields(at31Bytes, null, null).hasErrors)

        val over = "12345678901234567890123456789012"
        assertTrue(NodeSettingsViewModel.validateIdentityFields(over, null, null).name != null)

        // A multi-byte-per-character string under the character count cap but over the byte cap.
        val multiByte = "é".repeat(20) // 20 chars, 40 UTF-8 bytes
        assertTrue(NodeSettingsViewModel.validateIdentityFields(multiByte, null, null).name != null)
    }

    @Test
    fun `validateIdentityFields reports all three fields invalid at once`() {
        val result = NodeSettingsViewModel.validateIdentityFields("x".repeat(50), 999.0, 999.0)
        assertTrue(result.hasErrors)
        assertTrue(result.name != null)
        assertTrue(result.latitude != null)
        assertTrue(result.longitude != null)
    }

    // MARK: - validateBehaviorFields

    @Test
    fun `validateBehaviorFields accepts zero as disabled for the two intervals`() {
        val result = NodeSettingsViewModel.validateBehaviorFields(advertInterval = 0, floodInterval = 0, floodMaxHops = 0)
        assertFalse(result.hasErrors)
    }

    @Test
    fun `validateBehaviorFields rejects out-of-range non-zero intervals and hops`() {
        assertTrue(NodeSettingsViewModel.validateBehaviorFields(59, null, null).advertInterval != null)
        assertTrue(NodeSettingsViewModel.validateBehaviorFields(241, null, null).advertInterval != null)
        assertTrue(NodeSettingsViewModel.validateBehaviorFields(null, 2, null).floodInterval != null)
        assertTrue(NodeSettingsViewModel.validateBehaviorFields(null, 169, null).floodInterval != null)
        assertTrue(NodeSettingsViewModel.validateBehaviorFields(null, null, 65).floodMaxHops != null)
    }

    @Test
    fun `validateBehaviorFields accepts inclusive boundaries`() {
        assertFalse(NodeSettingsViewModel.validateBehaviorFields(60, 3, 0).hasErrors)
        assertFalse(NodeSettingsViewModel.validateBehaviorFields(240, 168, 64).hasErrors)
    }

    // MARK: - applyIdentitySettings

    @Test
    fun `applyIdentitySettings blocks the send when a field is out of range`() = runTest {
        val recorder = CommandRecorder()
        val viewModel = configuredViewModel(recorder)
        viewModel.setLatitude(999.0)

        viewModel.applyIdentitySettings()

        assertTrue(recorder.sentCommands.isEmpty())
        assertTrue(viewModel.uiState.value.latitudeError != null)
    }

    @Test
    fun `applyIdentitySettings only sends fields that changed from original`() = runTest {
        val recorder = CommandRecorder()
        val viewModel = configuredViewModel(recorder)
        viewModel.setNodeInfo(name = "Original")
        viewModel.setName("Original") // unchanged
        viewModel.setLatitude(12.5) // changed from unset original

        viewModel.applyIdentitySettings()

        assertEquals(listOf("set lat 12.5"), recorder.sentCommands)
        assertEquals(12.5, viewModel.uiState.value.originalLatitude)
        // flashSuccess runs to completion (set true, flash delay, set false) before the awaited
        // call returns, same as Swift's own equivalent test asserting `identityApplySuccess == false`.
        assertFalse(viewModel.uiState.value.identityApplySuccess)
    }

    @Test
    fun `applyIdentitySettings clears a stale error once the field is corrected`() = runTest {
        val recorder = CommandRecorder()
        val viewModel = configuredViewModel(recorder)
        viewModel.setLatitude(999.0)
        viewModel.applyIdentitySettings()
        assertTrue(viewModel.uiState.value.latitudeError != null)

        viewModel.setLatitude(10.0)
        viewModel.applyIdentitySettings()

        assertNull(viewModel.uiState.value.latitudeError)
    }

    // MARK: - applyRadioSettings

    @Test
    fun `applyRadioSettings reports an error instead of sending when radio settings are not loaded`() = runTest {
        val recorder = CommandRecorder()
        val viewModel = configuredViewModel(recorder)

        viewModel.applyRadioSettings()

        assertTrue(recorder.sentCommands.isEmpty())
        assertEquals(UiText.of(R.string.nodeset_radio_not_loaded), viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `applyRadioSettings sends set radio and clears the modified flag on success`() = runTest {
        val recorder = CommandRecorder()
        val viewModel = configuredViewModel(recorder)
        viewModel.setFrequency(915.0)
        viewModel.setBandwidth(250.0)
        viewModel.setSpreadingFactor(10)
        viewModel.setCodingRate(5)
        assertTrue(viewModel.uiState.value.radioSettingsModified)

        viewModel.applyRadioSettings()

        assertEquals(listOf("set radio 915.0,250.0,10,5"), recorder.sentCommands)
        assertFalse(viewModel.uiState.value.radioSettingsModified)
    }

    @Test
    fun `fetchRadioSettings sends only get radio`() = runTest {
        val recorder = CommandRecorder().apply { responses = mapOf("get radio" to "915.000,250.0,10,5") }
        val viewModel = configuredViewModel(recorder)

        viewModel.fetchRadioSettings()

        assertEquals(listOf("get radio"), recorder.sentCommands)
        assertEquals(915.0, viewModel.uiState.value.frequency)
        assertEquals(10, viewModel.uiState.value.spreadingFactor)
    }

    // MARK: - Owner info wire mapping

    @Test
    fun `applyContactInfoSettings converts display newlines to the pipe wire form`() = runTest {
        val recorder = CommandRecorder()
        val viewModel = configuredViewModel(recorder)
        viewModel.setOwnerInfo("Line1\nLine2")

        viewModel.applyContactInfoSettings()

        assertEquals(listOf("set owner.info Line1|Line2"), recorder.sentCommands)
        assertEquals("Line1\nLine2", viewModel.uiState.value.originalOwnerInfo)
        assertFalse(viewModel.uiState.value.contactInfoApplySuccess)
    }

    // MARK: - changePassword

    @Test
    fun `changePassword rejects an empty password without sending`() = runTest {
        val recorder = CommandRecorder()
        val viewModel = configuredViewModel(recorder)

        viewModel.changePassword()

        assertTrue(recorder.sentCommands.isEmpty())
        assertEquals(UiText.of(R.string.nodeset_pw_empty), viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `changePassword rejects a mismatched confirmation without sending`() = runTest {
        val recorder = CommandRecorder()
        val viewModel = configuredViewModel(recorder)
        viewModel.setNewPassword("secret")
        viewModel.setConfirmPassword("different")

        viewModel.changePassword()

        assertTrue(recorder.sentCommands.isEmpty())
        assertEquals(UiText.of(R.string.nodeset_pw_mismatch), viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `changePassword succeeds on firmware's password-now echo, not a bare OK`() = runTest {
        val recorder = CommandRecorder().apply { responses = mapOf("password secret" to "password now: secret") }
        val viewModel = configuredViewModel(recorder)
        viewModel.setNewPassword("secret")
        viewModel.setConfirmPassword("secret")

        viewModel.changePassword()

        assertEquals("", viewModel.uiState.value.newPassword)
        assertNull(viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.changePasswordSuccess)
    }

    // MARK: - reboot

    @Test
    fun `reboot treats a timeout as success, matching firmware's no-reply restart`() = runTest {
        val recorder = CommandRecorder().apply { timeoutCommands = setOf("reboot") }
        val viewModel = configuredViewModel(recorder)

        viewModel.reboot()

        assertEquals(UiText.of(R.string.nodeset_reboot_sent), viewModel.uiState.value.successMessage)
        assertFalse(viewModel.uiState.value.isRebooting)
    }

    // MARK: - syncTime

    @Test
    fun `syncTime rewrites clock sync into a host-epoch time command`() = runTest {
        val recorder = CommandRecorder()
        val viewModel = configuredViewModel(recorder)

        viewModel.syncTime()

        assertEquals(1, recorder.sentCommands.size)
        assertTrue(recorder.sentCommands[0].startsWith("time "))
        assertFalse(recorder.sentCommands[0].contains("clock sync"))
    }

    @Test
    fun `syncTime on ClockAhead surfaces the ahead-of-phone warning`() = runTest {
        val viewModel = NodeSettingsViewModel()
        viewModel.configure(
            session = fakeSession(),
            sendCommand = { _, _, _ -> "ERR: clock cannot go backwards" },
            sendRawCommand = { _, _, _ -> "ERR: clock cannot go backwards" },
        )

        viewModel.syncTime()

        assertEquals(UiText.of(R.string.nodeset_clock_ahead), viewModel.uiState.value.errorMessage)
    }

    // MARK: - fetchDeviceInfo / clock drift

    @Test
    fun `fetchDeviceInfo records drift from clock against a fixed now`() = runTest {
        val recorder = CommandRecorder().apply {
            responses = mapOf("ver" to "MeshCore v1.10.0 (2025-04-18)", "clock" to "06:40 - 18/4/2025 UTC")
        }
        val viewModel = configuredViewModel(recorder)
        viewModel.now = { Instant.parse("2025-04-18T06:40:10Z") }

        viewModel.fetchDeviceInfo()

        assertEquals("MeshCore v1.10.0 (2025-04-18)", viewModel.uiState.value.firmwareVersion)
        assertEquals(-10L, viewModel.uiState.value.clockDrift)
    }

    // MARK: - Late reply recovery

    @Test
    fun `handleCommonLateResponse recovers a timed-out get radio reply`() = runTest {
        val recorder = CommandRecorder().apply { timeoutCommands = setOf("get radio") }
        val viewModel = configuredViewModel(recorder)

        try {
            viewModel.sendAndWait("get radio")
        } catch (error: RemoteNodeError.Timeout) {
            // Expected: the command is now tracked as unanswered.
        }
        assertTrue(viewModel.uiState.value.frequency == null)

        viewModel.handleCommonLateResponse("915.000,250.0,10,5")

        assertEquals(915.0, viewModel.uiState.value.frequency)
        assertEquals(10, viewModel.uiState.value.spreadingFactor)
    }

    @Test
    fun `handleCommonLateResponse ignores a reply that matches no unanswered query`() = runTest {
        val recorder = CommandRecorder()
        val viewModel = configuredViewModel(recorder)

        viewModel.handleCommonLateResponse("915.000,250.0,10,5")

        assertNull(viewModel.uiState.value.frequency)
    }
}
