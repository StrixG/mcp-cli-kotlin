import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OAuthTest {

    /** RFC 7636 Appendix B known-answer vector for the S256 PKCE transformation. */
    @Test
    fun pkceChallengeMatchesRfc7636Vector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expected, OAuth.sha256Base64Url(verifier))
    }

    @Test
    fun discoveryThenClientCredentialsYieldsToken() = runTest {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("oauth-protected-resource") -> respond(
                    """{"resource":"https://rs.local","authorization_servers":["https://as.local"]}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                path.endsWith("oauth-authorization-server") -> respond(
                    """{"issuer":"https://as.local","authorization_endpoint":"https://as.local/authorize","token_endpoint":"https://as.local/token","registration_endpoint":"https://as.local/register"}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                path.endsWith("/token") -> respond(
                    """{"access_token":"abc123","token_type":"Bearer","expires_in":3600}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val http = HttpClient(engine)
        val spec = ServerSpec(
            name = "rs",
            transport = "sse",
            url = "https://rs.local",
            auth = AuthSpec(flow = "client_credentials", clientId = "cli", clientSecret = "sec"),
        )
        val token = OAuth.obtainToken(http, spec, resolve = { null }, existingRefresh = null)
        assertEquals("abc123", token.accessToken)
        http.close()
    }
}
