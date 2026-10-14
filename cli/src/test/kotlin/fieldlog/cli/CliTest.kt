package fieldlog.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CliTest {
    private val site = File("../examples/site.csv").path

    private fun tmp(suffix: String) = File.createTempFile("fieldlog", suffix).also { it.delete(); it.deleteOnExit() }

    @Test
    fun importExportAndCheckExitCodes() {
        val ops = tmp(".ops")
        assertEquals(0, run(listOf("import", site, "-o", ops.path)))
        assertEquals(0, run(listOf("check", ops.path)))
        val csv = tmp(".csv")
        assertEquals(0, run(listOf("export", ops.path, "-o", csv.path)))
        assertEquals(3 + 188, csv.readLines().size, "comment, units, header, then one row per interval")
    }

    @Test
    fun anInconsistentNotebookIsRefused() {
        val ops = tmp(".ops")
        run(listOf("import", site, "-o", ops.path))
        // Append a hole with a gap in its log, written by another device.
        val r = load(ops.path, "pixel-z")
        val nb = fieldlog.core.Notebook(r)
        val (h, _) = nb.addHole(name = "BH99")
        nb.setHole(h, "x", "1"); nb.setHole(h, "y", "1"); nb.setHole(h, "collar", "50")
        nb.addInterval(h, "topsoil", "0", "1"); nb.addInterval(h, "clay", "1.5", "3")
        save(r, ops.path)
        assertEquals(1, run(listOf("check", ops.path)))
        assertEquals(1, run(listOf("export", ops.path)))
    }

    @Test
    fun usageErrors() {
        assertFailsWith<UsageError> { run(listOf("nope")) }
        assertFailsWith<UsageError> { run(listOf("export")) }
        assertFailsWith<UsageError> { run(listOf("fuzz", "hunt", "--seeds")) }
        assertFailsWith<UsageError> { run(listOf("fuzz", "hunt", "--sedes", "3")) }
        assertFailsWith<IllegalArgumentException> { run(listOf("fuzz", "hunt", "--bug", "nope")) }
        assertFailsWith<IllegalArgumentException> { run(listOf("check", "/no/such/file")) }
    }

    @Test
    fun fuzzCommands() {
        assertEquals(0, run(listOf("fuzz", "hunt", "--seeds", "20")))
        assertEquals(1, run(listOf("fuzz", "hunt", "--seeds", "20", "--bug", "rga-no-skip")))
        assertEquals(0, run(listOf("fuzz", "run", "--seed", "3")))
    }
}
