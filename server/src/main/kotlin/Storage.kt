import java.io.Closeable
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/** One persisted snapshot of an entity's state at a point in time. */
data class Measurement(
    val entityId: String,
    val state: String,
    /** Numeric value parsed from [state], or null for non-numeric states (e.g. "on"/"home"). */
    val value: Double?,
    val timestamp: Instant,
    /** Raw attributes JSON, stored verbatim for later inspection. */
    val attributesJson: String,
)

/** Aggregated stats for one entity over a time window. Returned by [Storage.summarize]. */
data class EntitySummary(
    val entityId: String,
    val count: Int,
    val min: Double?,
    val max: Double?,
    val avg: Double?,
    val last: Double?,
    val lastState: String?,
    val lastTimestamp: Instant?,
)

/** Persisted collection settings, restored on startup so config survives restarts. */
data class StoredConfig(
    val entities: List<String>,
    val intervalSeconds: Long,
)

/**
 * SQLite persistence over plain JDBC (sqlite-jdbc). Holds a single connection guarded
 * by [lock] — the background collector and the MCP tool handlers run on different
 * coroutines, and a JDBC [Connection] is not thread-safe, so every DB touch is
 * serialized. Low write volume (one batch per interval) makes this more than adequate.
 *
 * Two tables:
 *  - `measurements(entity_id, state, value, ts, attributes)` — the time series.
 *  - `config(id=1, entities, interval_seconds)` — single-row collection settings.
 */
class Storage(jdbcUrl: String) : Closeable {

    private val lock = Any()
    private val conn: Connection = DriverManager.getConnection(jdbcUrl)

