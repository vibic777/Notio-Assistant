package com.notio.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.notio.shared.assistant.AssistantAccess
import com.notio.shared.assistant.AssistantRight
import com.notio.shared.assistant.JournalEntry
import com.notio.shared.assistant.ModelPlace

/** How the tests find the parts of the assistant's pages. */
const val ASSISTANT_SWITCH = "assistant_switch"
const val ASSISTANT_RIGHT = "assistant_right_"
const val ASSISTANT_ACCESS = "assistant_access_"
const val ASSISTANT_FIELD = "assistant_field"
const val ASSISTANT_SEND = "assistant_send"
const val ASSISTANT_ROLLBACK = "assistant_rollback"
const val ASSISTANT_CONFIRM = "assistant_confirm"
const val ASSISTANT_BOT = "assistant_bot"
const val ASSISTANT_BOT_RIGHT = "assistant_bot_right_"
const val ASSISTANT_KEEP_CHAT = "assistant_keep_chat"
const val ASSISTANT_PROGRAM_URL = "assistant_program_url"
const val ASSISTANT_PROGRAM_CODE = "assistant_program_code"
const val ASSISTANT_PROGRAM_SHOW = "assistant_program_show"
const val ASSISTANT_PROGRAM_NEW = "assistant_program_new"
const val ASSISTANT_PERSON = "assistant_person_"
const val ASSISTANT_CLEAR_CHAT = "assistant_clear_chat"
const val ASSISTANT_DELETE_LINE = "assistant_delete_line"
const val ASSISTANT_BOT_RIGHTS_ALL = "assistant_bot_rights_all"
const val ASSISTANT_BOT_RIGHTS_NONE = "assistant_bot_rights_none"
const val ASSISTANT_BOT_CODE = "assistant_bot_code"
const val ASSISTANT_BOT_NEW_CODE = "assistant_bot_new_code"

private fun clockTime(millis: Long): String = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
private fun dayTime(millis: Long): String = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))

private fun rightKey(right: AssistantRight) = "assistant_right_" + right.name.lowercase()

private fun placeKey(place: ModelPlace) = when (place) {
    ModelPlace.THIS_COMPUTER -> "assistant_place_this"
    ModelPlace.HOME_NETWORK -> "assistant_place_home"
    ModelPlace.OUTSIDE -> "assistant_place_outside"
}

@Composable
private fun Part(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            if (hint != null) Hint(hint)
            content()
        }
    }
}

