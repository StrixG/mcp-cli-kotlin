package auth

/**
 * OAuth 2.1 authorization settings for the HTTP/SSE transport. stdio runs unauthenticated
 * (the MCP spec says stdio transports take credentials from the environment, not OAuth).
 *
 * All values come from the environment (real process env or the server `.env`). When auth
 * is enabled, [publicUrl] is mandatory: it is the server's canonical identity used as the
 * JWT issuer, the token audience (RFC 8707), and the base for every metadata/redirect URL.
 */
data class AuthConfig(
    val enabled: Boolean,
    /** Canonical base URL, no trailing slash, no fragment (e.g. https://mcp.example.com). */
    val publicUrl: String,
    /** Seeded confidential client for the headless client_credentials grant (optional). */
    val clientId: String?,
    val clientSecret: String?,
    /** Single-owner password gating the /authorize consent step (authorization_code flow). */
    val ownerPassword: String?,
    val accessTokenTtlSeconds: Long,
    /** Where the RSA signing key is persisted (kept on the Docker /data volume). */
    val keyPath: String,
) {
    /** Resource-server metadata URL advertised in 401 WWW-Authenticate responses (RFC 9728). */
    val resourceMetadataUrl: String get() = "$publicUrl/.well-known/oauth-protected-resource"

    companion object {
        /**
         * Build from an env lookup. [get] is typically `dotenv::get`. Auth defaults ON so a
         * cloud `--sse` deploy is protected unless explicitly opted out via AUTH_ENABLED=false.
         * Fails fast if enabled without a PUBLIC_URL — an unprotected token audience is worse
         * than not starting.
         */
        fun from(get: (String) -> String?): AuthConfig {
            val enabled = get("AUTH_ENABLED")?.trim()?.lowercase() != "false"
            val publicUrl = get("PUBLIC_URL")?.trim()?.trimEnd('/').orEmpty()
            if (enabled) {
                require(publicUrl.isNotEmpty()) {
                    "AUTH_ENABLED but PUBLIC_URL is unset. Set PUBLIC_URL to the server's " +
                        "canonical URL (e.g. https://mcp.example.com), or AUTH_ENABLED=false."
                }
                require(!publicUrl.contains('#')) { "PUBLIC_URL must not contain a fragment." }
            }
            val ttl = parseTtlSeconds(get("ACCESS_TOKEN_TTL")?.trim()) ?: 3600L
            return AuthConfig(
                enabled = enabled,
                publicUrl = publicUrl,
                clientId = get("OAUTH_CLIENT_ID")?.trim()?.takeIf { it.isNotEmpty() },
                clientSecret = get("OAUTH_CLIENT_SECRET")?.trim()?.takeIf { it.isNotEmpty() },
                ownerPassword = get("OWNER_PASSWORD")?.trim()?.takeIf { it.isNotEmpty() },
                accessTokenTtlSeconds = ttl,
                keyPath = get("JWT_KEY_PATH")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "data/oauth-signing-key.json",
            )
        }

        /** Accepts plain seconds or suffixed forms: 3600, 90s, 30m, 1h. */
        private fun parseTtlSeconds(raw: String?): Long? {
            if (raw.isNullOrEmpty()) return null
            val m = Regex("""^(\d+)\s*([smh]?)$""").find(raw.lowercase()) ?: return null
            val n = m.groupValues[1].toLong()
            return when (m.groupValues[2]) {
                "m" -> n * 60
                "h" -> n * 3600
                else -> n
            }
        }
    }
}
