import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [Storage], driving a private in-memory SQLite DB. A single shared
 * connection keeps the `:memory:` database alive across calls for the test's lifetime.
 */
class StorageTest {

    // Unique URL per instance so tests don't share the same in-memory DB.
    private val storage = Storage("jdbc:sqlite:file:storage-test-${System.nanoTime()}?mode=memory&cache=shared")

    @AfterTest
    fun tearDown() = storage.close()

    private fun m(entity: String, value: String, ts: Instant) =
        Measurement(entity, value, value.toDoubleOrNull(), ts, "{}")

    @Test
    fun `summarize computes count min max avg and last`() {
        val now = Instant.now()
        storage.insertAll(
            listOf(
                m("sensor.temp", "20", now.minusSeconds(300)),
                m("sensor.temp", "24", now.minusSeconds(200)),
                m("sensor.temp", "22", now.minusSeconds(100)),
            ),
        )
        val s = storage.summarize("sensor.temp", now.minusSeconds(3600).epochSecond)
        assertEquals(3, s.count)
        assertEquals(20.0, s.min)
        assertEquals(24.0, s.max)
        assertEquals(22.0, s.avg)
        assertEquals(22.0, s.last)        // most recent point
        assertEquals("22", s.lastState)
    }

    @Test
    fun `period window excludes older points`() {
        val now = Instant.now()
        storage.insertAll(
            listOf(
                m("sensor.temp", "10", now.minusSeconds(7200)), // 2h ago — outside 1h window
                m("sensor.temp", "30", now.minusSeconds(600)),  // 10m ago — inside
            ),
        )
        val s = storage.summarize("sensor.temp", now.minusSeconds(3600).epochSecond)
        assertEquals(1, s.count)
        assertEquals(30.0, s.avg)
    }

    @Test
    fun `non-numeric states count but do not break numeric stats`() {
        val now = Instant.now()
        storage.insertAll(
            listOf(
                m("binary_sensor.door", "on", now.minusSeconds(120)),
                m("binary_sensor.door", "off", now.minusSeconds(60)),
            ),
        )
        val s = storage.summarize("binary_sensor.door", now.minusSeconds(3600).epochSecond)
        assertEquals(2, s.count)
        assertNull(s.avg)               // no numeric values
        assertNull(s.last)              // last value is non-numeric
        assertEquals("off", s.lastState)
    }

    @Test
    fun `purgeOlderThan removes only old rows`() {
        val now = Instant.now()
        storage.insertAll(
            listOf(
                m("sensor.temp", "1", now.minusSeconds(10_000)),
                m("sensor.temp", "2", now.minusSeconds(100)),
            ),
        )
        val removed = storage.purgeOlderThan(now.minusSeconds(5_000))
        assertEquals(1, removed)
        val s = storage.summarize("sensor.temp", 0)
        assertEquals(1, s.count)
    }

    @Test
    fun `knownEntities lists distinct entities`() {
        val now = Instant.now()
        storage.insertAll(
            listOf(
                m("sensor.a", "1", now),
                m("sensor.a", "2", now),
                m("sensor.b", "3", now),
            ),
        )
        assertEquals(listOf("sensor.a", "sensor.b"), storage.knownEntities())
    }

    @Test
    fun `config round-trips and upserts`() {
        assertNull(storage.loadConfig())
        storage.saveConfig(StoredConfig(listOf("sensor.a", "sensor.b"), 60))
        val first = storage.loadConfig()!!
        assertEquals(listOf("sensor.a", "sensor.b"), first.entities)
        assertEquals(60L, first.intervalSeconds)

        storage.saveConfig(StoredConfig(listOf("sensor.c"), 120))
        val second = storage.loadConfig()!!
        assertEquals(listOf("sensor.c"), second.entities)
        assertEquals(120L, second.intervalSeconds)
    }

    @Test
    fun `empty entities config round-trips as empty list`() {
        storage.saveConfig(StoredConfig(emptyList(), 30))
        val c = storage.loadConfig()!!
        assertTrue(c.entities.isEmpty())
        assertEquals(30L, c.intervalSeconds)
    }
}
