import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant

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

private fun JsonObject?.stringList(key: String): List<String>? =
    (this?.get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
        ?.filter { it.isNotEmpty() }

/** "21.4" -> "21.4", null stays "—". Compact one-decimal-ish formatting for summaries. */
private fun Double?.fmt(): String = this?.let {
    if (it == it.toLong().toDouble()) it.toLong().toString() else "%.2f".format(it)
} ?: "—"

/**
 * Builds the MCP server and registers all Home Assistant tools against [client].
 * The same configured server is used by both the stdio and SSE transports. [storage]
 * and [config] back the day-18 scheduler tools (`get_summary`, `configure_collection`).
 */
fun configureServer(
    client: HomeAssistantClient,
    storage: Storage,
    config: CollectionConfig,
): Server {
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

    // 5) get_summary --------------------------------------------------------
    server.addTool(
        name = "get_summary",
        description = "Aggregate the background collector's stored measurements over a time window. " +
            "Returns count + min/max/avg/last per entity. Use this on a schedule to get a periodic " +
            "digest of tracked sensors (temperature, humidity, energy, …) collected 24/7 by the server.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("entity_id", prop("string", "Optional entity id to summarise. Omit to summarise every tracked entity."))
                put("period", prop("string", "Time window back from now: '1h', '24h', '7d', '30m', '60s'. Default '24h'."))
                put("metric", prop("string", "Headline metric to highlight: 'avg' (default), 'min', 'max' or 'last'."))
            },
        ),
    ) { request ->
        val periodRaw = request.arguments.string("period") ?: "24h"
        val periodSec = parseDurationSeconds(periodRaw)
        val metric = (request.arguments.string("metric")?.trim()?.lowercase() ?: "avg")
            .takeIf { it in setOf("avg", "min", "max", "last") } ?: "avg"
        if (periodSec == null) {
            err("Invalid 'period' '$periodRaw'. Use forms like '1h', '24h', '7d', '30m', '60s'.")
        } else try {
            val since = Instant.now().minusSeconds(periodSec).epochSecond
            val requested = request.arguments.string("entity_id")?.trim()?.takeIf { it.isNotEmpty() }
            val entities = requested?.let { listOf(it) } ?: storage.knownEntities()

            if (entities.isEmpty()) {
                text("No measurements stored yet. The collector writes a snapshot every ${config.intervalSeconds}s; check back after the next cycle.")
            } else {
                val summaries = entities.map { storage.summarize(it, since) }
                val withData = summaries.filter { it.count > 0 }
                if (withData.isEmpty()) {
                    text("No data points in the last $periodRaw for ${if (requested != null) requested else "any tracked entity"}.")
                } else {
                    val body = withData.joinToString("\n") { s ->
                        val headline = when (metric) {
                            "min" -> "min=${s.min.fmt()}"
                            "max" -> "max=${s.max.fmt()}"
                            "last" -> "last=${s.last?.fmt() ?: s.lastState ?: "—"}"
                            else -> "avg=${s.avg.fmt()}"
                        }
                        buildString {
                            append("- ${s.entityId}: $headline  ")
                            append("(min=${s.min.fmt()}, max=${s.max.fmt()}, avg=${s.avg.fmt()}, ")
                            append("last=${s.last?.fmt() ?: s.lastState ?: "—"}, n=${s.count})")
                        }
                    }
                    text("Summary over last $periodRaw (metric=$metric):\n$body")
                }
            }
        } catch (e: Exception) {
            err("Failed to build summary: ${e.message}")
        }
    }

    // 6) configure_collection -----------------------------------------------
    server.addTool(
        name = "configure_collection",
        description = "View or change what the background collector tracks and how often. Pass " +
            "'entities' and/or 'interval' to update (changes apply on the next cycle and persist across " +
            "restarts); pass nothing to just read the current settings.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("entities", buildJsonObject {
                    put("type", "array")
                    put("description", "Entity ids to track, e.g. ['sensor.outdoor_temp','sensor.humidity']. Empty array = track all entities.")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("interval", prop("string", "Collection interval: '60s', '5m', '1h'. Minimum 1s."))
            },
        ),
    ) { request ->
        try {
            val newEntities = request.arguments.stringList("entities")
            val intervalRaw = request.arguments.string("interval")
            val newInterval = intervalRaw?.let { parseDurationSeconds(it) }
            if (intervalRaw != null && newInterval == null) {
                err("Invalid 'interval' '$intervalRaw'. Use forms like '60s', '5m', '1h'.")
            } else {
                if (newEntities != null || newInterval != null) {
                    val snap = config.update(entities = newEntities, intervalSeconds = newInterval)
                    storage.saveConfig(snap)
                }
                val snap = config.snapshot()
                val tracked = snap.entities.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "(all entities)"
                text(
                    """
                    Collection settings${if (newEntities != null || newInterval != null) " updated" else ""}:
                    - interval: ${snap.intervalSeconds}s
                    - tracking: $tracked
                    """.trimIndent(),
                )
            }
        } catch (e: Exception) {
            err("Failed to configure collection: ${e.message}")
        }
    }

    return server
}
