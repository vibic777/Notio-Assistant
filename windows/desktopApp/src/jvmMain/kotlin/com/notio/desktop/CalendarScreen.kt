@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.notio.desktop

import androidx.compose.runtime.collectAsState
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notio.shared.domain.CalendarEvent
import com.notio.shared.domain.CalendarPlan
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The calendar of the main screen (D236): one month at a time, what is on each of its days, and the chosen day — the phone's
 * `CalendarViewModel` on the controller's state. Only the shown month is worked out, off the UI thread, again when a reminder or a
 * note title changes.
 */
class CalendarModel(
    state: StateFlow<UiState>,
    scope: CoroutineScope,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val _month = MutableStateFlow(YearMonth.from(today()))
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    private val _selected = MutableStateFlow(today())
    val selected: StateFlow<LocalDate> = _selected.asStateFlow()

    val days: StateFlow<Map<LocalDate, List<CalendarEvent>>> =
        combine(state.map { it.reminderRows to it.reminderNotes }.distinctUntilChanged(), _month) { (rows, notes), month ->
            // Reminders, and the budgets that merge on a day (D237).
            val merges = scheduledMerges(notes)
            CalendarPlan.join(
                CalendarPlan.join(
                    CalendarPlan.reminders(rows.map { it.reminder }, notes.associate { it.id to it.title }, month, zone()),
                    CalendarPlan.merges(merges, month, zone()),
                ),
                CalendarPlan.tasks(scheduledTasks(notes), month, zone()),
            )
        }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Everything on the calendar across all months, for its search (D252): each reminder once at its time, each merge at its time. */
    val everything: StateFlow<List<CalendarEvent>> =
        state.map { it.reminderRows to it.reminderNotes }.distinctUntilChanged().map { (rows, notes) ->
            CalendarPlan.all(rows.map { it.reminder }, notes.associate { it.id to it.title }, scheduledMerges(notes), scheduledTasks(notes))
        }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Shows the month of the moment [at] with its day chosen (a found item of the search, D252). */
    fun showDayOf(at: Long) {
        val day = java.time.Instant.ofEpochMilli(at).atZone(zone()).toLocalDate()
        _month.value = YearMonth.from(day)
        _selected.value = day
    }

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

    /** Another month: its first day is chosen, or today when it is this month. */
    private fun showMonth(month: YearMonth) {
        _month.value = month
        _selected.value = if (month == YearMonth.from(today())) today() else month.atDay(1)
    }
}

/** "Today" of the calendar's top bar. */
@Composable
fun CalendarTodayButton(model: CalendarModel) {
    TextButton(onClick = model::goToToday) { Text(stringResource("calendar_today")) }
}

/**
 * The calendar (D236): a month of days with a bell on each day that has a reminder. A click on a day chooses it and lists its
 * notes under the month; the mouse over a day with marks shows the names in a bubble (a click on a name opens the note). The
 * arrows, a drag sideways or the wheel with Shift change the month.
 */
@Composable
fun CalendarScreen(model: CalendarModel, onOpenNote: (String) -> Unit, modifier: Modifier = Modifier, marked: String = "") {
    val month by model.month.collectAsState()
    val selected by model.selected.collectAsState()
    val days by model.days.collectAsState()
    // The language of the app names the months and days and says which day starts the week.
    val locale = LocalRes.current.locale
    val firstDay = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val cells = remember(month, firstDay) { CalendarPlan.cells(month, firstDay) }
    val today = LocalDate.now()
    // The search of the calendar (D252): the names of everything on it, in every month; a found item shows its day.
    val search = LocalNoteSearch.current
    val everything by model.everything.collectAsState()
    val pattern = search?.activePattern
    val found = remember(everything, pattern) { if (pattern == null) emptyList() else everything.filter { pattern.foundIn(it.noteTitle) } }
    val hits = remember(found, pattern) { itemHits(found.map { SearchableItem(eventKey(it), it.noteTitle, "") }, pattern) }
    androidx.compose.runtime.LaunchedEffect(hits) { search?.report(hits) }
    androidx.compose.runtime.LaunchedEffect(search?.jumps, hits) {
        val key = search?.currentHit?.key as? ItemHitKey ?: return@LaunchedEffect
        found.firstOrNull { eventKey(it) == key.id }?.let { model.showDayOf(it.at) }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        val side = ((maxWidth - 900.dp) / 2).coerceAtLeast(0.dp)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp + side, end = 12.dp + side, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MonthHeader(month, locale, model::previousMonth, model::nextMonth)
            // A drag sideways changes the month, as the arrows do.
            Column(
                Modifier.pointerInput(Unit) {
                    var travelled = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { travelled = 0f },
                        onHorizontalDrag = { change, amount -> change.consume(); travelled += amount },
                        onDragEnd = {
                            val limit = 64.dp.toPx()
                            if (travelled > limit) model.previousMonth() else if (travelled < -limit) model.nextMonth()
                        },
                    )
                },
            ) {
                WeekdayRow(firstDay, locale)
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            DayCell(
                                day = day,
                                events = day?.let { days[it] }.orEmpty(),
                                isToday = day == today,
                                isSelected = day == selected,
                                onSelect = { day?.let(model::select) },
                                onOpenNote = onOpenNote,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            DayList(selected, days[selected].orEmpty(), onOpenNote, marked)
            if (pattern != null && found.isNotEmpty()) {
                FoundList(found, onGo = { e ->
                    val index = hits.indexOfFirst { (it.key as? ItemHitKey)?.id == eventKey(e) }
                    if (index >= 0) search?.jumpTo(index) else model.showDayOf(e.at)
                })
            }
        }
    }
}

