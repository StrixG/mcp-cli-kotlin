import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One MCP server entry from servers.json / the MCP_SERVERS env var.
 *
 * - `transport` = "stdio" (spawn `command` + `args` as a subprocess) or
 *   "sse" (connect to `url`).
 * - `env` are extra environment variables for a stdio subprocess (e.g. HA secrets).
 * - `enabled=false` keeps an entry in the file but skips connecting to it.
 */
@Serializable
data class ServerSpec(
    val name: String,
    val transport: String = "stdio",
    val command: String? = null,
    val args: List<String> = emptyList(),
    val url: String? = null,
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    /** OAuth 2.1 settings for an `sse` server that requires authorization (cloud deploy). */
    val auth: AuthSpec? = null,
) {
    /** Full stdio launch line (command + args), for logging and ProcessBuilder. */
    fun stdioCommand(): List<String> = buildList {
        command?.let { add(it) }
        addAll(args)
    }
}

/**
 * How the client obtains an OAuth access token for an `sse` server.
 *
 * - `flow = "client_credentials"`: headless machine token from [clientId] + [clientSecret]
 *   (no browser). Best for the agent itself.
 * - `flow = "authorization_code"`: interactive browser login + PKCE; if [clientId] is null
 *   the client self-registers (RFC 7591 dynamic client registration).
 *
 * String fields support `${ENV_VAR}` interpolation so secrets stay out of servers.json
 * (resolved against the process env / client .env at connect time).
 */
@Serializable
data class AuthSpec(
    val flow: String = "client_credentials",
    val clientId: String? = null,
    val clientSecret: String? = null,
    val scope: String? = null,
    /** Token cache file. Default: mcp-data/<server>-token.json. */
    val tokenCache: String? = null,
)

/**
 * Expand `${VAR}` placeholders in [value] using [resolve] (typically the process env / .env),
 * so secrets like an OAuth client secret live in the environment, not in servers.json. A
 * referenced-but-unset variable throws, surfacing a config mistake loudly. Plain text and
 * literal `$` not followed by `{` pass through unchanged.
 */
fun interpolateEnv(value: String, resolve: (String) -> String?): String =
    Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}""").replace(value) { m ->
        val name = m.groupValues[1]
        resolve(name) ?: error("config references env var '$name' but it is not set")
    }

/** Lenient JSON: tolerate comments/trailing commas and unknown keys in servers.json. */
private val ServersJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    allowComments = true
    allowTrailingComma = true
}

/**
 * Resolve the list of MCP servers to connect, in priority order:
 *  1. CLI args (back-compat): a single stdio server `:client <command> <args…>`.
 *  2. MCP_SERVERS env — either inline JSON (`[ {…} ]`) or a path to a JSON file.
 *  3. A servers.json file found near the client module.
 *  4. A built-in default (the reference "everything" server) so it still runs bare.
 *
 * Disabled entries are dropped. Throws on a present-but-unparseable config so a typo
 * surfaces loudly instead of silently falling back.
 */
fun loadServerSpecs(args: Array<String>): List<ServerSpec> {
    if (args.isNotEmpty()) {
        return listOf(ServerSpec(name = "cli", command = args.first(), args = args.drop(1)))
    }

    val envValue = System.getenv("MCP_SERVERS")?.trim().orEmpty()
    if (envValue.isNotEmpty()) {
        val json = if (envValue.startsWith("[") || envValue.startsWith("{")) {
            envValue
        } else {
            val f = File(envValue)
            require(f.isFile) { "MCP_SERVERS points to '$envValue' but no such file exists." }
            f.readText()
        }
        return parseServers(json, "MCP_SERVERS")
    }

    val file = listOf(File("servers.json"), File("client/servers.json"), File("../client/servers.json"))
        .firstOrNull { it.isFile }
    if (file != null) {
        return parseServers(file.readText(), file.path)
    }

    return listOf(
        ServerSpec(name = "everything", command = "npx", args = listOf("-y", "@modelcontextprotocol/server-everything")),
    )
}

/** Parse a `[ {…}, … ]` array (or `{ "mcpServers": {…} }` object) into specs. */
private fun parseServers(json: String, source: String): List<ServerSpec> {
    val specs = runCatching {
        if (json.trimStart().startsWith("[")) {
            ServersJson.decodeFromString<List<ServerSpec>>(json)
        } else {
            ServersJson.decodeFromString<ServersFile>(json).toSpecs()
        }
    }.getOrElse { e -> error("Cannot parse MCP server config from $source: ${e.message}") }
    return specs.filter { it.enabled }
}

/**
 * Accepts either a bare JSON array of [ServerSpec] or a `{ "servers": [...] }` /
 * `{ "mcpServers": { name: {...} } }` wrapper, so common config shapes all work.
 */
@Serializable
private data class ServersFile(
    val servers: List<ServerSpec>? = null,
    @SerialName("mcpServers") val mcpServers: Map<String, ServerSpec>? = null,
) {
    fun toSpecs(): List<ServerSpec> = when {
        servers != null -> servers
        mcpServers != null -> mcpServers.map { (name, s) -> if (s.name.isBlank()) s.copy(name = name) else s }
        else -> emptyList()
    }
}
