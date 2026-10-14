package com.notebudget.app.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notebudget.app.domain.model.BudgetKind
import com.notebudget.app.domain.model.CalendarEvent
import com.notebudget.app.domain.model.CalendarPlan
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.ScheduledMerge
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.repository.ReminderRepository
import com.notebudget.app.feature.budget.BudgetLayout
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The calendar of the main screen (D236): one month at a time, what is on each of its days, and the chosen day. Only the shown
 * month is worked out, off the main thread, again when a reminder or a note title changes.
 */
class CalendarViewModel(
    reminderRepository: ReminderRepository,
    noteRepository: NoteRepository,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {

    private val _month = MutableStateFlow(YearMonth.from(today()))
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    private val _selected = MutableStateFlow(today())
    val selected: StateFlow<LocalDate> = _selected.asStateFlow()

    /** The reminders, the note titles and the budgets that merge on a day (D237), read once for both the month and the search. */
    private val sources = combine(reminderRepository.observeAll(), noteRepository.observeAll()) { reminders, notes ->
        val merges = notes.filter { it.type == NoteType.BUDGET }.mapNotNull { note ->
            val layout = BudgetLayout.parse(note.extras)
            val at = layout.mergeAt
            if (layout.budgetKind == BudgetKind.ACCOUNT || at == null) null
            else ScheduledMerge(note.id, note.title, at)
        }
        // Tasks with a start or an end (D239).
        val tasks = notes.filter { it.type == NoteType.TASK }.mapNotNull { note ->
            val task = com.notebudget.app.domain.model.TaskDocument.decode(note.extras)
            val start = task.start ?: task.end ?: return@mapNotNull null
            val end = task.end ?: start
            com.notebudget.app.domain.model.ScheduledTask(
                note.id, note.title, start, end, task.done,
                note.colorHex?.let { com.notebudget.app.core.common.ArgbColor.parseHex(it) },
            )
        }
        Sources(reminders, notes.associate { it.id to it.title }, merges, tasks)
    }.flowOn(Dispatchers.Default)

    private data class Sources(
        val reminders: List<com.notebudget.app.domain.model.Reminder>,
        val titles: Map<String, String>,
        val merges: List<ScheduledMerge>,
        val tasks: List<com.notebudget.app.domain.model.ScheduledTask>,
    )

    val days: StateFlow<Map<LocalDate, List<CalendarEvent>>> =
        combine(sources, _month) { src, month ->
            CalendarPlan.join(
                CalendarPlan.join(
                    CalendarPlan.reminders(src.reminders, src.titles, month, zone()),
                    CalendarPlan.merges(src.merges, month, zone()),
                ),
                CalendarPlan.tasks(src.tasks, month, zone()),
            )
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Everything on the calendar across all months, for its search (D252). */
    val everything: StateFlow<List<CalendarEvent>> =
        sources.map { src -> CalendarPlan.all(src.reminders, src.titles, src.merges, src.tasks) }
            .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun previousMonth() = showMonth(_month.value.minusMonths(1))

    fun nextMonth() = showMonth(_month.value.plusMonths(1))

    /** Back to this month, today chosen. */
    fun goToToday() {
        _month.value = YearMonth.from(today())
        _selected.value = today()
    }

    fun select(day: LocalDate) {
        _selected.value = day
    }

    /** Shows the month of the moment [at] with its day chosen (a found item of the search, D252). */
    fun showDayOf(at: Long) {
        val day = java.time.Instant.ofEpochMilli(at).atZone(zone()).toLocalDate()
        _month.value = YearMonth.from(day)
        _selected.value = day
    }

    /** Another month: its first day is chosen, or today when it is this month. */
    private fun showMonth(month: YearMonth) {
        _month.value = month
        _selected.value = if (month == YearMonth.from(today())) today() else month.atDay(1)
    }
}
