package auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A registered OAuth client. A null [secret] marks a public client (PKCE, no secret). */
private data class OAuthClient(
    val clientId: String,
    val secret: String?,
    val redirectUris: List<String>,
)

/** A pending authorization code from the authorization_code + PKCE flow. Single use. */
private data class AuthCode(
    val clientId: String,
    val redirectUri: String,
    val codeChallenge: String,
    val resource: String,
    val scope: String?,
    val expiresAtMs: Long,
)

/** A live refresh token grant (rotated on each use). */
private data class RefreshGrant(
    val clientId: String,
    val resource: String,
    val scope: String?,
)

/** Thrown inside route handlers to short-circuit with an OAuth JSON error response. */
private class OAuthError(
    val status: HttpStatusCode,
    val error: String,
    val description: String,
) : RuntimeException(description)

/**
 * Embedded OAuth 2.1 authorization server for the MCP resource server. Issues RS256 JWT
 * access tokens for two grants — `client_credentials` (headless agents) and
 * `authorization_code` + PKCE (interactive clients) — plus refresh-token rotation and
 * RFC 7591 dynamic client registration.
 *
 * Stores are in-memory: this targets a single-instance personal server. A multi-instance
 * deployment would need shared storage for clients / codes / refresh tokens.
 */
class OAuthServer(private val cfg: AuthConfig, private val keys: SigningKeys) {
    private val clients = ConcurrentHashMap<String, OAuthClient>()
    private val authCodes = ConcurrentHashMap<String, AuthCode>()
    private val refreshTokens = ConcurrentHashMap<String, RefreshGrant>()
    private val random = SecureRandom()

    init {
        // Seed the confidential client used by the headless client_credentials grant.
        if (cfg.clientId != null) {
            clients[cfg.clientId] = OAuthClient(cfg.clientId, cfg.clientSecret, emptyList())
        }
    }

    // --- token issuing -------------------------------------------------------

