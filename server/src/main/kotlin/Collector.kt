import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

private fun log(message: String) = System.err.println("[collector] $message")

/**
 * Mutable, thread-safe collection settings shared between the [Collector] loop (reader)
 * and the `configure_collection` tool (writer). Reads are lock-free via `@Volatile`;
 * the loop re-reads [intervalSeconds]/[entities] each cycle so changes take effect on
 * the next tick without a restart.
 */
class CollectionConfig(
    entities: List<String>,
    intervalSeconds: Long,
) {
    @Volatile
    var entities: List<String> = entities
        private set

    @Volatile
    var intervalSeconds: Long = intervalSeconds
        private set

    /** Apply a partial update (null = leave unchanged) and return the new snapshot. */
    @Synchronized
    fun update(entities: List<String>? = null, intervalSeconds: Long? = null): StoredConfig {
        if (entities != null) this.entities = entities
        if (intervalSeconds != null) this.intervalSeconds = intervalSeconds.coerceAtLeast(1)
        return snapshot()
    }

    fun snapshot() = StoredConfig(entities, intervalSeconds)
}

/**
 * Background scheduler: every [CollectionConfig.intervalSeconds] it polls Home Assistant
 * for the tracked entities and persists a snapshot of each. Runs on its own
 * [SupervisorJob] scope so a failure in one cycle never tears the loop down — HA being
 * briefly unreachable just logs a warning and retries next tick.
 *
 * The first collection runs immediately on [start] so a demo sees data without waiting a
 * full interval. Optional [retentionDays] prunes rows older than the cutoff each cycle.
 */
class Collector(
    private val client: HomeAssistantClient,
    private val storage: Storage,
    private val config: CollectionConfig,
    private val retentionDays: Long?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        log(
            "starting — interval=${config.intervalSeconds}s, tracking=" +
                (config.entities.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "(all entities)") +
                (retentionDays?.let { ", retention=${it}d" } ?: ""),
        )
        scope.launch {
            while (isActive) {
                runCatching { collectOnce() }
                    .onFailure { log("collection cycle failed (will retry): ${it.message}") }
                delay(config.intervalSeconds * 1000)
            }
        }
    }

    /** One poll+persist pass. Public so it can be unit-tested / triggered directly. */
    suspend fun collectOnce() {
        val tracked = config.entities
        val now = Instant.now()
        // One GET /api/states, then filter locally — cheaper than N per-entity calls.
        val states = client.getStates()
        val selected = if (tracked.isEmpty()) states else states.filter { it.entity_id in tracked }

        if (selected.isEmpty()) {
            log("no matching entities this cycle (tracked=${tracked.joinToString(",")})")
            return
        }

        val rows = selected.map { s ->
            Measurement(
                entityId = s.entity_id,
                state = s.state,
                value = s.state.toDoubleOrNull(),
                timestamp = now,
                attributesJson = s.attributes.toString(),
            )
        }
        storage.insertAll(rows)
        log("persisted ${rows.size} snapshot(s) at $now")

        if (retentionDays != null) {
            val removed = storage.purgeOlderThan(now.minusSeconds(retentionDays * 86_400))
            if (removed > 0) log("purged $removed row(s) older than ${retentionDays}d")
        }
    }

    /** Cancel the loop. The DB connection is closed separately by the owner. */
    fun stop() {
        log("stopping scheduler")
        scope.cancel()
    }
}
