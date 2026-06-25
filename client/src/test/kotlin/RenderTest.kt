import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Render markdown through a recorder-backed, ANSI-NONE terminal so output is
 * deterministic plain text: markdown markers stripped, text retained, no escapes.
 */
class RenderTest {

    private fun render(markdown: String): String {
        val recorder = TerminalRecorder()
        renderMarkdown(markdown, Terminal(terminalInterface = recorder))
        return recorder.output()
    }

    @Test
    fun boldMarkersStripped() {
        val out = render("**bold**")
        assertFalse(out.contains("**"), out)
        assertTrue(out.contains("bold"), out)
    }

    @Test
    fun headingMarkerStripped() {
        val out = render("# Title")
        assertFalse(out.contains("#"), out)
        assertTrue(out.contains("Title"), out)
    }

    @Test
    fun inlineCodeMarkersStripped() {
        val out = render("call `get_state` now")
        assertFalse(out.contains("`"), out)
        assertTrue(out.contains("get_state"), out)
    }

    @Test
    fun bulletListTextRetained() {
        val out = render("- first\n- second")
        assertTrue(out.contains("first"), out)
        assertTrue(out.contains("second"), out)
        assertFalse(out.contains("- first"), out)
    }
}