/**
 * Settings -> Assistant (D145, the branch `assistant`; the plan, section 10): the main switch, the connection, the rights, the
 * access of folders and notes, the journal with Roll back. While the switch is off nothing else is shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantPage(controller: NotioController) {
    val desk = controller.assistant
    val state by controller.state.collectAsState()
    LaunchedEffect(desk) { desk.reload() }
    Page { pad ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource("assistant_title"), style = MaterialTheme.typography.titleLarge)
                    Hint("assistant_hint")
                }
                Switch(desk.enabled, { desk.turn(it) }, modifier = Modifier.testTag(ASSISTANT_SWITCH))
            }
            if (!desk.enabled) return@Column
            // The chat is one press away from here too, not only from the main screen.
            Button(onClick = { controller.openCatalog("assistant_chat") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource("assistant_chat")) }

            Part("assistant_connection") {
                OutlinedTextField(
                    desk.url, { desk.changeUrl(it) }, label = { Text(stringResource("assistant_url")) }, singleLine = true,
                    supportingText = { Text(stringResource("assistant_url_hint")) }, modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(placeKey(desk.place)), style = MaterialTheme.typography.bodySmall, color = if (desk.place == ModelPlace.OUTSIDE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                // The key is shown as dots, with Show; Copy stands beside it, so that it is not lost (the owner, 2026-10-13).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PasswordTextField(desk.key, { desk.changeKey(it) }, label = { Text(stringResource("assistant_key")) }, modifier = Modifier.weight(1f))
                    if (desk.key.isNotBlank()) CopyIcon { desk.key }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { desk.checkConnection() }, enabled = !desk.checking && desk.url.isNotBlank()) { Text(stringResource("assistant_check")) }
                    if (desk.checking) CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)
                    desk.check?.let { found ->
                        if (found.error != null) SelectionContainer { Text(found.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        else Text(stringResource("assistant_check_ok", found.models.size), style = MaterialTheme.typography.bodySmall)
                    }
                }
                val models = desk.check?.models.orEmpty()
                if (models.isNotEmpty()) {
                    Text(stringResource("assistant_model"), style = MaterialTheme.typography.titleSmall)
                    models.forEach { name -> OptionRow(name, desk.model == name) { desk.changeModel(name) } }
                } else {
                    OutlinedTextField(desk.model, { desk.changeModel(it) }, label = { Text(stringResource("assistant_model")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }

            Part("assistant_chat_options") {
                OptionRow(stringResource("assistant_send_ctrl_enter"), !desk.sendOnEnter) { desk.changeSendOnEnter(false) }
                OptionRow(stringResource("assistant_send_enter"), desk.sendOnEnter) { desk.changeSendOnEnter(true) }
                Row(Modifier.fillMaxWidth().clickable { desk.changeKeepChat(!desk.keepChat) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(desk.keepChat, { desk.changeKeepChat(it) }, modifier = Modifier.testTag(ASSISTANT_KEEP_CHAT))
                    Text(stringResource("assistant_chat_keep"))
                }
                Hint("assistant_chat_keep_hint")
            }

            Part("assistant_rights", "assistant_rights_hint") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { desk.changeRights(desk.offeredRights.toSet()) }) { Text(stringResource("assistant_rights_all_on")) }
                    OutlinedButton(onClick = { desk.changeRights(emptySet()) }) { Text(stringResource("assistant_rights_all_off")) }
                }
                desk.offeredRights.forEach { right ->
                    val on = right in desk.rights
                    Row(
                        Modifier.fillMaxWidth().clickable { desk.changeRights(if (on) desk.rights - right else desk.rights + right) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(on, { desk.changeRights(if (it) desk.rights + right else desk.rights - right) }, modifier = Modifier.testTag(ASSISTANT_RIGHT + right.name))
                        Text(stringResource(rightKey(right)))
                    }
                }
                Hint("assistant_rights_never")
            }

            Part("assistant_access", "assistant_access_hint") {
                state.folders.forEach { row -> FolderAccess(controller, row.folder.id, row.folder.name) }
            }

            Part("assistant_telegram", "assistant_telegram_hint") {
                // D151: Notio reads the bot itself, or the person's own bot program reads it and passes messages on.
                OptionRow(stringResource("assistant_telegram_mode_direct"), !desk.botThroughProgram) { desk.changeBotMode(false) }
                OptionRow(stringResource("assistant_telegram_mode_program"), desk.botThroughProgram) { desk.changeBotMode(true) }
                if (!desk.botThroughProgram) {
                    // The key is typed whole and then set: every key is another bot, and its person is bound anew.
                    var draft by remember(desk.botKey) { mutableStateOf(desk.botKey) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PasswordTextField(draft, { draft = it }, label = { Text(stringResource("assistant_telegram_key")) }, modifier = Modifier.weight(1f))
                        if (desk.botKey.isNotBlank()) CopyIcon { desk.botKey }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { desk.changeBotKey(draft); if (draft.isNotBlank()) desk.turnBot(true) }, enabled = draft.trim() != desk.botKey) { Text(stringResource("assistant_check")) }
                        Text(stringResource("assistant_telegram_on"), Modifier.weight(1f))
                        Switch(desk.botOn, { desk.turnBot(it) }, enabled = desk.botKey.isNotBlank(), modifier = Modifier.testTag(ASSISTANT_BOT))
                    }
                } else {
                    ProgramLink(controller)
                }
                desk.botError?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                if (desk.botSet) {
                    desk.botName?.let { Text(stringResource("assistant_telegram_checked", it), style = MaterialTheme.typography.bodySmall) }
                    val owner = desk.botOwner
                    if (desk.botThroughProgram) {
                        ProgramPeople(desk)
                    } else if (owner == null) {
                        // D149: the code lives ten minutes and five wrong tries; then the person takes a new one.
                        if (desk.botCodeAlive()) {
                            SelectionContainer { Text(stringResource("assistant_telegram_bind", desk.botCode), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag(ASSISTANT_BOT_CODE)) }
                            Text(stringResource("assistant_telegram_code_until", clockTime(desk.botCodeUntil)), style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(stringResource("assistant_telegram_code_gone"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { desk.newBotCode() }, modifier = Modifier.testTag(ASSISTANT_BOT_NEW_CODE)) { Text(stringResource("assistant_telegram_new_code")) }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SelectionContainer(Modifier.weight(1f)) {
                                Text(stringResource("assistant_telegram_bound_who", desk.botOwnerName.ifBlank { "—" }, owner.toString()))
                            }
                            OutlinedButton(onClick = { desk.unbindBot() }) { Text(stringResource("assistant_telegram_unbind")) }
                        }
                        if (desk.botStrangers > 0) {
                            Text(stringResource("assistant_telegram_strangers", desk.botStrangers.toString(), dayTime(desk.botStrangerAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!desk.botThroughProgram) Hint("assistant_telegram_bind_hint")
                    Row(Modifier.fillMaxWidth().clickable { desk.changeBotWhenLocked(!desk.botWhenLocked) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(desk.botWhenLocked, { desk.changeBotWhenLocked(it) })
                        Text(stringResource("assistant_telegram_locked"))
                    }
                    Hint("assistant_telegram_locked_hint")
                    Row(Modifier.fillMaxWidth().clickable { desk.changeBotOutside(!desk.botOutside) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(desk.botOutside, { desk.changeBotOutside(it) })
                        Text(stringResource("assistant_telegram_outside"))
                    }
                    Text(stringResource("assistant_telegram_rights"), style = MaterialTheme.typography.titleSmall)
                    Hint("assistant_telegram_rights_hint")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { desk.changeBotRights(desk.offeredRights.toSet()) }, modifier = Modifier.testTag(ASSISTANT_BOT_RIGHTS_ALL)) { Text(stringResource("assistant_rights_all_on")) }
                        OutlinedButton(onClick = { desk.changeBotRights(emptySet()) }, modifier = Modifier.testTag(ASSISTANT_BOT_RIGHTS_NONE)) { Text(stringResource("assistant_rights_all_off")) }
                    }
                    desk.offeredRights.forEach { right ->
                        val allowed = right in desk.botRights
                        Row(Modifier.fillMaxWidth().clickable { desk.changeBotRights(if (allowed) desk.botRights - right else desk.botRights + right) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(allowed, { desk.changeBotRights(if (it) desk.botRights + right else desk.botRights - right) }, modifier = Modifier.testTag(ASSISTANT_BOT_RIGHT + right.name))
                            Text(stringResource(rightKey(right)))
                        }
                    }
                }
            }

            Part("assistant_journal", "assistant_journal_hint") {
                if (desk.journal.isEmpty()) Text(stringResource("assistant_journal_empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                desk.journal.forEach { entry -> JournalLine(controller, entry) }
                if (desk.journal.isNotEmpty()) TextButton(onClick = { desk.clearJournal() }) { Text(stringResource("assistant_journal_clear")) }
            }
        }
    }
}

/**
 * Through the bot program (D151): its address, the code of 12 characters - hidden by dots, Show / Hide, Copy, New code - and
 * the switch of the bot.
 */
