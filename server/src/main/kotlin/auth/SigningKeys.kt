package auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import java.io.File
import java.util.UUID

/**
 * RS256 signing material for the embedded authorization server.
 *
 * The private key signs access tokens; the public half is published at the JWKS endpoint and
 * used by the resource-server middleware to verify them. The key is persisted as a JWK JSON
 * file so tokens keep verifying across restarts — regenerating it on every boot would
 * invalidate every issued token. Mount [AuthConfig.keyPath] on a durable volume in Docker.
 */
class SigningKeys private constructor(val rsaKey: RSAKey) {
    /** Public-only JWK Set JSON for the `/.well-known/jwks.json` endpoint. */
    val publicJwkSetJson: String = JWKSet(rsaKey.toPublicJWK()).toString(/* publicKeysOnly = */ true)

    companion object {
        /** Load the persisted key, or generate + persist a fresh one on first run. */
        fun loadOrCreate(path: String): SigningKeys {
            val file = File(path).absoluteFile
            if (file.isFile) {
                return SigningKeys(RSAKey.parse(file.readText()))
            }
            val key = RSAKeyGenerator(2048)
                .keyUse(KeyUse.SIGNATURE)
                .keyID(UUID.randomUUID().toString())
                .algorithm(JWSAlgorithm.RS256)
                .generate()
            file.parentFile?.mkdirs()
            file.writeText(key.toJSONString())
            return SigningKeys(key)
        }
    }
}
