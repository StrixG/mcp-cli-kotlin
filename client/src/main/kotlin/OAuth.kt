import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.awt.Desktop
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private fun log(message: String) = System.err.println(message)

/**
 * OAuth 2.1 client for connecting to an authorization-protected MCP server (per the MCP
 * authorization spec). Discovers the authorization server from the resource's metadata, then
 * obtains an access token via the configured grant:
 *  - `client_credentials` — headless machine token (the agent itself), no browser.
 *  - `authorization_code` + PKCE — interactive browser login, with a loopback redirect
 *    catcher and optional dynamic client registration (RFC 7591).
 *
 * Tokens are cached on disk and reused until near expiry; a refresh token (when issued) is
 * rotated before falling back to a fresh grant. Every token request carries the RFC 8707
 * `resource` parameter bound to the server's canonical URL.
 */
object OAuth {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val random = SecureRandom()

    /** A usable access token with its absolute expiry and (optional) rotating refresh token. */
    data class Token(val accessToken: String, val expiresAtMs: Long, val refreshToken: String?)

    private data class AsMeta(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String?,
    )

    @Serializable
    private data class CacheDto(val accessToken: String, val expiresAtMs: Long, val refreshToken: String? = null)

    /**
     * Resolve an access token for [spec], using its cache when possible. [resolve] expands
     * `${ENV}` placeholders in the auth config (process env / client .env).
     */
    suspend fun token(spec: ServerSpec, resolve: (String) -> String?): Token {
        val auth = spec.auth ?: error("server '${spec.name}' has no auth config")
        val resource = (spec.url ?: error("server '${spec.name}' needs a url for OAuth")).trimEnd('/')
        val cacheFile = File(auth.tokenCache ?: "mcp-data/${spec.name}-token.json")

        readCache(cacheFile)?.let { if (it.expiresAtMs - 60_000 > System.currentTimeMillis()) return it }

        val http = HttpClient(CIO)
        try {
            val tok = obtainToken(http, spec, resolve, readCache(cacheFile)?.refreshToken)
            saveCache(cacheFile, tok)
            return tok
        } finally {
            http.close()
        }
    }

    /**
     * Discover the authorization server and obtain a token over [http] — no cache, no client
     * lifecycle. Split out from [token] so tests can drive it with a stubbed engine. Tries a
     * refresh with [existingRefresh] first, then the configured grant.
     */
    internal suspend fun obtainToken(
        http: HttpClient,
        spec: ServerSpec,
        resolve: (String) -> String?,
        existingRefresh: String?,
    ): Token {
        val auth = spec.auth ?: error("server '${spec.name}' has no auth config")
        val resource = (spec.url ?: error("server '${spec.name}' needs a url for OAuth")).trimEnd('/')
        val asMeta = discover(http, resource)
        if (existingRefresh != null) {
            runCatching { refresh(http, asMeta, existingRefresh, resource) }.getOrNull()?.let { return it }
        }
        val clientId = auth.clientId?.let { interpolateEnv(it, resolve) }
        val clientSecret = auth.clientSecret?.let { interpolateEnv(it, resolve) }
        return when (auth.flow.lowercase()) {
            "client_credentials" -> clientCredentials(http, asMeta, clientId, clientSecret, auth.scope, resource)
            "authorization_code" -> authorizationCode(http, asMeta, clientId, auth.scope, resource)
            else -> error("unknown auth flow '${auth.flow}' (use client_credentials or authorization_code)")
        }
    }

    /** Drop a cached token (e.g. after a 401) so the next [token] call re-authenticates. */
    fun invalidate(spec: ServerSpec) {
        val cache = spec.auth?.tokenCache ?: "mcp-data/${spec.name}-token.json"
        runCatching { File(cache).delete() }
    }

    // --- discovery -----------------------------------------------------------

