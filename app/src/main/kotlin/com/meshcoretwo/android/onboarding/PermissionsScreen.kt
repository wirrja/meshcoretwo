// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.meshcoretwo.android.R

/**
 * Ported from `PermissionsView.swift`/`PermissionsCoordinator.swift`/`PermissionCard.swift`,
 * adapted to Compose's permission model (`ActivityResultContracts`) instead of `@Observable`
 * coordinator state. "Continue" is always enabled — mirrors iOS: permissions here are best-effort,
 * not gating.
 *
 * Card set differs from iOS: iOS has no explicit Bluetooth card (CoreBluetooth/AccessorySetupKit
 * gate it implicitly at pair time); Android has no such implicit gate, so a Bluetooth card is
 * added. Which permission each card requests is API-level dependent — see [PermissionCardSpec].
 */
@Composable
fun PermissionsScreen(onContinue: () -> Unit) {
    val specs = rememberPermissionCardSpecs()
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
            Text(stringResource(R.string.permissions_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.permissions_subtitle),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                specs.forEachIndexed { index, spec ->
                    PermissionRow(spec)
                    if (index < specs.lastIndex) HorizontalDivider()
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_continue))
            }
        }
    }
}

private data class PermissionCardSpec(
    @StringRes val title: Int,
    @StringRes val description: Int,
    val permissions: List<String>,
)

@Composable
private fun rememberPermissionCardSpecs(): List<PermissionCardSpec> = remember {
    buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(
                PermissionCardSpec(
                    title = R.string.permissions_bluetooth_title,
                    description = R.string.permissions_bluetooth_desc,
                    permissions = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                ),
            )
        } else {
            add(
                PermissionCardSpec(
                    title = R.string.permissions_location_title,
                    description = R.string.permissions_location_desc,
                    permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
                ),
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(
                PermissionCardSpec(
                    title = R.string.permissions_notifications_title,
                    description = R.string.permissions_notifications_desc,
                    permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
                ),
            )
        }
    }
}

@Composable
private fun PermissionRow(spec: PermissionCardSpec) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(spec.permissions.all { isGranted(context, it) })
    }
    var permanentlyDenied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
        if (!granted) {
            val activity = context.findActivity()
            permanentlyDenied = activity != null &&
                spec.permissions.any { !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(spec.title), style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(spec.description), style = MaterialTheme.typography.bodyLarge)
        }
        when {
            granted -> Text(stringResource(R.string.permissions_granted), style = MaterialTheme.typography.labelLarge)
            permanentlyDenied -> TextButton(onClick = { context.openAppSettings() }) { Text(stringResource(R.string.permissions_open_settings)) }
            else -> TextButton(onClick = { launcher.launch(spec.permissions.toTypedArray()) }) { Text(stringResource(R.string.permissions_grant)) }
        }
    }
}

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    startActivity(intent)
}
