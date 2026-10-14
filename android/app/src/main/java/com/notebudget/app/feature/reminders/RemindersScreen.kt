package com.notebudget.app.feature.reminders

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
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
import com.notebudget.app.core.ui.tip.DropdownMenu
import com.notebudget.app.core.ui.tip.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import com.notebudget.app.core.ui.tip.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.material3.MaterialTheme
import com.notebudget.app.core.ui.tip.OutlinedButton
import androidx.compose.material3.Scaffold
import com.notebudget.app.core.ui.NoticeHost
import com.notebudget.app.core.ui.showNotice
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notebudget.app.R
import com.notebudget.app.core.ui.appViewModel
import com.notebudget.app.core.ui.messageRes
import com.notebudget.app.core.ui.rememberAppContainer
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.Reminder
import com.notebudget.app.domain.model.ReminderDraft
import com.notebudget.app.domain.model.ReminderStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Date

/** The reminders of all notes, or of one note (D122): see them, add, change, cancel, delete, confirm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(
    noteId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The note of a reminder, from the last icon of its card (D312). */
    onOpenNote: (String) -> Unit = {},
    viewModel: RemindersViewModel = appViewModel { RemindersViewModel(it.reminderRepository, it.noteRepository, it.reminderUseCases) },
) {
    val allRows by viewModel.rows.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val rows = if (noteId == null) allRows else allRows.filter { it.reminder.noteId == noteId }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val container = rememberAppContainer()
    var editing by remember { mutableStateOf<ReminderRow?>(null) }
    var adding by remember { mutableStateOf(false) }
    // The reminders are off (D328): making one first says so and offers to turn them on.
    var askTurnOn by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ReminderRow?>(null) }
    var cancelling by remember { mutableStateOf<ReminderRow?>(null) }
    val ringingId by com.notebudget.app.core.notification.ReminderRingService.ringing.collectAsStateWithLifecycle()
    // All reminders on or off (D318).
    val allScope = androidx.compose.runtime.rememberCoroutineScope()
    remember { com.notebudget.app.core.alarm.RemindersSwitch.isOn(context) }
    val allOn by com.notebudget.app.core.alarm.RemindersSwitch.enabled.collectAsStateWithLifecycle()
    val notifierChanges by com.notebudget.app.core.notification.ReminderNotifier.changes.collectAsStateWithLifecycle()

    // Android 13+ asks before a notification can be shown: ask when the screen opens, once.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            // In a job of its own: a newer message takes the place of the one shown, nothing waits for it (D360).
            launch {
                if (event is ReminderEvent.Failed) snackbar.showNotice(context.getString(event.error.messageRes()))
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reminders_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { if (allOn) adding = true else askTurnOn = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.reminders_add), Modifier.padding(start = 8.dp))
            }
        },
        snackbarHost = { NoticeHost(snackbar) },
    ) { padding ->
        // Read again when the person comes back from the system settings (D199).
        val resumed = com.notebudget.app.core.ui.rememberResumeCount()
        val exactAllowed = androidx.compose.runtime.remember(resumed) { container.reminderScheduler.canScheduleExact() }
        // The reminders that call for attention now (D296): their notification is in the shade, with or without a melody.
        val shownNow = androidx.compose.runtime.remember(resumed, notifierChanges, ringingId, allRows) {
            container.reminderNotifier.shownNow(allRows.map { it.reminder.id })
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // All reminders on or off at once (D318): first on the page, so an off state is never missed.
            item(key = "all") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.reminders_all_on), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            val allLabel = stringResource(if (allOn) R.string.reminders_turn_all_off else R.string.reminders_turn_all_on)
                            Switch(checked = allOn, onCheckedChange = { on -> allScope.launch { com.notebudget.app.core.alarm.RemindersSwitch.change(context, container, on) } }, modifier = Modifier.semantics { contentDescription = allLabel })
                        }
                        if (!allOn) Text(stringResource(R.string.reminders_all_off_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
            // Exact alarms are a permission the person grants in the system settings; without it a reminder can be late.
            if (!exactAllowed) {
                item(key = "exact") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.reminders_exact_hint), style = MaterialTheme.typography.bodyMedium)
                            if (Build.VERSION.SDK_INT >= 31) {
                                OutlinedButton(onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }) { Text(stringResource(R.string.reminders_exact_open)) }
                            }
                        }
                    }
                }
            }
            // The reminder that rings now (D293): a red light and Stop, also when the alarm screen was covered (the phone's own
            // alarm clock, a call) or the notification is out of sight.
            ringingId?.let { id ->
                item(key = "ringing") {
                    val title = allRows.firstOrNull { it.reminder.id == id }?.noteTitle ?: stringResource(R.string.reminder_generic_title)
                    RingingBanner(title, onStop = { com.notebudget.app.core.notification.ReminderRingService.stop(context) })
                }
            }
            if (rows.isEmpty()) {
                item(key = "empty") {
                    Text(stringResource(R.string.reminders_empty), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
                }
            }
            items(rows, key = { it.reminder.id }) { row ->
                ReminderCard(
                    row,
                    ringing = row.reminder.id == ringingId,
                    active = row.reminder.id == ringingId || row.reminder.id in shownNow,
                    onStopSound = { com.notebudget.app.core.notification.ReminderRingService.stop(context) },
                    onReset = { container.reminderNotifier.dismiss(row.reminder.id) },
                    onEdit = { editing = row },
                    onCancel = { cancelling = row },
                    onConfirm = { viewModel.confirm(row.reminder.id) },
                    onDelete = { deleting = row },
                    onOpenNote = { onOpenNote(row.reminder.noteId) },
                    onEnabled = { viewModel.setEnabled(row.reminder.id, it) },
                )
            }
        }
    }

    if (askTurnOn) {
        AlertDialog(
            onDismissRequest = { askTurnOn = false },
            title = { Text(stringResource(R.string.reminders_off_create_title)) },
            text = { Text(stringResource(R.string.reminders_off_create_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    askTurnOn = false
                    allScope.launch {
                        com.notebudget.app.core.alarm.RemindersSwitch.change(context, container, true)
                        adding = true
                    }
                }) { Text(stringResource(R.string.reminders_turn_all_on)) }
            },
            dismissButton = { TextButton(onClick = { askTurnOn = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (adding) {
        ReminderDialog(
            initial = null,
            notes = notes,
            presetNoteId = noteId,
            onSave = { chosen, draft -> viewModel.create(chosen, draft) { adding = false } },
            onDismiss = { adding = false },
        )
    }
    editing?.let { row ->
        ReminderDialog(
            initial = row.reminder,
            notes = notes,
            presetNoteId = row.reminder.noteId,
            onSave = { _, draft -> viewModel.update(row.reminder.id, draft) { editing = null } },
            onDismiss = { editing = null },
        )
    }
    // Cancelling is asked first too (D269): it cannot be taken back, only a new time set with Edit.
    cancelling?.let { row ->
        AlertDialog(
            onDismissRequest = { cancelling = null },
            title = { Text(stringResource(R.string.reminders_cancel_title)) },
            text = { Text(stringResource(R.string.reminders_cancel_message, row.noteTitle)) },
            confirmButton = {
                TextButton(onClick = { viewModel.cancel(row.reminder.id); cancelling = null }) {
                    Text(stringResource(R.string.reminders_cancel), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { cancelling = null }) { Text(stringResource(R.string.reminders_cancel_keep)) } },
        )
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.reminders_delete_title)) },
            text = { Text(stringResource(R.string.reminders_delete_message, row.noteTitle)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(row.reminder.id); deleting = null }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** A red light that pulses while the reminder rings; only drawn then, so it costs nothing otherwise. */
@Composable
private fun RingingLight(modifier: Modifier = Modifier) {
    // Battery saver (D308): the light is there, it does not pulse.
    val saving by com.notebudget.app.core.common.PowerSaving.enabled.collectAsStateWithLifecycle()
    if (saving) {
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
    androidx.compose.foundation.layout.Box(
        modifier
            .size(14.dp)
            .alpha(alpha)
            .background(androidx.compose.ui.graphics.Color(0xFFE53935), androidx.compose.foundation.shape.CircleShape),
    )
}

/** At the top of the list while a reminder rings: the light, which one, and Stop (the reminder itself stays as it is). */
@Composable
private fun RingingBanner(title: String, onStop: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RingingLight()
            Text(
                stringResource(R.string.reminder_ringing_now, title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            com.notebudget.app.core.ui.IconAction(
                com.notebudget.app.core.ui.AppIcons.VolumeOff,
                stringResource(R.string.reminder_stop_sound),
                onStop,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ReminderCard(
    row: ReminderRow,
    ringing: Boolean,
    /** It calls for attention now: its notification is shown, with or without a melody (D296). */
    active: Boolean,
    onStopSound: () -> Unit,
    /** Ends this ring for good: the melody and the notification. The reminder itself stays as it is. */
    onReset: () -> Unit,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onDelete: () -> Unit,
    onOpenNote: () -> Unit,
    onEnabled: (Boolean) -> Unit,
) {
    val r = row.reminder
    val context = LocalContext.current
    val whenText = remember(r.triggerAt) { DateFormat.getMediumDateFormat(context).format(Date(r.triggerAt)) + " " + DateFormat.getTimeFormat(context).format(Date(r.triggerAt)) }
    Card(Modifier.fillMaxWidth()) {
        // A switched-off reminder is shown paler (D268).
        Column(Modifier.padding(12.dp).alpha(if (row.isPending && !r.isEnabled) 0.6f else 1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) RingingLight(Modifier.padding(end = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.noteTitle, style = MaterialTheme.typography.titleMedium)
                    Text(whenText, style = MaterialTheme.typography.bodyLarge)
                }
                // On / off, like an alarm clock (D268): only for a reminder still to ring.
                if (row.isPending) {
                    val switchLabel = stringResource(if (r.isEnabled) R.string.reminder_switch_off else R.string.reminder_switch_on)
                    Switch(
                        checked = r.isEnabled,
                        onCheckedChange = onEnabled,
                        modifier = Modifier.semantics { contentDescription = switchLabel },
                    )
                }
            }
            if (active) {
                Text(stringResource(R.string.reminder_status_active), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            }
            Text(
                stringResource(
                    when {
                        row.isPending && !r.isEnabled -> R.string.reminder_status_off
                        r.status == ReminderStatus.CANCELLED -> R.string.reminder_status_cancelled
                        r.status == ReminderStatus.COMPLETED -> R.string.reminder_status_done
                        r.status == ReminderStatus.AWAITING_CONFIRMATION -> R.string.reminder_status_waiting
                        r.status == ReminderStatus.FIRED -> R.string.reminder_status_fired
                        else -> R.string.reminder_status_scheduled
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (r.snoozedUntil > 0) {
                Text(stringResource(R.string.reminder_snoozed_until, com.notebudget.app.core.ui.stampText(r.snoozedUntil)), style = MaterialTheme.typography.labelLarge)
            }
            val flags = listOfNotNull(
                stringResource(R.string.reminder_sound).takeIf { r.soundEnabled },
                stringResource(R.string.reminder_vibration).takeIf { r.vibrationEnabled },
                stringResource(R.string.reminder_popup).takeIf { r.popupEnabled },
                stringResource(R.string.reminder_confirm_short).takeIf { r.requiresConfirmation },
                r.repeatIntervalMinutes?.takeIf { r.repeatUntilConfirmed }?.let { stringResource(R.string.reminder_repeat_short, it) },
                r.repeatIntervalMinutes?.takeIf { r.repeatCount > 0 }?.let { stringResource(R.string.reminder_extra_short, r.repeatCount, it) },
                when (r.recurrence) {
                    com.notebudget.app.domain.model.Recurrence.WEEKLY ->
                        if (r.weekdaysMask == com.notebudget.app.domain.model.RecurrenceRules.ALL_DAYS) stringResource(R.string.reminder_recurs_daily)
                        else stringResource(
                            R.string.reminder_recurs_weekly,
                            java.time.DayOfWeek.values().filter { com.notebudget.app.domain.model.RecurrenceRules.includes(r.weekdaysMask, it) }
                                .joinToString(", ") { it.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) },
                        )
                    com.notebudget.app.domain.model.Recurrence.MONTHLY -> stringResource(R.string.reminder_recurs_monthly, r.monthDay)
                    else -> null
                },
            )
            if (flags.isNotEmpty()) Text(flags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // D278: the actions of a card as icons (a long press says which); Cancel and Delete still ask first.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (ringing) {
                    com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.VolumeOff, stringResource(R.string.reminder_stop_sound), onStopSound, tint = MaterialTheme.colorScheme.error)
                }
                // Reset is a word, not one more icon: the one thing to find at once while a reminder calls (D296).
                if (active) {
                    com.notebudget.app.core.ui.tip.FilledTonalButton(
                        onClick = onReset,
                        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text(stringResource(R.string.reminder_reset)) }
                }
                if (r.status == ReminderStatus.AWAITING_CONFIRMATION) {
                    com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.TaskAlt, stringResource(R.string.reminder_action_complete), onConfirm, tint = MaterialTheme.colorScheme.primary)
                }
                com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.Edit, stringResource(R.string.action_edit), onEdit)
                if (row.isPending) com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.NotificationsOff, stringResource(R.string.reminders_cancel), onCancel)
                com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.Delete, stringResource(R.string.action_delete), onDelete, tint = MaterialTheme.colorScheme.error)
                // After the actions of the reminder: to the note it reminds of (D312).
                com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.OpenInNew, stringResource(R.string.alarm_open_note), onOpenNote)
            }
        }
    }
}

/** Add or change a reminder: the note, the day and time, and how it should call for attention. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReminderDialog(initial: Reminder?, notes: List<Note>, presetNoteId: String?, onSave: (String, ReminderDraft) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
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
    var recurrence by remember { mutableStateOf(initial?.recurrence ?: com.notebudget.app.domain.model.Recurrence.NONE) }
    var weekdays by remember { mutableStateOf(initial?.weekdaysMask?.takeIf { it != 0 } ?: com.notebudget.app.domain.model.RecurrenceRules.ALL_DAYS) }
    var everyDay by remember { mutableStateOf(initial == null || initial.recurrence != com.notebudget.app.domain.model.Recurrence.WEEKLY || initial.weekdaysMask == com.notebudget.app.domain.model.RecurrenceRules.ALL_DAYS) }
    var monthDay by remember { mutableStateOf((initial?.monthDay?.takeIf { it != 0 } ?: start.dayOfMonth).toString()) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var noteMenu by remember { mutableStateOf(false) }
    // The melody (D129): null = the phone's default notification sound. Kept as a text address in the reminder.
    var soundUri by remember { mutableStateOf(initial?.soundUri) }
    var soundBusy by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf<android.media.Ringtone?>(null) }
    val defaultSoundTitle = stringResource(R.string.reminder_sound_default)
    val soundBadFile = stringResource(R.string.reminder_sound_bad_file)
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { previewing?.stop() } }
    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val picked = result.data?.let {
            if (Build.VERSION.SDK_INT >= 33) it.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            else @Suppress("DEPRECATION") it.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        if (result.resultCode == android.app.Activity.RESULT_OK && picked != null) soundUri = picked.toString()
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        soundBusy = true
        val copied = com.notebudget.app.core.notification.ReminderSounds.importFile(context, uri)
        soundBusy = false
        if (copied != null) soundUri = copied
        else android.widget.Toast.makeText(context, soundBadFile, android.widget.Toast.LENGTH_LONG).show()
    }

    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.reminders_add else R.string.reminders_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.reminder_note), style = MaterialTheme.typography.labelLarge)
                Column {
                    OutlinedButton(onClick = { noteMenu = true }, enabled = initial == null) {
                        Text(notes.firstOrNull { it.id == noteId }?.title ?: stringResource(R.string.reminder_pick_note), maxLines = 1)
                    }
                    DropdownMenu(expanded = noteMenu, onDismissRequest = { noteMenu = false }) {
                        notes.forEach { note -> DropdownMenuItem(text = { Text(note.title) }, onClick = { noteId = note.id; noteMenu = false }) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate = true }) { Text(DateFormat.getMediumDateFormat(context).format(Date(ReminderTime.toMillis(date, time)))) }
                    OutlinedButton(onClick = { pickTime = true }) { Text(DateFormat.getTimeFormat(context).format(Date(ReminderTime.toMillis(date, time)))) }
                }
                SwitchRow(R.string.reminder_sound_long, sound) { sound = it }
                if (sound) {
                    Text(
                        stringResource(R.string.reminder_melody, com.notebudget.app.core.notification.ReminderSounds.title(context, soundUri, defaultSoundTitle)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            previewing?.stop()
                            previewing = com.notebudget.app.core.notification.ReminderSounds.preview(context, soundUri)
                        }) { Text(stringResource(R.string.reminder_sound_play)) }
                        OutlinedButton(onClick = { previewing?.stop(); previewing = null }) { Text(stringResource(R.string.audio_stop)) }
                        OutlinedButton(onClick = {
                            val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALL)
                                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                .putExtra(
                                    android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                    soundUri?.let(Uri::parse) ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                                )
                            systemPicker.launch(intent)
                        }) { Text(stringResource(R.string.reminder_sound_system)) }
                        OutlinedButton(enabled = !soundBusy, onClick = { filePicker.launch(arrayOf("audio/*")) }) { Text(stringResource(R.string.reminder_sound_file)) }
                        if (soundUri != null) OutlinedButton(onClick = { soundUri = null }) { Text(stringResource(R.string.reminder_sound_reset)) }
                    }
                }
                if (sound) {
                    Text(stringResource(R.string.rem_volume, volume.toInt()), style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Slider(value = volume, onValueChange = { volume = it }, valueRange = 10f..100f, steps = 8)
                    Text(stringResource(R.string.rem_volume_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.rem_ramp_title), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0, 10, 30, 60, 120).forEach { secs ->
                            FilterChip(ramp == secs, { ramp = secs }, { Text(if (secs == 0) stringResource(R.string.rem_ramp_off) else stringResource(R.string.rem_ramp_secs, secs)) })
                        }
                    }
                }
                SwitchRow(R.string.reminder_vibration_long, vibration) { vibration = it }
                SwitchRow(R.string.reminder_popup_long, popup) { popup = it }
                Text(stringResource(R.string.rem_extra_title), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.rem_extra_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = extraRings, onValueChange = { extraRings = it.filter(Char::isDigit).take(2) }, singleLine = true,
                        label = { Text(stringResource(R.string.rem_extra_count)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = interval.toString(), onValueChange = { interval = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0 }, singleLine = true,
                        label = { Text(stringResource(R.string.rem_extra_every)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                SwitchRow(R.string.reminder_confirm_long, confirmation) { confirmation = it; if (!it) repeat = false }
                if (confirmation) {
                    SwitchRow(R.string.reminder_repeat_long, repeat) { repeat = it }
                    if (repeat) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RemindersViewModel.REPEAT_CHOICES.forEach { minutes ->
                                FilterChip(interval == minutes, { interval = minutes }, { Text(stringResource(R.string.reminder_every_minutes, minutes)) })
                            }
                        }
                    }
                }
                // D130: the days it comes back.
                Text(stringResource(R.string.rem_repeat_title), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(recurrence == com.notebudget.app.domain.model.Recurrence.NONE, { recurrence = com.notebudget.app.domain.model.Recurrence.NONE }, { Text(stringResource(R.string.rem_rec_none)) })
                    FilterChip(recurrence == com.notebudget.app.domain.model.Recurrence.WEEKLY && everyDay, { recurrence = com.notebudget.app.domain.model.Recurrence.WEEKLY; everyDay = true; weekdays = com.notebudget.app.domain.model.RecurrenceRules.ALL_DAYS }, { Text(stringResource(R.string.rem_rec_daily)) })
                    FilterChip(recurrence == com.notebudget.app.domain.model.Recurrence.WEEKLY && !everyDay, { recurrence = com.notebudget.app.domain.model.Recurrence.WEEKLY; everyDay = false }, { Text(stringResource(R.string.rem_rec_weekdays)) })
                    FilterChip(recurrence == com.notebudget.app.domain.model.Recurrence.MONTHLY, { recurrence = com.notebudget.app.domain.model.Recurrence.MONTHLY }, { Text(stringResource(R.string.rem_rec_monthly)) })
                }
                if (recurrence == com.notebudget.app.domain.model.Recurrence.WEEKLY && !everyDay) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        java.time.DayOfWeek.values().forEach { day ->
                            val on = com.notebudget.app.domain.model.RecurrenceRules.includes(weekdays, day)
                            FilterChip(on, { weekdays = weekdays xor com.notebudget.app.domain.model.RecurrenceRules.bit(day) }, {
                                Text(day.getDisplayName(java.time.format.TextStyle.SHORT, androidx.compose.ui.platform.LocalConfiguration.current.locales[0]))
                            })
                        }
                    }
                }
                if (recurrence == com.notebudget.app.domain.model.Recurrence.MONTHLY) {
                    androidx.compose.material3.OutlinedTextField(
                        value = monthDay, onValueChange = { monthDay = it.filter(Char::isDigit).take(2) }, singleLine = true,
                        label = { Text(stringResource(R.string.rem_month_day)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    )
                    Text(stringResource(R.string.rem_month_skip_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            weekdaysMask = if (recurrence == com.notebudget.app.domain.model.Recurrence.WEEKLY) (if (everyDay) com.notebudget.app.domain.model.RecurrenceRules.ALL_DAYS else weekdays) else 0,
                            monthDay = if (recurrence == com.notebudget.app.domain.model.Recurrence.MONTHLY) monthDay.toIntOrNull() ?: 0 else 0,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = state) }
    }
    if (pickTime) {
        val state = rememberTimePickerState(time.hour, time.minute, DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { TimePicker(state = state) },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(state.hour, state.minute); pickTime = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
