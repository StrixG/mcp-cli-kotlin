import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpSseTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

/** Service messages go to stderr so stdout stays clean for the actual result. */
private fun log(message: String) = System.err.println(message)

/** A live MCP connection: its config, the connected client + reported tools, and how to tear it down. */
private class Connection(
    val spec: ServerSpec,
    val client: Client,
    val tools: List<Tool>,
    val cleanup: () -> Unit,
)

fun main(args: Array<String>) {
    // Force UTF-8 on our own streams. Default JVM stdout/stderr charset follows the
    // platform (CP866 on a Russian Windows console), which mangles non-ASCII like the
    // em dash in printTool into mojibake ("тАФ"). UTF-8 keeps output deterministic.
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    val specs = try {
        loadServerSpecs(args)
    } catch (e: Exception) {
        log("ERROR: ${e.message}")
        exitProcess(1)
    }
    log("Configured MCP servers (${specs.size}): ${specs.joinToString(", ") { it.name }}")

    val connections = mutableListOf<Connection>()
    try {
        runBlocking {
            // Connect every configured server. A server that fails to start is logged and
            // skipped — one bad entry must not bring the whole agent down.
            for (spec in specs) {
                val conn = try {
                    connect(spec)
                } catch (e: Exception) {
                    log("WARN: server '${spec.name}' failed to connect: ${e.message} — skipping.")
                    null
                }
                if (conn != null) {
                    connections += conn
                    log("Connected '${conn.spec.name}': ${conn.tools.size} tool(s).")
                }
            }

            if (connections.isEmpty()) {
                log("ERROR: no MCP servers connected. Check servers.json / MCP_SERVERS.")
                exitProcess(1)
            }

            val router = ToolRouter(
                connections.map { conn ->
                    ToolRouter.RoutedServer(conn.spec.name, conn.tools) { tool, callArgs ->
                        conn.client.callTool(name = tool, arguments = callArgs)
                    }
                },
            )

            println("Aggregated ${router.tools.size} tool(s) from ${router.serverNames.size} server(s):")
            router.tools.forEachIndexed { i, tool -> printTool(i + 1, tool) }

            // If a DeepSeek key is present, hand control to the LLM agent: it picks
            // tools across servers, calls them via the router, and uses the results.
            // Otherwise fall back to the deterministic demo so `:client` still runs
            // (and tests stay valid) without any API key.
            val apiKey = loadDeepSeekKey()
            if (apiKey != null) {
                val model = loadDeepSeekModel()
                log("DeepSeek agent mode (model=$model).")
                val deepseek = DeepSeekClient(apiKey = apiKey, model = model)
                try {
                    runAgentRepl(router, deepseek)
                } finally {
                    deepseek.close()
                }
            } else {
                log("DEEPSEEK_API_KEY not set — running deterministic demo (no LLM). " +
                    "Set it in client/.env for agent mode.")
                runDeterministicDemo(router)
            }
        }
    } catch (e: Exception) {
        log("ERROR: MCP communication failed: ${e.message}")
        closeAll(connections)
        exitProcess(1)
    } finally {
        closeAll(connections)
    }

    exitProcess(0)
}

/** Bring up one server per its transport. Throws on failure (caller logs + skips). */
private suspend fun connect(spec: ServerSpec): Connection = when (spec.transport.lowercase()) {
    "stdio" -> connectStdio(spec)
    "sse" -> connectSse(spec)
    else -> error("unknown transport '${spec.transport}' (use stdio or sse)")
}

/** Spawn a stdio server subprocess and run the MCP handshake over its stdin/stdout. */
private suspend fun connectStdio(spec: ServerSpec): Connection {
    val command = spec.stdioCommand()
    require(command.isNotEmpty()) { "server '${spec.name}' has stdio transport but no command" }
    log("Starting '${spec.name}': ${command.joinToString(" ")}")

    // On Windows, npx/node launchers are .cmd files; Java's ProcessBuilder won't
    // resolve them from a bare name, so route through the shell.
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val launch = if (isWindows) listOf("cmd", "/c") + command else command

    val builder = ProcessBuilder(launch)
        .redirectError(ProcessBuilder.Redirect.INHERIT) // server logs -> our stderr
    builder.environment().putAll(spec.env) // extra env (e.g. HA secrets) for this server
    val process = builder.start()

    val transport = StdioClientTransport(
        input = process.inputStream.asSource().buffered(),
        output = process.outputStream.asSink().buffered(),
    )
    val client = Client(clientInfo = Implementation(name = "mcp-cli-kotlin", version = "0.1.0"))

    return try {
        client.connect(transport)
        val tools = client.listTools().tools
        Connection(spec, client, tools) {
            runCatching { runBlocking { client.close() } }
            if (process.isAlive) process.destroy()
        }
    } catch (e: Exception) {
        // Handshake failed: make sure we don't leak the subprocess we just spawned.
        if (process.isAlive) process.destroy()
        throw e
    }
}

