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
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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
    reportsDir: java.io.File,
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
            "state. Always call this first to discover available entity ids — never guess an entity id. " +
            "Optionally filter by domain (light, switch, sensor, climate, …).",
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
        description = "Get the current state and key attributes of one Home Assistant entity. " +
            "Note: last_changed only moves when the on/off state flips; last_updated moves on every " +
            "change including attributes (brightness, color_temp). Use last_updated to verify that a " +
            "setting change took effect, not last_changed.",
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
            "switch/toggle, climate/set_temperature. Returns the resulting state when HA reports it. " +
            "To verify the change took effect, compare last_updated (not last_changed — last_changed " +
            "only moves on on/off state flips, not on attribute changes like brightness or color_temp).",
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
            val since = Instant.now().minusSeconds(periodSec).toEpochMilli()
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

    // 7) save_report -------------------------------------------------------
    server.addTool(
        name = "save_report",
        description = "Save a text report to a file on the server and return its path. Use this as the " +
            "final step of a pipeline when the user wants to save/export a summary or report: pass the " +
            "text produced by an earlier tool (e.g. the output of get_summary) as 'content'. The server " +
            "writes exactly the content it is given — it does not call any AI.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("content", prop("string", "The report text to write, e.g. the summary returned by get_summary."))
                put("filename", prop("string", "Optional file name (basename only, no directories). Default: report-YYYYMMDD-HHmmss.<format>."))
                put("format", prop("string", "File format / extension: 'md' (default), 'txt' or 'json'."))
            },
            required = listOf("content"),
        ),
    ) { request ->
        val content = request.arguments.string("content")
        if (content.isNullOrEmpty()) {
            err("Parameter 'content' is required (the report text to save).")
        } else try {
            val saved = writeReport(
                baseDir = reportsDir,
                content = content,
                filename = request.arguments.string("filename"),
                format = request.arguments.string("format"),
            )
            text("Saved report to ${saved.absolutePath} (${saved.length()} bytes).")
        } catch (e: IllegalArgumentException) {
            err(e.message)
        } catch (e: Exception) {
            err("Failed to save report: ${e.message}")
        }
    }

    return server
}

/** Report formats save_report accepts; the value is also used as the file extension. */
private val REPORT_EXTS = setOf("md", "txt", "json")

private val REPORT_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/**
 * Write [content] (verbatim, UTF-8) to a file under [baseDir]. [filename] is sanitised to a
 * bare basename — any path component, '..' or separator is rejected to prevent traversal —
 * and defaults to `report-YYYYMMDD-HHmmss.<format>` when omitted. [format] selects the
 * extension for generated/extension-less names (md/txt/json, default md). Returns the file.
 *
 * Top-level + `internal` so it is unit-testable directly, without MCP plumbing. Throws
 * [IllegalArgumentException] on an unsupported format or an unsafe filename; the tool handler
 * turns that into a readable `err(...)` message.
 */
internal fun writeReport(baseDir: File, content: String, filename: String?, format: String?): File {
    val ext = format?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        ?.let { if (it in REPORT_EXTS) it else throw IllegalArgumentException("Unsupported format '$it'. Use md, txt or json.") }
        ?: "md"

    val name = filename?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        // Basename only: reject anything that escapes the reports directory.
        if (raw != File(raw).name || raw.contains("..") || raw.contains('/') || raw.contains('\\')) {
            throw IllegalArgumentException("'filename' must be a bare name without path separators, got '$raw'.")
        }
        if (raw.contains('.')) raw else "$raw.$ext"
    } ?: "report-${ZonedDateTime.now().format(REPORT_TS)}.$ext"

    baseDir.mkdirs()
    val target = File(baseDir, name).canonicalFile
    // Defense in depth: the resolved path must stay inside the sandbox.
    if (!target.canonicalPath.startsWith(baseDir.canonicalFile.canonicalPath + File.separator)) {
        throw IllegalArgumentException("Refusing to write outside the reports directory.")
    }
    target.writeText(content, Charsets.UTF_8)
    return target
}
