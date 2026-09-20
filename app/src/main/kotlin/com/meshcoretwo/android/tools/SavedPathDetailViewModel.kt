// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.tracePathService
import com.meshcoretwo.services.persistence.TracePathDto
import com.meshcoretwo.services.persistence.TracePathRunDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Runs newest-first. Ported from `SavedPathDetailViewModel.swift`'s `sortedRuns`. */
val TracePathDto.sortedRuns: List<TracePathRunDto> get() = runs.sortedByDescending { it.date }

/** Successful runs only, newest first. Ported from `successfulRuns`. */
val TracePathDto.successfulRuns: List<TracePathRunDto> get() = sortedRuns.filter { it.success }

/** Fastest successful round trip. Ported from `bestRoundTrip`. */
val TracePathDto.bestRoundTripMs: Int? get() = successfulRuns.minOfOrNull { it.roundTripMs }

/**
 * Backs [SavedPathDetailScreen] with a refreshable snapshot of one saved path. Ported from
 * `SavedPathDetailViewModel.swift`; `hashSize`/`averageRoundTripMs`/`successRate` are already
 * plain [TracePathDto] properties (see `TracePathDto.kt`), so only [refresh] needs a live
 * dependency here.
 */
class SavedPathDetailViewModel(private val connectionManager: ConnectionManager, initialPath: TracePathDto) : ViewModel() {
    private val _savedPath = MutableStateFlow(initialPath)
    val savedPath: StateFlow<TracePathDto> = _savedPath.asStateFlow()

    /** Re-fetches the path's latest run history. Ported from `refresh`. */
    suspend fun refresh() {
        val service = connectionManager.tracePathService ?: return
        service.fetchSavedTracePath(_savedPath.value.id)?.let { updated -> _savedPath.value = updated }
    }

    class Factory(private val connectionManager: ConnectionManager, private val initialPath: TracePathDto) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SavedPathDetailViewModel(connectionManager, initialPath) as T
    }
}