/**
 * Connect to an SSE server over HTTP and run the MCP handshake. For an OAuth-protected
 * server (`spec.auth`), obtain an access token first and send it on every request via the
 * Authorization header (covers both the SSE GET stream and the message POSTs). If the first
 * attempt fails — e.g. a cached token went stale — drop the token and re-authenticate once.
 */
private suspend fun connectSse(spec: ServerSpec): Connection {
    val url = spec.url ?: error("server '${spec.name}' has sse transport but no url")
    log("Connecting '${spec.name}' (sse): $url")
    return try {
        openSse(spec, url)
    } catch (e: Exception) {
        if (spec.auth == null) throw e
        log("'${spec.name}' SSE connect failed (${e.message}); refreshing token and retrying once")
        OAuth.invalidate(spec)
        openSse(spec, url)
    }
}

/** One SSE connection attempt with the current (possibly freshly minted) token. */
private suspend fun openSse(spec: ServerSpec, url: String): Connection {
    val http = if (spec.auth != null) {
        val token = OAuth.token(spec, ::resolveEnv)
        HttpClient(CIO) {
            install(SSE)
            install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer ${token.accessToken}") }
        }
    } else {
        HttpClient(CIO) { install(SSE) }
    }
    val transport = http.mcpSseTransport(url)
    val client = Client(clientInfo = Implementation(name = "mcp-cli-kotlin", version = "0.1.0"))

    return try {
        client.connect(transport)
        val tools = client.listTools().tools
        Connection(spec, client, tools) {
            runCatching { runBlocking { client.close() } }
            http.close()
        }
    } catch (e: Exception) {
        http.close()
        throw e
    }
}

/** Tear down every connection: close clients, kill subprocesses, release HTTP clients. */
private fun closeAll(connections: List<Connection>) {
    for (conn in connections) {
        runCatching { conn.cleanup() }
            .onFailure { log("WARN: cleanup for '${conn.spec.name}' failed: ${it.message}") }
    }
}

/** Flatten a tool result's text content blocks into one string. */
private fun CallToolResult?.text(): String =
    this?.content?.filterIsInstance<TextContent>()?.mapNotNull { it.text }?.joinToString("\n").orEmpty()

private fun printTool(index: Int, tool: Tool) {
    val desc = tool.description?.trim().takeUnless { it.isNullOrEmpty() } ?: "(no description)"
    println("  $index. ${tool.name} — $desc")
}

/**
 * Deterministic fallback (no LLM): pick one safe namespaced tool and call it through
 * the router, proving aggregation + routing without an API key. Prefers a Home
 * Assistant `list_entities`, then a time tool, then any no-required-param tool.
 */
private suspend fun runDeterministicDemo(router: ToolRouter) {
    val call = pickDemoCall(router.tools)
    if (call == null) {
        log("No tool safe to auto-call (all have required params we can't guess); skipping demo call.")
        return
    }
    val (name, arguments) = call
    println("\nCalling tool: $name ${if (arguments.isEmpty()) "(no args)" else arguments}")
    val result = try {
        router.callTool(name, arguments)
    } catch (e: Exception) {
        log("Demo call failed: ${e.message}")
        return
    }
    println("Result${if (result.isError == true) " (error)" else ""}:")
    renderMarkdown(result.text().ifBlank { "(empty)" })
}

/** Choose a namespaced demo tool + args, or null if nothing is safe to auto-call. */
private fun pickDemoCall(tools: List<Tool>): Pair<String, JsonObject>? {
    val empty = JsonObject(emptyMap())
    tools.firstOrNull { it.name.endsWith("${ToolRouter.SEPARATOR}list_entities") }
        ?.let { return it.name to empty }
    tools.firstOrNull { it.inputSchema.required.isNullOrEmpty() }
        ?.let { return it.name to empty }
    return null
}

/** Read DEEPSEEK_API_KEY from process env or client/.env (process env wins). */
private fun loadDeepSeekKey(): String? =
    deepseekDotenv()["DEEPSEEK_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() }

/** Read DEEPSEEK_MODEL override, defaulting to deepseek-v4-pro. */
private fun loadDeepSeekModel(): String =
    deepseekDotenv()["DEEPSEEK_MODEL"]?.trim()?.takeIf { it.isNotEmpty() } ?: "deepseek-v4-pro"

/** Cached env view (process env + client/.env) used to expand ${VAR} in server auth config. */
private val clientEnv by lazy { deepseekDotenv() }

/** Resolve an env var for config interpolation; process env wins (dotenv merges it). */
private fun resolveEnv(name: String): String? = clientEnv[name]?.takeIf { it.isNotEmpty() }

/** dotenv reader that locates client/.env across the launchers' working dirs. */
private fun deepseekDotenv() = dotenv {
    ignoreIfMissing = true
    ignoreIfMalformed = true
    val dir = listOf(File("."), File("client"), File("../client"))
        .firstOrNull { File(it, ".env").isFile }?.path
    if (dir != null) directory = dir
}
