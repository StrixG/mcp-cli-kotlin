import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Unit tests for [parseDurationSeconds]. */
class DurationTest {

    @Test
    fun `units convert to seconds`() {
        assertEquals(60L, parseDurationSeconds("60s"))
        assertEquals(300L, parseDurationSeconds("5m"))
        assertEquals(3_600L, parseDurationSeconds("1h"))
        assertEquals(86_400L, parseDurationSeconds("24h").let { it!! / 24 } * 24) // sanity
        assertEquals(86_400L, parseDurationSeconds("1d"))
        assertEquals(604_800L, parseDurationSeconds("7d"))
    }

    @Test
    fun `bare number is seconds, case and spaces tolerated`() {
        assertEquals(90L, parseDurationSeconds("90"))
        assertEquals(3_600L, parseDurationSeconds(" 1H "))
        assertEquals(120L, parseDurationSeconds("2 m"))
    }

    @Test
    fun `garbage and empty return null`() {
        assertNull(parseDurationSeconds(null))
        assertNull(parseDurationSeconds(""))
        assertNull(parseDurationSeconds("abc"))
        assertNull(parseDurationSeconds("1y"))
        assertNull(parseDurationSeconds("-5m"))
    }
}
