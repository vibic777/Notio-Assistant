package com.notio.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.notio.shared.AppLog
import com.notio.shared.assistant.AgentReply
import com.notio.shared.assistant.AssistantAccess
import com.notio.shared.assistant.AssistantAgent
import com.notio.shared.assistant.AssistantCommands
import com.notio.shared.assistant.AssistantHost
import com.notio.shared.assistant.AssistantJournal
import com.notio.shared.assistant.AssistantRight
import com.notio.shared.assistant.BotLink
import com.notio.shared.assistant.BotSettings
import com.notio.shared.assistant.RelayApi
import kotlinx.serialization.builtins.ListSerializer
import com.notio.shared.assistant.Channel
import com.notio.shared.assistant.ChatMessage
import com.notio.shared.assistant.HttpModelClient
import com.notio.shared.assistant.JournalEntry
import com.notio.shared.assistant.ModelException
import com.notio.shared.assistant.ModelPlace
import com.notio.shared.assistant.ModelSettings
import com.notio.shared.assistant.RollbackResult
import com.notio.shared.assistant.TelegramApi
import com.notio.shared.assistant.TelegramBot
import com.notio.shared.assistant.TelegramException
import com.notio.shared.data.NotioDatabase
import com.notio.shared.data.NotioEditor
import com.notio.shared.sync.SecretBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One line of the chat: the person's words or the answer, with the record of what the answer changed. Kept as JSON (D150). */
@Serializable
data class ChatLine(
    val mine: Boolean,
    val text: String,
    val failed: Boolean = false,
    /** The record of the journal of this answer, when it changed something. */
    val entryId: String? = null,
    val changed: Int = 0,
    val rolledBack: Boolean = false,
    /** How many commands the model asked for while it answered; 0 - it answered with words alone. */
    val commands: Int = 0,
    /** The request this answer stopped at for the lack of the person's yes; "Confirm" sends it again with the yes. */
    val confirm: String? = null,
    val id: String = java.util.UUID.randomUUID().toString(),
    val at: Long = System.currentTimeMillis(),
)

/** A person who wrote to this Notio through the bot program (D151): allowed by the person of Notio, or waiting. */
@Serializable
data class RelayPerson(val id: Long, val name: String, val allowed: Boolean = false, val at: Long = System.currentTimeMillis())

/** What "Check" of the connection found: the models of the server, or why it could not be asked. */
data class ModelCheck(val models: List<String> = emptyList(), val error: String? = null)

/**
 * The assistant in the window (D145 - D148, the branch `assistant`; docs/ASSISTANT_PLAN_RU.md): its settings of this computer,
 * the chat, the access of notes, the journal and the Telegram bot, as the pages draw them. The work itself is
 * `com.notio.shared.assistant`. The settings are this computer's: in no backup and no sync. The keys - of the model and of the
 * bot - are kept sealed, as the other secrets of Notio.
 */
