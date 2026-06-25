# DeepSeek LLM Agent in `:client` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the deterministic `:client` into an interactive LLM agent that lets DeepSeek pick MCP tools via function calling, executes them over MCP, feeds results back, and uses them to answer.

**Architecture:** Add three Kotlin files to `:client` — `DeepSeek.kt` (Ktor chat client + DTOs), `Bridge.kt` (MCP→DeepSeek schema mapping + arg parsing), `Agent.kt` (REPL + bounded tool-calling loop). `Main.kt` branches: if `DEEPSEEK_API_KEY` is set → agent REPL; else the existing deterministic demo (preserved). The `:server` is unchanged — light switching is the existing `call_service` tool (`turn_on`/`turn_off`/`toggle`).

**Tech Stack:** Kotlin/JVM, coroutines, MCP Kotlin SDK 0.13.0, Ktor client (CIO) 3.4.3, kotlinx.serialization 1.11.0, dotenv-kotlin 6.5.1, kotlin-test/JUnit5.

## Global Constraints

- Model default `deepseek-v4-pro`; overridable via `DEEPSEEK_MODEL`. (Legacy `deepseek-chat`/`deepseek-reasoner` aliases retire 2026-07-24 — do not use them.)
- DeepSeek API: base `https://api.deepseek.com`, `POST /chat/completions`, `Authorization: Bearer <key>`, OpenAI-compatible.
- Secrets from process env or `client/.env` only (dotenv-kotlin; process env wins). Never from code/args.
- Pin versions exactly: Ktor `3.4.3`, kotlinx-serialization-json `1.11.0`, kotlinx-coroutines `1.11.0`, dotenv-kotlin `6.5.1` (aligned with SDK 0.13.0 / `:server`).
- JSON parser must use `ignoreUnknownKeys = true` (V4 Pro replies carry `reasoning_content`) and `explicitNulls = false` (omit null `content`/`tool_calls`).
- Diagnostics → stderr; user-facing answers → stdout. Keep the existing UTF-8 stream forcing in `Main.kt`.
- Tool-calling loop bounded at 8 iterations per user turn.

---

### Task 1: DeepSeek chat client + build wiring

**Files:**
- Modify: `client/build.gradle.kts`
- Create: `client/.env.example`
- Create: `client/src/main/kotlin/DeepSeek.kt`
- Test: `client/src/test/kotlin/DeepSeekSerializationTest.kt`

**Interfaces:**
- Produces:
  - `val DeepSeekJson: Json`
  - `data class ToolFunctionDef(name: String, description: String, parameters: JsonObject)`
  - `data class ToolDef(type: String = "function", function: ToolFunctionDef)`
  - `data class FunctionCall(name: String, arguments: String)`
  - `data class ToolCall(id: String, type: String = "function", function: FunctionCall)`
  - `data class ChatMessage(role: String, content: String? = null, toolCalls: List<ToolCall>? = null, toolCallId: String? = null, name: String? = null)`
  - `data class ChatRequest(model, messages, tools, temperature)`
  - `data class ChatResponse(choices: List<Choice>)`, `data class Choice(message: ChatMessage, finishReason: String?)`
  - `class DeepSeekClient(apiKey, model, baseUrl)` with `suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDef>): ChatMessage` and `fun close()`

- [ ] **Step 1: Add dependencies and test wiring to `client/build.gradle.kts`**

Replace the file with:

```kotlin
plugins {
    kotlin("jvm")
    // @Serializable DTOs for the DeepSeek API.
    kotlin("plugin.serialization")
    application
    // Self-contained fat JAR -> runnable with plain `java -jar`.
    id("com.gradleup.shadow")
}

dependencies {
    // Official MCP Kotlin SDK (client + types + stdio transport).
    implementation("io.modelcontextprotocol:kotlin-sdk:0.13.0")
    // runBlocking in main.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // asSource()/asSink() bridge the subprocess streams into the stdio transport.
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.0")
    // JSON DTOs + tool schema building for the DeepSeek agent.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Ktor HTTP client -> DeepSeek chat/completions.
    implementation("io.ktor:ktor-client-core:3.4.3")
    implementation("io.ktor:ktor-client-cio:3.4.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.4.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.3")

    // .env loader for the DeepSeek API key (real process env still takes precedence).
    implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")

    // Unit tests: kotlin-test on the JUnit5 platform, coroutine test scope.
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("MainKt")
}

// Forward stdin for the agent REPL; force UTF-8 so non-ASCII isn't mangled by the
// platform default charset (CP866 on a Russian Windows console).
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
```

