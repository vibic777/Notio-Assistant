package com.notebudget.app.feature.budget

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import com.notebudget.app.core.ui.tip.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import com.notebudget.app.core.ui.tip.DropdownMenu
import com.notebudget.app.core.ui.tip.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import com.notebudget.app.core.ui.tip.OutlinedButton
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.ui.LocalDateTimeFormats
import com.notebudget.app.domain.model.BudgetKind
import com.notebudget.app.domain.model.Note
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
private fun kindName(kind: BudgetKind): String = stringResource(
    when (kind) {
        BudgetKind.ACCOUNT -> R.string.budget_kind_account
        BudgetKind.INCOME -> R.string.budget_kind_income
        BudgetKind.EXPENSE -> R.string.budget_kind_expense
    },
)

/** The words of a repeat (D339). */
@Composable
private fun repeatName(repeat: com.notebudget.app.domain.model.MergeRepeat): String = stringResource(
    when (repeat) {
        com.notebudget.app.domain.model.MergeRepeat.NONE -> R.string.budget_merge_repeat_none
        com.notebudget.app.domain.model.MergeRepeat.DAILY -> R.string.budget_merge_repeat_daily
        com.notebudget.app.domain.model.MergeRepeat.WEEKLY -> R.string.budget_merge_repeat_weekly
        com.notebudget.app.domain.model.MergeRepeat.MONTHLY -> R.string.budget_merge_repeat_monthly
        com.notebudget.app.domain.model.MergeRepeat.YEARLY -> R.string.budget_merge_repeat_yearly
    },
)

