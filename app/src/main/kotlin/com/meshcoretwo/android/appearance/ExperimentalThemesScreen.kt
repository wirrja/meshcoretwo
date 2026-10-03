// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.theme.ThemeRegistry
import com.meshcoretwo.android.ui.theme.ThemeService

/**
 * Settings → Appearance → Experimental themes: the [ThemeRegistry.experimentalThemes] in their
 * [com.meshcoretwo.android.ui.theme.ThemeGroup] sections, under a notice that they may still change.
 * Same tiles and the same apply-on-tap as Appearance itself; returning to a classic theme is
 * picking one of the five there. Not a port — MeshCore One has no experimental themes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentalThemesScreen(themeService: ThemeService, onBack: () -> Unit) {
    val current by themeService.current.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(R.string.appearance_experimental_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = ThemeGridItemMinimum),
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(ThemeGridSpacing),
            verticalArrangement = Arrangement.spacedBy(ThemeGridSpacing),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_info),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        stringResource(R.string.appearance_experimental_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            ThemeRegistry.experimentalByGroup().forEach { (group, themes) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "group-${group.name}") {
                    Text(
                        stringResource(group.title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                items(themes, key = { it.id }) { theme ->
                    ThemeSelectionCard(
                        theme = theme,
                        isSelected = theme.id == current.id,
                        onSelect = { themeService.setCurrent(theme) },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
