package auth

import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import java.util.Date

/** Paths served without a token: OAuth discovery + authorization-server endpoints. */
private fun isPublicPath(path: String): Boolean =
    path.startsWith("/.well-known") ||
        path == "/authorize" ||
        path == "/token" ||
        path == "/register"

/**
 * Resource-server gate for the MCP endpoints. Every request that is not an OAuth/discovery
 * path must carry a valid Bearer access token; otherwise it gets a 401 with a
 * `WWW-Authenticate` header pointing at the protected-resource metadata (RFC 9728), which is
 * how a spec-compliant MCP client discovers where to authenticate.
 *
 * Tokens are validated for RS256 signature (against our own public key), issuer, expiry, and
 * audience — the audience MUST be our canonical URL (RFC 8707), so a token minted for another
 * resource cannot be replayed here.
 */
fun Application.installMcpAuth(cfg: AuthConfig, keys: SigningKeys) {
    val verifier = RSASSAVerifier(keys.rsaKey.toRSAPublicKey())

    fun isValid(token: String): Boolean = runCatching {
        val jwt = SignedJWT.parse(token)
        if (!jwt.verify(verifier)) return false
        val claims = jwt.jwtClaimsSet
        val notExpired = claims.expirationTime?.after(Date()) == true
        val rightIssuer = claims.issuer == cfg.publicUrl
        val rightAudience = claims.audience?.contains(cfg.publicUrl) == true
        notExpired && rightIssuer && rightAudience
    }.getOrDefault(false)

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (isPublicPath(path)) return@intercept

        val header = call.request.headers[HttpHeaders.Authorization]
        val token = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
        if (token == null || !isValid(token)) {
            call.response.headers.append(
                HttpHeaders.WWWAuthenticate,
                "Bearer resource_metadata=\"${cfg.resourceMetadataUrl}\", error=\"invalid_token\"",
            )
            call.respondText(
                """{"error":"invalid_token"}""",
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
            finish() // abort the pipeline so the MCP route never runs
        }
    }
}