- [ ] **Step 2: Create `client/.env.example`**

```bash
# DeepSeek agent — environment (copy to client/.env, fill in, never commit real values).
# Get a key at https://platform.deepseek.com/ -> API keys.
DEEPSEEK_API_KEY=sk-replace_me

# Optional model override. Default is deepseek-v4-pro.
# DEEPSEEK_MODEL=deepseek-v4-pro
```

- [ ] **Step 3: Write the failing test `client/src/test/kotlin/DeepSeekSerializationTest.kt`**

```kotlin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeepSeekSerializationTest {

    @Test
    fun requestKeepsModelAndOmitsNullFields() {
        val req = ChatRequest(
            model = "deepseek-v4-pro",
            messages = listOf(ChatMessage(role = "user", content = "hi")),
        )
        val s = DeepSeekJson.encodeToString(ChatRequest.serializer(), req)
        assertTrue(s.contains("\"model\":\"deepseek-v4-pro\""), s)
        assertTrue(!s.contains("\"tool_calls\""), "null tool_calls must be omitted: $s")
        assertTrue(!s.contains("\"temperature\""), "null temperature must be omitted: $s")
    }

    @Test
    fun responseParsesToolCallsAndIgnoresReasoningContent() {
        val json = """
            {"choices":[{"finish_reason":"tool_calls","message":{
              "role":"assistant","content":null,"reasoning_content":"thinking...",
              "tool_calls":[{"id":"c1","type":"function",
                "function":{"name":"get_state","arguments":"{\"entity_id\":\"light.kitchen\"}"}}]}}]}
        """.trimIndent()
        val resp = DeepSeekJson.decodeFromString(ChatResponse.serializer(), json)
        val msg = resp.choices.single().message
        assertEquals("get_state", msg.toolCalls!!.single().function.name)
        assertEquals("c1", msg.toolCalls!!.single().id)
        assertEquals("{\"entity_id\":\"light.kitchen\"}", msg.toolCalls!!.single().function.arguments)
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :client:test --tests DeepSeekSerializationTest`
Expected: FAIL — compilation error, `ChatRequest`/`DeepSeekJson` unresolved.

- [ ] **Step 5: Create `client/src/main/kotlin/DeepSeek.kt`**

