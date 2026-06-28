import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolRouterTest {

    private fun tool(name: String, description: String = "d") =
        Tool(name = name, inputSchema = ToolSchema(), description = description)

    /** A fake server that records (toolName, args) and echoes the tool name back. */
    private class FakeServer(val name: String, toolNames: List<String>) {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val tools = toolNames.map { Tool(name = it, inputSchema = ToolSchema(), description = "d") }
        fun routed() = ToolRouter.RoutedServer(name, tools) { tool, args ->
            calls += tool to args
            CallToolResult(content = listOf(TextContent("$name/$tool")))
        }
    }

    @Test
    fun namespacesEveryToolWithItsServer() {
        val router = ToolRouter(
            listOf(
                ToolRouter.RoutedServer("time", listOf(tool("current_time"))) { _, _ ->
                    CallToolResult(content = emptyList())
                },
                ToolRouter.RoutedServer("fs", listOf(tool("write_file"))) { _, _ ->
                    CallToolResult(content = emptyList())
                },
            ),
        )
        val names = router.tools.map { it.name }.toSet()
        assertEquals(setOf("time__current_time", "fs__write_file"), names)
        // Description is tagged with the owning server so the model can reason about it.
        assertTrue(router.tools.all { it.description!!.startsWith("[") })
    }

    @Test
    fun routesCallToOwningServerAndStripsPrefix() = runTest {
        val time = FakeServer("time", listOf("current_time"))
        val fs = FakeServer("fs", listOf("write_file"))
        val router = ToolRouter(listOf(time.routed(), fs.routed()))

        router.callTool("fs__write_file", JsonObject(emptyMap()))

        assertTrue(time.calls.isEmpty())
        assertEquals(1, fs.calls.size)
        // The prefix is stripped: the underlying server sees the ORIGINAL tool name.
        assertEquals("write_file", fs.calls.single().first)
    }

    @Test
    fun resolvesNameCollisionsBetweenServers() = runTest {
        // Both servers expose a tool literally named "echo" — namespacing disambiguates.
        val a = FakeServer("a", listOf("echo"))
        val b = FakeServer("b", listOf("echo"))
        val router = ToolRouter(listOf(a.routed(), b.routed()))

        assertEquals(setOf("a__echo", "b__echo"), router.tools.map { it.name }.toSet())

        router.callTool("b__echo", JsonObject(emptyMap()))
        assertTrue(a.calls.isEmpty())
        assertEquals(1, b.calls.size)
    }

    @Test
    fun preservesToolNamesContainingTheSeparator() = runTest {
        // A tool name with its own "__" must round-trip: split only on the FIRST "__".
        val srv = FakeServer("srv", listOf("get__state"))
        val router = ToolRouter(listOf(srv.routed()))

        router.callTool("srv__get__state", JsonObject(emptyMap()))
        assertEquals("get__state", srv.calls.single().first)
    }

    @Test
    fun unknownToolRaisesReadableError() = runTest {
        val router = ToolRouter(
            listOf(
                ToolRouter.RoutedServer("time", listOf(tool("current_time"))) { _, _ ->
                    CallToolResult(content = emptyList())
                },
            ),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            router.callTool("nope__missing", JsonObject(emptyMap()))
        }
        assertTrue(ex.message!!.contains("Unknown tool"))
        assertTrue(ex.message!!.contains("time__current_time"))
    }
}
