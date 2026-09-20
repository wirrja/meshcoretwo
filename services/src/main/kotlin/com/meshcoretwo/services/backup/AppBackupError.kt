// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup

/**
 * Errors that can occur during app backup export or import. Ported from `AppBackupError.swift`;
 * the human-readable message lives directly on each case's [Exception] message instead of a
 * separate `AppBackupError+Localized.swift`-equivalent file, the same choice already made for
 * [com.meshcoretwo.services.contacts.ContactServiceError] and friends.
 */
sealed class AppBackupError(message: String) : Exception(message) {
    object InvalidFile : AppBackupError("The backup file is invalid or could not be read.")

    data class FileTooLarge(val actualBytes: Long, val maxBytes: Long) :
        AppBackupError("The backup file is too large to import (${actualBytes / 1_048_576} MB; limit is ${maxBytes / 1_048_576} MB).")

    data class DecompressedTooLarge(val maxBytes: Long) :
        AppBackupError("The backup file expands past the safe size limit (${maxBytes / 1_048_576} MB uncompressed).")

    data class UnsupportedVersion(val found: Int, val maxSupported: Int) :
        AppBackupError("This backup was created with a newer format (version $found). This app supports up to version $maxSupported. Please update the app and try again.")

    object CorruptedManifest :
        AppBackupError("The backup file appears to be corrupted. The declared item counts do not match the actual data.")

    data class ExportFailed(val underlying: Throwable) : AppBackupError("Failed to create backup: ${underlying.message}")

    data class ImportFailed(val underlying: Throwable) : AppBackupError("Failed to import backup: ${underlying.message}")
}
