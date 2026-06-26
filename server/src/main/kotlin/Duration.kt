/**
 * Parse a compact duration like `"60s"`, `"5m"`, `"1h"`, `"24h"`, `"7d"` into seconds.
 *
 * Used both for the collector interval (`COLLECT_INTERVAL`) and the `get_summary`
 * `period` window. A bare number is treated as seconds. Returns null on garbage so
 * callers can fall back to a default or report a readable error.
 */
fun parseDurationSeconds(raw: String?): Long? {
    val s = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val match = Regex("""^(\d+)\s*([smhd]?)$""").matchEntire(s) ?: return null
    val n = match.groupValues[1].toLongOrNull() ?: return null
    val unit = match.groupValues[2]
    val factor = when (unit) {
        "", "s" -> 1L
        "m" -> 60L
        "h" -> 3_600L
        "d" -> 86_400L
        else -> return null
    }
    return n * factor
}
