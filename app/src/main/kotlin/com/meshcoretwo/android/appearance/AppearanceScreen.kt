// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.appearance

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.SettingsListRow
import com.meshcoretwo.android.ui.i18n.AppLanguage
import com.meshcoretwo.android.ui.i18n.AppLanguageManager
import com.meshcoretwo.android.ui.theme.AppColorSchemePreference
import com.meshcoretwo.android.ui.theme.ThemeRegistry
import com.meshcoretwo.android.ui.theme.ThemeService

private val GridItemMinimum = 160.dp
private val GridSpacing = 12.dp

/**
 * Settings → Appearance: the global light/dark/system picker plus the theme grid. Every built-in
 * theme is available and selectable — this port has no monetization (project constraints), which is exactly
 * iOS's sideload branch (`ThemeService.isAccessible`), so the "Purchase more themes" link
 * (`AppearanceView.shouldShowBrowseMore`, only shown when some registry theme is inaccessible)
 * never applies and isn't ported.
 *
 * Ported from `AppearanceView.swift`, as one `LazyVerticalGrid` instead of a `List` with a nested
 * `LazyVGrid` section — the scheme picker is a full-span grid item ahead of the theme cards, since
 * Compose (unlike SwiftUI's `List`) can't cheaply nest one lazy scroll container inside another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(themeService: ThemeService, onBack: () -> Unit) {
    val current by themeService.current.collectAsStateWithLifecycle()
    val preference by themeService.colorSchemePreference.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.appearance_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = GridItemMinimum),
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(GridSpacing),
            verticalArrangement = Arrangement.spacedBy(GridSpacing),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SchemePicker(
                    preference = preference,
                    onSelect = themeService::setColorSchemePreference,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.appearance_themes), style = MaterialTheme.typography.titleMedium)
            }
            items(ThemeRegistry.allThemes, key = { it.id }) { theme ->
                ThemeSelectionCard(
                    theme = theme,
                    isSelected = theme.id == current.id,
                    onSelect = { themeService.setCurrent(theme) },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchemePicker(
    preference: AppColorSchemePreference,
    onSelect: (AppColorSchemePreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        AppColorSchemePreference.SYSTEM to stringResource(R.string.appearance_scheme_system),
        AppColorSchemePreference.LIGHT to stringResource(R.string.appearance_scheme_light),
        AppColorSchemePreference.DARK to stringResource(R.string.appearance_scheme_dark),
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier.width(IntrinsicSize.Max)) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = preference == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label)
            }
        }
    }
}
