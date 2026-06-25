import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.terminal.Terminal

/**
 * Single rendering seam for user-facing markdown. Mordant's [Markdown] widget
 * renders headings/bold/lists/code as ANSI when stdout is an interactive TTY, and
 * auto-downgrades to plain text (markers stripped, no escapes) when output is
 * piped or redirected. On Windows it enables virtual-terminal sequences too.
 */
private val defaultTerminal = Terminal()

/**
 * Print [text] as rendered markdown. Falls back to a plain print if the widget
 * throws, so an answer is never silently dropped. The [terminal] is injectable for
 * tests (a recorder-backed, ANSI-NONE terminal yields deterministic plain output).
 */
fun renderMarkdown(text: String, terminal: Terminal = defaultTerminal) {
    try {
        terminal.println(Markdown(text))
    } catch (e: Exception) {
        terminal.println(text)
    }
}
