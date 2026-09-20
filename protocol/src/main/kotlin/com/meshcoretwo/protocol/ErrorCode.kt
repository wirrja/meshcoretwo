// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.protocol

/**
 * Device error sub-codes carried by a `PACKET_ERROR` frame.
 *
 * When a command fails, the firmware replies with `RESP_CODE_ERR` followed by a
 * single error-code byte. These values mirror the `ERR_CODE_*` constants defined
 * in the reference firmware (`examples/companion_radio/MyMesh.cpp`).
 *
 * The wire value should be preserved as a raw `UByte?` on the error event so that
 * sub-codes outside the known range survive round-tripping; use [fromValue] to
 * obtain the typed value when the byte is one of the known codes.
 */
enum class ErrorCode(val value: UByte) {
    /** Unknown or unsupported command byte / sub-command. */
    UNSUPPORTED_COMMAND(1u),
    /** Target not found (channel, contact, message, etc.). */
    NOT_FOUND(2u),
    /** Internal queue or table is full; retry later. */
    TABLE_FULL(3u),
    /** Operation not valid in the current device state (e.g. iterator already running). */
    BAD_STATE(4u),
    /** Filesystem or storage I/O failure. */
    FILE_IO_ERROR(5u),
    /** Invalid argument (bad length, out-of-range value, reserved field, etc.). */
    ILLEGAL_ARGUMENT(6u);

    companion object {
        private val byValue = entries.associateBy { it.value }
        fun fromValue(value: UByte): ErrorCode? = byValue[value]
    }
}
