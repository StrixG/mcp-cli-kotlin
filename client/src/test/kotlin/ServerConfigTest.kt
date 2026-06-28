import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerConfigTest {

    private val env = mapOf("HA_MCP_SECRET" to "s3cr3t", "ID" to "cli")

    @Test
    fun interpolatesKnownVars() {
        assertEquals("cli", interpolateEnv("\${ID}", env::get))
        assertEquals("Bearer s3cr3t", interpolateEnv("Bearer \${HA_MCP_SECRET}", env::get))
    }

    @Test
    fun leavesLiteralsUntouched() {
        assertEquals("no placeholders here", interpolateEnv("no placeholders here", env::get))
        assertEquals("price is \$5", interpolateEnv("price is \$5", env::get))
    }

    @Test
    fun throwsOnMissingVar() {
        assertFailsWith<IllegalStateException> { interpolateEnv("\${NOPE}", env::get) }
    }
}
