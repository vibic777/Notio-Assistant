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
 * The person's own bot program in between (D151; the program TelegramBot, its module `bot/notio_relay.py`): the program reads
 * Telegram, and Notio asks it for the messages meant for this Notio and gives it the answers. Notio speaks with its code of 12
 * characters (`Authorization: Notio <code>`); the program knows a code only when its admin entered it. Plain HTTP: the program
 * is on this computer or on a home network / VPN.
 *
 * A message from the program is always from a chat of one person with the bot, so the number of the chat is the number of the
 * person, and an answer to it goes back through the program, which sends it only to a chat bound to this Notio.
 */
class RelayApi(private val base: String, private val code: String) : BotLink {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val root = base.trim().trimEnd('/')

    private fun call(request: HttpRequest.Builder): JsonObject {
        val built = request.header("Authorization", "Notio $code").build()
        val response = try {
            http.send(built, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TelegramException("Stopped.", e)
        } catch (e: Exception) {
            throw TelegramException("The bot program does not answer at $root.")
        }
        when (response.statusCode()) {
            200 -> {}
            401 -> throw TelegramException("The bot program does not know this code of Notio: its admin enters it in /admin -> Notio.", fatal = true)
            403 -> throw TelegramException("The bot program does not let this Notio write to that chat.")
            else -> throw TelegramException("The bot program answered with an error ${response.statusCode()}.")
        }
        return runCatching { json.parseToJsonElement(response.body()).jsonObject }.getOrNull() ?: throw TelegramException("The bot program answered with something that is not JSON.")
    }

    private fun uri(path: String) = try { URI(root + path) } catch (e: Exception) { throw TelegramException("The address of the bot program is not right.", fatal = true) }

    /** The name of the bot and the name the admin gave this Notio: `@bot (Home)`. */
    override fun me(): String {
        val answer = call(HttpRequest.newBuilder(uri("/notio/v1/hello")).timeout(Duration.ofSeconds(15)).GET())
        val bot = (answer["bot"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val name = (answer["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return if (name.isEmpty()) bot else "$bot ($name)"
    }

    override fun updates(offset: Long, waitSeconds: Int): List<TelegramMessage> {
        val query = "?offset=$offset&wait=$waitSeconds"
        val answer = call(HttpRequest.newBuilder(uri("/notio/v1/updates$query")).timeout(Duration.ofSeconds(waitSeconds + 20L)).GET())
        val items = answer["updates"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            fun long(name: String) = (o[name] as? JsonPrimitive)?.longOrNull
            fun text(name: String) = (o[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val id = long("update_id") ?: return@mapNotNull null
            TelegramMessage(id, long("chat_id") ?: 0, long("user_id") ?: 0, text("text"), private = true, name = text("name"))
        }
    }

    override fun send(chatId: Long, text: String) {
        val body = buildJsonObject { put("chat_id", chatId); put("text", text.ifBlank { "…" }) }.toString()
        call(HttpRequest.newBuilder(uri("/notio/v1/send")).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)))
    }

    companion object {
        const val DEFAULT_URL = "http://127.0.0.1:8790"
    }
}
