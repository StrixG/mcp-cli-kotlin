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
