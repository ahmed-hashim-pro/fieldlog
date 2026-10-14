package fieldlog.cli

import fieldlog.core.BOREHOLE
import fieldlog.core.Notebook
import fieldlog.core.RelayClient
import fieldlog.core.Replica
import fieldlog.core.Strata
import fieldlog.core.SyncCursor
import fieldlog.core.syncOnce
import java.io.File
import java.io.PrintStream

/**
 * The README's story, end to end, over real HTTP: a relay, a desk that loads
 * strata's example site, and two phones that go offline, log the same hole
 * name in two places, and come back.
 */
class Demo(private val out: PrintStream, private val exportTo: String? = null) {
    private class Phone(val name: String, url: String) {
        val replica = Replica(name)
        val nb = Notebook(replica)
        val client = RelayClient(url)
        val cursor = SyncCursor()
        fun sync() = syncOnce(replica, client, cursor)
    }

    private fun say(who: String, what: String) = out.println("%-8s %s".format(who, what))

    fun run(): Int {
        val relay = Relay()
        val port = relay.start("127.0.0.1", 0)
        val url = "http://127.0.0.1:$port"
        try {
            say("relay", "listening on $url (store-and-forward, no authority)")

            val desk = Replica("desk")
            val site = javaClass.getResource("/site.csv")!!.readText()
            Strata.import(site, Notebook(desk))
            Notebook(desk).siteName("Riverside (strata example, seed 7)")
            RelayClient(url).push(desk.log)
            say("desk", "imports strata's example site: ${Notebook(desk).view().holes.size} boreholes, ${desk.log.size} operations to the relay")

            val a = Phone("pixel-a", url)
            val b = Phone("pixel-b", url)
            a.sync(); b.sync()
            say("", "pixel-a has ${a.nb.view().holes.size} holes, pixel-b has ${b.nb.view().holes.size}")

            out.println("\n-- both phones lose signal --")
            val (bh41, _) = a.nb.addHole(BOREHOLE, "BH41")
            a.nb.setHole(bh41, "x", "600"); a.nb.setHole(bh41, "y", "480"); a.nb.setHole(bh41, "collar", "64.62")
            for ((unit, from, to) in listOf(
                Triple("topsoil", "0", "0.9"), Triple("clay", "0.9", "6.2"), Triple("sand", "6.2", "10.3"),
                Triple("gravel", "10.3", "13.2"), Triple("chalk", "13.2", "14.5"),
            )) a.nb.addInterval(bh41, unit, from, to)
            say("pixel-a", "logs BH41 at (600, 480), collar 64.62: topsoil to chalk, 14.5 m")
            a.nb.flag(bh41, "water-strike", true)
            a.nb.editNotes(bh41, "Water strike at 7.4 m, rose to 6.8 m in 20 min.")
            say("pixel-a", "flags BH41 water-strike and notes the levels")

            val (other, _) = b.nb.addHole(BOREHOLE, "BH41")
            b.nb.setHole(other, "x", "560"); b.nb.setHole(other, "y", "520"); b.nb.setHole(other, "collar", "63.80")
            for ((unit, from, to) in listOf(
                Triple("topsoil", "0", "0.7"), Triple("clay", "0.7", "6.1"), Triple("sand", "6.1", "10.0"),
                Triple("gravel", "10.0", "13.8"), Triple("chalk", "13.8", "15.0"),
            )) b.nb.addInterval(other, unit, from, to)
            say("pixel-b", "logs a hole it also calls BH41, at (560, 520), collar 63.80, 15.0 m")

            val bh16a = a.nb.view().holes.first { it.name == "BH16" }.id
            a.nb.editNotes(bh16a, "Clay firm, fissured. ")
            b.nb.editNotes(bh16a, "Recheck collar level. ")
            say("pixel-a", "types in BH16's notes: \"Clay firm, fissured. \"")
            say("pixel-b", "types in BH16's notes at the same time: \"Recheck collar level. \"")

            out.println("\n-- back in signal: each phone syncs --")
            val ra1 = a.sync(); val rb = b.sync(); val ra2 = a.sync()
            say("pixel-a", "sent ${ra1.sent}, received ${ra1.newHere + ra2.newHere} new")
            say("pixel-b", "sent ${rb.sent}, received ${rb.newHere} new")
            for (p in listOf(a, b)) {
                say(p.name, "${p.nb.view().holes.size} holes, state ${digest(p.replica)}")
            }
            if (a.replica.dump() != b.replica.dump()) {
                say("", "the phones DISAGREE"); return 1
            }
            say("", "identical, down to the tombstones")
            say("", "BH16 notes on both: \"${a.nb.view().holes.first { it.name == "BH16" }.notes.trim()}\"")

            out.println("\n-- export for strata --")
            val (refused, report) = Strata.export(a.nb.view())
            if (refused == null) {
                say("check", "refused: ${report.problems.joinToString("; ")}")
            }
            b.nb.setHole(other, "name", "BH42")
            say("pixel-b", "renames its hole BH42; both sync")
            b.sync(); a.sync(); b.sync()
            val (csv, ok) = Strata.export(a.nb.view())
            if (csv == null) {
                say("check", "still refused: ${ok.problems.joinToString("; ")}"); return 1
            }
            val n = ok.ready.size
            if (exportTo != null) {
                File(exportTo).writeText(csv)
                say("check", "$n boreholes ready; wrote $exportTo")
            } else {
                say("check", "$n boreholes ready (fieldlog demo -o merged.csv writes them)")
            }
            return 0
        } finally {
            relay.stop()
        }
    }
}
