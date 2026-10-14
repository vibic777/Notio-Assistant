package com.notio.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notio.shared.domain.LimitUnit
import com.notio.shared.domain.RecordingLimit

/** "4 min", "30 s", "2 h" in the language of the app (the phone's `limitText`, D251). */
fun limitText(res: AndroidRes, limit: RecordingLimit): String = res.str(
    when (limit.unit) {
        LimitUnit.SECONDS -> "rec_limit_s"
        LimitUnit.MINUTES -> "rec_limit_min"
        LimitUnit.HOURS -> "rec_limit_h"
    },
    limit.amount,
)

/** Said when a recording starts that has a limit (the phone's toast). */
fun warnRecordingLimit(controller: NotioController, limit: RecordingLimit) {
    if (limit.unlimited) return
    controller.say(controller.res.str("rec_limit_warning", limitText(controller.res, limit)))
}

/** Said when a recording was stopped and saved by its limit. */
fun tellRecordingLimitReached(controller: NotioController) = controller.say(controller.res.str("rec_limit_reached"))

/**
 * A limit for the next recording only (Android D263), typed under the Record button: it overrides the one of Settings → Recordings
 * for that one recording, which takes it ([take]); the one after goes by Settings again. Kept while the app runs.
 */
object RecordingLimitOnce {
    private val limits = androidx.compose.runtime.mutableStateMapOf<com.notio.shared.domain.RecordingKind, RecordingLimit>()

    fun get(kind: com.notio.shared.domain.RecordingKind): RecordingLimit? = limits[kind]

    fun set(kind: com.notio.shared.domain.RecordingKind, limit: RecordingLimit?) {
        if (limit == null) limits.remove(kind) else limits[kind] = limit
    }

    /** The limit of the recording that starts now: the one typed for it (then forgotten), else [default]. */
    fun take(kind: com.notio.shared.domain.RecordingKind, default: RecordingLimit): RecordingLimit = limits.remove(kind) ?: default
}

/**
 * Under the Record button: "Stops by itself after 4 min (Settings → Recordings)" or "No time limit" (D251), and "Change for this
 * recording" (D263) when [kind] is given.
 */
@Composable
fun RecordingLimitLine(limit: RecordingLimit, modifier: Modifier = Modifier, kind: com.notio.shared.domain.RecordingKind? = null) {
    val res = LocalRes.current
    val once = kind?.let { RecordingLimitOnce.get(it) }
    val shown = once ?: limit
    var editing by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(
            when {
                once != null && shown.unlimited -> stringResource("rec_limit_once_none")
                once != null -> stringResource("rec_limit_once_line", limitText(res, shown))
                shown.unlimited -> stringResource("rec_limit_none")
                else -> stringResource("rec_limit_line", limitText(res, shown))
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (once != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (kind != null) TextButton(onClick = { editing = true }) { Text(stringResource("rec_limit_change")) }
    }
    if (editing && kind != null) {
        var draft by remember { mutableStateOf(shown) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource("rec_limit_once_title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordingLimitEditor(draft) { draft = it }
                    Text(stringResource("rec_limit_once_hint"), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { RecordingLimitOnce.set(kind, draft); editing = false }) { Text(stringResource("action_save")) }
            },
            dismissButton = {
                TextButton(onClick = { RecordingLimitOnce.set(kind, null); editing = false }) { Text(stringResource("rec_limit_back")) }
            },
        )
    }
}

/** The editor of one limit in Settings → Recordings: a number 0 - 65535 (0 = until stopped) and seconds / minutes / hours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordingLimitEditor(limit: RecordingLimit, onChange: (RecordingLimit) -> Unit) {
    var typed by remember { mutableStateOf(limit.amount.toString()) }
    // A change from elsewhere shows up; while typing, the field keeps what is typed.
    androidx.compose.runtime.LaunchedEffect(limit.amount) { if (RecordingLimit.parseAmount(typed) != limit.amount) typed = limit.amount.toString() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = typed,
            onValueChange = { t ->
                typed = t.filter { it.isDigit() }.take(5)
                RecordingLimit.parseAmount(typed)?.let { onChange(limit.copy(amount = it)) }
            },
            singleLine = true,
            label = { Text(stringResource("settings_rec_limit_amount")) },
            modifier = Modifier.width(200.dp),
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                LimitUnit.SECONDS to "rec_unit_seconds",
                LimitUnit.MINUTES to "rec_unit_minutes",
                LimitUnit.HOURS to "rec_unit_hours",
            ).forEach { (unit, label) ->
                FilterChip(limit.unit == unit, { onChange(limit.copy(unit = unit)) }, { Text(stringResource(label)) })
            }
        }
    }
}