    /** Sign an RS256 access token bound to [resource] as audience (RFC 8707). */
    private fun issueAccessToken(clientId: String, resource: String, scope: String?): String {
        val now = System.currentTimeMillis()
        val claims = JWTClaimsSet.Builder()
            .issuer(cfg.publicUrl)
            .subject(clientId)
            .audience(resource)
            .issueTime(Date(now))
            .expirationTime(Date(now + cfg.accessTokenTtlSeconds * 1000))
            .jwtID(UUID.randomUUID().toString())
            .apply { if (scope != null) claim("scope", scope) }
            .build()
        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keys.rsaKey.keyID).build(),
            claims,
        )
        jwt.sign(RSASSASigner(keys.rsaKey))
        return jwt.serialize()
    }

    private fun randomToken(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })

    /** Canonical resource check: requested resource (if any) must equal our public URL. */
    private fun resolveResource(requested: String?): String {
        val r = requested?.trim()?.trimEnd('/')
        if (r != null && r.isNotEmpty() && !r.equals(cfg.publicUrl, ignoreCase = true)) {
            throw OAuthError(HttpStatusCode.BadRequest, "invalid_target", "resource must be ${cfg.publicUrl}")
        }
        return cfg.publicUrl
    }

    // --- route wiring --------------------------------------------------------

    fun mount(route: Route) = with(route) {
        get("/.well-known/oauth-protected-resource") {
            call.respondJson(protectedResourceMetadata())
        }
        get("/.well-known/oauth-authorization-server") {
            call.respondJson(authServerMetadata())
        }
        get("/.well-known/jwks.json") {
            call.respondText(keys.publicJwkSetJson, ContentType.Application.Json)
        }
        post("/register") { handleRegister(call) }
        get("/authorize") { handleAuthorizeGet(call) }
        post("/authorize") { handleAuthorizePost(call) }
        post("/token") { handleToken(call) }
    }

    // --- metadata ------------------------------------------------------------

    private fun protectedResourceMetadata() = buildJsonObject {
        put("resource", cfg.publicUrl)
        put("authorization_servers", buildJsonArray { add(cfg.publicUrl) })
    }

    private fun authServerMetadata() = buildJsonObject {
        put("issuer", cfg.publicUrl)
        put("authorization_endpoint", "${cfg.publicUrl}/authorize")
        put("token_endpoint", "${cfg.publicUrl}/token")
        put("registration_endpoint", "${cfg.publicUrl}/register")
        put("jwks_uri", "${cfg.publicUrl}/.well-known/jwks.json")
        put("grant_types_supported", buildJsonArray {
            add("authorization_code"); add("client_credentials"); add("refresh_token")
        })
        put("response_types_supported", buildJsonArray { add("code") })
        put("code_challenge_methods_supported", buildJsonArray { add("S256") })
        put("token_endpoint_auth_methods_supported", buildJsonArray {
            add("client_secret_basic"); add("client_secret_post"); add("none")
        })
    }

    // --- dynamic client registration (RFC 7591) ------------------------------

    private suspend fun handleRegister(call: ApplicationCall) = call.guarded {
        val body = runCatching { Json.parseToJsonElement(call.receiveText()) as JsonObject }
            .getOrElse { throw OAuthError(HttpStatusCode.BadRequest, "invalid_client_metadata", "body must be JSON") }
        val redirectUris = body["redirect_uris"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val authMethod = body["token_endpoint_auth_method"]?.jsonPrimitive?.content ?: "none"
        // Redirect URIs must be loopback or HTTPS (OAuth 2.1 communication security).
        redirectUris.forEach { uri ->
            val ok = uri.startsWith("https://") ||
                uri.startsWith("http://127.0.0.1") || uri.startsWith("http://localhost")
            if (!ok) throw OAuthError(HttpStatusCode.BadRequest, "invalid_redirect_uri", "redirect_uri must be https or loopback: $uri")
        }
        val clientId = "dcr-" + UUID.randomUUID()
        val confidential = authMethod != "none"
        val secret = if (confidential) randomToken() else null
        clients[clientId] = OAuthClient(clientId, secret, redirectUris)
        call.respondJson(
            buildJsonObject {
                put("client_id", clientId)
                if (secret != null) put("client_secret", secret)
                put("client_id_issued_at", System.currentTimeMillis() / 1000)
                put("token_endpoint_auth_method", authMethod)
                put("grant_types", buildJsonArray { add("authorization_code"); add("refresh_token") })
                put("redirect_uris", buildJsonArray { redirectUris.forEach { add(it) } })
            },
            HttpStatusCode.Created,
        )
    }

    // --- authorization_code: /authorize --------------------------------------

    private suspend fun handleAuthorizeGet(call: ApplicationCall) {
        val q = call.request.queryParameters
        val req = parseAuthorizeRequest(call, q) ?: return // already responded with an error
        call.respondText(loginPage(req, error = null), ContentType.Text.Html)
    }

    private suspend fun handleAuthorizePost(call: ApplicationCall) {
        val form = call.receiveParameters()
        val req = parseAuthorizeRequest(call, form) ?: return
        val password = form["password"].orEmpty()
        if (cfg.ownerPassword == null || password != cfg.ownerPassword) {
            call.respondText(loginPage(req, error = "Invalid password"), ContentType.Text.Html, HttpStatusCode.Unauthorized)
            return
        }
        val code = randomToken()
        authCodes[code] = AuthCode(
            clientId = req.clientId,
            redirectUri = req.redirectUri,
            codeChallenge = req.codeChallenge,
            resource = req.resource,
            scope = req.scope,
            expiresAtMs = System.currentTimeMillis() + 60_000, // codes are short-lived
        )
        val sep = if (req.redirectUri.contains('?')) '&' else '?'
        val state = req.state?.let { "&state=${urlEncode(it)}" }.orEmpty()
        call.respondRedirect("${req.redirectUri}${sep}code=${urlEncode(code)}$state")
    }

    private data class AuthorizeRequest(
        val clientId: String,
        val redirectUri: String,
        val codeChallenge: String,
        val resource: String,
        val scope: String?,
        val state: String?,
    )

    /**
     * Validate an /authorize request. Client + redirect_uri errors are returned as 400 (we
     * must NOT redirect to an unverified URI); other protocol errors redirect back per OAuth.
     * Returns null after responding on any error.
     */
    private suspend fun parseAuthorizeRequest(call: ApplicationCall, p: Parameters): AuthorizeRequest? {
        val clientId = p["client_id"].orEmpty()
        val redirectUri = p["redirect_uri"].orEmpty()
        val client = clients[clientId]
        if (client == null || redirectUri.isEmpty() || redirectUri !in client.redirectUris) {
            call.respondText("invalid client_id or redirect_uri", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return null
        }
        val state = p["state"]
        suspend fun redirectError(err: String): AuthorizeRequest? {
            val sep = if (redirectUri.contains('?')) '&' else '?'
            val s = state?.let { "&state=${urlEncode(it)}" }.orEmpty()
            call.respondRedirect("$redirectUri${sep}error=$err$s")
            return null
        }
        if (p["response_type"] != "code") return redirectError("unsupported_response_type")
        val challenge = p["code_challenge"].orEmpty()
        if (challenge.isEmpty() || p["code_challenge_method"] != "S256") return redirectError("invalid_request")
        val resource = try {
            resolveResource(p["resource"])
        } catch (e: OAuthError) {
            return redirectError(e.error)
        }
        return AuthorizeRequest(clientId, redirectUri, challenge, resource, p["scope"], state)
    }

    // --- token endpoint ------------------------------------------------------

    private suspend fun handleToken(call: ApplicationCall) = call.guarded {
        val form = call.receiveParameters()
        when (val grant = form["grant_type"]) {
            "authorization_code" -> tokenFromCode(call, form)
            "client_credentials" -> tokenFromClientCredentials(call, form)
            "refresh_token" -> tokenFromRefresh(call, form)
            else -> throw OAuthError(HttpStatusCode.BadRequest, "unsupported_grant_type", "grant_type=$grant")
        }
    }

    private suspend fun tokenFromCode(call: ApplicationCall, form: Parameters) {
        val code = form["code"].orEmpty()
        val entry = authCodes.remove(code) // single use: removed regardless of outcome
            ?: throw OAuthError(HttpStatusCode.BadRequest, "invalid_grant", "unknown or used code")
        if (System.currentTimeMillis() > entry.expiresAtMs) {
            throw OAuthError(HttpStatusCode.BadRequest, "invalid_grant", "code expired")
        }
        if (form["redirect_uri"] != entry.redirectUri) {
            throw OAuthError(HttpStatusCode.BadRequest, "invalid_grant", "redirect_uri mismatch")
        }
        // PKCE: SHA-256(code_verifier) base64url must equal the stored challenge.
        val verifier = form["code_verifier"].orEmpty()
        if (verifier.isEmpty() || sha256Base64Url(verifier) != entry.codeChallenge) {
            throw OAuthError(HttpStatusCode.BadRequest, "invalid_grant", "PKCE verification failed")
        }
        resolveResource(form["resource"]).let { if (it != entry.resource) throw OAuthError(HttpStatusCode.BadRequest, "invalid_target", "resource mismatch") }
        respondTokens(call, entry.clientId, entry.resource, entry.scope, withRefresh = true)
    }

    private suspend fun tokenFromClientCredentials(call: ApplicationCall, form: Parameters) {
        val client = authenticateClient(call, form)
            ?: throw OAuthError(HttpStatusCode.Unauthorized, "invalid_client", "client authentication failed")
        if (client.secret == null) {
            throw OAuthError(HttpStatusCode.Unauthorized, "invalid_client", "client_credentials requires a confidential client")
        }
        val resource = resolveResource(form["resource"])
        respondTokens(call, client.clientId, resource, form["scope"], withRefresh = false)
    }

    private suspend fun tokenFromRefresh(call: ApplicationCall, form: Parameters) {
        val token = form["refresh_token"].orEmpty()
        val grant = refreshTokens.remove(token) // rotate: old token is invalidated
            ?: throw OAuthError(HttpStatusCode.BadRequest, "invalid_grant", "unknown refresh_token")
        respondTokens(call, grant.clientId, grant.resource, grant.scope, withRefresh = true)
    }

    /** Build the access token (+ optional rotated refresh token) and respond. */
    private suspend fun respondTokens(call: ApplicationCall, clientId: String, resource: String, scope: String?, withRefresh: Boolean) {
        val access = issueAccessToken(clientId, resource, scope)
        val refresh = if (withRefresh) randomToken().also { refreshTokens[it] = RefreshGrant(clientId, resource, scope) } else null
        call.respondJson(
            buildJsonObject {
                put("access_token", access)
                put("token_type", "Bearer")
                put("expires_in", cfg.accessTokenTtlSeconds)
                if (refresh != null) put("refresh_token", refresh)
                if (scope != null) put("scope", scope)
            },
        )
    }

    /** Resolve client credentials from a Basic header or the request body (client_secret_post). */
    private fun authenticateClient(call: ApplicationCall, form: Parameters): OAuthClient? {
        var clientId = form["client_id"]
        var secret = form["client_secret"]
        val header = call.request.headers[HttpHeaders.Authorization]
        if (header != null && header.startsWith("Basic ")) {
            val decoded = String(Base64.getDecoder().decode(header.removePrefix("Basic ").trim()))
            val idx = decoded.indexOf(':')
            if (idx >= 0) {
                clientId = urlDecode(decoded.substring(0, idx))
                secret = urlDecode(decoded.substring(idx + 1))
            }
        }
        val client = clientId?.let { clients[it] } ?: return null
        return if (client.secret != null && client.secret == secret) client else null
    }

    // --- helpers -------------------------------------------------------------

    private fun sha256Base64Url(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.US_ASCII)),
        )

    private fun loginPage(req: AuthorizeRequest, error: String?): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val hidden = buildString {
            append(hiddenField("response_type", "code"))
            append(hiddenField("client_id", req.clientId))
            append(hiddenField("redirect_uri", req.redirectUri))
            append(hiddenField("code_challenge", req.codeChallenge))
            append(hiddenField("code_challenge_method", "S256"))
            append(hiddenField("resource", req.resource))
            req.scope?.let { append(hiddenField("scope", it)) }
            req.state?.let { append(hiddenField("state", it)) }
        }
        val err = error?.let { "<p style=\"color:#c00\">${esc(it)}</p>" }.orEmpty()
        return """
            <!doctype html><html><head><meta charset="utf-8"><title>Authorize</title></head>
            <body style="font-family:sans-serif;max-width:24rem;margin:4rem auto">
            <h2>Home Assistant MCP</h2>
            <p>Client <code>${esc(req.clientId)}</code> requests access.</p>
            $err
            <form method="post" action="/authorize">
              $hidden
              <label>Owner password<br><input type="password" name="password" autofocus></label>
              <p><button type="submit">Authorize</button></p>
            </form>
            </body></html>
        """.trimIndent()
    }

    private fun hiddenField(name: String, value: String) =
        "<input type=\"hidden\" name=\"$name\" value=\"${value.replace("\"", "&quot;")}\">\n"
}

/** Mount the OAuth authorization-server endpoints. */
fun Route.oauthRoutes(oauth: OAuthServer) = oauth.mount(this)

private suspend fun ApplicationCall.respondJson(obj: JsonObject, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(obj.toString(), ContentType.Application.Json, status)

/** Run an endpoint body, turning any [OAuthError] into a spec-shaped JSON error response. */
private suspend fun ApplicationCall.guarded(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: OAuthError) {
        respondJson(
            buildJsonObject {
                put("error", e.error)
                put("error_description", e.description)
            },
            e.status,
        )
    }
}

private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8.name())
private fun urlDecode(s: String): String = java.net.URLDecoder.decode(s, Charsets.UTF_8.name())
