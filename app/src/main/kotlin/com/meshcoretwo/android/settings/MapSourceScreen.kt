// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoretwo.android.R
import com.meshcoretwo.android.map.MapBaseStyle
import com.meshcoretwo.android.map.MapProbeResult
import com.meshcoretwo.android.map.MapTileProviderId
import com.meshcoretwo.android.map.MapTiles
import com.meshcoretwo.android.ui.components.SettingsGroupLabel
import kotlinx.coroutines.launch

/**
 * Settings → Maps → Map source. Not a port: iOS has one fixed basemap. Lists the providers from
 * [MapTileProviderId], and "Check availability" probes each from the current network
 * ([MapTiles.probeAll]), since which host loads depends on the ISP (see [MapTileProviderId]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapSourceScreen(onBack: () -> Unit) {
    val selected by MapTiles.selected.collectAsStateWithLifecycle()
    val savedCustomUrl by MapTiles.customUrl.collectAsStateWithLifecycle()
    val autoResolved by MapTiles.autoResolved.collectAsStateWithLifecycle()
    var customDraft by remember { mutableStateOf(savedCustomUrl) }
    var customExpanded by remember { mutableStateOf(selected == MapTileProviderId.CUSTOM) }
    var probeResults by remember { mutableStateOf<Map<MapTileProviderId, MapProbeResult>>(emptyMap()) }
    var isProbing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val customValid = MapBaseStyle.custom(customDraft) != null

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.map_source_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Text(
                stringResource(R.string.map_source_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsGroupLabel(stringResource(R.string.map_source_title))
            MapTileProviderId.entries.forEachIndexed { index, id ->
                if (index > 0) HorizontalDivider()
                val isSelected = if (id == MapTileProviderId.CUSTOM) customExpanded else id == selected && !customExpanded
                ProviderRow(
                    id = id,
                    selected = isSelected,
                    status = when (id) {
                        MapTileProviderId.AUTO -> autoResolved?.let { stringResource(R.string.map_source_auto_current, stringResource(it.labelRes)) }
                        else -> null
                    },
                    probe = probeResults[id],
                    onClick = {
                        if (id == MapTileProviderId.CUSTOM) {
                            customExpanded = true
                            if (customValid) MapTiles.select(id, customDraft)
                        } else {
                            customExpanded = false
                            MapTiles.select(id)
                        }
                    },
                )
                if (id == MapTileProviderId.CUSTOM && customExpanded) {
                    OutlinedTextField(
                        value = customDraft,
                        onValueChange = { customDraft = it },
                        label = { Text(stringResource(R.string.map_source_custom_url)) },
                        placeholder = { Text("https://…/style.json") },
                        supportingText = {
                            Text(
                                if (customDraft.isNotBlank() && !customValid) {
                                    stringResource(R.string.map_source_custom_invalid)
                                } else {
                                    stringResource(R.string.map_source_custom_help)
                                },
                            )
                        },
                        isError = customDraft.isNotBlank() && !customValid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(start = 36.dp),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            enabled = customValid && (selected != MapTileProviderId.CUSTOM || customDraft.trim() != savedCustomUrl),
                            onClick = { MapTiles.select(MapTileProviderId.CUSTOM, customDraft) },
                        ) { Text(stringResource(R.string.common_save)) }
                    }
                }
            }
            HorizontalDivider()
            OutlinedButton(
                enabled = !isProbing,
                onClick = {
                    isProbing = true
                    probeResults = emptyMap()
                    scope.launch {
                        try {
                            probeResults = MapTiles.probeAll()
                            // The network may have changed since AUTO settled; let it pick again.
                            MapTiles.invalidate()
                        } finally {
                            isProbing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            ) {
                if (isProbing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(if (isProbing) R.string.map_source_checking else R.string.map_source_check))
            }
            Text(
                stringResource(R.string.map_source_offline_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun ProviderRow(id: MapTileProviderId, selected: Boolean, status: String?, probe: MapProbeResult?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(id.labelRes), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(id.descriptionRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        when (probe) {
            is MapProbeResult.Reachable -> Text(
                stringResource(R.string.map_source_reachable, probe.millis),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            is MapProbeResult.Unreachable -> Text(
                stringResource(R.string.map_source_unreachable),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            null -> Unit
        }
    }
}
