package com.notebudget.app.feature.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notebudget.app.core.common.Clock
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.Reminder
import com.notebudget.app.domain.model.ReminderDraft
import com.notebudget.app.domain.model.ReminderStatus
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.repository.ReminderRepository
import com.notebudget.app.domain.usecase.ReminderUseCases
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A reminder with the title of its note. */
data class ReminderRow(val reminder: Reminder, val noteTitle: String) {
    /** Still to fire (or waiting for a confirmation), switched off or not (D268): these have the on / off switch. */
    val isPending: Boolean
        get() = reminder.status == ReminderStatus.SCHEDULED || reminder.status == ReminderStatus.AWAITING_CONFIRMATION

    /** Still to fire (or waiting for a confirmation) and switched on. */
    val isActive: Boolean
        get() = reminder.isEnabled && isPending
}

sealed interface ReminderEvent {
    data class Failed(val error: DomainError) : ReminderEvent
}

/** Time helpers of the reminder dialog: a date and a time of the person's own time zone become UTC milliseconds. */
object ReminderTime {
    fun toMillis(date: LocalDate, time: LocalTime, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()

    fun toDateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

    /** What a new reminder starts at: the next full hour that is at least 30 minutes away. */
    fun suggested(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime {
        val later = toDateTime(nowMillis + 30 * 60_000L, zone)
        return later.withMinute(0).withSecond(0).withNano(0).plusHours(1)
    }
}

/**
 * The reminders of all notes (D122): upcoming ones first (soonest first), then the finished ones (latest first). A
 * reminder of a note in the trash is not shown (it is cancelled with the note anyway). Creating, changing, cancelling,
 * deleting and confirming all go through [ReminderUseCases], which also arm and disarm the alarms.
 */
class RemindersViewModel(
    reminderRepository: ReminderRepository,
    noteRepository: NoteRepository,
    private val useCases: ReminderUseCases,
) : ViewModel() {

    val rows: StateFlow<List<ReminderRow>> = combine(reminderRepository.observeAll(), noteRepository.observeAll()) { reminders, notes ->
        val titles = notes.associate { it.id to it.title }
        val all = reminders.mapNotNull { r -> titles[r.noteId]?.let { ReminderRow(r, it) } }
        // Switched-off reminders stay among the upcoming ones (D268), so they can be switched back on where they were.
        val (active, done) = all.partition { it.isPending }
        active.sortedBy { it.reminder.triggerAt } + done.sortedByDescending { it.reminder.triggerAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The notes a reminder can be put on, by title. */
    val notes: StateFlow<List<Note>> = noteRepository.observeAll()
        .map { list -> list.sortedBy { it.title.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = Channel<ReminderEvent>(Channel.BUFFERED)
    val events: Flow<ReminderEvent> = _events.receiveAsFlow()

    fun create(noteId: String, draft: ReminderDraft, onDone: () -> Unit) = launchResult({ useCases.create(noteId, draft) }, onDone)

    fun update(id: String, draft: ReminderDraft, onDone: () -> Unit) = launchResult({ useCases.update(id, draft) }, onDone)

    fun cancel(id: String) = launchResult({ useCases.cancel(id) })

    /** The on / off switch of a reminder (D268). */
    fun setEnabled(id: String, enabled: Boolean) = launchResult({ useCases.setEnabled(id, enabled) })

    fun delete(id: String) = launchResult({ useCases.delete(id) })

    /** "Done": the reminder that asked for a confirmation is confirmed. */
    fun confirm(id: String) = launchResult({ useCases.onConfirmed(id) })

    private fun <T> launchResult(block: suspend () -> DomainResult<T>, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            when (val result = block()) {
                is DomainResult.Success -> onDone()
                is DomainResult.Failure -> _events.send(ReminderEvent.Failed(result.error))
            }
        }
    }

    companion object {
        /** How often "repeat until confirmed" repeats, in minutes. */
        val REPEAT_CHOICES = listOf(1, 5, 10, 15, 30, 60)
    }
}