class AssistantDesk(
    private val db: NotioDatabase,
    editor: NotioEditor,
    private val scope: CoroutineScope,
    private val get: (String) -> String?,
    private val set: (String, String) -> Unit,
    private val box: SecretBox,
    private val host: AssistantHost,
    /** The texts of the app in its language now: the name of a text and what goes into it. */
    private val words: (String, String) -> String,
    /** After the notes were changed: the window reads them again. */
    private val changed: () -> Unit,
) {
    private fun secret(name: String) = get(name)?.takeIf { it.isNotEmpty() }?.let { sealed -> runCatching { box.open(sealed) }.getOrNull() }.orEmpty()

    var enabled by mutableStateOf(get(ON) == "true")
        private set
    var url by mutableStateOf(get(URL) ?: DEFAULT_URL)
        private set
    var key by mutableStateOf(secret(KEY))
        private set
    var model by mutableStateOf(get(MODEL).orEmpty())
        private set
    var rights by mutableStateOf(AssistantRight.decode(get(RIGHTS)))
        private set

    /** Which key sends a request of the chat: Enter, or Ctrl+Enter (the start; the owner, 2026-10-13) with Enter for a new line. */
    var sendOnEnter by mutableStateOf(get(SEND_ON_ENTER) == "true")
        private set

    /** Whether the chat is kept after a restart (D150): on from the start. */
    var keepChat by mutableStateOf(get(KEEP_CHAT) != "false")
        private set

    // ---- the Telegram bot (D148)
    var botKey by mutableStateOf(secret(BOT_KEY))
        private set
    var botOn by mutableStateOf(get(BOT_ON) == "true")
        private set
    /** The Telegram id of the person the bot listens to; `null` - nobody yet. */
    var botOwner by mutableStateOf(get(BOT_OWNER)?.toLongOrNull())
        private set
    /** The name and @name the person had in Telegram when the code came (D149); the number above is what the bot trusts. */
    var botOwnerName by mutableStateOf(get(BOT_OWNER_NAME).orEmpty())
        private set
    /** The one-time code that binds the bot to whoever sends it; empty - burnt or used. */
    var botCode by mutableStateOf(get(BOT_CODE).orEmpty())
        private set
    /** Till when [botCode] may be sent. */
    var botCodeUntil by mutableStateOf(get(BOT_CODE_UNTIL)?.toLongOrNull() ?: 0L)
        private set
    /** How many messages of strangers the bot left unanswered since its person was bound, and when the last came. */
    var botStrangers by mutableStateOf(get(BOT_STRANGERS)?.toIntOrNull() ?: 0)
        private set
    var botStrangerAt by mutableStateOf(get(BOT_STRANGER_AT)?.toLongOrNull() ?: 0L)
        private set
    var botWhenLocked by mutableStateOf(get(BOT_LOCKED) == "true")
        private set
    var botOutside by mutableStateOf(get(BOT_OUTSIDE) == "true")
        private set
    /** What the assistant may do when the request came from Telegram: apart from [rights], nothing until allowed. */
    var botRights by mutableStateOf(AssistantRight.decode(get(BOT_RIGHTS)))
        private set
    /** D151: `false` - Notio reads the bot itself by its key; `true` - through the person's own bot program (TelegramBot). */
    var botThroughProgram by mutableStateOf(get(BOT_MODE) == MODE_PROGRAM)
        private set
    var relayUrl by mutableStateOf(get(RELAY_URL) ?: RelayApi.DEFAULT_URL)
        private set
    /** The code of 12 characters the admin of the bot program enters; kept sealed, as a key. */
    var relayCode by mutableStateOf(secret(RELAY_CODE))
        private set
    /** Who wrote through the bot program: allowed, or asking for access. */
    var relayPeople by mutableStateOf(runCatching { chatJson.decodeFromString(ListSerializer(RelayPerson.serializer()), get(RELAY_PEOPLE).orEmpty()) }.getOrDefault(emptyList()))
        private set

    /** The name of the bot when its key was checked, or why Telegram could not be asked. */
    var botName by mutableStateOf<String?>(null)
        private set
    var botError by mutableStateOf<String?>(null)
        private set

    /** The id of a note or a folder -> its access; read again after every change. */
    var access by mutableStateOf(emptyMap<String, Int>())
        private set
    var journal by mutableStateOf(emptyList<JournalEntry>())
        private set
    var check by mutableStateOf<ModelCheck?>(null)
        private set
    var checking by mutableStateOf(false)
        private set

    val lines = mutableStateListOf<ChatLine>()
    var busy by mutableStateOf(false)
        private set

    @Volatile private var stopAsked = false
    @Volatile private var worker: Thread? = null

    private val log: (String) -> Unit = { line -> AppLog.i("assistant", line) }
    private val journalStore = AssistantJournal(db, host = host)
    private val commands = AssistantCommands(db, editor, journalStore, { rights }, host, log)
    private val commandsOfBot = AssistantCommands(db, editor, journalStore, { botRights }, host, log)
    private var bot: TelegramBot? = null

    val place: ModelPlace get() = ModelPlace.of(url)

    /** The rights that have commands behind them: only these are offered on the page. */
    val offeredRights: List<AssistantRight> = AssistantRight.entries.filter { right -> AssistantCommands.ALL.any { it.right == right } }

    /** Whether a request can be sent: on, with an address and a model. */
    val ready: Boolean get() = enabled && url.isNotBlank() && model.isNotBlank()

    /** Whether the bot can be bound and asked: on, with a key. */
    val botSet: Boolean get() = enabled && botOn && if (botThroughProgram) relayCode.isNotBlank() && relayUrl.isNotBlank() else botKey.isNotBlank()

    // ---- through the bot program (D151)
    fun changeBotMode(throughProgram: Boolean) {
        botThroughProgram = throughProgram
        set(BOT_MODE, if (throughProgram) MODE_PROGRAM else "direct")
        botName = null
        botError = null
        if (throughProgram && relayCode.isBlank()) newRelayCode()
        if (botOn) startBot()
    }

    fun changeRelayUrl(value: String) {
        relayUrl = value.trim()
        set(RELAY_URL, relayUrl)
        botName = null
        botError = null
        if (botOn && botThroughProgram) startBot()
    }

    /** A new code: the old one stops at once, and the admin of the bot program enters the new one. */
    fun newRelayCode() {
        relayCode = com.notio.shared.sync.SyncCrypto.newCode()
        set(RELAY_CODE, box.seal(relayCode))
        botName = null
        botError = null
        if (botOn && botThroughProgram) startBot()
    }

    private fun savePeople(next: List<RelayPerson>) {
        relayPeople = next
        set(RELAY_PEOPLE, chatJson.encodeToString(ListSerializer(RelayPerson.serializer()), next))
    }

    fun allowPerson(id: Long, allowed: Boolean) = savePeople(relayPeople.map { if (it.id == id) it.copy(allowed = allowed) else it })
    fun removePerson(id: Long) = savePeople(relayPeople.filter { it.id != id })

    init {
        if (enabled) { reload(); startBot() }
        if (keepChat) loadChat()
    }

    // ---- the kept chat (D150)

    private fun loadChat() {
        scope.launch {
            val kept = withContext(Dispatchers.IO) {
                runCatching { db.assistantChat() }.getOrElse { AppLog.e("assistant", "the kept chat could not be read", it); emptyList() }
                    .mapNotNull { (_, body) -> runCatching { chatJson.decodeFromString(ChatLine.serializer(), body) }.getOrNull() }
            }
            // Lines written while it was read stay after the kept ones.
            if (kept.isNotEmpty()) lines.addAll(0, kept.filter { k -> lines.none { it.id == k.id } })
        }
    }

    /** Puts a line into the chat, or puts it in place of the line with its id; the kept chat follows. */
    private fun putLine(line: ChatLine) {
        val at = lines.indexOfFirst { it.id == line.id }
        if (at >= 0) lines[at] = line else lines += line
        if (!keepChat) return
        val body = chatJson.encodeToString(ChatLine.serializer(), line)
        scope.launch(Dispatchers.IO) { runCatching { db.putAssistantChat(line.id, line.at, body, KEPT_LINES) }.onFailure { AppLog.e("assistant", "a line of the chat was not kept", it) } }
    }

    /** What the model remembers of the talk: the person's words and the answers that did not fail, the last ones. */
    private fun talk(): List<ChatMessage> =
        lines.filter { it.mine || !it.failed }.takeLast(HISTORY).map { ChatMessage(if (it.mine) "user" else "assistant", it.text) }

    fun changeKeepChat(on: Boolean) {
        keepChat = on
        set(KEEP_CHAT, on.toString())
        val now = lines.toList()
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (on) now.forEach { db.putAssistantChat(it.id, it.at, chatJson.encodeToString(ChatLine.serializer(), it), KEPT_LINES) } else db.deleteAssistantChat(null)
            }
        }
    }

    /** Deletes one message of the chat: from the window, from what is kept and from what the model remembers. */
    fun deleteLine(line: ChatLine) {
        lines.removeAll { it.id == line.id }
        scope.launch(Dispatchers.IO) { runCatching { db.deleteAssistantChat(line.id) } }
    }

    fun turn(on: Boolean) { enabled = on; set(ON, on.toString()); if (on) { reload(); startBot() } else stopBot() }
    fun changeUrl(value: String) { url = value; set(URL, value.trim()); check = null }
    fun changeKey(value: String) { key = value; set(KEY, if (value.isBlank()) "" else box.seal(value.trim())) }
    fun changeModel(value: String) { model = value; set(MODEL, value) }
    fun changeRights(next: Set<AssistantRight>) { rights = next; set(RIGHTS, AssistantRight.encode(next)) }
    fun changeSendOnEnter(on: Boolean) { sendOnEnter = on; set(SEND_ON_ENTER, on.toString()) }

    fun changeBotKey(value: String) {
        botKey = value
        set(BOT_KEY, if (value.isBlank()) "" else box.seal(value.trim()))
        botName = null
        botError = null
        // Another key is another bot: whoever was bound to the old one is not bound to this one.
        unbindBot()
        if (botOn) startBot()
    }

    fun turnBot(on: Boolean) { botOn = on; set(BOT_ON, on.toString()); if (on) startBot() else stopBot() }
    fun changeBotWhenLocked(on: Boolean) { botWhenLocked = on; set(BOT_LOCKED, on.toString()) }
    fun changeBotOutside(on: Boolean) { botOutside = on; set(BOT_OUTSIDE, on.toString()) }
    fun changeBotRights(next: Set<AssistantRight>) { botRights = next; set(BOT_RIGHTS, AssistantRight.encode(next)) }

    /** The bot forgets its person and waits for a new code, which may be sent for ten minutes. */
    fun unbindBot() {
        botOwner = null
        set(BOT_OWNER, "")
        botOwnerName = ""
        set(BOT_OWNER_NAME, "")
        botStrangers = 0
        set(BOT_STRANGERS, "0")
        newBotCode()
    }

    /** A new code for the person to send (the old one burnt or its time ran out). */
    fun newBotCode() {
        botCode = (100_000 + java.security.SecureRandom().nextInt(900_000)).toString()
        set(BOT_CODE, botCode)
        botCodeUntil = System.currentTimeMillis() + TelegramBot.CODE_MILLIS
        set(BOT_CODE_UNTIL, botCodeUntil.toString())
    }

    /** Whether [botCode] may still be sent: the page shows it, or "New code". */
    fun botCodeAlive(now: Long = System.currentTimeMillis()): Boolean = botCode.isNotEmpty() && now <= botCodeUntil

    private fun startBot() {
        stopBot()
        if (!botSet) return
        val program = botThroughProgram
        if (!program && botOwner == null && !botCodeAlive()) newBotCode()
        botError = null
        val api: BotLink = if (program) RelayApi(relayUrl, relayCode) else TelegramApi(botKey)
        val made = TelegramBot(
            api,
            settings = {
                BotSettings(
                    botOwner, botCode, botCodeUntil, botWhenLocked, botOutside, place == ModelPlace.OUTSIDE, ready,
                    people = if (program) relayPeople.filter { it.allowed }.map { it.id }.toSet() else null,
                )
            },
            knock = { id, name ->
                scope.launch {
                    val known = relayPeople.firstOrNull { it.id == id }
                    if (known == null) savePeople(relayPeople + RelayPerson(id, name))
                    else if (name.isNotBlank() && known.name != name) savePeople(relayPeople.map { if (it.id == id) it.copy(name = name) else it })
                }
            },
            bind = { id, name ->
                scope.launch {
                    botOwner = id; set(BOT_OWNER, id.toString())
                    botOwnerName = name; set(BOT_OWNER_NAME, name)
                    botCode = ""; set(BOT_CODE, "")
                    botStrangers = 0; set(BOT_STRANGERS, "0")
                }
            },
            burnCode = { scope.launch { botCode = ""; set(BOT_CODE, "") } },
            stranger = {
                scope.launch {
                    botStrangers += 1; set(BOT_STRANGERS, botStrangers.toString())
                    botStrangerAt = System.currentTimeMillis(); set(BOT_STRANGER_AT, botStrangerAt.toString())
                }
            },
            words = { word, arg -> words("assistant_bot_" + word.name.lowercase(), arg) },
            locked = { host.locked() },
            ask = { earlier, request, channel, confirmed ->
                log("request from Telegram: rights " + AssistantRight.encode(botRights).ifEmpty { "none" } + ", commands offered " + commandsOfBot.tools().size)
                AssistantAgent(commandsOfBot, journalStore, HttpModelClient(ModelSettings(url, key.ifBlank { null }, model))).ask(earlier, request, channel, confirmed)
                    .also { reply -> if (reply.entry != null) changed(); reload() }
            },
            journal = journalStore,
            log = log,
        )
        bot = made
        made.start { why -> scope.launch { botError = why } }
        // The name of the bot, to show on the page that the key is right.
        scope.launch {
            try { botName = withContext(Dispatchers.IO) { api.me() } } catch (e: TelegramException) { botError = e.message } catch (e: Throwable) { botError = e.javaClass.simpleName }
        }
    }

    private fun stopBot() { bot?.stop(); bot = null }

    /** Called when Notio closes. */
    fun close() = stopBot()

    /**
     * A reminder rings: when its note has "Reminders to Telegram" and the bot has its person, the words go to Telegram - the
     * title of the note, nothing of its text.
     */
    fun remindToTelegram(noteId: String, title: String) {
        val current = bot ?: return
        scope.launch(Dispatchers.IO) {
            if ((db.assistantAccess()[noteId] ?: 0) and AssistantAccess.REMINDERS_TELEGRAM != 0) current.remind(words("assistant_bot_reminder", title))
        }
    }

    /** Reads the access and the journal again (the pages call it when they open). */
    fun reload() {
        scope.launch {
            val (a, j) = withContext(Dispatchers.IO) { db.assistantAccess() to journalStore.entries() }
            access = a
            journal = j
        }
    }

    /** "Check": asks the server for its models. */
    fun checkConnection() {
        if (checking) return
        checking = true
        val settings = ModelSettings(url, key.ifBlank { null }, model)
        scope.launch {
            check = withContext(Dispatchers.IO) {
                try {
                    ModelCheck(HttpModelClient(settings, java.time.Duration.ofSeconds(15)).models())
                } catch (e: ModelException) {
                    ModelCheck(error = e.message)
                } catch (e: Throwable) {
                    AppLog.e("assistant", "the check of the connection failed", e)
                    ModelCheck(error = e.message ?: e.toString())
                }
            }
            checking = false
            // The only model of the server is chosen by itself.
            check?.models?.singleOrNull()?.let { if (model.isBlank()) changeModel(it) }
        }
    }

    /** The access of the notes and folders [ids]. */
    fun setAccess(ids: Collection<String>, flags: Int, done: () -> Unit = {}) {
        scope.launch {
            access = withContext(Dispatchers.IO) { db.setAssistantAccess(ids, flags); db.assistantAccess() }
            done()
        }
    }

    /** "Apply to all notes": the access of the folder goes to every note of it that is not in the trash; [done] gets how many. */
    fun applyToFolder(folderId: String, done: (Int) -> Unit) {
        val flags = access[folderId] ?: AssistantAccess.NONE
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                val notes = db.notesOf(folderId).filter { it.deletedAt == null }.map { it.id }
                db.setAssistantAccess(notes, flags)
                notes.size
            }
            access = withContext(Dispatchers.IO) { db.assistantAccess() }
            done(count)
        }
    }

    /** One request of the person from the chat; [confirmed] - sent again with the person's yes ("Confirm and continue"). */
    fun send(text: String, confirmed: Boolean = false) {
        val request = text.trim()
        if (request.isEmpty() || busy || !ready) return
        // The talk before this request, as the lines of the chat have it (a request sent again with the yes is in it already).
        val before = talk()
        if (!confirmed) putLine(ChatLine(mine = true, text = request))
        busy = true
        stopAsked = false
        val settings = ModelSettings(url, key.ifBlank { null }, model)
        val channel = Channel("chat", externalModel = settings.place == ModelPlace.OUTSIDE)
        // The log of the app tells how a request went - never its words and never a note (the owner, 2026-10-13: "the log holds
        // the work of the assistant too?").
        log(
            "request from the chat: the model at " + settings.place.name.lowercase() + ", rights " + AssistantRight.encode(rights).ifEmpty { "none" } +
                ", commands offered " + commands.tools().size + ", open to it " + access.count { it.value and AssistantAccess.ASSISTANT != 0 } + " notes and folders" +
                if (confirmed) ", confirmed by the person" else "",
        )
        scope.launch {
            // Whatever goes wrong, the chat gets an answer and stops waiting: a failure must not leave "the model is working" for ever.
            val reply = try {
                withContext(Dispatchers.IO) {
                    worker = Thread.currentThread()
                    try {
                        AssistantAgent(commands, journalStore, HttpModelClient(settings)).ask(before, request, channel, confirmed) { stopAsked }
                    } finally {
                        worker = null
                        // The mark of Stop does not stay on a thread that goes back to the pool.
                        Thread.interrupted()
                    }
                }
            } catch (e: Throwable) {
                AppLog.e("assistant", "a request of the chat failed", e)
                AgentReply("The request failed: " + (e.message ?: e.toString()), failed = true)
            }
            val changedNow = reply.entry?.body?.changes ?: 0
            log("answer: commands " + reply.commands + ", changed " + changedNow + (if (reply.needsConfirm) ", waits for the person's yes" else "") + (if (reply.failed) ", failed - " + reply.text.take(200) else ""))
            putLine(
                ChatLine(
                    mine = false, text = reply.text, failed = reply.failed, entryId = reply.entry?.id, changed = changedNow, commands = reply.commands,
                    confirm = request.takeIf { reply.needsConfirm },
                ),
            )
            busy = false
            if (reply.entry != null) changed()
            reload()
        }
    }

    /** "Confirm and continue" under an answer that stopped for the person's yes: the same request, with the yes. */
    fun confirm(line: ChatLine) {
        val request = line.confirm ?: return
        if (busy || !ready) return
        putLine(line.copy(confirm = null))
        send(request, confirmed = true)
    }

    /**
     * Stop of the chat: the request ends now, not when the model has answered - the question to the model is broken off. What
     * the request changed before that is in the journal as one record.
     */
    fun stop() {
        stopAsked = true
        worker?.interrupt()
    }

    /** "Clear the chat": every message goes, from the window and from what is kept; the notes and the journal stay. */
    fun clearChat() {
        lines.clear()
        scope.launch(Dispatchers.IO) { runCatching { db.deleteAssistantChat(null) } }
    }

    /** "Roll back" of a record; [done] gets what came of it (the notes the person changed since stop it unless [force]). */
    fun rollback(entryId: String, force: Boolean = false, done: (RollbackResult) -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { journalStore.rollback(entryId, force) }
            if (result.done) {
                lines.firstOrNull { it.entryId == entryId }?.let { putLine(it.copy(rolledBack = true)) }
                changed()
            }
            reload()
            done(result)
        }
    }

    fun clearJournal() {
        scope.launch { withContext(Dispatchers.IO) { journalStore.clear() }; reload() }
    }

    companion object {
        const val DEFAULT_URL = "http://127.0.0.1:1234/v1"
        private const val ON = "assistant.on"
        private const val URL = "assistant.url"
        private const val KEY = "assistant.key"
        private const val MODEL = "assistant.model"
        private const val RIGHTS = "assistant.rights"
        private const val SEND_ON_ENTER = "assistant.sendOnEnter"
        private const val KEEP_CHAT = "assistant.keepChat"
        private const val BOT_MODE = "assistant.bot.mode"
        private const val MODE_PROGRAM = "program"
        private const val RELAY_URL = "assistant.bot.programUrl"
        private const val RELAY_CODE = "assistant.bot.programCode"
        private const val RELAY_PEOPLE = "assistant.bot.programPeople"
        private val chatJson = Json { ignoreUnknownKeys = true }
        /** The newest lines of the chat that are kept. */
        private const val KEPT_LINES = 500
        /** How many lines of the talk the model gets with a request. */
        private const val HISTORY = 20
        private const val BOT_KEY = "assistant.bot.key"
        private const val BOT_ON = "assistant.bot.on"
        private const val BOT_OWNER = "assistant.bot.owner"
        private const val BOT_CODE = "assistant.bot.code"
        private const val BOT_CODE_UNTIL = "assistant.bot.codeUntil"
        private const val BOT_OWNER_NAME = "assistant.bot.ownerName"
        private const val BOT_STRANGERS = "assistant.bot.strangers"
        private const val BOT_STRANGER_AT = "assistant.bot.strangerAt"
        private const val BOT_LOCKED = "assistant.bot.whenLocked"
        private const val BOT_OUTSIDE = "assistant.bot.outsideModel"
        private const val BOT_RIGHTS = "assistant.bot.rights"
    }
}
