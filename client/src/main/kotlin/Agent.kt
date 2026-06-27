import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val MAX_TOOL_ITERATIONS = 8

private val SYSTEM_PROMPT = """
    You are a smart-home assistant controlling Home Assistant through the provided tools.
    Use the tools to inspect and change device state. Never invent entity ids — discover
    them with list_entities first. To switch a light, call call_service with
    domain="light" and service "turn_on", "turn_off", or "toggle" for the target
    entity_id. After acting, confirm the outcome to the user in one short sentence.

    Judging whether a change took effect: compare the entity's attributes (brightness,
    color_temp, etc.) and `last_updated`, NOT `last_changed`. `last_changed` only moves
    when the on/off state flips; adjusting brightness or color on an already-on light
    leaves `last_changed` frozen while `last_updated` advances. A frozen `last_changed`
    does not mean the device is unresponsive.
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
suspend fun runAgentRepl(mcpClient: Client, tools: List<Tool>, deepseek: DeepSeekClient) {
    val toolDefs = toDeepSeekTools(tools)
    val history = mutableListOf(ChatMessage(role = "system", content = systemPrompt()))
    val stdin = System.`in`.bufferedReader(Charsets.UTF_8)

    println("\nAgent ready. Type a goal (e.g. \"turn on the kitchen light\"). 'exit' to quit.")
    while (true) {
        print("\n> ")
        System.out.flush()
        val goal = stdin.readLine()?.trim() ?: break
        if (goal.isEmpty()) continue
        if (goal.equals("exit", ignoreCase = true) || goal.equals("quit", ignoreCase = true)) break

        history[0] = ChatMessage(role = "system", content = systemPrompt())
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
            renderMarkdown(reply.content?.trim().orEmpty().ifEmpty { "(no answer)" })
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
    System.err.println("(stopped: reached $MAX_TOOL_ITERATIONS tool iterations without a final answer)")
}
