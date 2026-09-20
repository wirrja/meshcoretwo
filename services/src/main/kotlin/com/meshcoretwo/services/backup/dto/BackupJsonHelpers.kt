// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.backup.dto

import com.meshcoretwo.protocol.decodeHex
import com.meshcoretwo.protocol.hexString
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small `org.json` conversion helpers shared by every `*BackupJson.kt` file in this package — the
 * mechanical part of hand-writing `toBackupJson()`/`from*BackupJson()` for each of the 12 backup
 * model DTOs (see PLAN.md's "Backup/restore — план среза", sub-slice 2). Same `org.json` choice as
 * `NodeConfig.kt` (not `kotlinx.serialization`, see that file's class doc), just factored out here
 * since 12 DTOs repeat the same handful of field shapes (hex-encoded bytes, epoch-millis instants,
 * nullable unsigned integers) far more than `NodeConfig.kt`'s single DTO did.
 *
 * A `ByteArray` round-trips as lowercase hex (via [hexString]/[String.decodeHex]) — matching this
 * codebase's existing convention for keys/paths everywhere else — rather than base64; a null
 * *present* key is `JSONObject.NULL`, never an absent key, so a decode failure reads as "wrong
 * type" rather than silently defaulting.
 */
internal fun JSONObject.putHex(key: String, value: ByteArray) {
    put(key, value.hexString)
}

internal fun JSONObject.getHex(key: String): ByteArray = getString(key).decodeHex() ?: ByteArray(0)

internal fun JSONObject.putHexOrNull(key: String, value: ByteArray?) {
    put(key, value?.hexString ?: JSONObject.NULL)
}

internal fun JSONObject.getHexOrNull(key: String): ByteArray? = if (isNull(key)) null else getString(key).decodeHex()

internal fun JSONObject.putInstant(key: String, value: Instant) {
    put(key, value.toEpochMilli())
}

internal fun JSONObject.getInstant(key: String): Instant = Instant.ofEpochMilli(getLong(key))

internal fun JSONObject.putInstantOrNull(key: String, value: Instant?) {
    put(key, value?.toEpochMilli() ?: JSONObject.NULL)
}

internal fun JSONObject.getInstantOrNull(key: String): Instant? = if (isNull(key)) null else Instant.ofEpochMilli(getLong(key))

internal fun JSONObject.putUByteOrNull(key: String, value: UByte?) {
    put(key, value?.toInt() ?: JSONObject.NULL)
}

internal fun JSONObject.getUByteOrNull(key: String): UByte? = if (isNull(key)) null else getInt(key).toUByte()

internal fun JSONObject.putUShortOrNull(key: String, value: UShort?) {
    put(key, value?.toInt() ?: JSONObject.NULL)
}

internal fun JSONObject.getUShortOrNull(key: String): UShort? = if (isNull(key)) null else getInt(key).toUShort()

internal fun JSONObject.putUIntOrNull(key: String, value: UInt?) {
    put(key, value?.toLong() ?: JSONObject.NULL)
}

internal fun JSONObject.getUIntOrNull(key: String): UInt? = if (isNull(key)) null else getLong(key).toUInt()

internal fun JSONObject.putIntOrNull(key: String, value: Int?) {
    put(key, value ?: JSONObject.NULL)
}

internal fun JSONObject.getIntOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)

internal fun JSONObject.putShortOrNull(key: String, value: Short?) {
    put(key, value?.toInt() ?: JSONObject.NULL)
}

internal fun JSONObject.getShortOrNull(key: String): Short? = if (isNull(key)) null else getInt(key).toShort()

internal fun JSONObject.putLongOrNull(key: String, value: Long?) {
    put(key, value ?: JSONObject.NULL)
}

internal fun JSONObject.getLongOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)

internal fun JSONObject.putDoubleOrNull(key: String, value: Double?) {
    put(key, value ?: JSONObject.NULL)
}

internal fun JSONObject.getDoubleOrNull(key: String): Double? = if (isNull(key)) null else getDouble(key)

internal fun JSONObject.putBooleanOrNull(key: String, value: Boolean?) {
    put(key, value ?: JSONObject.NULL)
}

internal fun JSONObject.getBooleanOrNull(key: String): Boolean? = if (isNull(key)) null else getBoolean(key)

internal fun JSONObject.putStringOrNull(key: String, value: String?) {
    put(key, value ?: JSONObject.NULL)
}

internal fun JSONObject.getStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

internal fun JSONObject.putUuid(key: String, value: UUID) {
    put(key, value.toString())
}

internal fun JSONObject.getUuid(key: String): UUID = UUID.fromString(getString(key))

internal fun JSONObject.putUuidOrNull(key: String, value: UUID?) {
    put(key, value?.toString() ?: JSONObject.NULL)
}

internal fun JSONObject.getUuidOrNull(key: String): UUID? = if (isNull(key)) null else UUID.fromString(getString(key))

internal fun JSONObject.putStringList(key: String, value: List<String>) {
    put(key, JSONArray().apply { value.forEach { put(it) } })
}

internal fun JSONObject.getStringList(key: String): List<String> {
    val array = getJSONArray(key)
    return List(array.length()) { array.getString(it) }
}

internal fun JSONObject.putDoubleList(key: String, value: List<Double>) {
    put(key, JSONArray().apply { value.forEach { put(it) } })
}

internal fun JSONObject.getDoubleList(key: String): List<Double> {
    val array = getJSONArray(key)
    return List(array.length()) { array.getDouble(it) }
}

internal fun <T> JSONObject.putObjectListOrNull(key: String, value: List<T>?, toJson: (T) -> JSONObject) {
    put(key, value?.let { list -> JSONArray().apply { list.forEach { put(toJson(it)) } } } ?: JSONObject.NULL)
}

internal fun <T> JSONObject.getObjectListOrNull(key: String, fromJson: (JSONObject) -> T): List<T>? {
    if (isNull(key)) return null
    val array = getJSONArray(key)
    return List(array.length()) { fromJson(array.getJSONObject(it)) }
}

internal fun <T> JSONObject.putObjectList(key: String, value: List<T>, toJson: (T) -> JSONObject) {
    put(key, JSONArray().apply { value.forEach { put(toJson(it)) } })
}

internal fun <T> JSONObject.getObjectList(key: String, fromJson: (JSONObject) -> T): List<T> {
    val array = getJSONArray(key)
    return List(array.length()) { fromJson(array.getJSONObject(it)) }
}
