// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.persistence

/** Decryption outcome for an [RxLogEntity]'s channel/DM payload. Ported from `DecryptStatus.swift`, trimmed to the raw enum — no display strings, no UI. */
enum class DecryptStatus(val rawValue: Int) {
    NOT_APPLICABLE(0), // Not a channel/DM message (e.g. advert)
    NO_MATCHING_KEY(1), // Channel: no stored secret matched
    HMAC_FAILED(2), // Key found but HMAC validation failed
    DECRYPT_FAILED(3), // HMAC passed but AES decrypt failed
    SUCCESS(4), // Decrypted successfully
    PENDING(5), // Key found but decryption not yet attempted (payload too short)
    DM_NO_MATCHING_KEY(6), // DM: missing private key or contact public key
    ;

    companion object {
        fun fromRawValue(value: Int): DecryptStatus? = entries.find { it.rawValue == value }
    }
}