@Composable
private fun ProgramLink(controller: NotioController) {
    val desk = controller.assistant
    var address by remember(desk.relayUrl) { mutableStateOf(desk.relayUrl) }
    OutlinedTextField(
        address, { address = it }, label = { Text(stringResource("assistant_telegram_program_url")) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(ASSISTANT_PROGRAM_URL),
    )
    var shown by remember { mutableStateOf(false) }
    var askNew by remember { mutableStateOf(false) }
    Text(stringResource("assistant_telegram_program_code"), style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                if (shown) desk.relayCode else desk.relayCode.map { if (it == '-') '-' else '\u2022' }.joinToString(""),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(ASSISTANT_PROGRAM_CODE),
            )
        }
        IconButton(onClick = { shown = !shown }, modifier = Modifier.testTag(ASSISTANT_PROGRAM_SHOW)) {
            Icon(if (shown) AppIcons.VisibilityOff else AppIcons.Visibility, contentDescription = stringResource(if (shown) "assistant_telegram_program_hide" else "assistant_telegram_program_show"))
        }
        CopyIcon { desk.relayCode }
        OutlinedButton(onClick = { askNew = true }, modifier = Modifier.testTag(ASSISTANT_PROGRAM_NEW)) { Text(stringResource("assistant_telegram_new_code")) }
    }
    Hint("assistant_telegram_program_hint")
    if (askNew) {
        ConfirmDialog(
            controller.strings, stringResource("assistant_telegram_new_code"), stringResource("assistant_telegram_program_new_ask"), stringResource("assistant_telegram_new_code"),
            onCancel = { askNew = false }, onConfirm = { askNew = false; desk.newRelayCode() },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { desk.changeRelayUrl(address); desk.turnBot(true) }, enabled = address.isNotBlank()) { Text(stringResource("assistant_check")) }
        Text(stringResource("assistant_telegram_on"), Modifier.weight(1f))
        Switch(desk.botOn, { desk.turnBot(it) }, enabled = desk.relayCode.isNotBlank(), modifier = Modifier.testTag(ASSISTANT_BOT))
    }
}