/** In View: one line saying what an income / expense will do (D237); nothing for an account. */
@Composable
fun BudgetMergeStatus(layout: BudgetLayout, accounts: List<Note>) {
    val kind = layout.budgetKind
    if (kind == BudgetKind.ACCOUNT) return
    val target = accounts.firstOrNull { it.id == layout.mergeTarget }
    val formats = LocalDateTimeFormats.current
    val text = when {
        target == null -> stringResource(R.string.budget_merge_status_no_target, kindName(kind))
        layout.mergeAt == null -> stringResource(R.string.budget_merge_status_no_time, kindName(kind), target.title)
        else -> stringResource(R.string.budget_merge_status, kindName(kind), target.title, formats.format(layout.mergeAt))
    }
    val repeats = if (layout.repeat != com.notebudget.app.domain.model.MergeRepeat.NONE && target != null && layout.mergeAt != null) " · " + repeatName(layout.repeat) else ""
    Text(text + repeats, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

/**
 * In Edit: the kind of the budget and, for an income / expense, how and when it is merged into an account (D237), with
 * "Merge now". [accounts] are the budgets it can go into; [onMergeNow] merges after a confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BudgetMergePanel(layout: BudgetLayout, accounts: List<Note>, onChange: (BudgetLayout) -> Unit, onMergeNow: () -> Unit) {
    val context = LocalContext.current
    val formats = LocalDateTimeFormats.current
    var pickAccount by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf<LocalDate?>(null) }
    var confirmMerge by remember { mutableStateOf(false) }
    val kind = layout.budgetKind
    val target = accounts.firstOrNull { it.id == layout.mergeTarget }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.budget_kind_title), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BudgetKind.entries.forEach { k ->
                    FilterChip(kind == k, { onChange(layout.copy(kind = k.name)) }, { Text(kindName(k)) })
                }
            }
            if (kind == BudgetKind.ACCOUNT) return@Column
            Text(stringResource(R.string.budget_kind_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // The account it goes into; none chosen = nothing happens (user, 2026-09-27).
            Text(stringResource(R.string.budget_merge_target), style = MaterialTheme.typography.labelLarge)
            if (accounts.isEmpty()) {
                Text(stringResource(R.string.budget_merge_no_accounts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(onClick = { pickAccount = true }) { Text(target?.title ?: stringResource(R.string.budget_merge_target_none)) }
                    DropdownMenu(expanded = pickAccount, onDismissRequest = { pickAccount = false }) {
                        accounts.forEach { note ->
                            DropdownMenuItem(text = { Text(note.title) }, onClick = { pickAccount = false; onChange(layout.copy(mergeTarget = note.id)) })
                        }
                    }
                }
            }

            // When: it merges once at that moment (seen in the calendar); "Merge now" at any time.
            Text(stringResource(R.string.budget_merge_when), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate = true }) {
                    Text(layout.mergeAt?.let { formats.format(it) } ?: stringResource(R.string.budget_merge_when_none))
                }
                if (layout.mergeAt != null) TextButton(onClick = { onChange(layout.copy(mergeAt = null)) }) { Text(stringResource(R.string.budget_merge_clear_time)) }
            }

            // D339: once, or again and again from that time on; a merge that repeats stays in its folder.
            Text(stringResource(R.string.budget_merge_repeat), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.notebudget.app.domain.model.MergeRepeat.entries.forEach { r ->
                    FilterChip(layout.repeat == r, { onChange(layout.copy(mergeRepeat = r.name)) }, { Text(repeatName(r)) })
                }
            }
            if (layout.repeat == com.notebudget.app.domain.model.MergeRepeat.WEEKLY) {
                // The days of the week; none chosen is the weekday of the time above.
                val chosen = layout.mergeDays and com.notebudget.app.domain.model.RecurrenceRules.ALL_DAYS
                val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    java.time.DayOfWeek.values().forEach { day ->
                        val bit = com.notebudget.app.domain.model.RecurrenceRules.bit(day)
                        FilterChip(chosen and bit != 0, { onChange(layout.copy(mergeDays = chosen xor bit)) }, { Text(day.getDisplayName(java.time.format.TextStyle.SHORT, locale)) })
                    }
                }
            }
            if (layout.repeat != com.notebudget.app.domain.model.MergeRepeat.NONE) {
                Text(stringResource(R.string.budget_merge_repeat_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            TickRow(R.string.budget_merge_sum, layout.mergeSum) { onChange(layout.copy(mergeSum = it)) }
            TickRow(R.string.budget_merge_same, layout.mergeSameItems) { onChange(layout.copy(mergeSameItems = it)) }
            TickRow(R.string.budget_merge_all, layout.mergeTransferAll) { onChange(layout.copy(mergeTransferAll = it)) }
            Text(stringResource(R.string.budget_merge_match), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!layout.mergeByPath, { onChange(layout.copy(mergeByPath = false)) }, { Text(stringResource(R.string.budget_merge_by_name)) })
                FilterChip(layout.mergeByPath, { onChange(layout.copy(mergeByPath = true)) }, { Text(stringResource(R.string.budget_merge_by_path)) })
            }
            // D340: "Cash" and "cash" are one item / one group, or two.
            if (layout.mergeSameItems) TickRow(R.string.budget_merge_case_items, layout.mergeIgnoreItemCase) { onChange(layout.copy(mergeIgnoreItemCase = it)) }
            if (layout.mergeByPath) TickRow(R.string.budget_merge_case_groups, layout.mergeIgnoreGroupCase) { onChange(layout.copy(mergeIgnoreGroupCase = it)) }
            Button(onClick = { confirmMerge = true }, enabled = target != null) { Text(stringResource(R.string.budget_merge_now)) }
        }
    }

    if (pickDate) {
        val initial = layout.mergeAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickDate = false
                    pickTime = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = state) }
    }
    pickTime?.let { day ->
        val initial = layout.mergeAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime() } ?: LocalTime.of(9, 0)
        val state = rememberTimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { pickTime = null },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val at = LocalDateTime.of(day, LocalTime.of(state.hour, state.minute)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    pickTime = null
                    // The day of the month is kept apart (D339): a monthly merge of the 31st comes back to the 31st after a shorter month.
                    onChange(layout.copy(mergeAt = at, mergeDay = day.dayOfMonth))
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmMerge && target != null) {
        AlertDialog(
            onDismissRequest = { confirmMerge = false },
            title = { Text(stringResource(R.string.budget_merge_now)) },
            text = { Text(stringResource(R.string.budget_merge_now_confirm, target.title)) },
            confirmButton = { TextButton(onClick = { confirmMerge = false; onMergeNow() }) { Text(stringResource(R.string.budget_merge_now)) } },
            dismissButton = { TextButton(onClick = { confirmMerge = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun TickRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(stringResource(label), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** The accounts an income / expense can go into, read once per note and change of the list of notes. */
@Composable
fun rememberMergeAccounts(service: BudgetMergeService, noteId: String, key: Any?): List<Note> {
    val accounts by produceState(emptyList<Note>(), noteId, key) { value = runCatching { service.accounts(noteId) }.getOrDefault(emptyList()) }
    return accounts
}
