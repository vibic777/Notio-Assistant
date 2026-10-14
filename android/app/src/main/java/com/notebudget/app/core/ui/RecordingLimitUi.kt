package com.notebudget.app.core.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.domain.model.LimitUnit
import com.notebudget.app.domain.model.RecordingLimit

/** "4 min", "30 s", "2 h" in the language of the app (D251). */
fun limitText(context: Context, limit: RecordingLimit): String = context.getString(
    when (limit.unit) {
        LimitUnit.SECONDS -> R.string.rec_limit_s
        LimitUnit.MINUTES -> R.string.rec_limit_min
        LimitUnit.HOURS -> R.string.rec_limit_h
    },
    limit.amount,
)

/** Said when a recording starts (D251): the person knows it will stop by itself. Nothing is said without a limit. */
fun warnRecordingLimit(context: Context, limit: RecordingLimit) {
    if (limit.unlimited) return
    Toast.makeText(context, context.getString(R.string.rec_limit_warning, limitText(context, limit)), Toast.LENGTH_LONG).show()
}

/** Said when a recording was stopped by its limit (D251). */
fun tellRecordingLimitReached(context: Context) {
    Toast.makeText(context.applicationContext, context.getString(R.string.rec_limit_reached), Toast.LENGTH_LONG).show()
}

/**
 * A limit for the next recording only (D263), typed under the Record button: it overrides the one of Settings → Recordings
 * for that one recording, which takes it ([take]); the one after goes by Settings again. Kept while the app runs.
 */
object RecordingLimitOnce {
    private val limits = androidx.compose.runtime.mutableStateMapOf<com.notebudget.app.domain.model.RecordingKind, RecordingLimit>()

    fun get(kind: com.notebudget.app.domain.model.RecordingKind): RecordingLimit? = limits[kind]

    fun set(kind: com.notebudget.app.domain.model.RecordingKind, limit: RecordingLimit?) {
        if (limit == null) limits.remove(kind) else limits[kind] = limit
    }

    /** The limit of the recording that starts now: the one typed for it (then forgotten), else [default]. */
    fun take(kind: com.notebudget.app.domain.model.RecordingKind, default: RecordingLimit): RecordingLimit = limits.remove(kind) ?: default
}

/**
 * The line under a Record button: when the recording will stop by itself, or that it will not (D251), and "Change for this
 * recording" (D263) when [kind] is given.
 */
@Composable
fun RecordingLimitLine(limit: RecordingLimit, modifier: Modifier = Modifier, kind: com.notebudget.app.domain.model.RecordingKind? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val once = kind?.let { RecordingLimitOnce.get(it) }
    val shown = once ?: limit
    var editing by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(
            when {
                once != null && shown.unlimited -> stringResource(R.string.rec_limit_once_none)
                once != null -> stringResource(R.string.rec_limit_once_line, limitText(context, shown))
                shown.unlimited -> stringResource(R.string.rec_limit_none)
                else -> stringResource(R.string.rec_limit_line, limitText(context, shown))
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (once != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (kind != null) {
            com.notebudget.app.core.ui.tip.TextButton(onClick = { editing = true }) { Text(stringResource(R.string.rec_limit_change)) }
        }
    }
    if (editing && kind != null) {
        var draft by remember { mutableStateOf(shown) }
        AppAlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.rec_limit_once_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordingLimitEditor(draft) { draft = it }
                    Text(stringResource(R.string.rec_limit_once_hint), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                com.notebudget.app.core.ui.tip.TextButton(onClick = { RecordingLimitOnce.set(kind, draft); editing = false }) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                com.notebudget.app.core.ui.tip.TextButton(onClick = { RecordingLimitOnce.set(kind, null); editing = false }) {
                    Text(stringResource(R.string.rec_limit_back))
                }
            },
        )
    }
}

/** The length (0 - 65535) and the unit of one limit, as the settings show it (D251). */
@Composable
fun RecordingLimitEditor(limit: RecordingLimit, onChange: (RecordingLimit) -> Unit) {
    var typed by remember { mutableStateOf(limit.amount.toString()) }
    // A change from elsewhere (a restored backup) shows up; while typing, the field keeps what is typed.
    LaunchedEffect(limit.amount) { if (RecordingLimit.parseAmount(typed) != limit.amount) typed = limit.amount.toString() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = typed,
            onValueChange = { text ->
                typed = text.filter { it.isDigit() }.take(5)
                RecordingLimit.parseAmount(typed)?.let { onChange(limit.copy(amount = it)) }
            },
            singleLine = true,
            label = { Text(stringResource(R.string.settings_rec_limit_amount)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(160.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                LimitUnit.SECONDS to R.string.rec_unit_seconds,
                LimitUnit.MINUTES to R.string.rec_unit_minutes,
                LimitUnit.HOURS to R.string.rec_unit_hours,
            ).forEach { (unit, label) ->
                FilterChip(limit.unit == unit, { onChange(limit.copy(unit = unit)) }, { Text(stringResource(label)) })
            }
        }
    }
}
