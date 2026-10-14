package fieldlog.cli

import fieldlog.core.Codec
import fieldlog.core.Notebook
import fieldlog.core.RelayClient
import fieldlog.core.Replica
import fieldlog.core.SyncCursor
import fieldlog.core.syncOnce
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RelayTest {
    private fun <T> withRelay(store: File? = null, body: (String, Relay) -> T): T {
        val relay = Relay(store)
        val port = relay.start("127.0.0.1", 0)
        try {
            return body("http://127.0.0.1:$port", relay)
        } finally {
            relay.stop()
        }
    }

    @Test
    fun twoDevicesConvergeThroughTheRelay() = withRelay { url, _ ->
        val a = Replica("a"); val b = Replica("b")
        val ca = SyncCursor(); val cb = SyncCursor()
        Notebook(a).addHole(name = "BH01")
        Notebook(b).addHole(name = "BH02")
        syncOnce(a, RelayClient(url), ca)
        syncOnce(b, RelayClient(url), cb)
        syncOnce(a, RelayClient(url), ca)
        assertEquals(a.dump(), b.dump())
        assertEquals(listOf("BH01", "BH02"), Notebook(a).view().holes.map { it.name })
        // Nothing new: a sync sends and receives nothing.
        val again = syncOnce(a, RelayClient(url), ca)
        assertEquals(0, again.sent)
        assertEquals(0, again.received)
    }

    @Test
    fun theRelayKeepsEachOperationOnceAndSurvivesARestart() {
        val store = File.createTempFile("relay", ".ops").also { it.delete(); it.deleteOnExit() }
        val a = Replica("a")
        Notebook(a).addHole(name = "BH01")
        withRelay(store) { url, relay ->
            RelayClient(url).push(a.log)
            RelayClient(url).push(a.log)
            assertEquals(a.log.size, relay.size)
        }
        withRelay(store) { url, relay ->
            assertEquals(a.log.size, relay.size)
            val (ops, next) = RelayClient(url).pull(0)
            assertEquals(a.log, ops)
            assertEquals(a.log.size.toLong(), next)
            assertEquals(0, RelayClient(url).pull(next).first.size)
        }
        assertEquals(Codec.HEADER, store.readLines().first())
    }

    @Test
    fun malformedOperationsAreRejectedWhole() = withRelay { url, relay ->
        val c = URL("$url/ops").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.outputStream.use { it.write("A\t1@a\tholes\tx\nnonsense\n".toByteArray()) }
        assertEquals(400, c.responseCode)
        assertEquals(0, relay.size)
    }

    @Test
    fun offlineIsAnIOExceptionAndLosesNothing() {
        val a = Replica("a")
        Notebook(a).addHole(name = "BH01")
        val cursor = SyncCursor()
        assertFailsWith<IOException> { syncOnce(a, RelayClient("http://127.0.0.1:9", 500), cursor) }
        assertEquals(0, cursor.pushed, "nothing marked as sent")
        withRelay { url, relay ->
            syncOnce(a, RelayClient(url), cursor)
            assertEquals(a.log.size, relay.size)
        }
    }

    @Test
    fun theDemoTellsItsStoryAndEndsReadyForStrata() {
        val buf = ByteArrayOutputStream()
        val out = File.createTempFile("merged", ".csv").also { it.deleteOnExit() }
        assertEquals(0, Demo(PrintStream(buf), out.path).run())
        val text = buf.toString()
        assertTrue("identical, down to the tombstones" in text, text)
        assertTrue("refused: BH41: 2 holes have this name" in text, text)
        assertTrue(out.readLines().any { it.startsWith("BH42,560,520,63.8,") }, out.readText())
    }
}
