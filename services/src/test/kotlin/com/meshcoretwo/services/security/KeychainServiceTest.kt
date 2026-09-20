// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.security

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/**
 * No direct Swift equivalent exists (`KeychainService.swift` has no test file — real Keychain
 * access isn't practical to unit test on iOS either), so this is new coverage rather than a port.
 *
 * Uses [KeychainService]'s `internal` constructor with a plain (unencrypted) [SharedPreferences]
 * rather than the real Keystore-backed one: Robolectric has no working `AndroidKeyStore`
 * provider, so [androidx.security.crypto.EncryptedSharedPreferences] construction always fails
 * under it — see [KeychainService]'s class doc. This still exercises every bit of this class's
 * own logic (account-key encoding, overwrite semantics, delete-is-a-no-op, per-key isolation);
 * only the Keystore-backed encryption itself goes unverified here.
 */
@RunWith(RobolectricTestRunner::class)
class KeychainServiceTest {
    // A fresh prefs file name per instance keeps tests isolated from each other.
    private fun newService() = KeychainService(
        RuntimeEnvironment.getApplication().getSharedPreferences("keychain-test-${UUID.randomUUID()}", Context.MODE_PRIVATE),
    )

    @Test
    fun `hasPassword is false when nothing stored`() = runTest {
        val service = newService()
        assertFalse(service.hasPassword(ByteArray(32) { 0x01 }))
    }

    @Test
    fun `stores and retrieves a password`() = runTest {
        val service = newService()
        val key = ByteArray(32) { 0x02 }

        service.storePassword("hunter2", key)

        assertEquals("hunter2", service.retrievePassword(key))
        assertTrue(service.hasPassword(key))
    }

    @Test
    fun `retrieving an unknown key returns null`() = runTest {
        val service = newService()
        assertNull(service.retrievePassword(ByteArray(32) { 0x03 }))
    }

    @Test
    fun `storing again overwrites the previous password`() = runTest {
        val service = newService()
        val key = ByteArray(32) { 0x04 }

        service.storePassword("first", key)
        service.storePassword("second", key)

        assertEquals("second", service.retrievePassword(key))
    }

    @Test
    fun `deletePassword removes a stored password`() = runTest {
        val service = newService()
        val key = ByteArray(32) { 0x05 }
        service.storePassword("temp", key)

        service.deletePassword(key)

        assertFalse(service.hasPassword(key))
        assertNull(service.retrievePassword(key))
    }

    @Test
    fun `deletePassword on an unknown key is a no-op`() = runTest {
        val service = newService()
        service.deletePassword(ByteArray(32) { 0x06 }) // should not throw
    }

    @Test
    fun `different node keys are stored independently`() = runTest {
        val service = newService()
        val keyA = ByteArray(32) { 0x07 }
        val keyB = ByteArray(32) { 0x08 }

        service.storePassword("passwordA", keyA)
        service.storePassword("passwordB", keyB)

        assertEquals("passwordA", service.retrievePassword(keyA))
        assertEquals("passwordB", service.retrievePassword(keyB))
    }
}
