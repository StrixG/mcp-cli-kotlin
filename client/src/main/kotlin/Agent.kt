import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val MAX_TOOL_ITERATIONS = 12

private val SYSTEM_PROMPT = """
    You are an orchestration agent wired to SEVERAL independent MCP servers at once.
    Every tool name is prefixed with its server as `<server>__<tool>` (e.g.
    `home-assistant__list_entities`, `time__current_time`, `fetch__fetch`).
    Pick the right server for each step and chain tools across servers to satisfy the
    request. Read each tool's description/schema; never invent a tool or its arguments.

    Time (`time__*`): use it for the current date/time/timezone — do not guess the clock.

    Fetch (`fetch__*`): prefer structured JSON API endpoints over HTML pages. Discover
    any context you need (location, entity ids) from HA tools first rather than asking
    the user.

    After completing the request, confirm the outcome to the user in a short answer.
""".trimIndent()

/** Human-readable local timestamp, e.g. "Saturday, 2026-06-27 14:05 MSK (UTC+03:00)". */
private val CLOCK_FORMAT =
    DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm zzz (xxx)")

/** System prompt plus a fresh "current time" line so relative queries resolve correctly. */
private fun systemPrompt(): String {
    val now = ZonedDateTime.now().format(CLOCK_FORMAT)
    return "$SYSTEM_PROMPT\n\nCurrent local date and time: $now. " +
        "Use this for any relative time reference (today, now, last hour, this morning)."
}

/** Flatten a tool result's text blocks into one string (mirrors Main's helper). */
private fun CallToolResult.textContent(): String =
    content.filterIsInstance<TextContent>().mapNotNull { it.text }.joinToString("\n")

/**
 * Interactive agent REPL: read a goal from stdin, let DeepSeek drive the MCP tools via
 * function calling, feed each tool result back, and print the model's final answer.
 * Diagnostics (which tool is being called) go to stderr; answers go to stdout.
 */
suspend fun runAgentRepl(router: ToolRouter, deepseek: DeepSeekClient) {
    val toolDefs = toDeepSeekTools(router.tools)
    val history = mutableListOf(ChatMessage(role = "system", content = systemPrompt()))
    val stdin = System.`in`.bufferedReader(Charsets.UTF_8)

    println("\nAgent ready (servers: ${router.serverNames.joinToString(", ")}). " +
        "Type a goal. 'exit' to quit.")
    while (true) {
        print("\n> ")
        System.out.flush()
        val goal = stdin.readLine()?.trim() ?: break
        if (goal.isEmpty()) continue
        if (goal.equals("exit", ignoreCase = true) || goal.equals("quit", ignoreCase = true)) break

        history[0] = ChatMessage(role = "system", content = systemPrompt())
        history += ChatMessage(role = "user", content = goal)
        try {
            runTurn(router, toolDefs, deepseek, history)
        } catch (e: Exception) {
            System.err.println("DeepSeek/agent error: ${e.message}")
        }
    }
    println("Bye.")
}

/** One user turn: loop tool-calls until the model returns a plain answer (bounded). */
private suspend fun runTurn(
    router: ToolRouter,
    toolDefs: List<ToolDef>,
    deepseek: DeepSeekClient,
    history: MutableList<ChatMessage>,
) {
    repeat(MAX_TOOL_ITERATIONS) {
        val reply = deepseek.chat(history, toolDefs)
        history += reply

        val calls = reply.toolCalls
        if (calls.isNullOrEmpty()) {
            renderMarkdown(reply.content?.trim().orEmpty().ifEmpty { "(no answer)" })
            return
        }

        for (call in calls) {
            val args = parseToolArgs(call.function.arguments)
            System.err.println("  · ${call.function.name} $args")
            val resultText = try {
                router.callTool(call.function.name, args).textContent()
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
    System.err.println("(stopped: reached $MAX_TOOL_ITERATIONS tool iterations without a final answer)")
}
