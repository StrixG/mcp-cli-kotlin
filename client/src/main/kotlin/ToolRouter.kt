import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.JsonObject

/**
 * Aggregates the tools of several MCP servers behind one namespaced tool list and
 * routes each call back to the server that owns it.
 *
 * DeepSeek (like the OpenAI function-calling spec) requires globally unique tool
 * names, but independent servers can each expose a `read_file` or `echo`. So every
 * tool is exposed to the model as `"<server>__<tool>"`. On a call we split off the
 * `<server>` prefix, look up the owning server, and invoke the *original* tool name
 * on it. This keeps the model's choice of server unambiguous and collision-free.
 */
class ToolRouter(servers: List<RoutedServer>) {

    /**
     * A connected server as far as routing is concerned: its name, the tools it
     * reported, and a suspend lambda that actually calls one of them. Decoupling the
     * lambda from the MCP `Client` keeps the router unit-testable with fakes.
     */
    class RoutedServer(
        val name: String,
        val tools: List<Tool>,
        val invoke: suspend (toolName: String, args: JsonObject) -> CallToolResult,
    )

    private data class Route(val server: RoutedServer, val toolName: String)

    /** namespaced name -> (owning server, original tool name). */
    private val routes: Map<String, Route> = buildMap {
        for (server in servers) {
            for (tool in server.tools) {
                put(namespaced(server.name, tool.name), Route(server, tool.name))
            }
        }
    }

    /**
     * The combined, namespaced tool list handed to the model. Each tool keeps its
     * schema; only the name is prefixed and the description is tagged with the server
     * so the model can reason about which backend a tool belongs to.
     */
    val tools: List<Tool> = servers.flatMap { server ->
        server.tools.map { tool ->
            tool.copy(
                name = namespaced(server.name, tool.name),
                description = "[${server.name}] " + (tool.description?.trim().orEmpty()),
            )
        }
    }

    /** Names of the servers that contributed at least one tool, for diagnostics. */
    val serverNames: List<String> = servers.map { it.name }

    /**
     * Route a namespaced call to its owning server. Unknown names (model hallucinated
     * a tool, or a typo) raise a readable error listing what is available, rather than
     * a null/NPE deep in the SDK.
     */
    suspend fun callTool(namespacedName: String, args: JsonObject): CallToolResult {
        val route = routes[namespacedName]
            ?: throw IllegalArgumentException(
                "Unknown tool '$namespacedName'. Known tools: ${routes.keys.sorted().joinToString(", ")}",
            )
        return route.server.invoke(route.toolName, args)
    }

    companion object {
        /** Separator between server and tool name. Avoids the single '_' many tools use. */
        const val SEPARATOR = "__"

        fun namespaced(server: String, tool: String): String = "$server$SEPARATOR$tool"
    }
}
