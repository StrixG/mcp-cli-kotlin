import auth.AuthConfig
import auth.OAuthServer
import auth.SigningKeys
import auth.installMcpAuth
import auth.oauthRoutes
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthTest {

    private val publicUrl = "https://test.local"

    private fun newKeys(): Pair<SigningKeys, File> {
        val f = File.createTempFile("oauth-key", ".json").apply { delete() }
        return SigningKeys.loadOrCreate(f.path) to f
    }

    private fun config() = AuthConfig(
        enabled = true,
        publicUrl = publicUrl,
        clientId = "cli",
        clientSecret = "sec",
        ownerPassword = "pw",
        accessTokenTtlSeconds = 3600,
        keyPath = "unused",
    )

    /** Wire the auth gate + OAuth endpoints + a stand-in protected MCP route. */
    private fun Application.setup(cfg: AuthConfig, keys: SigningKeys) {
        installMcpAuth(cfg, keys)
        routing {
            oauthRoutes(OAuthServer(cfg, keys))
            get("/mcp-stub") { call.respondText("ok") }
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun form(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun challenge(verifier: String) =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun accessToken(body: String) =
        Json.parseToJsonElement(body).jsonObject["access_token"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.postForm(path: String, body: String, basic: String? = null) =
        createClient { followRedirects = false }.post(path) {
            contentType(ContentType.Application.FormUrlEncoded)
            if (basic != null) header(HttpHeaders.Authorization, "Basic " + Base64.getEncoder().encodeToString(basic.toByteArray()))
            setBody(body)
        }

    @Test
    fun protectedResourceMetadataAdvertisesAuthServer() = testApplication {
        val (keys, f) = newKeys()
        application { setup(config(), keys) }
        val body = client.get("/.well-known/oauth-protected-resource").bodyAsText()
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals(publicUrl, obj["resource"]!!.jsonPrimitive.content)
        assertTrue(body.contains("authorization_servers"))
        f.delete()
    }

    @Test
    fun authServerMetadataAdvertisesPkceAndEndpoints() = testApplication {
        val (keys, f) = newKeys()
        application { setup(config(), keys) }
        val body = client.get("/.well-known/oauth-authorization-server").bodyAsText()
        assertTrue(body.contains("\"$publicUrl/token\""))
        assertTrue(body.contains("S256"))
        f.delete()
    }

    @Test
    fun jwksExposesKeys() = testApplication {
        val (keys, f) = newKeys()
        application { setup(config(), keys) }
        assertTrue(client.get("/.well-known/jwks.json").bodyAsText().contains("\"keys\""))
        f.delete()
    }

    @Test
    fun protectedRouteRejectsMissingToken() = testApplication {
        val (keys, f) = newKeys()
        application { setup(config(), keys) }
        val resp = client.get("/mcp-stub")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        val header = resp.headers[HttpHeaders.WWWAuthenticate]
        assertNotNull(header)
        assertTrue(header.contains("resource_metadata"))
        f.delete()
    }

    @Test
    fun clientCredentialsTokenWorksAndReachesProtectedRoute() = testApplication {
        val (keys, f) = newKeys()
        application { setup(config(), keys) }
        val tokenResp = postForm("/token", form(
            "grant_type" to "client_credentials",
            "client_id" to "cli",
            "client_secret" to "sec",
            "resource" to publicUrl,
        ))
        assertEquals(HttpStatusCode.OK, tokenResp.status)
        val token = accessToken(tokenResp.bodyAsText())
        val ok = client.get("/mcp-stub") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("ok", ok.bodyAsText())
        f.delete()
    }

    @Test
    fun clientCredentialsRejectsBadSecret() = testApplication {
        val (keys, f) = newKeys()
        application { setup(config(), keys) }
        val resp = postForm("/token", form(
            "grant_type" to "client_credentials",
            "client_id" to "cli",
            "client_secret" to "wrong",
            "resource" to publicUrl,
        ))
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        f.delete()
    }

    @Test
    fun wrongAudienceTokenIsRejected() = testApplication {
        val (keys, f) = newKeys()
        application { setup(config(), keys) }
        val claims = JWTClaimsSet.Builder()
            .issuer(publicUrl)
            .audience("https://evil.local")
            .expirationTime(Date(System.currentTimeMillis() + 60_000))
            .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keys.rsaKey.keyID).build(), claims)
        jwt.sign(RSASSASigner(keys.rsaKey))
        val resp = client.get("/mcp-stub") { header(HttpHeaders.Authorization, "Bearer ${jwt.serialize()}") }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        f.delete()
    }

    @Test
    fun authorizationCodeFlowWithPkce() = testApplication {
        val (keys, f) = newKeys()
        application { setup(config(), keys) }
        val redirectUri = "http://127.0.0.1:9999/cb"

        // 1. dynamic client registration -> public client_id
        val regBody = """{"redirect_uris":["$redirectUri"],"token_endpoint_auth_method":"none"}"""
        val regResp = createClient { followRedirects = false }.post("/register") {
            contentType(ContentType.Application.Json); setBody(regBody)
        }
        assertEquals(HttpStatusCode.Created, regResp.status)
        val clientId = Json.parseToJsonElement(regResp.bodyAsText()).jsonObject["client_id"]!!.jsonPrimitive.content

        // 2. authorize with owner password -> 302 redirect carrying the code
        val verifier = "verifier-0123456789-0123456789-0123456789-abc"
        val authResp = postForm("/authorize", form(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "code_challenge" to challenge(verifier),
            "code_challenge_method" to "S256",
            "state" to "xyz",
            "resource" to publicUrl,
            "password" to "pw",
        ))
        assertEquals(HttpStatusCode.Found, authResp.status)
        val location = authResp.headers[HttpHeaders.Location]!!
        val code = location.substringAfter("code=").substringBefore("&")
        assertTrue(code.isNotEmpty())

        // 3. wrong verifier -> 400
        val bad = postForm("/token", form(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to "not-the-verifier",
            "resource" to publicUrl,
        ))
        assertEquals(HttpStatusCode.BadRequest, bad.status)

        // re-run authorize to get a fresh single-use code for the happy path
        val authResp2 = postForm("/authorize", form(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "code_challenge" to challenge(verifier),
            "code_challenge_method" to "S256",
            "state" to "xyz",
            "resource" to publicUrl,
            "password" to "pw",
        ))
        val code2 = authResp2.headers[HttpHeaders.Location]!!.substringAfter("code=").substringBefore("&")

        // 4. correct verifier -> 200 with a usable access token
        val good = postForm("/token", form(
            "grant_type" to "authorization_code",
            "code" to code2,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to verifier,
            "resource" to publicUrl,
        ))
        assertEquals(HttpStatusCode.OK, good.status)
        val token = accessToken(good.bodyAsText())
        val ok = client.get("/mcp-stub") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, ok.status)
        f.delete()
    }

    @Test
    fun authorizeRejectsWrongOwnerPassword() = testApplication {
        val (keys, f) = newKeys()
        application { setup(config(), keys) }
        val redirectUri = "http://127.0.0.1:9999/cb"
        val regResp = createClient { followRedirects = false }.post("/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"redirect_uris":["$redirectUri"],"token_endpoint_auth_method":"none"}""")
        }
        val clientId = Json.parseToJsonElement(regResp.bodyAsText()).jsonObject["client_id"]!!.jsonPrimitive.content
        val resp = postForm("/authorize", form(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "code_challenge" to challenge("v"),
            "code_challenge_method" to "S256",
            "state" to "xyz",
            "resource" to publicUrl,
            "password" to "wrong",
        ))
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        f.delete()
    }
}
