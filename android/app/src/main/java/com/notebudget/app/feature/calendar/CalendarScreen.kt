package com.notebudget.app.feature.calendar

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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import com.notebudget.app.core.ui.tip.DropdownMenu
import com.notebudget.app.core.ui.tip.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.material3.TopAppBar
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
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notebudget.app.R
import com.notebudget.app.core.ui.LocalDateTimeFormats
import com.notebudget.app.core.ui.ItemHitKey
import com.notebudget.app.core.ui.LocalNoteSearch
import com.notebudget.app.core.ui.NoteSearch
import com.notebudget.app.core.ui.NoteSearchBar
import com.notebudget.app.core.ui.SearchableItem
import com.notebudget.app.core.ui.appViewModel
import com.notebudget.app.core.ui.highlighted
import com.notebudget.app.core.ui.itemHits
import com.notebudget.app.domain.model.CalendarEvent
import com.notebudget.app.domain.model.CalendarEventKind
import com.notebudget.app.domain.model.CalendarPlan
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The calendar (D236): a month of days with a bell on each day that has a reminder. A tap on a day shows the names of its
 * notes in a bubble (a tap on a name opens the note) and lists them under the month. Arrows or a swipe change the month.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** From a reminder notification (D267): the moment it rang, whose day is shown, and the reminder marked in that day's list. */
    focusAt: Long = 0L,
    focusReminderId: String = "",
    viewModel: CalendarViewModel = appViewModel { CalendarViewModel(it.reminderRepository, it.noteRepository) },
) {
    val month by viewModel.month.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val days by viewModel.days.collectAsStateWithLifecycle()
    // Read from the configuration, so a change of the app language redraws the names of the months and days.
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val firstDay = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val cells = remember(month, firstDay) { CalendarPlan.cells(month, firstDay) }
    val today = LocalDate.now()
    // Once per opening, not again after a turn of the screen (the person may have moved on to another day).
    var focused by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(focusAt) {
        if (focusAt > 0L && !focused) {
            viewModel.showDayOf(focusAt)
            focused = true
        }
    }

    // The search of the calendar (D252): the names of everything on it, in every month; a found item shows its day.
    val search = remember { NoteSearch() }
    val everything by viewModel.everything.collectAsStateWithLifecycle()
    val pattern = search.activePattern
    val found = remember(everything, pattern) { if (pattern == null) emptyList() else everything.filter { pattern.foundIn(it.noteTitle) } }
    val hits = remember(found, pattern) { itemHits(found.map { SearchableItem(eventKey(it), it.noteTitle, "") }, pattern) }
    androidx.compose.runtime.LaunchedEffect(hits) { search.report(hits) }
    androidx.compose.runtime.LaunchedEffect(search.jumps, hits) {
        val key = search.currentHit?.key as? ItemHitKey ?: return@LaunchedEffect
        found.firstOrNull { eventKey(it) == key.id }?.let { viewModel.showDayOf(it.at) }
    }
    androidx.activity.compose.BackHandler(enabled = search.open) { search.close() }

    androidx.compose.runtime.CompositionLocalProvider(LocalNoteSearch provides search) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
          Column {
            TopAppBar(
                title = { Text(stringResource(R.string.calendar_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { if (search.open) search.close() else search.open = true }) {
                        Icon(androidx.compose.material.icons.Icons.Filled.Search, contentDescription = stringResource(R.string.calendar_search))
                    }
                    TextButton(onClick = viewModel::goToToday) { Text(stringResource(R.string.calendar_today)) }
                },
            )
            if (search.open) NoteSearchBar(search, hint = stringResource(R.string.calendar_search))
          }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MonthHeader(month, locale, viewModel::previousMonth, viewModel::nextMonth)
            // A swipe sideways changes the month, as the arrows do.
            Column(
                Modifier.pointerInput(Unit) {
                    var travelled = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { travelled = 0f },
                        onHorizontalDrag = { change, amount -> change.consume(); travelled += amount },
                        onDragEnd = {
                            val limit = 64.dp.toPx()
                            if (travelled > limit) viewModel.previousMonth() else if (travelled < -limit) viewModel.nextMonth()
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
                                onSelect = { day?.let(viewModel::select) },
                                onOpenNote = onOpenNote,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            if (pattern != null && found.isNotEmpty()) {
                FoundList(found, onGo = { e ->
                    val index = hits.indexOfFirst { (it.key as? ItemHitKey)?.id == eventKey(e) }
                    if (index >= 0) search.jumpTo(index) else viewModel.showDayOf(e.at)
                })
            }
            DayList(selected, days[selected].orEmpty(), onOpenNote, marked = focusReminderId)
        }
    }
    }
}

/** The key of an event in the search: a reminder and a merge of the same note differ, and so do two times of one reminder. */
private fun eventKey(e: CalendarEvent): String = e.kind.name + ":" + e.sourceId + ":" + e.at

/** What the search found (D252), with its date: a tap shows that day in the month above. */
@Composable
private fun FoundList(found: List<CalendarEvent>, onGo: (CalendarEvent) -> Unit) {
    val formats = LocalDateTimeFormats.current
    Text(stringResource(R.string.calendar_search_found), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
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

@Composable
private fun MonthHeader(month: YearMonth, locale: Locale, onPrevious: () -> Unit, onNext: () -> Unit) {
    val title = remember(month, locale) {
        DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(month).replaceFirstChar { it.titlecase(locale) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.calendar_previous))
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.calendar_next))
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
            val marks = events.filter { it.kind != CalendarEventKind.TASK }
            if (marks.isNotEmpty()) {
                val label = stringResource(R.string.calendar_reminders_cd, marks.size)
                Row(Modifier.semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
                    marks.take(3).forEach { e -> Text(iconOf(e), Modifier.alpha(if (e.done) 0.4f else 1f), style = MaterialTheme.typography.labelSmall) }
                    if (marks.size > 3) Text("+" + (marks.size - 3), style = MaterialTheme.typography.labelSmall)
                }
            }
            // A task is a bar on every day from its start to its end (D239), in its note's colour, pale when done.
            events.filter { it.kind == CalendarEventKind.TASK }.take(3).forEach { e ->
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
        Text(stringResource(R.string.calendar_day_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    events.forEach { e ->
        // The reminder the person came from (D267) is framed; a tap opens its note or task, as for every line.
        val isMarked = marked.isNotEmpty() && e.kind == CalendarEventKind.REMINDER && e.sourceId == marked
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
    val time = if (e.kind == CalendarEventKind.TASK && e.endAt != null && e.endAt != e.at) formats.format(e.at) + " – " + formats.format(e.endAt)
    else formats.formatTime(e.at)
    Row(Modifier.alpha(if (e.done) 0.55f else 1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(iconOf(e))
        Text(time, style = MaterialTheme.typography.labelLarge)
        Text(e.noteTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
    }
}

/** A bell for a reminder, a money bag for a budget merge (D237). */
private fun iconOf(e: CalendarEvent): String = when (e.kind) {
    CalendarEventKind.REMINDER -> "🔔"
    CalendarEventKind.BUDGET_MERGE -> "💰"
    CalendarEventKind.TASK -> "📋"
}