@Composable
private fun MonthHeader(month: YearMonth, locale: Locale, onPrevious: () -> Unit, onNext: () -> Unit) {
    val title = remember(month, locale) {
        DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(month).replaceFirstChar { it.titlecase(locale) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource("calendar_previous"))
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource("calendar_next"))
        }
    }
}

@Composable
private fun WeekdayRow(firstDay: java.time.DayOfWeek, locale: Locale) {
    Row(Modifier.fillMaxWidth()) {
        repeat(7) { i ->
            Text(
                firstDay.plus(i.toLong()).getDisplayName(TextStyle.SHORT_STANDALONE, locale),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** One day: its number, a bell for each reminder (three at most, then "+n"), and the bubble with the names when tapped. */
@Composable
private fun DayCell(
    day: LocalDate?,
    events: List<CalendarEvent>,
    isToday: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var bubble by remember { mutableStateOf(false) }
    Box(modifier.height(64.dp).padding(2.dp)) {
        if (day == null) return@Box
        val colors = MaterialTheme.colorScheme
        // On the computer the mouse over a day shows the names of its notes (the phone's tap, D236); a click opens the bubble
        // whose names open the notes.
        androidx.compose.foundation.TooltipArea(
            tooltip = {
                if (events.isNotEmpty() && !bubble) androidx.compose.material3.Surface(
                    shape = MaterialTheme.shapes.small, tonalElevation = 3.dp, shadowElevation = 4.dp,
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        events.forEach { EventLine(it) }
                    }
                }
            },
            delayMillis = 300,
            modifier = Modifier.fillMaxSize(),
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.small)
                .then(if (isSelected) Modifier.border(2.dp, colors.primary, MaterialTheme.shapes.small) else Modifier)
                .clickable { onSelect(); if (events.isNotEmpty()) bubble = true },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .then(if (isToday) Modifier.background(colors.primary) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isToday) colors.onPrimary else colors.onSurface,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                )
            }
            val marks = events.filter { it.kind != com.notio.shared.domain.CalendarEventKind.TASK }
            if (marks.isNotEmpty()) {
                val label = stringResource("calendar_reminders_cd", marks.size)
                Row(Modifier.semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
                    marks.take(3).forEach { e -> Text(iconOf(e), Modifier.alpha(if (e.done) 0.4f else 1f), style = MaterialTheme.typography.labelSmall) }
                    if (marks.size > 3) Text("+" + (marks.size - 3), style = MaterialTheme.typography.labelSmall)
                }
            }
            // A task is a bar on every day from its start to its end (D239), in its note's colour, pale when done.
            events.filter { it.kind == com.notio.shared.domain.CalendarEventKind.TASK }.take(3).forEach { e ->
                Box(
                    Modifier
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .alpha(if (e.done) 0.35f else 1f)
                        .background(e.colorArgb?.let { androidx.compose.ui.graphics.Color(it) } ?: colors.tertiary),
                )
            }
        }
        }
        DropdownMenu(expanded = bubble, onDismissRequest = { bubble = false }) {
            events.forEach { e ->
                DropdownMenuItem(
                    text = { EventLine(e) },
                    onClick = { bubble = false; onOpenNote(e.noteId) },
                )
            }
        }
    }
}

