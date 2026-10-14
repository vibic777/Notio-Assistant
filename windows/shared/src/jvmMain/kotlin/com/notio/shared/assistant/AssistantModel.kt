package com.notio.shared.assistant

import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Where the model stands, by its address: what leaves the person's devices when it reads a note (the plan, section 6). */
enum class ModelPlace {
    /** This computer: nothing leaves it. */
    THIS_COMPUTER,

    /** Another computer of the home network (or of the person's own VPN): the text goes there over the network, not encrypted. */
    HOME_NETWORK,

    /** An outside service: the text goes to the internet. Only notes opened to an outside model are given to it. */
    OUTSIDE;

    companion object {
        /** By the host of [url]; an address that cannot be read is taken for the outside, the careful answer. */
        fun of(url: String): ModelPlace {
            val host = runCatching { URI(url.trim()).host }.getOrNull()?.trim('[', ']')?.lowercase() ?: return OUTSIDE
            if (host == "localhost") return THIS_COMPUTER
            // Only an address written in numbers is judged: a name is not looked up, it could point anywhere tomorrow.
            if (!host.matches(Regex("[0-9.]+")) && !host.contains(':')) return OUTSIDE
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return OUTSIDE
            val bytes = address.address
            return when {
                address.isLoopbackAddress -> THIS_COMPUTER
                address.isSiteLocalAddress || address.isLinkLocalAddress -> HOME_NETWORK
                // 100.64.0.0/10: the addresses of a private VPN between one's own devices.
                bytes.size == 4 && (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 64 -> HOME_NETWORK
                // fc00::/7: the private addresses of IPv6.
                bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC -> HOME_NETWORK
                else -> OUTSIDE
            }
        }
    }
}

/** How to reach the model: the whole address up to `/v1` (any host and port), a key if the server wants one, the name of the model. */
data class ModelSettings(val url: String, val key: String? = null, val model: String = "") {
    val place: ModelPlace get() = ModelPlace.of(url)
    val base: String get() = url.trim().trimEnd('/')
}

/** One message of the talk with the model, as the OpenAI-compatible protocol has it. */
data class ChatMessage(
    val role: String,
    val content: String? = null,
    /** What the model asks to be done (role "assistant"). */
    val calls: List<ToolCall> = emptyList(),
    /** Which call this is the answer to (role "tool"). */
    val callId: String? = null,
)

/** One command the model asks for: [arguments] is JSON as text, as the model wrote it. */
data class ToolCall(val id: String, val name: String, val arguments: String)

/** The model could not be asked, or answered with something that is not an answer. [message] is for the person. */
class ModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A model that takes a talk and the commands it may ask for, and answers with words or with calls. */
interface ModelClient {
    /** The names of the models the server has. */
    fun models(): List<String>

    fun chat(messages: List<ChatMessage>, tools: List<AssistantTool>): ChatMessage
}

/**
 * A model behind an OpenAI-compatible address (LM Studio and the like; D145): `GET …/models`, `POST …/chat/completions` with
 * the commands as "tools". Plain HTTP of the JDK, no library; one request at a time, with a time limit - a local model can
 * think for minutes.
 */
class HttpModelClient(private val settings: ModelSettings, private val timeout: Duration = Duration.ofMinutes(5)) : ModelClient {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    private fun request(path: String): HttpRequest.Builder {
        val uri = runCatching { URI(settings.base + path) }.getOrElse { throw ModelException("The address of the model is not an address.", it) }
        if (uri.scheme != "http" && uri.scheme != "https") throw ModelException("The address of the model must begin with http:// or https://.")
        val builder = HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/json")
        settings.key?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer " + it.trim()) }
        return builder
    }

    private fun send(request: HttpRequest): JsonObject {
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.net.http.HttpTimeoutException) {
            throw ModelException("The model did not answer in time.", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ModelException("The request was stopped.", e)
        } catch (e: Exception) {
            throw ModelException("The server of the model does not answer at this address.", e)
        }
        val body = runCatching { json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
        if (response.statusCode() !in 200..299) {
            val said = (body?.get("error") as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
            throw ModelException(
                when (response.statusCode()) {
                    401, 403 -> "The server of the model wants a key, or the key is wrong."
                    404 -> "The server answers, but not as a model does: check the address (it usually ends with /v1)."
                    else -> "The server of the model answered with an error ${response.statusCode()}."
                } + (said?.let { " It says: " + it.take(300) } ?: ""),
            )
        }
        return body ?: throw ModelException("The server answered with something that is not JSON.")
    }

    override fun models(): List<String> =
        (send(request("/models").GET().build())["data"] as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }

    override fun chat(messages: List<ChatMessage>, tools: List<AssistantTool>): ChatMessage {
        val body = buildJsonObject {
            put("model", settings.model)
            put("stream", false)
            put("messages", buildJsonArray { messages.forEach { add(encode(it)) } })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") { put("name", tool.name); put("description", tool.description); put("parameters", tool.parameters) }
                        })
                    }
                })
                put("tool_choice", "auto")
            }
        }
        val answer = send(request("/chat/completions").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build())
        val message = ((answer["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
            ?: throw ModelException("The model answered with nothing.")
        val calls = (message["tool_calls"] as? JsonArray).orEmpty().mapNotNull { call ->
            val function = (call as? JsonObject)?.get("function") as? JsonObject ?: return@mapNotNull null
            val name = (function["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            // The arguments come as JSON in a string; some servers send the object itself.
            val arguments = function["arguments"]?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() } ?: "{}"
            ToolCall((call["id"] as? JsonPrimitive)?.contentOrNull ?: name, name, arguments)
        }
        return ChatMessage("assistant", (message["content"] as? JsonPrimitive)?.contentOrNull, calls)
    }

    private fun encode(message: ChatMessage): JsonObject = buildJsonObject {
        put("role", message.role)
        put("content", message.content.orEmpty())
        if (message.calls.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                message.calls.forEach { call ->
                    add(buildJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        putJsonObject("function") { put("name", call.name); put("arguments", call.arguments) }
                    })
                }
            })
        }
        message.callId?.let { put("tool_call_id", it) }
    }
}
