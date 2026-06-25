import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Unit tests for [HomeAssistantClient], driving a Ktor [MockEngine] (no real HA). */
class HomeAssistantClientTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** Build a client whose HTTP engine answers with [handler]. */
    private fun client(
        baseUrl: String = "http://ha.local:8123",
        requestTimeoutMillis: Long = 10_000,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = HomeAssistantClient(
        baseUrl = baseUrl,
        token = "test-token",
        engine = MockEngine(handler),
        requestTimeoutMillis = requestTimeoutMillis,
    )

    /** Assert the block throws [HaException] and return it for message checks. */
    private inline fun assertHa(block: () -> Unit): HaException {
        try {
            block()
        } catch (e: HaException) {
            return e
        }
        fail("Expected HaException, but nothing was thrown")
    }

    // --- parsing ---------------------------------------------------------------

    @Test
    fun `getStates parses entity list`() = runBlocking {
        val c = client {
            respond(
                content = """
                    [
                      {"entity_id":"light.kitchen","state":"on","attributes":{"friendly_name":"Kitchen"}},
                      {"entity_id":"switch.fan","state":"off","attributes":{}}
                    ]
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val states = c.getStates()
        assertEquals(2, states.size)
        assertEquals("light.kitchen", states[0].entity_id)
        assertEquals("on", states[0].state)
        assertEquals("Kitchen", states[0].friendlyName)
        c.close()
    }

    @Test
    fun `getState parses state and attributes`() = runBlocking {
        val c = client {
            respond(
                content = """
                    {"entity_id":"light.kitchen","state":"on",
                     "attributes":{"friendly_name":"Kitchen","brightness":200},
                     "last_changed":"2026-06-25T10:00:00+00:00"}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val s = c.getState("light.kitchen")
        assertEquals("on", s.state)
        assertEquals("Kitchen", s.friendlyName)
        assertEquals("200", (s.attributes["brightness"] as JsonPrimitive).content)
        assertEquals("2026-06-25T10:00:00+00:00", s.last_changed)
        c.close()
    }

    @Test
    fun `friendlyName falls back to entity_id when absent`() {
        val s = EntityState(entity_id = "sensor.temp", state = "21")
        assertEquals("sensor.temp", s.friendlyName)
    }

    // --- request shaping -------------------------------------------------------

    @Test
    fun `getStates hits the states endpoint with Bearer token`() = runBlocking {
        var seen: HttpRequestData? = null
        val c = client {
            seen = it
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        }
        c.getStates()
        val req = seen!!
        assertEquals("http://ha.local:8123/api/states", req.url.toString())
        assertEquals("Bearer test-token", req.headers[HttpHeaders.Authorization])
        c.close()
    }

    @Test
    fun `base url with trailing api is normalised`() = runBlocking {
        var seen: HttpRequestData? = null
        val c = client(baseUrl = "http://ha.local:8123/api/") {
            seen = it
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        }
        c.getStates()
        assertEquals("http://ha.local:8123/api/states", seen!!.url.toString())
        c.close()
    }

    @Test
    fun `callService posts entity_id plus extra data`() = runBlocking {
        var seen: HttpRequestData? = null
        var body = ""
        val c = client {
            seen = it
            body = it.body.toByteArray().decodeToString()
            respond("""[{"entity_id":"light.kitchen","state":"on","attributes":{}}]""", HttpStatusCode.OK, jsonHeaders)
        }
        val data = buildJsonObject {
            put("entity_id", JsonPrimitive("light.kitchen"))
            put("brightness_pct", JsonPrimitive(50))
        }
        val changed = c.callService("light", "turn_on", data)
        val req = seen!!
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("http://ha.local:8123/api/services/light/turn_on", req.url.toString())
        assertTrue(body.contains("\"entity_id\":\"light.kitchen\""), "body: $body")
        assertTrue(body.contains("\"brightness_pct\":50"), "body: $body")
        assertEquals("on", changed.single().state)
        c.close()
    }

    // --- error mapping ---------------------------------------------------------

    @Test
    fun `401 maps to authentication error`() = runBlocking {
        val c = client { respondError(HttpStatusCode.Unauthorized) }
        val e = assertHa { runBlocking { c.getStates() } }
        assertTrue(e.message!!.contains("401"), e.message)
        assertTrue(e.message!!.contains("HA_TOKEN"), e.message)
        c.close()
    }

    @Test
    fun `404 on getState uses the not-found message`() = runBlocking {
        val c = client { respondError(HttpStatusCode.NotFound) }
        val e = assertHa { runBlocking { c.getState("light.ghost") } }
        assertTrue(e.message!!.contains("light.ghost"), e.message)
        assertTrue(e.message!!.contains("not found"), e.message)
        c.close()
    }

    @Test
    fun `connection refused maps to a connect error`() = runBlocking {
        val c = client { throw ConnectException("Connection refused") }
        val e = assertHa { runBlocking { c.getStates() } }
        assertTrue(e.message!!.contains("Cannot connect"), e.message)
        c.close()
    }

    @Test
    fun `unknown host maps to a host error`() = runBlocking {
        val c = client { throw UnknownHostException("ha.local") }
        val e = assertHa { runBlocking { c.getStates() } }
        assertTrue(e.message!!.contains("Unknown host"), e.message)
        c.close()
    }

    @Test
    fun `request timeout maps to a timeout error`() = runBlocking {
        // Engine stalls past the 20 ms request timeout -> HttpTimeout fires.
        val c = client(requestTimeoutMillis = 20) {
            delay(500)
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        }
        val e = assertHa { runBlocking { c.getStates() } }
        assertTrue(e.message!!.contains("timed out"), e.message)
        c.close()
    }

    @Test
    fun `5xx surfaces the status code`() = runBlocking {
        val c = client { respondError(HttpStatusCode.InternalServerError, "boom") }
        val e = assertHa { runBlocking { c.getStates() } }
        assertTrue(e.message!!.contains("500"), e.message)
        c.close()
    }
}
