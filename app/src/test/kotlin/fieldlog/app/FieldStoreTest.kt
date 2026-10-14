package fieldlog.app

import fieldlog.core.BOREHOLE
import fieldlog.core.Replica
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldStoreTest {
    private val dir = Files.createTempDirectory("fieldstore").toFile().also { it.deleteOnExit() }

    @Test
    fun theNotebookAndTheSyncPositionSurviveARestart() {
        val s = FieldStore(dir) { "pixel-a" }
        val id = s.edit { it.addHole(BOREHOLE, "BH01").first }
        s.edit { it.addInterval(id, "clay", "0", "2.5") }
        s.edit { it.editNotes(id, "firm, grey") }
        val (out, upTo) = s.outgoing()
        assertEquals(out.size, s.unsent)
        s.pushed(upTo)
        assertEquals(0, s.unsent)

        val again = FieldStore(dir) { "never-used" }
        assertEquals("pixel-a", again.device)
        assertEquals(s.replica.dump(), again.replica.dump())
        assertEquals(0, again.unsent)
        assertEquals("firm, grey", again.notebook.view().holes.single().notes)
    }

    @Test
    fun incomingOperationsArePersistedToo() {
        val other = Replica("pixel-b")
        fieldlog.core.Notebook(other).addHole(BOREHOLE, "BH09")
        val s = FieldStore(dir) { "pixel-a" }
        assertEquals(other.log.size, s.incoming(other.log, other.log.size.toLong()))
        assertEquals(0, s.incoming(other.log, other.log.size.toLong()), "duplicates are not new")
        assertEquals(0, s.unsent, "someone else's operations are not ours to send")
        val again = FieldStore(dir) { "x" }
        assertEquals(listOf("BH09"), again.notebook.view().holes.map { it.name })
        assertEquals(other.log.size.toLong(), again.cursor.pulled)
    }

    @Test
    fun theDeviceNameIsFixedOnceItHasWritten() {
        val s = FieldStore(dir) { "phone-abcde" }
        assertTrue(s.canRename)
        s.rename("pixel-a")
        s.edit { it.siteName("Riverside") }
        assertFalse(s.canRename)
        assertFailsWith<IllegalStateException> { s.rename("pixel-z") }
        assertEquals("pixel-a", FieldStore(dir) { "x" }.device)
    }
}