/** Under the month: what is on the chosen day, each line opens its note. */
@Composable
private fun DayList(day: LocalDate, events: List<CalendarEvent>, onOpenNote: (String) -> Unit, marked: String = "") {
    val formats = LocalDateTimeFormats.current
    Text(
        remember(day, formats) { formats.formatDate(day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) },
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (events.isEmpty()) {
        Text(stringResource("calendar_day_empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    events.forEach { e ->
        // The reminder the person came from (Android D267) is framed; a click opens its note or task, as for every line.
        val isMarked = marked.isNotEmpty() && e.kind == com.notio.shared.domain.CalendarEventKind.REMINDER && e.sourceId == marked
        Card(
            Modifier
                .fillMaxWidth()
                .then(if (isMarked) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier)
                .clickable { onOpenNote(e.noteId) },
        ) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) { EventLine(e) }
        }
    }
}

@Composable
private fun EventLine(e: CalendarEvent) {
    val formats = LocalDateTimeFormats.current
    // A task shows its start and end; anything else its time.
    val end = e.endAt
    val time = if (e.kind == com.notio.shared.domain.CalendarEventKind.TASK && end != null && end != e.at) formats.format(e.at) + " – " + formats.format(end)
    else formats.formatTime(e.at)
    Row(Modifier.alpha(if (e.done) 0.55f else 1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(iconOf(e))
        Text(time, style = MaterialTheme.typography.labelLarge)
        Text(e.noteTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
    }
}

/** A bell for a reminder, a money bag for a budget merge (D237). */
private fun iconOf(e: CalendarEvent): String = when (e.kind) {
    com.notio.shared.domain.CalendarEventKind.REMINDER -> Glyphs.bell
    com.notio.shared.domain.CalendarEventKind.BUDGET_MERGE -> Glyphs.money
    com.notio.shared.domain.CalendarEventKind.TASK -> Glyphs.task
}

/** The tasks with a start or an end (D239), for the calendar: bars from the start to the end in the note's colour. */
private fun scheduledTasks(notes: List<com.notio.shared.domain.Note>): List<com.notio.shared.domain.ScheduledTask> =
    notes.filter { it.type == com.notio.shared.domain.NoteType.TASK }.mapNotNull { note ->
        val task = com.notio.shared.domain.TaskDocument.decode(note.extras)
        val start = task.start ?: task.end ?: return@mapNotNull null
        val end = task.end ?: start
        com.notio.shared.domain.ScheduledTask(note.id, note.title, start, end, task.done, note.colorHex?.let { com.notio.shared.domain.ArgbColor.parseHex(it) })
    }

/** The budgets that merge at a moment (D237), for the calendar. */
private fun scheduledMerges(notes: List<com.notio.shared.domain.Note>): List<com.notio.shared.domain.ScheduledMerge> =
    notes.filter { it.type == com.notio.shared.domain.NoteType.BUDGET }.mapNotNull { note ->
        val layout = com.notio.shared.domain.BudgetLayout.parse(note.extras)
        val at = layout.mergeAt
        if (layout.budgetKind == com.notio.shared.domain.BudgetKind.ACCOUNT || at == null) null
        else com.notio.shared.domain.ScheduledMerge(note.id, note.title, at)
    }

/** The key of an event in the search: a reminder and a merge of the same note differ, and so do two times of one reminder. */
private fun eventKey(e: CalendarEvent): String = e.kind.name + ":" + e.sourceId + ":" + e.at

/** What the search found (D252), with its date: a click shows that day in the month above. */
@Composable
private fun FoundList(found: List<CalendarEvent>, onGo: (CalendarEvent) -> Unit) {
    val formats = LocalDateTimeFormats.current
    Text(stringResource("calendar_search_found"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    found.forEach { e ->
        Card(Modifier.fillMaxWidth().clickable { onGo(e) }) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(iconOf(e), Modifier.alpha(if (e.done) 0.55f else 1f))
                Text(formats.format(e.at), style = MaterialTheme.typography.labelLarge)
                Text(highlighted(e.noteTitle, ItemHitKey(eventKey(e), 0)), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            }
        }
    }
}
