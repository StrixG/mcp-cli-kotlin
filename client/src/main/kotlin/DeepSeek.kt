import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * JSON for the DeepSeek API. `ignoreUnknownKeys` so V4 Pro's extra `reasoning_content`
 * doesn't break parsing; `explicitNulls = false` so null `content`/`tool_calls` are
 * omitted from requests (DeepSeek rejects a `content: null` on some message shapes).
 */
val DeepSeekJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class ToolFunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class ToolDef(
    val type: String = "function",
    val function: ToolFunctionDef,
)

@Serializable
data class FunctionCall(
    val name: String,
    /** JSON-encoded argument object, as a string (DeepSeek/OpenAI convention). */
    val arguments: String,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDef>? = null,
    val temperature: Double? = null,
)

@Serializable
data class Choice(
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatResponse(
    val choices: List<Choice>,
)

/** Minimal DeepSeek chat client (function-calling capable). */
class DeepSeekClient(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://api.deepseek.com",
) {
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(DeepSeekJson) }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
    }

    /** One round-trip: send history + tool defs, return the assistant message. */
    suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDef>): ChatMessage {
        val resp: ChatResponse = http.post("$baseUrl/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(model = model, messages = messages, tools = tools))
        }.body()
        return resp.choices.firstOrNull()?.message
            ?: error("DeepSeek returned no choices")
    }

    fun close() = http.close()
}