```kotlin
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
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :client:test --tests DeepSeekSerializationTest`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add client/build.gradle.kts client/.env.example client/src/main/kotlin/DeepSeek.kt client/src/test/kotlin/DeepSeekSerializationTest.kt
git commit -m "feat(client): DeepSeek chat client + DTOs"
```

---

### Task 2: MCP→DeepSeek tool bridge

**Files:**
- Create: `client/src/main/kotlin/Bridge.kt`
- Test: `client/src/test/kotlin/ToolBridgeTest.kt`

**Interfaces:**
- Consumes: `ToolDef`, `ToolFunctionDef`, `DeepSeekJson` (Task 1); `io.modelcontextprotocol.kotlin.sdk.types.Tool` (SDK; `tool.name: String`, `tool.description: String?`, `tool.inputSchema.properties: JsonObject?`, `tool.inputSchema.required: List<String>?`).
- Produces:
  - `fun toolParameters(properties: JsonObject?, required: List<String>?): JsonObject`
  - `fun toDeepSeekTools(tools: List<Tool>): List<ToolDef>`
  - `fun parseToolArgs(arguments: String): JsonObject`

- [ ] **Step 1: Write the failing test `client/src/test/kotlin/ToolBridgeTest.kt`**

```kotlin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ToolBridgeTest {

    @Test
    fun parametersWrapPropertiesWithTypeObjectAndRequired() {
        val props = buildJsonObject {
            put("entity_id", buildJsonObject { put("type", "string") })
        }
        val params = toolParameters(props, listOf("entity_id"))
        assertEquals("object", params["type"]!!.jsonPrimitive.content)
        assertTrue(params["properties"]!!.jsonObject.containsKey("entity_id"))
        assertEquals("entity_id", params["required"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun requiredKeyOmittedWhenEmpty() {
        val params = toolParameters(JsonObject(emptyMap()), emptyList())
        assertTrue(!params.containsKey("required"), params.toString())
    }

    @Test
    fun parseToolArgsReadsObject() {
        val obj = parseToolArgs("""{"entity_id":"light.kitchen"}""")
        assertEquals("light.kitchen", obj["entity_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun parseToolArgsReturnsEmptyOnGarbage() {
        assertTrue(parseToolArgs("not json").isEmpty())
        assertTrue(parseToolArgs("").isEmpty())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :client:test --tests ToolBridgeTest`
Expected: FAIL — `toolParameters`/`parseToolArgs` unresolved.

- [ ] **Step 3: Create `client/src/main/kotlin/Bridge.kt`**

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Build a JSON-Schema `parameters` object for a DeepSeek tool from an MCP tool's
 * schema parts. MCP `inputSchema` is already JSON Schema, so this just wraps the
 * properties in the `{ type: object, properties, required }` envelope DeepSeek wants.
 */
fun toolParameters(properties: JsonObject?, required: List<String>?): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", properties ?: JsonObject(emptyMap()))
    if (!required.isNullOrEmpty()) {
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}

/** Map MCP tools to DeepSeek function-calling tool definitions. */
fun toDeepSeekTools(tools: List<Tool>): List<ToolDef> = tools.map { t ->
    ToolDef(
        function = ToolFunctionDef(
            name = t.name,
            description = t.description ?: "",
            parameters = toolParameters(t.inputSchema.properties, t.inputSchema.required),
        ),
    )
}

/**
 * Parse a DeepSeek tool_call `arguments` JSON string into a JsonObject. A JsonObject
 * is a Map<String, JsonElement>, so it can be passed straight to `Client.callTool`
 * (its `convertToJsonElement` passes JsonElement values through unchanged). Returns an
 * empty object on blank/garbage so a malformed model output never crashes the loop.
 */
fun parseToolArgs(arguments: String): JsonObject =
    runCatching { DeepSeekJson.parseToJsonElement(arguments).jsonObject }
        .getOrElse { JsonObject(emptyMap()) }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :client:test --tests ToolBridgeTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add client/src/main/kotlin/Bridge.kt client/src/test/kotlin/ToolBridgeTest.kt
git commit -m "feat(client): MCP->DeepSeek tool schema bridge + arg parsing"
```

---

### Task 3: Agent REPL + Main wiring (manual verification)

**Files:**
- Create: `client/src/main/kotlin/Agent.kt`
- Modify: `client/src/main/kotlin/Main.kt`
- Modify: `README.md`

**Interfaces:**
- Consumes: `DeepSeekClient`, `ChatMessage`, `ToolDef`, `ToolCall` (Task 1); `toDeepSeekTools`, `parseToolArgs` (Task 2); SDK `Client.callTool(name: String, arguments: Map<String, Any?>): CallToolResult`, `Client.listTools()`, `CallToolResult.content: List<ContentBlock>` (filter `TextContent`).
- Produces: `suspend fun runAgentRepl(mcpClient: Client, tools: List<Tool>, deepseek: DeepSeekClient)`

> No unit test: this path needs a live DeepSeek key + a running MCP server. The deterministic-fallback path keeps `:client` runnable without a key, so the existing build/run stays green. Verified manually in Steps 4–6. The pure logic it depends on (`toolParameters`, `parseToolArgs`, serialization) is unit-tested in Tasks 1–2.

- [ ] **Step 1: Create `client/src/main/kotlin/Agent.kt`**

```kotlin
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool

private const val MAX_TOOL_ITERATIONS = 8

private val SYSTEM_PROMPT = """
    You are a smart-home assistant controlling Home Assistant through the provided tools.
    Use the tools to inspect and change device state. Never invent entity ids — discover
    them with list_entities first. To switch a light, call call_service with
    domain="light" and service "turn_on", "turn_off", or "toggle" for the target
    entity_id. After acting, confirm the outcome to the user in one short sentence.
""".trimIndent()

/** Flatten a tool result's text blocks into one string (mirrors Main's helper). */
private fun CallToolResult?.textContent(): String =
    this?.content?.filterIsInstance<TextContent>()?.mapNotNull { it.text }?.joinToString("\n").orEmpty()

/**
 * Interactive agent REPL: read a goal from stdin, let DeepSeek drive the MCP tools via
 * function calling, feed each tool result back, and print the model's final answer.
 * Diagnostics (which tool is being called) go to stderr; answers go to stdout.
 */
suspend fun runAgentRepl(mcpClient: Client, tools: List<Tool>, deepseek: DeepSeekClient) {
    val toolDefs = toDeepSeekTools(tools)
    val history = mutableListOf(ChatMessage(role = "system", content = SYSTEM_PROMPT))
    val stdin = System.`in`.bufferedReader()

    println("\nAgent ready. Type a goal (e.g. \"turn on the kitchen light\"). 'exit' to quit.")
    while (true) {
        print("\n> ")
        System.out.flush()
        val goal = stdin.readLine()?.trim() ?: break
        if (goal.isEmpty()) continue
        if (goal.equals("exit", ignoreCase = true) || goal.equals("quit", ignoreCase = true)) break

        history += ChatMessage(role = "user", content = goal)
        try {
            runTurn(mcpClient, toolDefs, deepseek, history)
        } catch (e: Exception) {
            System.err.println("DeepSeek/agent error: ${e.message}")
        }
    }
    println("Bye.")
}

/** One user turn: loop tool-calls until the model returns a plain answer (bounded). */
private suspend fun runTurn(
    mcpClient: Client,
    toolDefs: List<ToolDef>,
    deepseek: DeepSeekClient,
    history: MutableList<ChatMessage>,
) {
    repeat(MAX_TOOL_ITERATIONS) {
        val reply = deepseek.chat(history, toolDefs)
        history += reply

        val calls = reply.toolCalls
        if (calls.isNullOrEmpty()) {
            println(reply.content?.trim().orEmpty().ifEmpty { "(no answer)" })
            return
        }

        for (call in calls) {
            val args = parseToolArgs(call.function.arguments)
            System.err.println("  · ${call.function.name} $args")
            val resultText = try {
                mcpClient.callTool(name = call.function.name, arguments = args).textContent()
            } catch (e: Exception) {
                "ERROR calling ${call.function.name}: ${e.message}"
            }
            history += ChatMessage(
                role = "tool",
                toolCallId = call.id,
                content = resultText.ifBlank { "(empty result)" },
            )
        }
    }
    println("(stopped: reached $MAX_TOOL_ITERATIONS tool iterations without a final answer)")
}
```

- [ ] **Step 2: Wire the branch into `client/src/main/kotlin/Main.kt`**

Add these imports at the top of the file (with the existing imports):

```kotlin
import io.github.cdimascio.dotenv.dotenv
import java.io.File
```

Replace the demo-call block (the lines from `// Pick a tool, CALL it ...` comment down to the end of the `if (call == null) { ... } else { ... }` block — currently around lines 71–93) with this branch:

```kotlin
            // If a DeepSeek key is present, hand control to the LLM agent: it picks
            // tools, calls them, and uses the results. Otherwise fall back to the
            // deterministic demo so `:client` still runs (and tests stay valid)
            // without any API key.
            val apiKey = loadDeepSeekKey()
            if (apiKey != null) {
                val model = loadDeepSeekModel()
                log("DeepSeek agent mode (model=$model).")
                val deepseek = DeepSeekClient(apiKey = apiKey, model = model)
                try {
                    runAgentRepl(client, tools, deepseek)
                } finally {
                    deepseek.close()
                }
            } else {
                log("DEEPSEEK_API_KEY not set — running deterministic demo (no LLM). " +
                    "Set it in client/.env for agent mode.")
                runDeterministicDemo(client, tools)
            }
```

Move the old demo logic into a helper at the bottom of the file (the body is the exact code that was inside the replaced block):

```kotlin
/** Deterministic fallback: pick one safe tool, call it, and use its result. */
private suspend fun runDeterministicDemo(client: Client, tools: List<Tool>) {
    val call = pickDemoCall(tools)
    if (call == null) {
        log("No tool safe to auto-call (all have required params we can't guess); skipping demo call.")
        return
    }
    val (name, arguments) = call
    println("\nCalling tool: $name ${arguments.ifEmpty { "(no args)" }}")
    val result = client.callTool(name = name, arguments = arguments)
    val out = result.text()
    println("Result${if (result?.isError == true) " (error)" else ""}:")
    println(out.ifBlank { "(empty)" }.prependIndent("  "))

    val firstEntity = Regex("""^- (\S+)""", RegexOption.MULTILINE).find(out)?.groupValues?.get(1)
    if (name == "list_entities" && firstEntity != null) {
        println("\nUsing result -> get_state(entity_id=$firstEntity):")
        val state = client.callTool(name = "get_state", arguments = mapOf("entity_id" to firstEntity))
        println(state.text().ifBlank { "(empty)" }.prependIndent("  "))
    }
}

/** Read DEEPSEEK_API_KEY from process env or client/.env (process env wins). */
private fun loadDeepSeekKey(): String? =
    deepseekDotenv()["DEEPSEEK_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() }

/** Read DEEPSEEK_MODEL override, defaulting to deepseek-v4-pro. */
private fun loadDeepSeekModel(): String =
    deepseekDotenv()["DEEPSEEK_MODEL"]?.trim()?.takeIf { it.isNotEmpty() } ?: "deepseek-v4-pro"

/** dotenv reader that locates client/.env across the launchers' working dirs. */
private fun deepseekDotenv() = dotenv {
    ignoreIfMissing = true
    ignoreIfMalformed = true
    val dir = listOf(File("."), File("client"), File("../client"))
        .firstOrNull { File(it, ".env").isFile }?.path
    if (dir != null) directory = dir
}
```

Add the SDK `Client` import if not already present (used by the new helper signature):

```kotlin
import io.modelcontextprotocol.kotlin.sdk.client.Client
```

> Note: `runAgentRepl` and `runDeterministicDemo` are `suspend`/called inside the existing `runBlocking { ... }` in `main`, so no new coroutine scaffolding is needed. `pickDemoCall`, `text()`, and `printTool` stay as-is.

- [ ] **Step 3: Build to confirm everything compiles and unit tests still pass**

Run: `./gradlew :client:build`
Expected: BUILD SUCCESSFUL; `DeepSeekSerializationTest` + `ToolBridgeTest` pass.

- [ ] **Step 4: Manual verify — fallback path still works (no key)**

Build the server jar, then run the client with no DeepSeek key set and no `client/.env`:

Run:
```bash
./gradlew :server:shadowJar
./gradlew :client:run --args="java -jar ../server/build/libs/server-all.jar --stdio"
```
Expected (stderr): `DEEPSEEK_API_KEY not set — running deterministic demo …`, then the existing list/get_state demo output (or an HA error if HA isn't configured — either proves the fallback path runs).

- [ ] **Step 5: Manual verify — agent path (live key)**

Put a real key in `client/.env` (`DEEPSEEK_API_KEY=sk-…`), ensure HA creds are set for the server (`server/.env` with `HA_BASE_URL`/`HA_TOKEN`), then:

Run:
```bash
./gradlew :client:run --args="java -jar ../server/build/libs/server-all.jar --stdio"
# at the prompt:
> what lights do I have?
```
Expected (stderr): `DeepSeek agent mode (model=deepseek-v4-pro)`, then `· list_entities {...}`; (stdout) a sentence listing the lights. Then:
```
> turn on the kitchen light
```
Expected: `· call_service {"domain":"light","service":"turn_on","entity_id":"light.kitchen"}` (and possibly a follow-up `· get_state …`), then a one-line confirmation. `exit` ends the REPL.

> If no HA instance is available, the tool returns a readable HA error; the agent relays it. The loop (LLM → tool → result → LLM) is still demonstrated.

- [ ] **Step 6: Update `README.md` — document agent mode**

In the `## MCP client (:client)` section, after the existing run example, add:

````markdown
### Agent mode (DeepSeek LLM drives the tools)

With a DeepSeek key present, `:client` becomes an interactive **LLM agent**: it
lists the server's tools, hands them to DeepSeek as function-calling tools, then
loops — the model picks a tool, the client calls it over MCP, the result is fed
back, and the model uses it to act and answer.

```bash
cp client/.env.example client/.env   # then set DEEPSEEK_API_KEY (model defaults to deepseek-v4-pro)
./gradlew :server:shadowJar
./gradlew :client:run --args="java -jar ../server/build/libs/server-all.jar --stdio"
> turn on the kitchen light
# · list_entities {"domain":"light"}  -> finds light.kitchen [off]
# · call_service {"domain":"light","service":"turn_on","entity_id":"light.kitchen"}
# · get_state {"entity_id":"light.kitchen"}
# "Kitchen light is now on."
```

Switching a light on/off/toggle is the existing `call_service` tool
(`service` = `turn_on` | `turn_off` | `toggle`) — no extra tool needed. Without
`DEEPSEEK_API_KEY`, `:client` runs the deterministic demo instead.
````

Also add DeepSeek to the `## Stack` section:

```markdown
- DeepSeek API (`deepseek-v4-pro`, OpenAI-compatible function calling) via Ktor client for the agent loop.
```

- [ ] **Step 7: Commit**

```bash
git add client/src/main/kotlin/Agent.kt client/src/main/kotlin/Main.kt README.md
git commit -m "feat(client): DeepSeek agent REPL with MCP tool-calling loop"
```

---

## Self-Review

**Spec coverage:**
- DeepSeek client / `deepseek-v4-pro` / OpenAI-compatible → Task 1. ✓
- `DEEPSEEK_API_KEY` env/.env, process env wins, `.env.example` → Task 1 (`.env.example`) + Task 3 (`deepseekDotenv`). ✓ (`.gitignore` already ignores `.env` and `*.env`.)
- MCP→DeepSeek schema bridge → Task 2 (`toDeepSeekTools`/`toolParameters`). ✓
- Tool-calling loop, feed results back, bounded at 8 → Task 3 (`runTurn`). ✓
- Interactive REPL → Task 3 (`runAgentRepl`). ✓
- Light on/off/toggle via `call_service`, baked into system prompt + demo → Task 3. ✓
- Deterministic fallback when no key → Task 3 (`runDeterministicDemo`). ✓
- `reasoning_content` ignored / `ignoreUnknownKeys` → Task 1 (`DeepSeekJson` + test). ✓
- Error handling (HTTP/tool/loop-cap) → Task 3 (`try/catch` in REPL + per-call + cap notice). ✓
- Tests for mapping + arg parsing → Tasks 1–2. ✓

**Placeholder scan:** none — every code/command step is concrete.

**Type consistency:** `ChatMessage(role, content, toolCalls, toolCallId)`, `ToolDef(function = ToolFunctionDef(...))`, `ToolCall.function.name/.arguments`, `toolParameters(JsonObject?, List<String>?)`, `parseToolArgs(String): JsonObject`, `DeepSeekClient.chat(List<ChatMessage>, List<ToolDef>): ChatMessage` — used identically across Tasks 1–3. `callTool(name, arguments: Map<String, Any?>)` receives a `JsonObject` (is a `Map<String, JsonElement>`) — confirmed compatible via the SDK's `convertToJsonElement` passthrough.
