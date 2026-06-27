import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests for [Collector.collectOnce] — history/snapshot/persist, no real HA, no timers. */
class CollectorTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val storage = Storage("jdbc:sqlite:file:collector-test-${System.nanoTime()}?mode=memory&cache=shared")

    @AfterTest
    fun tearDown() = storage.close()

    // GET /api/states — current snapshot (used by the "track all" fallback path).
    private val statesJson = """
        [
          {"entity_id":"sensor.temp","state":"21.5","attributes":{"friendly_name":"Temp"}},
          {"entity_id":"sensor.humidity","state":"48","attributes":{}},
          {"entity_id":"light.kitchen","state":"on","attributes":{}}
        ]
    """.trimIndent()

    // GET /api/history/period/… — array-of-arrays, one inner list per entity, with several
    // changes for sensor.temp inside a single cycle (the point of the zero-loss path).
    private val historyJson = """
        [
          [
            {"entity_id":"sensor.temp","state":"21.0","attributes":{},"last_changed":"2026-06-27T10:00:00+00:00"},
            {"entity_id":"sensor.temp","state":"22.5","attributes":{},"last_changed":"2026-06-27T10:00:20+00:00"},
            {"entity_id":"sensor.temp","state":"20.0","attributes":{},"last_changed":"2026-06-27T10:00:40+00:00"}
          ]
        ]
    """.trimIndent()

    // Routes by path so tracked entities exercise the history endpoint and the empty-list
    // fallback exercises /api/states.
    private fun haClient() = HomeAssistantClient(
        baseUrl = "http://ha.local:8123",
        token = "t",
        engine = MockEngine { request ->
            if (request.url.encodedPath.contains("history/period")) {
                respond(historyJson, HttpStatusCode.OK, jsonHeaders)
            } else {
                respond(statesJson, HttpStatusCode.OK, jsonHeaders)
            }
        },
    )

    @Test
    fun `collectHistory persists every change in the window`() = runBlocking {
        val client = haClient()
        val config = CollectionConfig(entities = listOf("sensor.temp"), intervalSeconds = 60)
        Collector(client, storage, config, retentionDays = null).collectOnce()

        val temp = storage.summarize("sensor.temp", 0)
        assertEquals(3, temp.count)               // all three changes, not one sample
        assertEquals(20.0, temp.min)
        assertEquals(22.5, temp.max)
        assertEquals(20.0, temp.last)             // most recent by timestamp
        client.close()
    }

    @Test
    fun `empty tracked list snapshots all entities`() = runBlocking {
        val client = haClient()
        val config = CollectionConfig(entities = emptyList(), intervalSeconds = 60)
        Collector(client, storage, config, retentionDays = null).collectOnce()

        assertEquals(3, storage.knownEntities().size)
        client.close()
    }

    @Test
    fun `retention purges nothing when all rows are fresh`() = runBlocking {
        val client = haClient()
        val config = CollectionConfig(entities = listOf("sensor.temp"), intervalSeconds = 60)
        Collector(client, storage, config, retentionDays = 7).collectOnce()

        val s = storage.summarize("sensor.temp", 0)
        assertTrue(s.count >= 1)
        client.close()
    }
}
