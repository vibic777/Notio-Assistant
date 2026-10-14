package com.notio.shared.assistant

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A message a person wrote to the bot: who ([userId] - the number of the account, which never changes; [name] - the name and
 * @name it has now), in which chat, and the words. [private] - a chat of one person with the bot.
 */
data class TelegramMessage(val updateId: Long, val chatId: Long, val userId: Long, val text: String, val private: Boolean, val name: String = "")

/**
 * Telegram (or the bot program in between) could not be asked. [message] is for the person; it never holds the key of the bot
 * or the code of Notio. [fatal] - asking again will not help (a wrong key, a code the bot does not know): the bot stops.
 */
class TelegramException(message: String, cause: Throwable? = null, val fatal: Boolean = false) : Exception(message, cause)

/**
 * Where the messages of the bot come from and where the answers go (D151): Telegram itself ([TelegramApi], Notio reads the
 * bot by its key) or the person's own bot program that reads Telegram and passes messages on ([RelayApi]).
 */
interface BotLink {
    /** Who answers on the other side, to show that the key or the code is right. */
    fun me(): String
    /** Waits up to [waitSeconds] for messages after [offset] (the id of the last one read, plus one). */
    fun updates(offset: Long, waitSeconds: Int): List<TelegramMessage>
    /** Sends [text] to a chat. */
    fun send(chatId: Long, text: String)
}

/**
 * The Bot API of Telegram, as much of it as the assistant needs (D148): who the bot is, the messages written to it, a message
 * back. Plain HTTPS of the JDK. This is the one place where Notio speaks to a server that is not the person's own: the words of
 * a request and of an answer pass through Telegram.
 */
class TelegramApi(private val token: String, private val base: String = "https://api.telegram.org") : BotLink {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val json = Json { ignoreUnknownKeys = true }

