// SPDX-License-Identifier: GPL-3.0-only

// androidx.security.crypto 1.1.0 (MasterKey/EncryptedSharedPreferences) ships marked
// @Deprecated — Google stopped actively developing this library and points new code at rolling
// your own Keystore/Tink-based scheme instead — but it is still functional, still the current
// stable release, and is the exact approach project constraints already commits this project to ("Android
// Keystore / EncryptedSharedPreferences вместо системного Keychain"). Rolling custom Keystore
// crypto is real security-sensitive work belonging to its own deliberate task, not a side effect
// of porting this class; revisit only if a future compileSdk bump actually breaks this library.
@file:Suppress("DEPRECATION")

package com.meshcoretwo.services.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Errors that can occur during [KeychainService] operations.
 *
 * Ported from `KeychainError` (`KeychainService.swift`). Drops Swift's `encodingFailed` case:
 * that guarded `String.data(using: .utf8)` before handing raw bytes to the Keychain API: Android's
 * [EncryptedSharedPreferences] takes a [String] directly, so there is no manual encoding step
 * that can fail. `storageFailed`/`retrievalFailed`/`deletionFailed` carry a message instead of
 * Swift's `OSStatus` — there is no Android equivalent status code, just success/failure and,
 * for initialization, a caught platform exception.
 */
sealed class KeychainError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data class InitializationFailed(val reason: String, val throwable: Throwable) :
        KeychainError("Failed to initialize secure storage: $reason", throwable)
    data class StorageFailed(val reason: String) : KeychainError("Failed to store password: $reason")
    data class RetrievalFailed(val reason: String, val throwable: Throwable) :
        KeychainError("Failed to retrieve password: $reason", throwable)
    data class DeletionFailed(val reason: String) : KeychainError("Failed to delete password: $reason")
}

/**
 * Secure password storage for remote node authentication. Ported from `KeychainService.swift`.
 *
 * iOS's Keychain (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, not synced to iCloud) becomes
 * [EncryptedSharedPreferences] backed by a Keystore-generated [MasterKey] — device-only by
 * construction, since Android Keystore keys are non-exportable and never leave the device (there
 * is no Android equivalent of iCloud Keychain sync to opt out of).
 *
 * **No `Mutex`, unlike most actor ports in this codebase.** Swift's `storePassword` needs its own
 * serialization because the raw Keychain API requires an explicit `SecItemDelete` before
 * `SecItemAdd` (`SecItemAdd` fails outright if an entry already exists) — a two-step sequence an
 * actor must make atomic. `SharedPreferences.Editor.putString` overwrites unconditionally in one
 * call, so that sequence — and the invariant a lock would protect — doesn't exist here; each
 * method is already a single atomic operation, and `SharedPreferences` itself is internally
 * thread-safe for concurrent access from multiple threads.
 *
 * **No retry loop**, unlike Swift's 3-attempt/100ms-delay retry around `SecItemAdd`: that works
 * around a known transient iOS failure mode (the Keychain briefly locked mid-unlock), which has
 * no Android Keystore equivalent — retrying a `commit()` that just failed would not plausibly
 * succeed on a second attempt.
 *
 * **Testing note:** the `internal` constructor exists so tests can supply a plain
 * [SharedPreferences] instead of the real Keystore-backed one — Robolectric (this module's JVM
 * unit-test environment) has no working `AndroidKeyStore` provider, so [EncryptedSharedPreferences]
 * construction always fails there (`NoSuchAlgorithmException`). All the actual logic this class
 * adds (account-key encoding, overwrite-on-store, delete-is-a-no-op, per-key isolation) is
 * exercised that way; only the Keystore-backed encryption itself is untested here and needs a
 * real device or emulator to verify, the same caveat as BLE (see PLAN.md).
 */
class KeychainService internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(createEncryptedPreferences(context.applicationContext))

    /**
     * Stores [password] for the remote node identified by its 32-byte [publicKey], overwriting
     * any password already stored for it.
     */
    suspend fun storePassword(password: String, publicKey: ByteArray) = withContext(Dispatchers.IO) {
        val success = prefs.edit().putString(accountKey(publicKey), password).commit()
        if (!success) throw KeychainError.StorageFailed("SharedPreferences commit() returned false")
    }

    /** Retrieves the stored password for [publicKey], or `null` if none is stored. */
    suspend fun retrievePassword(publicKey: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            prefs.getString(accountKey(publicKey), null)
        } catch (error: Exception) {
            throw KeychainError.RetrievalFailed(error.message ?: error.toString(), error)
        }
    }

    /** Deletes the stored password for [publicKey]. A no-op if none is stored. */
    suspend fun deletePassword(publicKey: ByteArray) = withContext(Dispatchers.IO) {
        val success = prefs.edit().remove(accountKey(publicKey)).commit()
        if (!success) throw KeychainError.DeletionFailed("SharedPreferences commit() returned false")
    }

    /** Whether a password is currently stored for [publicKey]. */
    suspend fun hasPassword(publicKey: ByteArray): Boolean = withContext(Dispatchers.IO) {
        prefs.contains(accountKey(publicKey))
    }

    private fun accountKey(publicKey: ByteArray): String = Base64.encodeToString(publicKey, Base64.NO_WRAP)
}

private const val PREFS_FILE_NAME = "com.meshcoretwo.nodepasswords"

private fun createEncryptedPreferences(context: Context): SharedPreferences = try {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
} catch (error: Exception) {
    throw KeychainError.InitializationFailed(error.message ?: error.toString(), error)
}
