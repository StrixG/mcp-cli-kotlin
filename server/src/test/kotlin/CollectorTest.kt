import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests for [Collector.collectOnce] — poll/filter/persist, no real HA, no timers. */
class CollectorTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val storage = Storage("jdbc:sqlite:file:collector-test-${System.nanoTime()}?mode=memory&cache=shared")

    @AfterTest
    fun tearDown() = storage.close()

    private val statesJson = """
        [
          {"entity_id":"sensor.temp","state":"21.5","attributes":{"friendly_name":"Temp"}},
          {"entity_id":"sensor.humidity","state":"48","attributes":{}},
          {"entity_id":"light.kitchen","state":"on","attributes":{}}
        ]
    """.trimIndent()

    private fun haClient() = HomeAssistantClient(
        baseUrl = "http://ha.local:8123",
        token = "t",
        engine = MockEngine { respond(statesJson, HttpStatusCode.OK, jsonHeaders) },
    )

    @Test
    fun `collectOnce persists only tracked entities`() = runBlocking {
        val client = haClient()
        val config = CollectionConfig(entities = listOf("sensor.temp", "sensor.humidity"), intervalSeconds = 60)
        Collector(client, storage, config, retentionDays = null).collectOnce()

        assertEquals(listOf("sensor.humidity", "sensor.temp"), storage.knownEntities())
        val temp = storage.summarize("sensor.temp", 0)
        assertEquals(1, temp.count)
        assertEquals(21.5, temp.avg)
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

        val s = storage.summarize("sensor.temp", Instant.now().minusSeconds(3600).epochSecond)
        assertTrue(s.count >= 1)
        client.close()
    }
}
