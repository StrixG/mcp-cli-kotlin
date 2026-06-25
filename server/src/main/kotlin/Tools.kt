import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private val prettyJson = Json { prettyPrint = true }

/** Text (success) result. */
private fun text(s: String) = CallToolResult(content = listOf(TextContent(s)))

/** Error result the agent can read as a message (no stack trace). */
private fun err(s: String?) =
    CallToolResult(content = listOf(TextContent(s ?: "Unknown error")), isError = true)

private fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

/** Attributes worth echoing after a service call so the agent sees what actually changed. */
private val NOTABLE_ATTRS = listOf(
    "brightness", "brightness_pct", "color_temp", "color_temp_kelvin", "rgb_color",
    "hs_color", "xy_color", "effect", "temperature", "current_temperature", "hvac_action",
    "fan_mode", "preset_mode", "position", "percentage",
)

/** One-line "key=value" digest of the notable attributes that are present. */
private fun EntityState.notableDigest(): String =
    NOTABLE_ATTRS.mapNotNull { k -> attributes[k]?.let { "$k=$it" } }.joinToString(", ")

/** A `{ "type": ..., "description": ... }` JSON-Schema property fragment. */
private fun prop(type: String, description: String) = buildJsonObject {
    put("type", type)
    put("description", description)
}

/**
 * Builds the MCP server and registers all Home Assistant tools against [client].
 * Same configured server is used by both the stdio and SSE transports.
 */
fun configureServer(client: HomeAssistantClient): Server {
    val server = Server(
        Implementation(name = "home-assistant-mcp", version = "0.1.0"),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    // 1) list_entities ------------------------------------------------------
    server.addTool(
        name = "list_entities",
        description = "List Home Assistant entities with their entity_id, friendly name and current " +
            "state. Optionally filter by domain (light, switch, sensor, climate, …).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("domain", prop("string", "Optional domain filter, e.g. 'light', 'switch', 'sensor', 'climate'."))
            },
        ),
    ) { request ->
        try {
            val domain = request.arguments.string("domain")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val states = client.getStates()
                .let { all -> if (domain != null) all.filter { it.entity_id.substringBefore('.') == domain } else all }
                .sortedBy { it.entity_id }

            val title = buildString {
                append("Found ${states.size} ")
                append(if (states.size == 1) "entity" else "entities")
                if (domain != null) append(" in domain '$domain'")
                append(":")
            }
            val lines = states.joinToString("\n") { "- ${it.entity_id}  [${it.state}]  ${it.friendlyName}" }
            text(if (states.isEmpty()) title else "$title\n$lines")
        } catch (e: HaException) {
            err(e.message)
        }
    }

    // 2) get_state ----------------------------------------------------------
    server.addTool(
        name = "get_state",
        description = "Get the current state and key attributes of one Home Assistant entity.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("entity_id", prop("string", "Full entity id, e.g. 'light.kitchen' or 'sensor.outdoor_temp'."))
            },
            required = listOf("entity_id"),
        ),
    ) { request ->
        val id = request.arguments.string("entity_id")
        if (id.isNullOrBlank()) {
            err("Parameter 'entity_id' is required.")
        } else try {
            val s = client.getState(id)
            val attrs = prettyJson.encodeToString(JsonObject.serializer(), s.attributes)
            text(
                """
                entity_id:    ${s.entity_id}
                friendly_name: ${s.friendlyName}
                state:        ${s.state}
                last_changed: ${s.last_changed ?: "?"}
                last_updated: ${s.last_updated ?: "?"}
                attributes:
                $attrs
                """.trimIndent(),
            )
        } catch (e: HaException) {
            err(e.message)
        }
    }

    // 3) call_service -------------------------------------------------------
    server.addTool(
        name = "call_service",
        description = "Call a Home Assistant service on an entity, e.g. light/turn_on, light/turn_off, " +
            "switch/toggle, climate/set_temperature. Returns the resulting state when HA reports it.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("domain", prop("string", "Service domain, e.g. 'light', 'switch', 'climate'."))
                put("service", prop("string", "Service name, e.g. 'turn_on', 'turn_off', 'toggle', 'set_temperature'."))
                put("entity_id", prop("string", "Target entity id, e.g. 'light.kitchen'."))
                put("data", prop("object", "Optional extra service data, e.g. {\"temperature\": 21} or {\"brightness_pct\": 50}."))
            },
            required = listOf("domain", "service", "entity_id"),
        ),
    ) { request ->
        val domain = request.arguments.string("domain")
        val service = request.arguments.string("service")
        val entityId = request.arguments.string("entity_id")
        when {
            domain.isNullOrBlank() -> err("Parameter 'domain' is required.")
            service.isNullOrBlank() -> err("Parameter 'service' is required.")
            entityId.isNullOrBlank() -> err("Parameter 'entity_id' is required.")
            else -> try {
                val extra = request.arguments.obj("data")
                val body = buildJsonObject {
                    put("entity_id", entityId)
                    extra?.forEach { (k, v) -> put(k, v) }
                }
                val changed = client.callService(domain, service, body)
                val newState = changed.firstOrNull { it.entity_id == entityId }
                    ?: runCatching { client.getState(entityId) }.getOrNull()
                text(
                    buildString {
                        append("Called $domain/$service on $entityId — OK.\n")
                        if (newState == null) {
                            append("(HA did not report a new state)")
                        } else {
                            append("New state: ${newState.state}")
                            newState.notableDigest().takeIf { it.isNotEmpty() }?.let { append(" ($it)") }
                            newState.last_updated?.let { append("\nlast_updated: $it") }
                        }
                    },
                )
            } catch (e: HaException) {
                err(e.message)
            }
        }
    }

    // 4) get_sensor (bonus) -------------------------------------------------
    server.addTool(
        name = "get_sensor",
        description = "Read a numeric sensor value with its unit, e.g. sensor.outdoor_temperature.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("entity_id", prop("string", "Sensor entity id, e.g. 'sensor.outdoor_temperature'."))
            },
            required = listOf("entity_id"),
        ),
    ) { request ->
        val id = request.arguments.string("entity_id")
        if (id.isNullOrBlank()) {
            err("Parameter 'entity_id' is required.")
        } else try {
            val s = client.getState(id)
            val unit = (s.attributes["unit_of_measurement"] as? JsonPrimitive)?.contentOrNull
            val numeric = s.state.toDoubleOrNull()
            if (numeric != null) {
                text("${s.friendlyName}: ${s.state}${unit?.let { " $it" } ?: ""}")
            } else {
                text("${s.friendlyName}: ${s.state} (non-numeric state${unit?.let { ", unit $it" } ?: ""})")
            }
        } catch (e: HaException) {
            err(e.message)
        }
    }

    return server
}
