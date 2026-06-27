import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.net.ConnectException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.time.Instant

/**
 * Failure that carries an agent-friendly message (no stack trace). Tool handlers
 * catch this and surface [message] directly instead of leaking internals.
 */
class HaException(message: String) : Exception(message)

/** A single Home Assistant entity and its current state. Unknown JSON fields ignored. */
@Serializable
data class EntityState(
    val entity_id: String,
    val state: String,
    val attributes: JsonObject = JsonObject(emptyMap()),
    val last_changed: String? = null,
    val last_updated: String? = null,
) {
    /** Human-readable name if HA provides one, else the entity_id. */
    val friendlyName: String
        get() = (attributes["friendly_name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: entity_id
}

/**
 * Thin async wrapper over the Home Assistant REST API.
 *
 * Base URL and token come from the caller (read from env). The base may be given
 * with or without a trailing `/api` — both are normalised to the host root.
 */
class HomeAssistantClient(
    baseUrl: String,
    token: String,
    // Injectable so tests can drive a MockEngine; production uses CIO.
    engine: HttpClientEngine = CIO.create(),
    connectTimeoutMillis: Long = 5_000,
    requestTimeoutMillis: Long = 10_000,
) : Closeable {

    // Strip trailing slashes and an optional `/api` suffix -> bare host root.
    private val root: String = baseUrl.trim().trimEnd('/').removeSuffix("/api").trimEnd('/')

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val http = HttpClient(engine) {
        expectSuccess = false // we map status codes to HaException ourselves
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            this.connectTimeoutMillis = connectTimeoutMillis
            this.requestTimeoutMillis = requestTimeoutMillis
        }
        defaultRequest {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    /** GET /api/states — all entities with their current state. */
    suspend fun getStates(): List<EntityState> =
        send(HttpMethod.Get, "states").body()

    /**
     * GET /api/history/period/<start>?filter_entity_id=…&end_time=… — every recorded
     * state change for [entityIds] in `[start, end]`.
     *
     * HA returns an array of arrays: one inner list per entity, chronologically ordered,
     * each element the same shape as [EntityState]. The first element of each list is the
     * state already in effect at [start], so overlapping windows re-emit the boundary row
     * (the caller's dedup handles it). Timestamps and the entity filter are URL-encoded —
     * ISO timestamps carry `:` and a `+00:00` offset.
     */
    suspend fun getHistory(entityIds: List<String>, start: Instant, end: Instant): List<List<EntityState>> {
        val startIso = URLEncoder.encode(start.toString(), Charsets.UTF_8)
        val endIso = URLEncoder.encode(end.toString(), Charsets.UTF_8)
        val filter = URLEncoder.encode(entityIds.joinToString(","), Charsets.UTF_8)
        return send(
            HttpMethod.Get,
            "history/period/$startIso?filter_entity_id=$filter&end_time=$endIso",
        ).body()
    }

    /** GET /api/states/<entity_id> — one entity. */
    suspend fun getState(entityId: String): EntityState =
        send(HttpMethod.Get, "states/$entityId", notFound = "Entity '$entityId' not found.").body()

    /**
     * POST /api/services/<domain>/<service> — invoke a service.
     * HA replies with the list of states that changed (may be empty).
     */
    suspend fun callService(domain: String, service: String, body: JsonObject): List<EntityState> =
        send(
            HttpMethod.Post,
            "services/$domain/$service",
            body = body,
            notFound = "Service '$domain/$service' not found.",
        ).body()

    /** GET /api/ — health/auth probe. Throws [HaException] if unreachable or 401. */
    suspend fun ping() {
        send(HttpMethod.Get, "")
    }

    private suspend fun send(
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
        notFound: String? = null,
    ): HttpResponse {
        val url = if (path.isEmpty()) "$root/api/" else "$root/api/$path"
        val resp = try {
            http.request(url) {
                this.method = method
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        } catch (e: HttpRequestTimeoutException) {
            throw HaException("Home Assistant timed out at $root (check the host is up and reachable).")
        } catch (e: TimeoutCancellationException) {
            throw HaException("Home Assistant timed out at $root (check the host is up and reachable).")
        } catch (e: ConnectException) {
            throw HaException("Cannot connect to Home Assistant at $root: ${e.message}")
        } catch (e: UnknownHostException) {
            throw HaException("Unknown host for Home Assistant: $root")
        } catch (e: Exception) {
            throw HaException("Home Assistant request failed: ${e.message}")
        }

        when (val code = resp.status.value) {
            in 200..299 -> return resp
            401 -> throw HaException("Authentication failed (401). Check HA_TOKEN (Long-Lived Access Token).")
            403 -> throw HaException("Forbidden (403). The token lacks permission for this action.")
            404 -> throw HaException(notFound ?: "Not found (404): $path")
            else -> throw HaException("Home Assistant returned $code: ${resp.bodyAsText().take(300)}")
        }
    }

    override fun close() = http.close()
}