    private suspend fun discover(http: HttpClient, resource: String): AsMeta {
        val prm = json.parseToJsonElement(
            http.get("$resource/.well-known/oauth-protected-resource").bodyAsText(),
        ).jsonObject
        val issuer = prm["authorization_servers"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content?.trimEnd('/')
            ?: error("protected-resource metadata has no authorization_servers")
        val asm = json.parseToJsonElement(
            http.get("$issuer/.well-known/oauth-authorization-server").bodyAsText(),
        ).jsonObject
        return AsMeta(
            authorizationEndpoint = asm["authorization_endpoint"]!!.jsonPrimitive.content,
            tokenEndpoint = asm["token_endpoint"]!!.jsonPrimitive.content,
            registrationEndpoint = asm["registration_endpoint"]?.jsonPrimitive?.content,
        )
    }

    // --- grants --------------------------------------------------------------

    private suspend fun clientCredentials(
        http: HttpClient, asMeta: AsMeta, clientId: String?, secret: String?, scope: String?, resource: String,
    ): Token {
        requireNotNull(clientId) { "client_credentials flow needs auth.clientId" }
        requireNotNull(secret) { "client_credentials flow needs auth.clientSecret" }
        val resp = http.post(asMeta.tokenEndpoint) {
            setBody(FormDataContent(Parameters.build {
                append("grant_type", "client_credentials")
                append("client_id", clientId)
                append("client_secret", secret)
                append("resource", resource)
                scope?.let { append("scope", it) }
            }))
        }
        if (!resp.status.isSuccess()) error("client_credentials token request failed: ${resp.status} ${resp.bodyAsText()}")
        return parseToken(resp.bodyAsText())
    }

    private suspend fun authorizationCode(
        http: HttpClient, asMeta: AsMeta, clientId0: String?, scope: String?, resource: String,
    ): Token {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        try {
            val redirectUri = "http://127.0.0.1:${server.localPort}/callback"
            val clientId = clientId0 ?: register(http, asMeta, redirectUri)
            val verifier = randomUrlSafe(32)
            val challenge = sha256Base64Url(verifier)
            val state = randomUrlSafe(16)
            val authUrl = buildString {
                append(asMeta.authorizationEndpoint)
                append("?response_type=code")
                append("&client_id=").append(enc(clientId))
                append("&redirect_uri=").append(enc(redirectUri))
                append("&code_challenge=").append(enc(challenge))
                append("&code_challenge_method=S256")
                append("&state=").append(enc(state))
                append("&resource=").append(enc(resource))
                scope?.let { append("&scope=").append(enc(it)) }
            }
            log("Opening browser to authorize. If it doesn't open, visit:\n$authUrl")
            openBrowser(authUrl)
            val (code, returnedState) = awaitCallback(server)
            require(returnedState == state) { "OAuth state mismatch — aborting (possible CSRF)" }
            val resp = http.post(asMeta.tokenEndpoint) {
                setBody(FormDataContent(Parameters.build {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", redirectUri)
                    append("client_id", clientId)
                    append("code_verifier", verifier)
                    append("resource", resource)
                }))
            }
            if (!resp.status.isSuccess()) error("authorization_code token exchange failed: ${resp.status} ${resp.bodyAsText()}")
            return parseToken(resp.bodyAsText())
        } finally {
            server.close()
        }
    }

    private suspend fun refresh(http: HttpClient, asMeta: AsMeta, refreshToken: String, resource: String): Token {
        val resp = http.post(asMeta.tokenEndpoint) {
            setBody(FormDataContent(Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("resource", resource)
            }))
        }
        if (!resp.status.isSuccess()) error("refresh failed: ${resp.status}")
        return parseToken(resp.bodyAsText())
    }

    /** Dynamic client registration (RFC 7591) → returns a public client_id. */
    private suspend fun register(http: HttpClient, asMeta: AsMeta, redirectUri: String): String {
        val endpoint = asMeta.registrationEndpoint
            ?: error("authorization server has no registration_endpoint; set auth.clientId manually")
        val body = buildJsonObject {
            put("redirect_uris", buildJsonArray { add(redirectUri) })
            put("token_endpoint_auth_method", "none")
            put("grant_types", buildJsonArray { add("authorization_code"); add("refresh_token") })
        }.toString()
        val resp = http.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!resp.status.isSuccess()) error("dynamic client registration failed: ${resp.status} ${resp.bodyAsText()}")
        return json.parseToJsonElement(resp.bodyAsText()).jsonObject["client_id"]!!.jsonPrimitive.content
    }

    // --- loopback redirect catcher ------------------------------------------

    /** Block on the loopback socket for the browser redirect; return (code, state). */
    private suspend fun awaitCallback(server: ServerSocket): Pair<String, String?> = withContext(Dispatchers.IO) {
        server.accept().use { sock ->
            val requestLine = sock.getInputStream().bufferedReader().readLine()
                ?: error("empty OAuth callback request")
            val target = requestLine.split(" ").getOrNull(1) ?: error("malformed callback request")
            val query = target.substringAfter('?', "")
            val params = query.split("&").mapNotNull {
                val i = it.indexOf('=')
                if (i < 0) null else dec(it.substring(0, i)) to dec(it.substring(i + 1))
            }.toMap()
            val html = "<html><body style=\"font-family:sans-serif\">Authorized. You can close this tab.</body></html>"
            sock.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\nContent-Length: ${html.toByteArray().size}\r\n\r\n$html".toByteArray())
                flush()
            }
            params["error"]?.let { error("authorization error: $it") }
            val code = params["code"] ?: error("no authorization code in callback")
            code to params["state"]
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun parseToken(body: String): Token {
        val o = json.parseToJsonElement(body).jsonObject
        val access = o["access_token"]?.jsonPrimitive?.content ?: error("token response missing access_token: $body")
        val expiresIn = o["expires_in"]?.jsonPrimitive?.long ?: 3600L
        val refresh = o["refresh_token"]?.jsonPrimitive?.content
        return Token(access, System.currentTimeMillis() + expiresIn * 1000, refresh)
    }

    private fun readCache(file: File): Token? = runCatching {
        val d = json.decodeFromString(CacheDto.serializer(), file.readText())
        Token(d.accessToken, d.expiresAtMs, d.refreshToken)
    }.getOrNull()

    private fun saveCache(file: File, t: Token) {
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(json.encodeToString(CacheDto.serializer(), CacheDto(t.accessToken, t.expiresAtMs, t.refreshToken)))
    }

    fun sha256Base64Url(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.US_ASCII)),
        )

    private fun randomUrlSafe(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { random.nextBytes(it) })

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8.name())
    private fun dec(s: String) = URLDecoder.decode(s, Charsets.UTF_8.name())

    private fun openBrowser(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        }
        val os = System.getProperty("os.name").lowercase()
        val cmd = when {
            os.contains("win") -> listOf("cmd", "/c", "start", "", url)
            os.contains("mac") -> listOf("open", url)
            else -> listOf("xdg-open", url)
        }
        runCatching { ProcessBuilder(cmd).start() }
    }
}