    private fun call(method: String, body: JsonObject, timeout: Duration): JsonObject {
        val request = HttpRequest.newBuilder(URI(base.trimEnd('/') + "/bot" + token.trim() + "/" + method))
            .timeout(timeout).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TelegramException("Stopped.", e)
        } catch (e: Exception) {
            // The address holds the key of the bot: the cause is not passed on, so that it never reaches a log.
            throw TelegramException("Telegram does not answer.")
        }
        val answer = runCatching { json.parseToJsonElement(response.body()).jsonObject }.getOrNull() ?: throw TelegramException("Telegram answered with something that is not JSON.")
        if ((answer["ok"] as? JsonPrimitive)?.contentOrNull != "true") {
            val code = response.statusCode()
            throw TelegramException(
                when (code) {
                    401, 404 -> "Telegram does not know this key of a bot."
                    409 -> "Another program is reading the messages of this bot."
                    else -> "Telegram answered with an error $code."
                },
                fatal = code == 401 || code == 404 || code == 409,
            )
        }
        return answer
    }

    /** The name of the bot (`@name`), to show that the key is right. */
    override fun me(): String = (call("getMe", buildJsonObject { }, Duration.ofSeconds(20))["result"] as? JsonObject)
        ?.let { (it["username"] as? JsonPrimitive)?.contentOrNull }?.let { "@$it" } ?: throw TelegramException("Telegram answered without the name of the bot.")

    /** Waits up to [waitSeconds] for messages after [offset] (the id of the last one read, plus one). */
    override fun updates(offset: Long, waitSeconds: Int): List<TelegramMessage> {
        val body = buildJsonObject { put("offset", offset); put("timeout", waitSeconds); put("allowed_updates", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("message")))) }
        val result = call("getUpdates", body, Duration.ofSeconds(waitSeconds + 20L))["result"] as? JsonArray ?: return emptyList()
        return result.mapNotNull { item ->
            val update = item as? JsonObject ?: return@mapNotNull null
            val id = (update["update_id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val message = update["message"] as? JsonObject
            val chat = message?.get("chat") as? JsonObject
            val from = message?.get("from") as? JsonObject
            fun field(name: String) = (from?.get(name) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            val shown = listOf(field("first_name"), field("last_name")).filter { it.isNotEmpty() }.joinToString(" ")
            val at = field("username").takeIf { it.isNotEmpty() }?.let { "@$it" }
            TelegramMessage(
                id,
                (chat?.get("id") as? JsonPrimitive)?.longOrNull ?: 0,
                (from?.get("id") as? JsonPrimitive)?.longOrNull ?: 0,
                (message?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                (chat?.get("type") as? JsonPrimitive)?.contentOrNull == "private",
                listOfNotNull(shown.takeIf { it.isNotEmpty() }, at).joinToString(" "),
            )
        }
    }

    /** Sends [text] to a chat, in pieces when it is longer than one message of Telegram may be. */
    override fun send(chatId: Long, text: String) {
        text.ifBlank { "…" }.chunked(MESSAGE_CHARS).forEach { piece ->
            call("sendMessage", buildJsonObject { put("chat_id", chatId); put("text", piece) }, Duration.ofSeconds(30))
        }
    }

    companion object {
        private const val MESSAGE_CHARS = 4_000
    }
}

/** What the bot says by itself, in the language of the app: the app gives the words by these names. */
enum class BotWords { PENDING, HELP_RELAY, BOUND, LOCKED, OUTSIDE_OFF, WORKING_FAILED, CHANGED, CONFIRM, NOTHING_TO_UNDO, UNDONE, UNDO_CONFLICT, HELP, NOTHING_TO_CONFIRM, NOT_READY }

/** How the bot is set: who its person is, and what the person allowed. Read anew for every message. */
data class BotSettings(
    /** The Telegram id of the person the bot listens to; `null` - nobody yet, the bot waits for [bindCode]. */
    val ownerId: Long?,
    /** The one-time code that makes its sender the person of the bot; empty - none (burnt or used). */
    val bindCode: String,
    /** Till when the code may be sent (milliseconds of the epoch, D149). */
    val codeUntil: Long,
    /** Whether the bot answers while Notio is locked (off unless the person turns it on). */
    val answerWhenLocked: Boolean,
    /** Whether the bot may work with a model outside the person's devices (off unless the person turns it on). */
    val outsideModelAllowed: Boolean,
    /** The model of the chat is an outside one now. */
    val outsideModel: Boolean,
    /** The address and the model are set, so a request can be asked. */
    val ready: Boolean,
    /**
     * Through the bot program (D151): the Telegram ids the person of Notio allowed; `null` - Notio reads the bot itself and
     * listens to [ownerId]. Knowing the code of Notio is not enough: everybody else is put on "Asked for access".
     */
    val people: Set<Long>? = null,
)

/**
 * The Telegram bot of the assistant (D148; docs/ASSISTANT_PLAN_RU.md, section 6, variant В): it runs inside Notio on the
 * computer, reads what its one person writes to it and answers. A message is a request like one of the chat - the same commands,
 * the same journal - through the [Channel] of Telegram, so only notes opened to Telegram are there for it, and with the rights
 * the person gave to Telegram, which are apart from those of the chat.
 *
 * Whom it listens to (D149): one person, picked out here and not by Telegram, which lets anybody find a bot. The one-time code
 * shown in Notio binds the number of the Telegram account that sent it - a number that a new name does not change. The code lives
 * [CODE_TRIES] wrong tries and the time Notio gives it, so it cannot be guessed. Anybody else, and any group, gets no answer at
 * all - not even "no"; Notio only counts them. While Notio is locked it says so and does nothing, unless the person chose otherwise.
 *
 * `/undo` rolls back the last request that came from Telegram; `/yes` confirms the one that waits for a yes.
 */
class TelegramBot(
    private val api: BotLink,
    private val settings: () -> BotSettings,
    /** The code came: the number of the account and its name now. */
    private val bind: (Long, String) -> Unit,
    private val words: (BotWords, String) -> String,
    private val locked: () -> Boolean,
    /** One request: the words and whether the person has confirmed it. */
    private val ask: (history: List<ChatMessage>, request: String, channel: Channel, confirmed: Boolean) -> AgentReply,
    /** The records of the journal, the newest first, and the rollback of one. */
    private val journal: AssistantJournal,
    private val log: (String) -> Unit = {},
    /** The code was tried wrongly [CODE_TRIES] times: Notio forgets it, and the person takes a new one. */
    private val burnCode: () -> Unit = {},
    /** Somebody who is not the person of the bot wrote to it; nothing of them is passed on. */
    private val stranger: () -> Unit = {},
    /** Through the bot program: a person who is not allowed in Notio wrote - Notio puts them on "Asked for access" (D151). */
    private val knock: (Long, String) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Each person apart (D151: through the bot program several people may be allowed): their own talk with the model, their
    // own request that waits for a yes, and their own changes for /undo - never those of another person.
    private val history = HashMap<Long, ArrayList<ChatMessage>>()
    private val waiting = HashMap<Long, String>()
    private val made = HashMap<Long, ArrayDeque<String>>()
    private var triedCode = ""
    private var wrongTries = 0
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false

    /** Starts reading the messages, on a thread of its own; [failed] hears why it had to stop (a wrong key). */
    fun start(failed: (String) -> Unit = {}) {
        if (running) return
        running = true
        thread = Thread({ loop(failed) }, "notio-telegram").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop(failed: (String) -> Unit) {
        var offset = 0L
        var pause = 1_000L
        while (running) {
            try {
                // One open question to Telegram that waits for a message: nothing is asked again and again.
                val messages = api.updates(offset, WAIT_SECONDS)
                pause = 1_000L
                for (message in messages) {
                    offset = maxOf(offset, message.updateId + 1)
                    runCatching { handle(message) }.onFailure { log("a message of Telegram failed: " + it.javaClass.simpleName) }
                }
            } catch (e: TelegramException) {
                if (!running) break
                log("Telegram: " + e.message)
                if (e.fatal) { running = false; failed(e.message.orEmpty()); break }
                // No network: wait, longer each time, up to five minutes.
                try { Thread.sleep(pause) } catch (i: InterruptedException) { break }
                pause = (pause * 2).coerceAtMost(300_000L)
            }
        }
    }

    /** One message. Public for the tests; the loop calls it. */
    fun handle(message: TelegramMessage) {
        val now = settings()
        // A group, a channel, a message without words: no answer.
        if (!message.private || message.text.isBlank() || message.userId == 0L) return
        val people = now.people
        if (people != null) {
            // Through the bot program (D151): the person knew the code of Notio, but Notio itself says whom it answers.
            if (message.userId !in people) {
                knock(message.userId, message.name)
                log("a person asked for access through the bot program")
                api.send(message.chatId, words(BotWords.PENDING, ""))
                return
            }
        } else if (now.ownerId == null) {
            // Nobody is bound yet: only the code is heard, and whoever writes anything else learns nothing.
            val code = now.bindCode
            if (code.isEmpty() || clock() > now.codeUntil) return
            if (code != triedCode) { triedCode = code; wrongTries = 0 }
            val text = message.text.trim()
            if (text == code) {
                bind(message.userId, message.name)
                log("the bot is bound to its person")
                api.send(message.chatId, words(BotWords.BOUND, ""))
            } else if (text.all { it.isDigit() }) {
                // Only digits are tries of the code: "/start" or a greeting of the person does not burn it.
                wrongTries++
                if (wrongTries >= CODE_TRIES) { wrongTries = 0; log("the code of the bot burnt after wrong tries"); burnCode() }
            }
            return
        }
        // Not the person of the bot: no answer at all; Notio only counts it.
        else if (message.userId != now.ownerId) { stranger(); return }
        if (locked() && !now.answerWhenLocked) { api.send(message.chatId, words(BotWords.LOCKED, "")); return }
        val text = message.text.trim()
        when (text.lowercase().substringBefore('@')) {
            "/start", "/help" -> api.send(message.chatId, words(if (people != null) BotWords.HELP_RELAY else BotWords.HELP, ""))
            "/undo" -> undo(message.chatId, message.userId, now)
            "/yes" -> waiting.remove(message.userId)?.let { request -> request(message.chatId, message.userId, request, confirmed = true, now) } ?: api.send(message.chatId, words(BotWords.NOTHING_TO_CONFIRM, ""))
            else -> request(message.chatId, message.userId, text, confirmed = false, now)
        }
    }

    private fun request(chatId: Long, person: Long, text: String, confirmed: Boolean, now: BotSettings) {
        if (!now.ready) { api.send(chatId, words(BotWords.NOT_READY, "")); return }
        if (now.outsideModel && !now.outsideModelAllowed) { api.send(chatId, words(BotWords.OUTSIDE_OFF, "")); return }
        val channel = Channel("telegram", telegram = true, externalModel = now.outsideModel)
        val reply = try {
            ask(history[person].orEmpty().toList(), text, channel, confirmed)
        } catch (e: Throwable) {
            log("a request of Telegram failed: " + e.javaClass.simpleName)
            api.send(chatId, words(BotWords.WORKING_FAILED, ""))
            return
        }
        val talk = history.getOrPut(person) { ArrayList() }
        talk += ChatMessage("user", text)
        if (!reply.failed) talk += ChatMessage("assistant", reply.text)
        while (talk.size > HISTORY) talk.removeAt(0)
        reply.entry?.let { entry -> made.getOrPut(person) { ArrayDeque() }.apply { addLast(entry.id); while (size > HISTORY) removeFirst() } }
        val tail = buildString {
            reply.entry?.let { append("\n\n").append(words(BotWords.CHANGED, it.body.changes.toString())) }
            if (reply.needsConfirm) { waiting[person] = text; append("\n\n").append(words(BotWords.CONFIRM, "")) }
        }
        log("answer to Telegram: commands " + reply.commands + ", changed " + (reply.entry?.body?.changes ?: 0) + (if (reply.failed) ", failed" else ""))
        api.send(chatId, reply.text + tail)
    }

    private fun undo(chatId: Long, person: Long, now: BotSettings) {
        val entries = journal.entries()
        // Through the bot program: only what this person changed. Notio reading the bot itself has one person, so every change
        // that came from Telegram is theirs - also those made before Notio started again.
        val mine = made[person].orEmpty().toSet()
        val last = entries.firstOrNull { it.source == "telegram" && !it.rolledBack && (now.people == null || it.id in mine) }
        if (last == null) { api.send(chatId, words(BotWords.NOTHING_TO_UNDO, "")); return }
        val result = journal.rollback(last.id)
        api.send(chatId, if (result.done) words(BotWords.UNDONE, last.request.take(200)) else words(BotWords.UNDO_CONFLICT, result.conflicts.joinToString(", ")))
    }

    /** Passes a reminder on to the person of the bot (a note with "Reminders to Telegram"): the words Notio gives, nothing of the note's text. */
    fun remind(text: String) {
        val now = settings()
        // A chat of one person with the bot has the number of that person: through the bot program, every allowed person gets it.
        val to = now.people ?: listOfNotNull(now.ownerId)
        to.forEach { id -> runCatching { api.send(id, text) }.onFailure { log("a reminder did not reach Telegram") } }
    }

    companion object {
        private const val WAIT_SECONDS = 50
        private const val HISTORY = 20
        /** Wrong tries of digits after which the code burns. */
        const val CODE_TRIES = 5
        /** How long a new code may be sent. */
        const val CODE_MILLIS = 10 * 60_000L
    }
}
