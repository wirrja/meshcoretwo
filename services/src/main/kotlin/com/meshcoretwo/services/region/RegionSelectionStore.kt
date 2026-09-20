// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.services.region

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted [RegionSelection], shared by onboarding's Region step and Settings' preset location.
 * Ported from `AppState.regionSelection`, which persists to `UserDefaults`; here a
 * [SharedPreferences] string via [RegionSelectionCodec].
 */
class RegionSelectionStore(private val prefs: SharedPreferences) {
    private val _selection = MutableStateFlow(RegionSelectionCodec.decode(prefs.getString(KEY, null)))
    val selection: StateFlow<RegionSelection?> = _selection.asStateFlow()

    fun set(selection: RegionSelection?) {
        _selection.value = selection
        prefs.edit { if (selection == null) remove(KEY) else putString(KEY, RegionSelectionCodec.encode(selection)) }
    }

    private companion object {
        const val KEY = "region.selection"
    }
}

/** Pipe-separated `country|admin|county|source` — plain text, no JSON dependency, tolerant of missing fields. */
object RegionSelectionCodec {
    fun encode(selection: RegionSelection): String =
        listOf(selection.countryCode, selection.administrativeAreaCode.orEmpty(), selection.countyKey.orEmpty(), selection.source.name)
            .joinToString("|")

    /** `null` for absent or malformed input (unknown source, empty country). */
    fun decode(raw: String?): RegionSelection? {
        val parts = raw?.split("|") ?: return null
        if (parts.size != 4 || parts[0].isEmpty()) return null
        val source = RegionSelection.Source.entries.firstOrNull { it.name == parts[3] } ?: return null
        return RegionSelection(
            countryCode = parts[0],
            administrativeAreaCode = parts[1].ifEmpty { null },
            countyKey = parts[2].ifEmpty { null },
            source = source,
        )
    }
}
