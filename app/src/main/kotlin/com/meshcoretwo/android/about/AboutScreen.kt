// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoretwo.android.BuildConfig
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.AppMark
import com.meshcoretwo.android.ui.components.SettingsGroupLabel
import com.meshcoretwo.android.ui.components.SettingsListRow

internal const val GPL_TITLE = "GNU General Public License v3.0"

private const val GPL_NOTICE =
    "This program is free software: you can redistribute it and/or modify it under the terms of the GNU " +
        "General Public License as published by the Free Software Foundation, version 3.\n\n" +
        "This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without " +
        "even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU " +
        "General Public License for more details."

// Verbatim notice required by the Copernicus DEM licence (PLAN.md Л3), Article 6(b) — the app shows
// elevation values derived from the DEM, not the raw raster, so the "adapted or modified" wording
// applies: https://docs.sentinel-hub.com/api/latest/static/files/data/dem/resources/license/License-COPDEM-30.pdf
private const val COPERNICUS_NOTICE =
    "produced using Copernicus WorldDEM-90 © DLR e.V. 2010-2014 and © Airbus Defence and " +
        "Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved"

/**
 * About this app (PLAN.md Л1): name and version, the unofficial-port notice, copyright and the
 * GPL-3.0 notice, the license texts bundled in the APK, and where map and elevation data come from
 * (Л3). Loosely ported from `AboutSection.swift` and `FeedbackView.swift`, minus everything that
 * leads to the original author: the sponsor link, their GitHub repository and issues, their email and
 * the meshcoreone.com privacy policy (project constraints, hard constraint 3). The origin is stated as plain
 * text instead, as in README.md.
 *
 * "Source code" and "Report a problem" appear only once [AppLinks.SOURCE_REPOSITORY_URL] is set.
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenLicense: (assetPath: String, title: String) -> Unit,
    onOpenLicenses: () -> Unit,
) {
    Scaffold(topBar = { BackTopAppBar(stringResource(R.string.settings_about), onBack) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppMark(size = 64.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Unofficial port of MeshCore One. Not affiliated with, endorsed by, or supported by the author " +
                        "of MeshCore One or the MeshCore project.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SettingsGroupLabel(stringResource(R.string.about_license))
            Text(
                "Copyright (C) 2025–2026 Avi0n and MeshCore One contributors\nCopyright (C) 2026 wirrja",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(GPL_NOTICE, style = MaterialTheme.typography.bodySmall)
            SettingsListRow(title = GPL_TITLE, onClick = { onOpenLicense(LicenseAssets.APP_LICENSE, GPL_TITLE) })
            HorizontalDivider()
            SettingsListRow(title = stringResource(R.string.about_third_party), onClick = onOpenLicenses)

            SettingsGroupLabel(stringResource(R.string.about_origin))
            Text(
                "A Kotlin rewrite for Android of MeshCore One (github.com/Avi0n/MeshCoreOne, GPL-3.0), the " +
                    "iOS messaging client for MeshCore LoRa mesh radios, based on its version v1.4.0. The " +
                    "protocol layer is ported from Swift MeshCore and meshcore_py (MIT).",
                style = MaterialTheme.typography.bodySmall,
            )

            AppLinks.SOURCE_REPOSITORY_URL?.let { repository ->
                SettingsGroupLabel(stringResource(R.string.about_source_code))
                Text(
                    "Every released APK is built from a tagged commit of this repository. Please report " +
                        "problems with this app there, not to the author of MeshCore One.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ExternalLinkRow(stringResource(R.string.about_source_code), repository)
                HorizontalDivider()
                ExternalLinkRow(stringResource(R.string.about_report_problem), "$repository/issues")
            }

            SettingsGroupLabel(stringResource(R.string.about_data_sources))
            Text("Map data: OpenFreeMap © OpenMapTiles Data from OpenStreetMap", style = MaterialTheme.typography.bodySmall)
            ExternalLinkRow(stringResource(R.string.about_osm_copyright), AppLinks.OPENSTREETMAP_COPYRIGHT)
            Text(
                "Elevation data: Copernicus DEM GLO-90, read directly from the public AWS Open Data bucket",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(COPERNICUS_NOTICE, style = MaterialTheme.typography.labelSmall)
            ExternalLinkRow("Copernicus DEM", AppLinks.COPERNICUS_DEM)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackTopAppBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
    )
}

/** A row that opens [url] in the browser. */
@Composable
internal fun ExternalLinkRow(label: String, url: String) {
    val context = LocalContext.current
    SettingsListRow(
        title = label,
        trailingIcon = R.drawable.ic_open_in_new,
        onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (error: ActivityNotFoundException) {
                // No browser installed; there is nothing sensible to fall back to.
            }
        },
    )
}
