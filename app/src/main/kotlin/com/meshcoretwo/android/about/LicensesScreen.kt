// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.LoadingScreen
import com.meshcoretwo.android.ui.components.SettingsGroupLabel
import com.meshcoretwo.android.ui.components.SettingsListRow
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val NOTICES_TITLE = "Third-party notices"

/**
 * Every license text bundled in the APK: this app's GPL-3.0, `THIRD_PARTY_NOTICES.md`, and the full
 * texts from `LICENSES/` it refers to. Takes the place of iOS's `Settings.bundle/Acknowledgements.plist`
 * panes. The `LICENSES/` entries are listed from the assets, so a newly added file shows up here
 * without a code change.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit, onOpenLicense: (assetPath: String, title: String) -> Unit) {
    val context = LocalContext.current
    var licenseTexts by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(context) {
        licenseTexts = withContext(Dispatchers.IO) {
            context.assets.list(LicenseAssets.LICENSE_TEXTS_DIRECTORY).orEmpty()
                .sorted()
                .map { "${LicenseAssets.LICENSE_TEXTS_DIRECTORY}/$it" }
        }
    }

    Scaffold(topBar = { BackTopAppBar(stringResource(R.string.licenses_title), onBack) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SettingsGroupLabel("This app")
            SettingsListRow(title = GPL_TITLE, onClick = { onOpenLicense(LicenseAssets.APP_LICENSE, GPL_TITLE) })
            HorizontalDivider()
            SettingsListRow(title = NOTICES_TITLE, onClick = { onOpenLicense(LicenseAssets.THIRD_PARTY_NOTICES, NOTICES_TITLE) })

            SettingsGroupLabel("Full license texts")
            Text(
                "Referenced from the third-party notices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            licenseTexts.forEachIndexed { index, path ->
                val title = LicenseAssets.title(path)
                SettingsListRow(title = title, onClick = { onOpenLicense(path, title) })
                if (index != licenseTexts.lastIndex) HorizontalDivider()
            }
        }
    }
}

/**
 * One bundled license document, laid out by [LicenseDocument]. The asset is read off the main
 * thread; if it's missing (a broken build), the screen says so instead of crashing.
 */
@Composable
fun LicenseTextScreen(assetPath: String, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var blocks by remember(assetPath) { mutableStateOf<List<LicenseBlock>?>(null) }
    LaunchedEffect(context, assetPath) {
        blocks = withContext(Dispatchers.IO) {
            try {
                val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
                LicenseDocument.parse(text, LicenseAssets.isMarkdown(assetPath))
            } catch (error: IOException) {
                listOf(LicenseBlock.Paragraph("Couldn't load $assetPath."))
            }
        }
    }

    Scaffold(topBar = { BackTopAppBar(title, onBack) }) { padding ->
        val loaded = blocks
        if (loaded == null) {
            LoadingScreen(modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(loaded) { block -> LicenseBlockText(block) }
            }
        }
    }
}

@Composable
private fun LicenseBlockText(block: LicenseBlock) {
    when (block) {
        is LicenseBlock.Heading -> Text(
            block.text,
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            modifier = Modifier.padding(top = 8.dp),
        )
        is LicenseBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
        is LicenseBlock.TableRow -> Column {
            block.cells.forEachIndexed { index, cell ->
                if (index == 0) {
                    Text(cell, style = MaterialTheme.typography.titleSmall)
                } else {
                    Text(
                        block.headers.getOrNull(index)?.let { header -> "$header: $cell" } ?: cell,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
