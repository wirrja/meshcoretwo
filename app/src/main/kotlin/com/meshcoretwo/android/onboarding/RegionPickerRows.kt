// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.meshcoretwo.android.R
import com.meshcoretwo.services.region.RegionSelection
import com.meshcoretwo.services.region.RegionalAreas

/** Country/State-province rows plus their pickers, shared by onboarding's Region step and Settings' preset location. Ported from `RegionPickerRows.swift`. */
@Composable
internal fun RegionPickerRows(selection: RegionSelection?, onSelectionChange: (RegionSelection) -> Unit) {
    var showCountryPicker by remember { mutableStateOf(false) }
    var showSubdivisionPicker by remember { mutableStateOf(false) }
    val availableSubdivisions = RegionalAreas.subdivisions(selection?.countryCode)

    Column(modifier = Modifier.fillMaxWidth()) {
        RegionPickerRow(
            label = stringResource(R.string.region_country),
            value = selection?.countryCode?.let(::countryDisplayName),
            onClick = { showCountryPicker = true },
        )
        if (availableSubdivisions.size > 1) {
            HorizontalDivider()
            RegionPickerRow(
                label = stringResource(R.string.region_state_province),
                value = selection?.administrativeAreaCode?.let { RegionalAreas.subdivisionDisplayName(it) },
                onClick = { showSubdivisionPicker = true },
            )
        }
    }

    if (showCountryPicker) {
        CountryPickerDialog(
            selectedCountry = selection?.countryCode,
            // Dropping the subdivision when the country changes keeps a stale id (e.g. "US-CA")
            // from riding on a new country (e.g. "CA") into the persisted selection.
            onSelect = { country ->
                RegionSelection.afterChoosingCountry(country, selection)?.let(onSelectionChange)
                showCountryPicker = false
            },
            onDismiss = { showCountryPicker = false },
        )
    }

    if (showSubdivisionPicker && selection != null) {
        SubdivisionPickerDialog(
            country = selection.countryCode,
            selectedSubdivision = selection.administrativeAreaCode,
            onSelect = { subdivision ->
                RegionSelection.afterChoosingSubdivision(subdivision, selection)?.let(onSelectionChange)
                showSubdivisionPicker = false
            },
            onDismiss = { showSubdivisionPicker = false },
        )
    }
}

internal fun isLocationGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun countryDisplayName(code: String): String =
    RegionalAreas.countries.firstOrNull { it.id == code }?.localizedName() ?: code

/** A picker row whose trailing button shows the current value, or "Set region" while nothing is chosen. */
@Composable
private fun RegionPickerRow(label: String, value: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.width(16.dp))
        TextButton(onClick = onClick) {
            Text(
                value ?: stringResource(R.string.region_set),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun CountryPickerDialog(selectedCountry: String?, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val countries = remember { RegionalAreas.countriesSortedByLocalizedName() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.region_country)) },
        text = {
            LazyColumn {
                items(countries, key = { it.id }) { country ->
                    TextButton(onClick = { onSelect(country.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(country.localizedName())
                            if (country.id == selectedCountry) Icon(painterResource(R.drawable.ic_check), contentDescription = null)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun SubdivisionPickerDialog(
    country: String,
    selectedSubdivision: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val subdivisions = remember(country) { RegionalAreas.subdivisions(country) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.region_state_province)) },
        text = {
            LazyColumn {
                items(subdivisions, key = { it.id }) { subdivision ->
                    TextButton(onClick = { onSelect(subdivision.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(RegionalAreas.subdivisionDisplayName(subdivision.id) ?: subdivision.id)
                            if (subdivision.id == selectedSubdivision) Icon(painterResource(R.drawable.ic_check), contentDescription = null)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
