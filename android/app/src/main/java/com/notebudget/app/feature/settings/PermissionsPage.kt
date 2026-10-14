package com.notebudget.app.feature.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.notebudget.app.R
import com.notebudget.app.core.common.AppLog
import com.notebudget.app.core.ui.tip.OutlinedButton
import com.notebudget.app.core.ui.tip.TextButton
import com.notebudget.app.core.ui.tip.stringResource

/**
 * The rights of Notio on this phone in one place (D354; owner, 2026-10-11: "a tab Rights in the menu: the giving of every
 * right and leave the app needs, moved (doubled) there, and there too the taking back of each right and of all of them").
 * The functions still ask for their right when they need it; this page gives each one beforehand and takes it back.
 *
 * Android has two kinds. A right the app may ask for itself ([Kind.ASKED]: notifications, microphone, camera, place) is given
 * by the question of the phone and taken back by `revokeSelfPermissionOnKill` (Android 13+), which acts when Notio is closed.
 * A leave only the phone's settings can change ([Kind.SETTINGS]: exact alarms, the alarm over the lock screen, showing over
 * other apps) is given and taken back on its own page of the settings, opened from here.
 */
enum class AppRight(
    val kind: Kind,
    @StringRes val title: Int,
    @StringRes val why: Int,
    /** The permissions of the manifest behind it (one right of the person may be two of Android). */
    val permissions: List<String> = emptyList(),
    /** The first Android version that knows this right; below it the phone gives it to every app. */
    val since: Int = 0,
) {
    NOTIFICATIONS(Kind.ASKED, R.string.perm_notifications, R.string.perm_notifications_why, listOf(Manifest.permission.POST_NOTIFICATIONS), since = 33),
    EXACT_ALARMS(Kind.SETTINGS, R.string.perm_exact_alarms, R.string.perm_exact_alarms_why, since = 31),
    FULL_SCREEN(Kind.SETTINGS, R.string.perm_full_screen, R.string.perm_full_screen_why, since = 34),
    OVERLAY(Kind.SETTINGS, R.string.perm_overlay, R.string.perm_overlay_why),
    MICROPHONE(Kind.ASKED, R.string.perm_microphone, R.string.perm_microphone_why, listOf(Manifest.permission.RECORD_AUDIO)),
    CAMERA(Kind.ASKED, R.string.perm_camera, R.string.perm_camera_why, listOf(Manifest.permission.CAMERA)),
    LOCATION(Kind.ASKED, R.string.perm_location, R.string.perm_location_why, listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION));

    enum class Kind { ASKED, SETTINGS }

    /** Does this phone know the right at all? An older Android has no such question: the right is there for every app. */
    fun known(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= since

    companion object {
        /** The app can take an asked right back itself from Android 13 on; before that only the phone's settings can. */
        fun canRevokeItself(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= 33
    }
}

private fun AppRight.granted(context: Context): Boolean = when (this) {
    AppRight.EXACT_ALARMS -> Build.VERSION.SDK_INT < 31 || runCatching { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(false)
    AppRight.FULL_SCREEN -> com.notebudget.app.core.notification.ReminderNotifier.canUseFullScreen(context)
    AppRight.OVERLAY -> Settings.canDrawOverlays(context)
    // Either of the two permissions of the place is the right: the phone lets the person give the rough one alone.
    else -> !known() || permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

/** The page of the phone's settings where this leave is given and taken back; the page of the app when the phone has no closer one. */
private fun AppRight.settingsIntent(context: Context): Intent {
    val app = Uri.parse("package:" + context.packageName)
    return when {
        this == AppRight.EXACT_ALARMS && Build.VERSION.SDK_INT >= 31 -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, app)
        this == AppRight.FULL_SCREEN && Build.VERSION.SDK_INT >= 34 -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, app)
        this == AppRight.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, app)
        this == AppRight.NOTIFICATIONS && Build.VERSION.SDK_INT >= 26 ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)
    }
}

private fun open(context: Context, intent: Intent) {
    // A phone without that page (some makers leave one out) shows the page of the app instead: every right is there too.
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) }
    }
}

@Composable
fun PermissionsOptions() {
    val context = LocalContext.current
    // Read again every time the page comes back (D199): the rights are changed in the phone's settings and by its questions.
    val resumed = com.notebudget.app.core.ui.rememberResumeCount()
    var asked by remember { mutableStateOf(0) }
    // Taken back here and not gone yet: Android takes an asked right away when Notio is closed.
    var leaving by remember { mutableStateOf(emptySet<AppRight>()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked++ }
    val rights = remember { AppRight.entries.filter { it.known() } }
    val granted = remember(resumed, asked) { rights.associateWith { it.granted(context) } }

    fun give(right: AppRight) {
        leaving = leaving - right
        if (right.kind == AppRight.Kind.SETTINGS) return open(context, right.settingsIntent(context))
        val activity = context as? android.app.Activity
        // Refused twice, the phone asks no more: the question would close at once, so the settings of the app are opened.
        val refusedForGood = activity != null && asked > 0 && right.permissions.none { androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (refusedForGood && !granted.getValue(right)) open(context, right.settingsIntent(context)) else ask.launch(right.permissions.toTypedArray())
    }

    fun takeBack(right: AppRight) {
        if (right.kind == AppRight.Kind.ASKED && AppRight.canRevokeItself()) {
            runCatching { if (Build.VERSION.SDK_INT >= 33) context.revokeSelfPermissionsOnKill(right.permissions) }
                .onSuccess { leaving = leaving + right; AppLog.i("app", "a right is taken back when Notio is closed: " + right.name) }
                .onFailure { open(context, right.settingsIntent(context)) }
        } else {
            open(context, right.settingsIntent(context))
        }
    }

    Text(stringResource(R.string.perm_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    rights.forEach { right ->
        val on = granted.getValue(right)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(right.title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(right.why), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(if (!on) R.string.perm_state_off else if (right in leaving) R.string.perm_state_leaving else R.string.perm_state_on),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (on && right !in leaving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    if (!on || right in leaving) OutlinedButton(onClick = { give(right) }) { Text(stringResource(R.string.perm_allow)) }
                    if (on && right !in leaving) OutlinedButton(onClick = { takeBack(right) }) { Text(stringResource(R.string.perm_revoke)) }
                }
            }
        }
    }
    Text(
        stringResource(if (AppRight.canRevokeItself()) R.string.perm_revoke_note else R.string.perm_revoke_note_old),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // All of them at once: what the app can take back itself goes when Notio is closed; for the rest, and on an older
    // Android for all, the page of the app in the phone's settings is opened, where each has its switch.
    OutlinedButton(
        onClick = {
            val mine = rights.filter { it.kind == AppRight.Kind.ASKED && granted.getValue(it) }
            if (AppRight.canRevokeItself() && mine.isNotEmpty()) {
                runCatching { if (Build.VERSION.SDK_INT >= 33) context.revokeSelfPermissionsOnKill(mine.flatMap { it.permissions }) }
                    .onSuccess { leaving = leaving + mine; AppLog.i("app", "all rights the app can take back are taken back when Notio is closed") }
            }
            val others = rights.any { it.kind == AppRight.Kind.SETTINGS && granted.getValue(it) }
            if (others || !AppRight.canRevokeItself()) open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
        },
        enabled = granted.values.any { it },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.perm_revoke_all)) }
    TextButton(onClick = { open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) }) {
        Text(stringResource(R.string.perm_open_settings))
    }
}
