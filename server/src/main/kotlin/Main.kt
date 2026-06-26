import io.github.cdimascio.dotenv.dotenv
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/** Service messages go to stderr so stdout stays clean for the MCP protocol (stdio). */
private fun log(message: String) = System.err.println(message)

/** The real fd-1 stream, reserved for MCP protocol bytes. Set once in [main]. */
private lateinit var protocolOut: PrintStream

/**
 * Locate the directory holding a `.env`, returning its path for dotenv-kotlin's
 * `directory`, or null if none found. Tries cwd-relative spots (covers gradle
 * :server:run, :client:run, and bare `java -jar`) plus the server module dir derived
 * from the running jar's own location — so it works no matter who launched us.
 */
private fun findEnvDir(): String? {
    val candidates = mutableListOf<java.io.File>()
    // Prefer the server module's own dir, derived from the running jar's location
    // (<server>/build/libs/server-all.jar -> .env sits three dirs up). This must come
    // first: when :client:run spawns us, our cwd is client/, whose .env is the agent's
    // (DEEPSEEK_API_KEY, no HA keys) — matching it would mask the real server/.env.
    runCatching {
        val jar = java.io.File(HomeAssistantClient::class.java.protectionDomain.codeSource.location.toURI())
        jar.parentFile?.parentFile?.parentFile?.let { candidates += it }
    }
    // Cwd-relative fallbacks for runs without a jar (e.g. :server:run, bare java -jar).
    candidates += listOf(
        java.io.File("."),
        java.io.File("server"),
        java.io.File("../server"),
    )
    return candidates.firstOrNull { java.io.File(it, ".env").isFile }?.path
}

/**
 * MCP server around the Home Assistant REST API.
 *
 * Transports:
 *  - (default) `--stdio`        : for local use / a code assistant launching the jar.
 *  - `--sse [port]`             : HTTP+SSE transport (default port 3001) for VPS deploy.
 *
 * Secrets come only from env: HA_BASE_URL, HA_TOKEN.
 */
fun main(args: Array<String>) {
    // fd 1 (real stdout) is reserved for the MCP protocol over stdio. Capture it now,
    // then point System.out at stderr so any library chatter — e.g. kotlin-logging's
    // "initializing…" banner, emitted on first SDK use — can never pollute fd 1.
    // Force UTF-8 too (Russian Windows console default is CP866).
    protocolOut = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
    System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, "UTF-8"))
    System.setOut(System.err)

    // Load secrets from .env if present; real process env always wins. The working
    // directory differs by launcher (gradle :server:run -> server/, gradle :client:run
    // spawns us with cwd=client/, java -jar -> wherever invoked), so locate the .env
    // ourselves across likely spots and jar-relative. Missing file is fine.
    val envDir = findEnvDir()
    val dotenv = dotenv {
        ignoreIfMissing = true
        ignoreIfMalformed = true
        if (envDir != null) directory = envDir
    }
    val baseUrl = dotenv["HA_BASE_URL"]?.trim().orEmpty()
    val token = dotenv["HA_TOKEN"]?.trim().orEmpty()
    if (baseUrl.isEmpty() || token.isEmpty()) {
        log("ERROR: HA_BASE_URL and HA_TOKEN must be set in the environment.")
        log("  HA_BASE_URL e.g. http://homeassistant.local:8123")
        log("  HA_TOKEN    = Long-Lived Access Token (HA profile -> Security).")
        exitProcess(1)
    }

    val client = HomeAssistantClient(baseUrl, token)

    // --- Day 18: persistent storage + background collector --------------------
    // DB path is configurable; default keeps it under the server module so it is easy
    // to find. Parent dir is created so a fresh checkout works without manual mkdir.
    val dbPath = dotenv["DB_PATH"]?.trim()?.takeIf { it.isNotEmpty() }
        ?: java.io.File(envDir ?: ".", "data/measurements.db").path
    java.io.File(dbPath).absoluteFile.parentFile?.mkdirs()
    val storage = Storage("jdbc:sqlite:$dbPath")

    // Collection settings: persisted config (if any) wins over env, so changes made via
    // configure_collection survive restarts; otherwise seed from env on first run.
    val envInterval = parseDurationSeconds(dotenv["COLLECT_INTERVAL"]?.trim()) ?: 60L
    val envEntities = (dotenv["TRACKED_ENTITIES"]?.trim().orEmpty())
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val stored = storage.loadConfig()
    val config = CollectionConfig(
        entities = stored?.entities ?: envEntities,
        intervalSeconds = stored?.intervalSeconds ?: envInterval,
    )
    if (stored == null) storage.saveConfig(config.snapshot())

    val retentionDays = dotenv["RETENTION_DAYS"]?.trim()?.toLongOrNull()
    val collector = Collector(client, storage, config, retentionDays)
    collector.start()

    // Graceful shutdown: stop the scheduler, close the DB, release the HTTP client.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            collector.stop()
            storage.close()
            client.close()
        },
    )

    val command = args.firstOrNull() ?: "--stdio"
    val port = args.getOrNull(1)?.toIntOrNull() ?: 3001

    when (command) {
        "--stdio" -> runStdio(client, storage, config)
        "--sse", "--http" -> runSse(client, storage, config, port)
        else -> {
            log("Unknown command: $command. Use --stdio (default) or --sse [port].")
            exitProcess(1)
        }
    }
}

/** Standard I/O transport: communicate over stdin/stdout with the parent process. */
private fun runStdio(client: HomeAssistantClient, storage: Storage, config: CollectionConfig) {
    log("Home Assistant MCP server starting on stdio…")
    val server = configureServer(client, storage, config)
    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        protocolOut.asSink().buffered(),
    ) { /* defaults: own scope + Dispatchers.IO */ }
    runBlocking {
        val session = server.createSession(transport)
        val done = Job()
        // Stop cleanly when the stdio stream closes (parent exits / EOF). In the
        // SDK 0.13 multi-session model this is a session event, not a server event.
        session.onClose { done.complete() }
        done.join()
    }
    log("stdio session closed — shutting down.")
    // Force exit: the Ktor/coroutine machinery can keep non-daemon threads alive
    // after the session ends, which would otherwise hang the process.
    client.close()
    exitProcess(0)
}

/** HTTP + SSE transport via the SDK's Ktor plugin. Endpoint: http://host:port/sse */
private fun runSse(client: HomeAssistantClient, storage: Storage, config: CollectionConfig, port: Int) {
    log("Home Assistant MCP server starting on http://0.0.0.0:$port/sse")
    embeddedServer(CIO, host = "0.0.0.0", port = port) {
        mcp { configureServer(client, storage, config) }
    }.start(wait = true)
}
