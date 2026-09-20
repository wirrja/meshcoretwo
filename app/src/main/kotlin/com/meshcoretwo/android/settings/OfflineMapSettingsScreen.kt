// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoretwo.android.R
import com.meshcoretwo.android.map.OfflineMapService
import com.meshcoretwo.android.map.OfflinePack
import com.meshcoretwo.android.ui.components.EmptyState
import kotlinx.coroutines.launch

/**
 * "Offline Maps" — download/manage MapLibre tile packs for use without a network connection.
 * Ported from `OfflineMapSettingsView.swift`, reached from Settings' `MapsSection`. Swift's
 * `MapsSettingsView` hub (basemap light/dark appearance picker + a link into this screen) is
 * skipped: the appearance half needs a map-wide light/dark style switch, which `MapScreen.kt`
 * consciously never got (see its class doc — one fixed style), so a hub with a single working
 * item would be dead weight. This screen is linked directly from `SettingsScreen`'s `MapsSection`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapSettingsScreen(offlineMapService: OfflineMapService, onBack: () -> Unit, onAddRegion: () -> Unit) {
    val packs by offlineMapService.packs.collectAsStateWithLifecycle()
    val lastPackError by offlineMapService.lastPackError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(lastPackError) {
        lastPackError?.let {
            snackbarHostState.showSnackbar(it)
            offlineMapService.clearLastPackError()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.settings_offline_maps)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) }
                },
                actions = {
                    if (packs.isNotEmpty()) {
                        IconButton(onClick = onAddRegion) { Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.offmap_download_region)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (packs.isEmpty()) {
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                EmptyState(
                    icon = R.drawable.ic_map,
                    title = stringResource(R.string.offmap_none),
                    description = stringResource(R.string.offmap_none_desc),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                OutlinedButton(onClick = onAddRegion, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.offmap_download_region))
                }
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(packs, key = { it.id }) { pack ->
                    OfflinePackRow(
                        pack = pack,
                        onPauseResume = {
                            if (pack.isPaused) offlineMapService.resumePack(pack) else offlineMapService.pausePack(pack)
                        },
                        onDelete = { scope.launch { offlineMapService.deletePack(pack) } },
                    )
                    HorizontalDivider()
                }
                item { StorageSection(databaseSizeBytes = packs.sumOf { it.completedBytes }) }
            }
        }
    }
}

@Composable
private fun StorageSection(databaseSizeBytes: Long) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.offmap_storage), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.offmap_storage_used))
            Text(Formatter.formatShortFileSize(context, databaseSizeBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            stringResource(R.string.offmap_storage_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OfflinePackRow(pack: OfflinePack, onPauseResume: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row {
                Text(pack.name)
                Text(" — ${stringResource(pack.layer.labelRes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    when {
                        pack.isComplete -> stringResource(R.string.offmap_complete)
                        pack.isPaused -> stringResource(R.string.offmap_paused)
                        else -> stringResource(R.string.offmap_downloading)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Formatter.formatShortFileSize(context, pack.completedBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    pack.downloadSpeedBytesPerSecond?.takeIf { it > 0 }?.let { speed ->
                        Text(
                            "${Formatter.formatShortFileSize(context, speed)}/s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!pack.isComplete) {
                LinearProgressIndicator(
                    progress = { pack.completedFraction.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        if (!pack.isComplete) {
            IconButton(onClick = onPauseResume) {
                Icon(
                    painterResource(if (pack.isPaused) R.drawable.ic_play_arrow else R.drawable.ic_pause),
                    contentDescription = if (pack.isPaused) stringResource(R.string.offmap_resume) else stringResource(R.string.offmap_pause),
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error)
        }
    }
}
