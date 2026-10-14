package com.notio.desktop

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notio.shared.domain.Note
import com.notio.shared.domain.Reminder
import com.notio.shared.domain.ReminderDraft
import com.notio.shared.domain.ReminderStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Date

/**
 * The reminders of all notes, or of one note (the phone's RemindersScreen, D122): upcoming ones first, then the finished ones;
 * Add, change, cancel, delete, confirm. The Android permission and exact-alarm cards have no desktop counterpart.
 */
@Composable
fun RemindersScreen(noteId: String?, controller: NotioController) {
    val state by controller.state.collectAsState()
    val allRows = state.reminderRows
    val notes = state.reminderNotes
    val rows = if (noteId == null) allRows else allRows.filter { it.reminder.noteId == noteId }
    val sounding by controller.reminders.sounding.collectAsState()
    // The reminders that call for attention now (Android D296): their alarm window is open, with or without a melody.
    val ringingNow by controller.reminders.ringing.collectAsState()
    val ringingTitle = sounding?.let { id ->
        controller.reminders.ringing.value.firstOrNull { it.reminder.id == id }?.noteTitle ?: allRows.firstOrNull { it.reminder.id == id }?.noteTitle
    }
    var editing by remember { mutableStateOf<ReminderRow?>(null) }
    var adding by remember { mutableStateOf(false) }
    // The reminders are off (D109, Android D328): making one first says so and offers to turn them on.
    var askTurnOn by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ReminderRow?>(null) }
    var cancelling by remember { mutableStateOf<ReminderRow?>(null) }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = ((maxWidth - 1000.dp) / 2).coerceAtLeast(0.dp)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp + side, end = 16.dp + side, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // All reminders on or off at once (Android D318): first on the page, so an off state is never missed.
                item(key = "all") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource("reminders_all_on"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                val allLabel = stringResource(if (controller.remindersOn) "reminders_turn_all_off" else "reminders_turn_all_on")
                                Switch(checked = controller.remindersOn, onCheckedChange = { controller.chooseRemindersOn(it) }, modifier = Modifier.semantics { contentDescription = allLabel })
                            }
                            if (!controller.remindersOn) Text(stringResource("reminders_all_off_hint"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                        }
                    }
                }
                // While a melody plays (Android D293): which reminder, and Stop.
                if (ringingTitle != null) {
                    item(key = "ringing") { RingingBanner(ringingTitle, controller.reminders::silence) }
                }
                if (rows.isEmpty()) {
                    item(key = "empty") {
                        Text(stringResource("reminders_empty"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
                    }
                }
                items(rows, key = { it.reminder.id }) { row ->
                    ReminderCard(
                        row,
                        ringing = row.reminder.id == sounding,
                        active = row.reminder.id == sounding || ringingNow.any { it.reminder.id == row.reminder.id },
                        onStopSound = controller.reminders::silence,
                        onReset = { controller.reminders.reset(row.reminder.id) },
                        onEdit = { editing = row },
                        onCancel = { cancelling = row },
                        onConfirm = { controller.confirmReminder(row.reminder.id) },
                        onDelete = { deleting = row },
                        // Opened from that very note: back to it (Android D312).
                        onOpenNote = { if (row.reminder.noteId == noteId) controller.backByPerson() else controller.openNote(row.reminder.noteId, from = Screen.Reminders(noteId)) },
                        onEnabled = { controller.setReminderEnabled(row.reminder.id, it) },
                    )
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { if (controller.remindersOn) adding = true else askTurnOn = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource("reminders_add"), Modifier.padding(start = 8.dp))
        }
    }

    if (askTurnOn) {
        AlertDialog(
            onDismissRequest = { askTurnOn = false },
            title = { Text(stringResource("reminders_off_create_title")) },
            text = { Text(stringResource("reminders_off_create_hint")) },
            confirmButton = {
                TextButton(onClick = { askTurnOn = false; controller.chooseRemindersOn(true); adding = true }) { Text(stringResource("reminders_turn_all_on")) }
            },
            dismissButton = { TextButton(onClick = { askTurnOn = false }) { Text(stringResource("action_cancel")) } },
        )
    }
    if (adding) {
        ReminderDialog(
            initial = null, notes = notes, presetNoteId = noteId,
            onSave = { chosen, draft -> controller.createReminder(chosen, draft) { adding = false } },
            onDismiss = { adding = false },
        )
    }
    editing?.let { row ->
        ReminderDialog(
            initial = row.reminder, notes = notes, presetNoteId = row.reminder.noteId,
            onSave = { _, draft -> controller.updateReminder(row.reminder.id, draft) { editing = null } },
            onDismiss = { editing = null },
        )
    }
    // Cancelling is asked first too (Android D269): it cannot be taken back, only a new time set with Edit.
    cancelling?.let { row ->
        AlertDialog(
            onDismissRequest = { cancelling = null },
            title = { Text(stringResource("reminders_cancel_title")) },
            text = { Text(stringResource("reminders_cancel_message", row.noteTitle)) },
            confirmButton = {
                TextButton(onClick = { controller.cancelReminder(row.reminder.id); cancelling = null }) {
                    Text(stringResource("reminders_cancel"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { cancelling = null }) { Text(stringResource("reminders_cancel_keep")) } },
        )
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource("reminders_delete_title")) },
            text = { Text(stringResource("reminders_delete_message", row.noteTitle)) },
            confirmButton = {
                TextButton(onClick = { controller.deleteReminder(row.reminder.id); deleting = null }) {
                    Text(stringResource("action_delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource("action_cancel")) } },
        )
    }
}

@Composable
private fun ReminderCard(
    row: ReminderRow,
    ringing: Boolean,
    /** It calls for attention now: its alarm window is open, with or without a melody (Android D296). */
    active: Boolean,
    onStopSound: () -> Unit,
    /** Ends this ring for good: the melody and the alarm window. The reminder itself stays as it is. */
    onReset: () -> Unit,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onDelete: () -> Unit,
    onOpenNote: () -> Unit,
    onEnabled: (Boolean) -> Unit,
) {
    val r = row.reminder
    val context = LocalRes.current
    val whenText = remember(r.triggerAt) { Format.dateTime(r.triggerAt) }
    Card(Modifier.fillMaxWidth()) {
        // A switched-off reminder is shown paler (Android D268).
        Column(
            Modifier.padding(12.dp).alpha(if (row.isPending && !r.isEnabled) 0.6f else 1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) RingingLight(Modifier.padding(end = 10.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.noteTitle, style = MaterialTheme.typography.titleMedium)
                    Text(whenText, style = MaterialTheme.typography.bodyLarge)
                }
                // On / off, like an alarm clock (Android D268): only for a reminder still to ring.
                if (row.isPending) {
                    val switchLabel = stringResource(if (r.isEnabled) "reminder_switch_off" else "reminder_switch_on")
                    androidx.compose.material3.Switch(
                        checked = r.isEnabled,
                        onCheckedChange = onEnabled,
                        modifier = Modifier.semantics { contentDescription = switchLabel },
                    )
                }
            }
            if (active) Text(stringResource("reminder_status_active"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            Text(
                stringResource(
                    when {
                        row.isPending && !r.isEnabled -> "reminder_status_off"
                        r.status == ReminderStatus.CANCELLED -> "reminder_status_cancelled"
                        r.status == ReminderStatus.COMPLETED -> "reminder_status_done"
                        r.status == ReminderStatus.AWAITING_CONFIRMATION -> "reminder_status_waiting"
                        r.status == ReminderStatus.FIRED -> "reminder_status_fired"
                        else -> "reminder_status_scheduled"
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (r.snoozedUntil > 0) {
                Text(stringResource("reminder_snoozed_until", Format.dateTime(r.snoozedUntil)), style = MaterialTheme.typography.labelLarge)
            }
            val flags = listOfNotNull(
                stringResource("reminder_sound").takeIf { r.soundEnabled },
                stringResource("reminder_vibration").takeIf { r.vibrationEnabled },
                stringResource("reminder_popup").takeIf { r.popupEnabled },
                stringResource("reminder_confirm_short").takeIf { r.requiresConfirmation },
                r.repeatIntervalMinutes?.takeIf { r.repeatUntilConfirmed }?.let { stringResource("reminder_repeat_short", it) },
                r.repeatIntervalMinutes?.takeIf { r.repeatCount > 0 }?.let { stringResource("reminder_extra_short", r.repeatCount, it) },
                when (r.recurrence) {
                    com.notio.shared.domain.Recurrence.WEEKLY ->
                        if (r.weekdaysMask == com.notio.shared.domain.RecurrenceRules.ALL_DAYS) stringResource("reminder_recurs_daily")
                        else stringResource(
                            "reminder_recurs_weekly",
                            java.time.DayOfWeek.values().filter { com.notio.shared.domain.RecurrenceRules.includes(r.weekdaysMask, it) }
                                .joinToString(", ") { it.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) },
                        )
                    com.notio.shared.domain.Recurrence.MONTHLY -> stringResource("reminder_recurs_monthly", r.monthDay)
                    else -> null
                },
            )
            if (flags.isNotEmpty()) Text(flags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Android D278: the actions of a card as icons (their names on hover); Cancel and Delete still ask first.
                if (ringing) IconAction(AppIcons.VolumeOff, stringResource("reminder_stop_sound"), onStopSound, tint = MaterialTheme.colorScheme.error)
                // Reset is a word, not one more icon: the one thing to find at once while a reminder calls (Android D296).
                if (active) {
                    FilledTonalButton(
                        onClick = onReset,
                        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) { Text(stringResource("reminder_reset")) }
                }
                if (r.status == ReminderStatus.AWAITING_CONFIRMATION) IconAction(AppIcons.TaskAlt, stringResource("reminder_action_complete"), onConfirm, tint = MaterialTheme.colorScheme.primary)
                IconAction(AppIcons.Edit, stringResource("action_edit"), onEdit)
                if (row.isPending) IconAction(AppIcons.NotificationsOff, stringResource("reminders_cancel"), onCancel)
                IconAction(AppIcons.Delete, stringResource("action_delete"), onDelete, tint = MaterialTheme.colorScheme.error)
                // After the actions of the reminder: to the note it reminds of (Android D312).
                IconAction(AppIcons.OpenInNew, stringResource("alarm_open_note"), onOpenNote)
            }
        }
    }
}

/** Add or change a reminder: the note, the day and time, and how it should call for attention. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReminderDialog(initial: Reminder?, notes: List<Note>, presetNoteId: String?, onSave: (String, ReminderDraft) -> Unit, onDismiss: () -> Unit) {
    val context = LocalRes.current
    val start = remember { initial?.let { ReminderTime.toDateTime(it.triggerAt) } ?: ReminderTime.suggested(System.currentTimeMillis()) }
    var noteId by remember { mutableStateOf(presetNoteId ?: notes.firstOrNull()?.id) }
    var date by remember { mutableStateOf(start.toLocalDate()) }
    var time by remember { mutableStateOf(start.toLocalTime().withSecond(0).withNano(0)) }
    var sound by remember { mutableStateOf(initial?.soundEnabled ?: true) }
    var vibration by remember { mutableStateOf(initial?.vibrationEnabled ?: true) }
    var popup by remember { mutableStateOf(initial?.popupEnabled ?: true) }
    var confirmation by remember { mutableStateOf(initial?.requiresConfirmation ?: false) }
    var repeat by remember { mutableStateOf(initial?.repeatUntilConfirmed ?: false) }
    var interval by remember { mutableStateOf(initial?.repeatIntervalMinutes ?: 5) }
    // D130: volume and fade-in, extra rings like an alarm clock, and days it comes back.
    var volume by remember { mutableStateOf((initial?.volumePercent ?: 100).toFloat()) }
    var ramp by remember { mutableStateOf(initial?.rampSeconds ?: 0) }
    var extraRings by remember { mutableStateOf((initial?.repeatCount ?: 0).toString()) }
    var recurrence by remember { mutableStateOf(initial?.recurrence ?: com.notio.shared.domain.Recurrence.NONE) }
    var weekdays by remember { mutableStateOf(initial?.weekdaysMask?.takeIf { it != 0 } ?: com.notio.shared.domain.RecurrenceRules.ALL_DAYS) }
    var everyDay by remember { mutableStateOf(initial == null || initial.recurrence != com.notio.shared.domain.Recurrence.WEEKLY || initial.weekdaysMask == com.notio.shared.domain.RecurrenceRules.ALL_DAYS) }
    var monthDay by remember { mutableStateOf((initial?.monthDay?.takeIf { it != 0 } ?: start.dayOfMonth).toString()) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var noteMenu by remember { mutableStateOf(false) }
    // The melody (D129): null = the phone's default notification sound. Kept as a text address in the reminder.
    var soundUri by remember { mutableStateOf(initial?.soundUri) }


    com.notio.desktop.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) "reminders_add" else "reminders_edit")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource("reminder_note"), style = MaterialTheme.typography.labelLarge)
                Column {
                    OutlinedButton(onClick = { noteMenu = true }, enabled = initial == null) {
                        Text(notes.firstOrNull { it.id == noteId }?.title ?: stringResource("reminder_pick_note"), maxLines = 1)
                    }
                    DropdownMenu(expanded = noteMenu, onDismissRequest = { noteMenu = false }) {
                        notes.forEach { note -> DropdownMenuItem(text = { Text(note.title) }, onClick = { noteId = note.id; noteMenu = false }) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate = true }) { Text(date.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(java.util.Locale.forLanguageTag(context.language)))) }
                    OutlinedButton(onClick = { pickTime = true }) { Text(time.toString()) }
                }
                SwitchRow("reminder_sound_long", sound) { sound = it }
                if (sound) {
                    Text(
                        stringResource("reminder_melody", soundUri?.let { java.io.File(it).name } ?: stringResource("reminder_sound_default")),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { ReminderSoundPreview.play(soundUri) }) { Text(stringResource("reminder_sound_play")) }
                        OutlinedButton(onClick = { ReminderSoundPreview.stop() }) { Text(stringResource("audio_stop")) }
                        OutlinedButton(onClick = { pickWav()?.let { soundUri = it } }) { Text(stringResource("reminder_sound_file")) }
                        if (soundUri != null) OutlinedButton(onClick = { soundUri = null }) { Text(stringResource("reminder_sound_reset")) }
                    }
                }
                if (sound) {
                    Text(stringResource("rem_volume", volume.toInt()), style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Slider(value = volume, onValueChange = { volume = it }, valueRange = 10f..100f, steps = 8)
                    Text(stringResource("rem_volume_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource("rem_ramp_title"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0, 10, 30, 60, 120).forEach { secs ->
                            FilterChip(ramp == secs, { ramp = secs }, { Text(if (secs == 0) stringResource("rem_ramp_off") else stringResource("rem_ramp_secs", secs)) })
                        }
                    }
                }
                SwitchRow("reminder_vibration_long", vibration) { vibration = it }
                SwitchRow("reminder_popup_long", popup) { popup = it }
                Text(stringResource("rem_extra_title"), style = MaterialTheme.typography.labelLarge)
                Text(stringResource("rem_extra_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = extraRings, onValueChange = { extraRings = it.filter(Char::isDigit).take(2) }, singleLine = true,
                        label = { Text(stringResource("rem_extra_count")) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = interval.toString(), onValueChange = { interval = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0 }, singleLine = true,
                        label = { Text(stringResource("rem_extra_every")) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                SwitchRow("reminder_confirm_long", confirmation) { confirmation = it; if (!it) repeat = false }
                if (confirmation) {
                    SwitchRow("reminder_repeat_long", repeat) { repeat = it }
                    if (repeat) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            REPEAT_CHOICES.forEach { minutes ->
                                FilterChip(interval == minutes, { interval = minutes }, { Text(stringResource("reminder_every_minutes", minutes)) })
                            }
                        }
                    }
                }
                // D130: the days it comes back.
                Text(stringResource("rem_repeat_title"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(recurrence == com.notio.shared.domain.Recurrence.NONE, { recurrence = com.notio.shared.domain.Recurrence.NONE }, { Text(stringResource("rem_rec_none")) })
                    FilterChip(recurrence == com.notio.shared.domain.Recurrence.WEEKLY && everyDay, { recurrence = com.notio.shared.domain.Recurrence.WEEKLY; everyDay = true; weekdays = com.notio.shared.domain.RecurrenceRules.ALL_DAYS }, { Text(stringResource("rem_rec_daily")) })
                    FilterChip(recurrence == com.notio.shared.domain.Recurrence.WEEKLY && !everyDay, { recurrence = com.notio.shared.domain.Recurrence.WEEKLY; everyDay = false }, { Text(stringResource("rem_rec_weekdays")) })
                    FilterChip(recurrence == com.notio.shared.domain.Recurrence.MONTHLY, { recurrence = com.notio.shared.domain.Recurrence.MONTHLY }, { Text(stringResource("rem_rec_monthly")) })
                }
                if (recurrence == com.notio.shared.domain.Recurrence.WEEKLY && !everyDay) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        java.time.DayOfWeek.values().forEach { day ->
                            val on = com.notio.shared.domain.RecurrenceRules.includes(weekdays, day)
                            FilterChip(on, { weekdays = weekdays xor com.notio.shared.domain.RecurrenceRules.bit(day) }, {
                                Text(day.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.forLanguageTag(LocalRes.current.language)))
                            })
                        }
                    }
                }
                if (recurrence == com.notio.shared.domain.Recurrence.MONTHLY) {
                    androidx.compose.material3.OutlinedTextField(
                        value = monthDay, onValueChange = { monthDay = it.filter(Char::isDigit).take(2) }, singleLine = true,
                        label = { Text(stringResource("rem_month_day")) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    )
                    Text(stringResource("rem_month_skip_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = noteId != null,
                onClick = {
                    onSave(
                        noteId ?: return@TextButton,
                        ReminderDraft(
                            triggerAt = ReminderTime.toMillis(date, time),
                            soundEnabled = sound, soundUri = soundUri.takeIf { sound }, vibrationEnabled = vibration, popupEnabled = popup,
                            requiresConfirmation = confirmation, repeatUntilConfirmed = confirmation && repeat,
                            repeatIntervalMinutes = if ((confirmation && repeat) || (extraRings.toIntOrNull() ?: 0) > 0) interval else null,
                            volumePercent = volume.toInt().coerceIn(10, 100), rampSeconds = if (sound) ramp else 0,
                            repeatCount = extraRings.toIntOrNull() ?: 0,
                            recurrence = recurrence,
                            weekdaysMask = if (recurrence == com.notio.shared.domain.Recurrence.WEEKLY) (if (everyDay) com.notio.shared.domain.RecurrenceRules.ALL_DAYS else weekdays) else 0,
                            monthDay = if (recurrence == com.notio.shared.domain.Recurrence.MONTHLY) monthDay.toIntOrNull() ?: 0 else 0,
                        ),
                    )
                },
            ) { Text(stringResource("action_save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text(stringResource("action_save")) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource("action_cancel")) } },
        ) { DatePicker(state = state) }
    }
    if (pickTime) {
        val state = rememberTimePickerState(time.hour, time.minute, true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { TimePicker(state = state) },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(state.hour, state.minute); pickTime = false }) { Text(stringResource("action_save")) } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource("action_cancel")) } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** How often "repeat until confirmed" repeats, in minutes (the phone's RemindersViewModel.REPEAT_CHOICES). */
val REPEAT_CHOICES = listOf(1, 5, 10, 15, 30, 60)

/** A reminder with the title of its note. */
data class ReminderRow(val reminder: Reminder, val noteTitle: String) {
    /** Still to fire (or waiting for a confirmation), switched off or not (Android D268): these have the on / off switch. */
    val isPending: Boolean
        get() = reminder.status == ReminderStatus.SCHEDULED || reminder.status == ReminderStatus.AWAITING_CONFIRMATION

    /** Still to fire (or waiting for a confirmation) and switched on. */
    val isActive: Boolean
        get() = reminder.isEnabled && isPending
}

/** Time helpers of the reminder dialog: a date and a time of the person's own time zone become UTC milliseconds. */
object ReminderTime {
    fun toMillis(date: java.time.LocalDate, time: java.time.LocalTime, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long =
        java.time.LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()

    fun toDateTime(millis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): java.time.LocalDateTime =
        java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

    /** What a new reminder starts at: the next full hour that is at least 30 minutes away. */
    fun suggested(nowMillis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): java.time.LocalDateTime {
        val later = toDateTime(nowMillis + 30 * 60_000L, zone)
        return later.withMinute(0).withSecond(0).withNano(0).plusHours(1)
    }
}

/** Asks for a WAV file for a reminder's melody. */
private fun pickWav(): String? {
    val fd = java.awt.FileDialog(null as java.awt.Frame?, "WAV", java.awt.FileDialog.LOAD)
    fd.file = "*.wav"
    fd.isVisible = true
    return fd.file?.let { java.io.File(fd.directory, it).absolutePath }
}

/** A red light that pulses while the reminder rings (Android D293); only drawn then (at most a minute), so it costs nothing otherwise. */
@Composable
private fun RingingLight(modifier: Modifier = Modifier) {
    // Battery saver (Android D308): the light is there, it does not pulse.
    if (LocalController.current?.powerSaving == true) {
        androidx.compose.foundation.layout.Box(modifier.size(14.dp).background(androidx.compose.ui.graphics.Color(0xFFE53935), androidx.compose.foundation.shape.CircleShape))
        return
    }
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "ringing")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween<Float>(600),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "ringing-alpha",
    )
    Box(
        modifier
            .size(14.dp)
            .alpha(alpha)
            .background(androidx.compose.ui.graphics.Color(0xFFE53935), androidx.compose.foundation.shape.CircleShape),
    )
}

/** At the top of the list while a reminder rings (Android D293): the light, which one, and Stop (the reminder itself stays as it is). */
@Composable
private fun RingingBanner(title: String, onStop: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RingingLight()
            Text(
                stringResource("reminder_ringing_now", title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            IconAction(AppIcons.VolumeOff, stringResource("reminder_stop_sound"), onStop, tint = MaterialTheme.colorScheme.error)
        }
    }
}
