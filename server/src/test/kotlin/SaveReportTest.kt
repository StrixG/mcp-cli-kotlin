import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [writeReport] — the file-writing core behind the save_report tool. Each test
 * writes into a throwaway temp directory that is removed afterwards.
 */
class SaveReportTest {

    private val dir: File = Files.createTempDirectory("save-report-test").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `writes content to the named file in UTF-8`() {
        val body = "# Summary\n\navg=21.5 °C, min=20, max=24"
        val file = writeReport(dir, content = body, filename = "report.md", format = null)
        assertTrue(file.isFile)
        assertEquals("report.md", file.name)
        assertEquals(body, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `generates a timestamped default name when filename omitted`() {
        val file = writeReport(dir, content = "x", filename = null, format = null)
        assertTrue(
            Regex("""report-\d{8}-\d{6}\.md""").matches(file.name),
            "unexpected generated name: ${file.name}",
        )
        assertTrue(file.isFile)
    }

    @Test
    fun `format selects the extension for extension-less names`() {
        assertEquals("notes.txt", writeReport(dir, "x", filename = "notes", format = "txt").name)
        assertEquals("data.json", writeReport(dir, "x", filename = "data", format = "json").name)
        // Default format is md.
        assertEquals("plain.md", writeReport(dir, "x", filename = "plain", format = null).name)
    }

    @Test
    fun `keeps an explicit extension already present in the filename`() {
        assertEquals("summary.txt", writeReport(dir, "x", filename = "summary.txt", format = "md").name)
    }

    @Test
    fun `rejects path traversal in the filename`() {
        for (bad in listOf("../escape.md", "..\\escape.md", "sub/dir.md", "/etc/passwd", "a/../../b.md")) {
            assertFailsWith<IllegalArgumentException>("should reject '$bad'") {
                writeReport(dir, "x", filename = bad, format = null)
            }
        }
        // Nothing leaked outside the sandbox.
        assertTrue(dir.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun `rejects an unsupported format`() {
        assertFailsWith<IllegalArgumentException> {
            writeReport(dir, "x", filename = null, format = "exe")
        }
    }
}