/** Who wrote to this Notio through the bot program: allowed, and asking for access - with Allow, Forbid and Remove (D151). */
@Composable
private fun ProgramPeople(desk: AssistantDesk) {
    Text(stringResource("assistant_telegram_people"), style = MaterialTheme.typography.titleSmall)
    Hint("assistant_telegram_people_hint")
    if (desk.relayPeople.isEmpty()) Text(stringResource("assistant_telegram_people_none"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    desk.relayPeople.sortedBy { it.allowed }.forEach { person ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(person.allowed, { desk.allowPerson(person.id, it) }, modifier = Modifier.testTag(ASSISTANT_PERSON + person.id))
            Column(Modifier.weight(1f)) {
                SelectionContainer { Text(stringResource("assistant_telegram_person", person.name.ifBlank { "\u2014" }, person.id.toString())) }
                Text(
                    stringResource(if (person.allowed) "assistant_telegram_person_allowed" else "assistant_telegram_person_waits"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (person.allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = { desk.removePerson(person.id) }) { Text(stringResource("assistant_telegram_person_remove")) }
        }
    }
}

/** One folder on the page of access: its switch, "Apply to all notes", and its notes with their own switches. */
@Composable
private fun FolderAccess(controller: NotioController, folderId: String, name: String) {
    val desk = controller.assistant
    var open by remember(folderId) { mutableStateOf(false) }
    var said by remember(folderId) { mutableStateOf<Int?>(null) }
    val notes = remember(folderId, desk.access, open) { controller.notesOf(folderId) }
    val flags = desk.access[folderId] ?: AssistantAccess.NONE
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (open) "▾" else "▸", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable { open = !open }.padding(end = 4.dp))
            Column(Modifier.weight(1f).clickable { open = !open }) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource("assistant_access_count", notes.count { (desk.access[it.id] ?: 0) and AssistantAccess.ASSISTANT != 0 }, notes.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AccessSwitches(desk, folderId, flags)
        }
        Row(Modifier.padding(start = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { desk.applyToFolder(folderId) { said = it } }) { Text(stringResource("assistant_access_apply")) }
            said?.let { Text(stringResource("assistant_access_applied", it), style = MaterialTheme.typography.bodySmall) }
        }
        if (open) {
            Column(Modifier.padding(start = 28.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource("assistant_access_notes"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                notes.forEach { note ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(note.title, Modifier.weight(1f), maxLines = 1)
                        AccessSwitches(desk, note.id, desk.access[note.id] ?: AssistantAccess.NONE)
                    }
                }
            }
        }
    }
}

/**
 * The switch "Assistant" of a note or a folder and, while it is on, what is under it: "May go to Telegram" (when the bot is set)
 * and "May go to an outside model" (when the model is an outside one). Apart from them, "Reminders to Telegram", which works
 * with the assistant off too (the plan, section 8).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccessSwitches(desk: AssistantDesk, id: String, flags: Int) {
    val on = flags and AssistantAccess.ASSISTANT != 0
    @Composable
    fun mark(bit: Int, word: String) {
        val set = flags and bit != 0
        Row(Modifier.clickable { desk.setAccess(listOf(id), flags xor bit) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(set, { desk.setAccess(listOf(id), flags xor bit) }, modifier = Modifier.testTag(ASSISTANT_ACCESS + bit + "_" + id))
            Text(stringResource(word), style = MaterialTheme.typography.bodySmall)
        }
    }
    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (desk.botSet || flags and AssistantAccess.REMINDERS_TELEGRAM != 0) mark(AssistantAccess.REMINDERS_TELEGRAM, "assistant_access_reminders")
        if (on && (desk.botSet || flags and AssistantAccess.TELEGRAM != 0)) mark(AssistantAccess.TELEGRAM, "assistant_access_telegram")
        if (on && (desk.place == ModelPlace.OUTSIDE || flags and AssistantAccess.EXTERNAL != 0)) mark(AssistantAccess.EXTERNAL, "assistant_access_external")
        val word = stringResource("assistant_title")
        HoverTip(word, topic = "assistant") {
            // Turned off, the note is closed to the assistant in every way; the reminders to Telegram are a matter of their own and stay.
            Switch(on, { desk.setAccess(listOf(id), if (it) flags or AssistantAccess.ASSISTANT else flags and AssistantAccess.REMINDERS_TELEGRAM) }, modifier = Modifier.testTag(ASSISTANT_ACCESS + id))
        }
    }
}

/** One record of the journal: when, the request, what was done, and Roll back. */
@Composable
private fun JournalLine(controller: NotioController, entry: JournalEntry) {
    val desk = controller.assistant
    var conflict by remember(entry.id) { mutableStateOf<List<String>?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Format.dateTime(entry.at) + "  ·  " + entry.source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry.request, style = MaterialTheme.typography.bodyMedium)
            }
            if (entry.rolledBack) Text(stringResource("assistant_rolled_back"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else OutlinedButton(onClick = { desk.rollback(entry.id) { result -> conflict = result.conflicts.takeIf { !result.done && it.isNotEmpty() } } }, modifier = Modifier.testTag(ASSISTANT_ROLLBACK)) {
                Text(stringResource("assistant_rollback"))
            }
        }
        entry.body.ops.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        conflict?.let { titles ->
            Text(stringResource("assistant_rollback_conflict", titles.joinToString(", ")), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { desk.rollback(entry.id, force = true) { conflict = null } }) { Text(stringResource("assistant_rollback_force")) }
        }
    }
}

/**
 * The chat with the assistant (the plan, section 6, variant A): the person writes what to do, the model asks Notio for commands,
 * and under an answer that changed something stands Roll back. What the model says is shown as it is; what it did is in the journal.
 */
@Composable
fun AssistantChatPage(controller: NotioController) {
    val desk = controller.assistant
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(desk.lines.size, desk.busy) { if (desk.lines.isNotEmpty()) list.animateScrollToItem(desk.lines.size - 1) }
    Page { pad ->
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(placeKey(desk.place)), style = MaterialTheme.typography.bodySmall, color = if (desk.place == ModelPlace.OUTSIDE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            // What the assistant is allowed and what is open to it, so that "it does nothing" is seen at a glance.
            val state by controller.state.collectAsState()
            LaunchedEffect(desk) { desk.reload() }
            val folderIds = state.folders.map { it.folder.id }.toSet()
            val opened = desk.access.filterValues { it and AssistantAccess.ASSISTANT != 0 }.keys
            val openFolders = opened.count { it in folderIds }
            var askClear by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    when {
                        !desk.ready -> Notice("assistant_not_ready") { controller.openCatalog("assistant") }
                        desk.rights.isEmpty() -> Notice("assistant_no_rights") { controller.openCatalog("assistant") }
                        openFolders == 0 -> Notice("assistant_nothing_open") { controller.openCatalog("assistant") }
                        else -> Text(
                            stringResource("assistant_status", desk.offeredRights.filter { it in desk.rights }.map { stringResource(rightKey(it)).substringBefore(':') }.joinToString(", "), openFolders, opened.size - openFolders),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // D150: every message goes at once; the notes and the journal stay, so it asks first.
                if (desk.lines.isNotEmpty()) TextButton(onClick = { askClear = true }, enabled = !desk.busy, modifier = Modifier.testTag(ASSISTANT_CLEAR_CHAT)) { Text(stringResource("assistant_chat_clear")) }
            }
            if (askClear) {
                ConfirmDialog(
                    controller.strings, stringResource("assistant_chat_clear"), stringResource("assistant_chat_clear_ask"), stringResource("assistant_chat_clear"),
                    onCancel = { askClear = false }, onConfirm = { askClear = false; desk.clearChat() },
                )
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(desk.lines.size) { index -> Bubble(desk, desk.lines[index]) }
                if (desk.busy) item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                        Text(stringResource("assistant_working"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    draft, { draft = it }, placeholder = { Text(stringResource("assistant_chat_field")) }, maxLines = 5,
                    enabled = desk.ready,
                    supportingText = { Text(stringResource(if (desk.sendOnEnter) "assistant_send_enter" else "assistant_send_ctrl_enter")) },
                    modifier = Modifier.weight(1f).testTag(ASSISTANT_FIELD)
                        // The key that sends (Settings -> Assistant -> Chat): Enter, or Ctrl+Enter; the other one starts a new line.
                        .onPreviewKeyEvent { e ->
                            val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
                            val sends = enter && e.type == KeyEventType.KeyDown && !e.isShiftPressed && e.isCtrlPressed != desk.sendOnEnter
                            if (sends && !desk.busy && desk.ready && draft.isNotBlank()) { desk.send(draft); draft = "" }
                            sends
                        },
                )
                if (desk.busy) OutlinedButton(onClick = { desk.stop() }) { Text(stringResource("assistant_stop")) }
                else Button(onClick = { desk.send(draft); draft = "" }, enabled = desk.ready && draft.isNotBlank(), modifier = Modifier.testTag(ASSISTANT_SEND)) { Text(stringResource("assistant_send")) }
            }
        }
    }
}

@Composable
private fun Notice(text: String, onSetUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(text), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onSetUp) { Text(stringResource("assistant_setup")) }
    }
}

/** One line of the chat: the person's on the right, the answer on the left with what it changed and Roll back. */
@Composable
private fun Bubble(desk: AssistantDesk, line: ChatLine) {
    // D150: Copy and Delete stand over the message under the mouse, as the icons of a card do.
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(Modifier.fillMaxWidth().hoverable(hover), contentAlignment = if (line.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Card(
            Modifier.widthIn(max = 720.dp),
            colors = CardDefaults.cardColors(containerColor = if (line.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    SelectionContainer(Modifier.weight(1f, fill = false)) { Text(line.text.ifBlank { "…" }, color = if (line.failed) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified) }
                    // The place of the icons is kept while they are hidden, so the message does not jump under the mouse.
                    if (hovered) {
                        Row(Modifier.padding(start = 4.dp)) {
                            CopyIcon(small = true) { line.text }
                            IconButton(onClick = { desk.deleteLine(line) }, modifier = Modifier.size(36.dp).testTag(ASSISTANT_DELETE_LINE)) {
                                Icon(AppIcons.Delete, contentDescription = stringResource("assistant_chat_delete"), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        Spacer(Modifier.padding(start = 4.dp).width(72.dp))
                    }
                }
                // How the answer was made: with commands, or with words alone - then nothing was done.
                if (!line.mine && !line.failed) {
                    Text(
                        if (line.commands > 0) stringResource("assistant_commands_done", line.commands) else stringResource("assistant_no_commands"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The request stopped for the person's yes (many notes at once, the sums of a budget): the same request goes on with it.
                if (line.confirm != null) {
                    Text(stringResource("assistant_confirm_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { desk.confirm(line) }, enabled = !desk.busy, modifier = Modifier.testTag(ASSISTANT_CONFIRM)) { Text(stringResource("assistant_confirm")) }
                }
                if (line.entryId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource("assistant_changed", line.changed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (line.rolledBack) Text(stringResource("assistant_rolled_back"), style = MaterialTheme.typography.labelLarge)
                        else {
                            var conflict by remember(line.entryId) { mutableStateOf(false) }
                            OutlinedButton(onClick = { desk.rollback(line.entryId, force = conflict) { conflict = !it.done && it.conflicts.isNotEmpty() } }, modifier = Modifier.testTag(ASSISTANT_ROLLBACK)) {
                                Text(stringResource(if (conflict) "assistant_rollback_force" else "assistant_rollback"))
                            }
                        }
                    }
                }
            }
        }
    }
}
