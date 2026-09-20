// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject
import java.io.File

/**
 * Persists inline-image aspect ratios (chat link-preview frame heights) to a flat JSON file.
 * Ported from `InlineImageDimensionsStore.swift`. The store is disposable: if the on-disk file is
 * missing or corrupt, it starts empty and re-populates via probe-on-next-receive. Not part of any
 * backup/export envelope.
 *
 * Swift's actor isolation (`nonisolated func aspect(for:)` reading a lock-protected mirror so
 * SwiftUI view bodies resolve frame heights without an actor hop) has no Kotlin actor to port.
 * Instead, [aspect] reads a `@Volatile` immutable snapshot map that [save] republishes as a whole
 * on each write — a plain field read is enough for cross-thread visibility of an immutable map,
 * so no lock is needed on the read path either.
 *
 * Uses `org.json` (part of the Android SDK, no new Gradle dependency) rather than
 * kotlinx.serialization, since this is the only JSON-shaped persistence in the codebase so far.
 */
class InlineImageDimensionsStore(context: Context, fileName: String = FILE_NAME) {
    private data class Entry(val aspect: Double, val fetchedAtEpochMillis: Long)

    private val file = File(context.filesDir, fileName)
    private val writeMutex = Mutex()

    private val entries: MutableMap<String, Entry> = loadFromDisk().toMutableMap()

    @Volatile
    private var aspectMirror: Map<String, Double> = entries.mapValues { it.value.aspect }

    private val resolutionFlow = MutableSharedFlow<String>(extraBufferCapacity = RESOLUTION_STREAM_BUFFER_DEPTH)

    /**
     * Upserts an aspect ratio for [url]. Rejects (silently, no stream emission) a non-positive
     * [width] or [height].
     */
    suspend fun save(url: String, width: Double, height: Double) {
        if (width <= 0 || height <= 0) return
        val aspect = width / height

        writeMutex.withLock {
            entries[url] = Entry(aspect, System.currentTimeMillis())
            aspectMirror = entries.mapValues { it.value.aspect }
            persist()
        }
        resolutionFlow.emit(url)
    }

    /** Wait-free aspect lookup, safe to call from a Compose recomposition. */
    fun aspect(url: String): Double? = aspectMirror[url]

    /**
     * A multicast stream of URLs whose aspect was just (re)saved, including idempotent re-saves.
     * Every subscriber sees every event emitted after it subscribes.
     */
    fun resolutionUpdates(): Flow<String> = resolutionFlow

    private fun persist() {
        val json = JSONObject()
        for ((url, entry) in entries) {
            json.put(
                url,
                JSONObject().apply {
                    put("aspect", entry.aspect)
                    put("fetchedAt", entry.fetchedAtEpochMillis)
                },
            )
        }
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.toString())
        } catch (error: Exception) {
            Log.e(TAG, "Failed to write inline image dimensions store", error)
        }
    }

    private fun loadFromDisk(): Map<String, Entry> {
        if (!file.exists()) return emptyMap()
        return try {
            val json = JSONObject(file.readText())
            buildMap {
                for (url in json.keys()) {
                    val obj = json.getJSONObject(url)
                    put(url, Entry(obj.getDouble("aspect"), obj.getLong("fetchedAt")))
                }
            }
        } catch (error: Exception) {
            Log.i(TAG, "Inline image dimensions file present but undecodable; starting empty", error)
            emptyMap()
        }
    }

    companion object {
        private const val TAG = "InlineImageDimensions"
        private const val FILE_NAME = "inline_image_dimensions.json"
        private const val RESOLUTION_STREAM_BUFFER_DEPTH = 64
    }
}
