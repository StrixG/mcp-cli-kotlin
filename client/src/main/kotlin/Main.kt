import io.github.cdimascio.dotenv.dotenv
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

/** Default reference server: an "everything" test server with a rich toolset. */
private val DEFAULT_SERVER = listOf("npx", "-y", "@modelcontextprotocol/server-everything")

/** Service messages go to stderr so stdout stays clean for the actual result. */
private fun log(message: String) = System.err.println(message)

fun main(args: Array<String>) {
    // Force UTF-8 on our own streams. Default JVM stdout/stderr charset follows the
    // platform (CP866 on a Russian Windows console), which mangles non-ASCII like the
    // em dash in printTool into mojibake ("тАФ"). UTF-8 keeps output deterministic.
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    // CLI args = server command + its args. No args -> default reference server.
    val command = if (args.isNotEmpty()) args.toList() else DEFAULT_SERVER
    log("Starting MCP server: ${command.joinToString(" ")}")

    // On Windows, npx/node launchers are .cmd files; Java's ProcessBuilder won't
    // resolve them from a bare name, so route through the shell.
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val launch = if (isWindows) listOf("cmd", "/c") + command else command

    val process = try {
        ProcessBuilder(launch)
            .redirectError(ProcessBuilder.Redirect.INHERIT) // server logs -> our stderr
            .start()
    } catch (e: Exception) {
        log("ERROR: cannot start server process '${command.first()}': ${e.message}")
        log("Check that the command exists on PATH (e.g. Node/npx for the stdio reference server).")
        exitProcess(1)
    }

    val transport = StdioClientTransport(
        input = process.inputStream.asSource().buffered(),
        output = process.outputStream.asSink().buffered(),
    )

    val client = Client(
        clientInfo = Implementation(name = "mcp-cli-kotlin", version = "0.1.0"),
    )

    try {
        runBlocking {
            // handshake / initialize
            client.connect(transport)

            val server = client.serverVersion
            println("Connected to: ${server?.name ?: "unknown"} ${server?.version ?: ""}".trim())

            val tools = client.listTools().tools
            println("Available tools (${tools.size}):")
            tools.forEachIndexed { i, tool ->
                printTool(i + 1, tool)
            }

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

            client.close()
        }
    } catch (e: Exception) {
        log("ERROR: MCP communication failed: ${e.message}")
        process.destroy()
        exitProcess(1)
    } finally {
        // Make sure no subprocess is left hanging.
        if (process.isAlive) process.destroy()
    }

    exitProcess(0)
}

/**
 * Choose a tool to demo-call plus arguments for it, or null if nothing is safe to
 * call without inventing required parameters. Prefers our own Home Assistant
 * `list_entities`, then the reference server's `echo`, then any tool that takes no
 * required params.
 */
private fun pickDemoCall(tools: List<Tool>): Pair<String, Map<String, Any?>>? {
    tools.firstOrNull { it.name == "list_entities" }
        ?.let { return "list_entities" to mapOf("domain" to "light") }
    tools.firstOrNull { it.name == "echo" }
        ?.let { return "echo" to mapOf("message" to "Hello from mcp-cli-kotlin") }
    tools.firstOrNull { it.inputSchema.required.isNullOrEmpty() }
        ?.let { return it.name to emptyMap() }
    return null
}

/** Flatten a tool result's text content blocks into one string. */
private fun CallToolResult?.text(): String =
    this?.content?.filterIsInstance<TextContent>()?.mapNotNull { it.text }?.joinToString("\n").orEmpty()

private fun printTool(index: Int, tool: Tool) {
    val desc = tool.description?.trim().takeUnless { it.isNullOrEmpty() } ?: "(no description)"
    println("  $index. ${tool.name} — $desc")
    println("     params: ${formatParams(tool.inputSchema.properties, tool.inputSchema.required)}")
}

/** Renders inputSchema properties as "name: type[*]" (asterisk marks required), or "none". */
private fun formatParams(properties: JsonObject?, required: List<String>?): String {
    if (properties == null || properties.isEmpty()) return "none"
    val req = required?.toSet().orEmpty()
    return properties.entries.joinToString(", ") { (name, schema) ->
        val type = (schema as? JsonObject)?.get("type")?.jsonPrimitive?.content ?: "any"
        val mark = if (name in req) "*" else ""
        "$name: $type$mark"
    }
}

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
    println("Result${if (result.isError == true) " (error)" else ""}:")
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
