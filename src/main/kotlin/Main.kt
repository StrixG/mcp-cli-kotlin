import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