    init {
        synchronized(lock) {
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS measurements (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        entity_id  TEXT    NOT NULL,
                        state      TEXT    NOT NULL,
                        value      REAL,
                        ts         INTEGER NOT NULL,            -- epoch millis
                        attributes TEXT    NOT NULL DEFAULT '{}'
                    )
                    """.trimIndent(),
                )
                st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_measurements_entity_ts ON measurements(entity_id, ts)",
                )
                // Dedup guard: history windows overlap and restarts re-pull, so the same
                // (entity_id, ts) can be offered repeatedly. The unique index + INSERT OR
                // IGNORE in insertAll collapses those to a single row.
                st.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_measurements_entity_ts_uniq ON measurements(entity_id, ts)",
                )
                // One-time migration: ts was epoch *seconds* before the history-backed
                // collector; now it is epoch *millis*. Seconds values are < 1e11; after the
                // ×1000 they exceed it, so this never re-runs (idempotent).
                st.executeUpdate("UPDATE measurements SET ts = ts * 1000 WHERE ts < 100000000000")
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS config (
                        id               INTEGER PRIMARY KEY CHECK (id = 1),
                        entities         TEXT    NOT NULL,       -- comma-separated entity_ids
                        interval_seconds INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    /** Insert a batch of snapshots in one transaction. */
    fun insertAll(measurements: List<Measurement>) {
        if (measurements.isEmpty()) return
        synchronized(lock) {
            conn.prepareStatement(
                "INSERT OR IGNORE INTO measurements(entity_id, state, value, ts, attributes) VALUES (?,?,?,?,?)",
            ).use { ps ->
                for (m in measurements) {
                    ps.setString(1, m.entityId)
                    ps.setString(2, m.state)
                    if (m.value != null) ps.setDouble(3, m.value) else ps.setNull(3, java.sql.Types.REAL)
                    ps.setLong(4, m.timestamp.toEpochMilli())
                    ps.setString(5, m.attributesJson)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    /** Distinct entity_ids that have at least one stored measurement. */
    fun knownEntities(): List<String> = synchronized(lock) {
        conn.prepareStatement("SELECT DISTINCT entity_id FROM measurements ORDER BY entity_id").use { ps ->
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
    }

    /**
     * Aggregate one entity over `[sinceEpochSecond, now]`. min/max/avg/last are computed
     * from the numeric `value` column (null when no numeric points exist); `lastState`
     * is the most recent raw state regardless of whether it parsed as a number.
     */
    fun summarize(entityId: String, sinceEpochMilli: Long): EntitySummary = synchronized(lock) {
        var count = 0
        var min: Double? = null
        var max: Double? = null
        var avg: Double? = null
        conn.prepareStatement(
            """
            SELECT COUNT(*), MIN(value), MAX(value), AVG(value)
            FROM measurements WHERE entity_id = ? AND ts >= ? AND value IS NOT NULL
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, entityId)
            ps.setLong(2, sinceEpochMilli)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    count = rs.getInt(1)
                    min = rs.getObject(2)?.let { rs.getDouble(2) }
                    max = rs.getObject(3)?.let { rs.getDouble(3) }
                    avg = rs.getObject(4)?.let { rs.getDouble(4) }
                }
            }
        }
        // Total point count (incl. non-numeric) and the latest raw reading in-window.
        var totalCount = 0
        var lastState: String? = null
        var lastValue: Double? = null
        var lastTs: Instant? = null
        conn.prepareStatement(
            "SELECT state, value, ts FROM measurements WHERE entity_id = ? AND ts >= ? ORDER BY ts DESC LIMIT 1",
        ).use { ps ->
            ps.setString(1, entityId)
            ps.setLong(2, sinceEpochMilli)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    lastState = rs.getString(1)
                    lastValue = rs.getObject(2)?.let { rs.getDouble(2) }
                    lastTs = Instant.ofEpochMilli(rs.getLong(3))
                }
            }
        }
        conn.prepareStatement("SELECT COUNT(*) FROM measurements WHERE entity_id = ? AND ts >= ?").use { ps ->
            ps.setString(1, entityId)
            ps.setLong(2, sinceEpochMilli)
            ps.executeQuery().use { rs -> if (rs.next()) totalCount = rs.getInt(1) }
        }
        EntitySummary(
            entityId = entityId,
            count = totalCount,
            min = if (count > 0) min else null,
            max = if (count > 0) max else null,
            avg = if (count > 0) avg else null,
            last = lastValue,
            lastState = lastState,
            lastTimestamp = lastTs,
        )
    }

    /** Delete measurements older than [cutoff]. Returns rows removed. */
    fun purgeOlderThan(cutoff: Instant): Int = synchronized(lock) {
        conn.prepareStatement("DELETE FROM measurements WHERE ts < ?").use { ps ->
            ps.setLong(1, cutoff.toEpochMilli())
            ps.executeUpdate()
        }
    }

    /**
     * Latest stored timestamp among [entityIds] (or across all entities if the list is
     * empty), or null when nothing is stored. The collector uses this to resume its
     * history window after a restart so any downtime gap is backfilled.
     */
    fun latestTimestamp(entityIds: List<String>): Instant? = synchronized(lock) {
        val sql = if (entityIds.isEmpty()) {
            "SELECT MAX(ts) FROM measurements"
        } else {
            val placeholders = entityIds.joinToString(",") { "?" }
            "SELECT MAX(ts) FROM measurements WHERE entity_id IN ($placeholders)"
        }
        conn.prepareStatement(sql).use { ps ->
            entityIds.forEachIndexed { i, id -> ps.setString(i + 1, id) }
            ps.executeQuery().use { rs ->
                if (rs.next() && rs.getObject(1) != null) Instant.ofEpochMilli(rs.getLong(1)) else null
            }
        }
    }

    /** Persist current collection settings (single-row upsert). */
    fun saveConfig(config: StoredConfig) = synchronized(lock) {
        conn.prepareStatement(
            """
            INSERT INTO config(id, entities, interval_seconds) VALUES (1, ?, ?)
            ON CONFLICT(id) DO UPDATE SET entities = excluded.entities,
                                          interval_seconds = excluded.interval_seconds
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, config.entities.joinToString(","))
            ps.setLong(2, config.intervalSeconds)
            ps.executeUpdate()
        }
        Unit
    }

    /** Load persisted settings, or null if none saved yet. */
    fun loadConfig(): StoredConfig? = synchronized(lock) {
        conn.prepareStatement("SELECT entities, interval_seconds FROM config WHERE id = 1").use { ps ->
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@synchronized null
                val entities = rs.getString(1).split(",").map { it.trim() }.filter { it.isNotEmpty() }
                StoredConfig(entities, rs.getLong(2))
            }
        }
    }

    override fun close() = synchronized(lock) { conn.close() }
}
