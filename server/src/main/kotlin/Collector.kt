import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Logger for the collector. Routine per-cycle chatter is logged at INFO and is
 * therefore suppressed by the default WARN level (see simplelogger.properties), so it
 * never floods a code assistant's stdio console. Failures stay at WARN and remain
 * visible. Raise the level when troubleshooting.
 */
private val log = LoggerFactory.getLogger("collector")

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

    /**
     * High-water mark: end of the last history window pulled. Each cycle fetches
     * `[lastEnd, now]`. Seeded on [start] from the newest stored row so a restart
     * backfills any downtime gap (bounded by HA Recorder retention).
     */
    @Volatile
    private var lastEnd: Instant = Instant.now()

    fun start() {
        log.info(
            "starting — interval={}s, tracking={}{}",
            config.intervalSeconds,
            config.entities.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "(all entities)",
            retentionDays?.let { ", retention=${it}d" } ?: "",
        )
        // Resume from the newest stored point; else look back one interval so the first
        // cycle has a non-empty window.
        lastEnd = storage.latestTimestamp(config.entities)
            ?: Instant.now().minusSeconds(config.intervalSeconds)
        scope.launch {
            while (isActive) {
                runCatching { collectOnce() }
                    .onFailure { log.warn("collection cycle failed (will retry): {}", it.message) }
                delay(config.intervalSeconds * 1000)
            }
        }
    }

    /** One poll+persist pass. Public so it can be unit-tested / triggered directly. */
    suspend fun collectOnce() {
        val tracked = config.entities
        if (tracked.isEmpty()) {
            collectSnapshot()
        } else {
            collectHistory(tracked)
        }

        if (retentionDays != null) {
            val removed = storage.purgeOlderThan(Instant.now().minusSeconds(retentionDays * 86_400))
            if (removed > 0) log.info("purged {} row(s) older than {}d", removed, retentionDays)
        }
    }

    /**
     * Fallback for "track all" (empty entity list): point-sample the current state of every
     * entity via one `GET /api/states`. History without an entity filter would return every
     * entity's full change log each cycle — far too heavy — so we keep the cheap snapshot here.
     */
    private suspend fun collectSnapshot() {
        val now = Instant.now()
        val states = client.getStates()
        if (states.isEmpty()) {
            log.info("no entities returned this cycle")
            return
        }
        val rows = states.map { s ->
            Measurement(
                entityId = s.entity_id,
                state = s.state,
                value = s.state.toDoubleOrNull(),
                timestamp = now,
                attributesJson = s.attributes.toString(),
            )
        }
        storage.insertAll(rows)
        log.info("persisted {} snapshot(s) at {}", rows.size, now)
    }

    /**
     * History-backed catch-up for an explicit entity list: pull every state change HA
     * recorded in `[lastEnd, now]` and persist them all (INSERT OR IGNORE dedups the
     * overlapping boundary row). This is the zero-loss path — short-lived changes between
     * ticks are captured, not sampled over.
     */
    private suspend fun collectHistory(tracked: List<String>) {
        val start = lastEnd
        val end = Instant.now()
        val histories = client.getHistory(tracked, start, end)
        val rows = histories.flatten().mapNotNull { s ->
            val ts = s.last_changed ?: return@mapNotNull null
            Measurement(
                entityId = s.entity_id,
                state = s.state,
                value = s.state.toDoubleOrNull(),
                timestamp = OffsetDateTime.parse(ts).toInstant(),
                attributesJson = s.attributes.toString(),
            )
        }
        storage.insertAll(rows)
        lastEnd = end
        log.info("persisted {} change(s) over [{}, {}]", rows.size, start, end)
    }

    /** Cancel the loop. The DB connection is closed separately by the owner. */
    fun stop() {
        log.info("stopping scheduler")
        scope.cancel()
    }
}
