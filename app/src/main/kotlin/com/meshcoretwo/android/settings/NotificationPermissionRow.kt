// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.meshcoretwo.android.R
import com.meshcoretwo.android.ui.components.SettingsListRow

/**
 * Shown at the top of Settings > Notifications while the system blocks this app's notifications
 * (Android 13+ `POST_NOTIFICATIONS` not granted, or notifications switched off in system settings).
 * Onboarding asks for the permission once; without this row a denial there left every toggle below
 * looking active while nothing was ever delivered. Renders nothing while notifications are allowed.
 *
 * "Allow" asks for the permission; if the system refuses to show its dialog again (denied twice),
 * it opens the app's notification settings instead. State re-checks on resume, so returning from
 * system settings updates the row.
 */
@Composable
fun NotificationPermissionRow() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!granted) {
            // Denied with no rationale to show = the system won't prompt again; send the user to settings.
            val activity = context.findActivity()
            if (activity == null || !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)) {
                context.openNotificationSettings()
            }
        }
    }

    if (enabled) return
    SettingsListRow(
        title = stringResource(R.string.notif_blocked_title),
        value = stringResource(R.string.notif_blocked_desc),
        singleLineValue = false,
        trailing = {
            TextButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.openNotificationSettings()
                    }
                },
            ) { Text(stringResource(R.string.common_allow)) }
        },
    )
    HorizontalDivider()
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun Context.openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
