package fieldlog.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrataTest {
    private val site = File("../examples/site.csv").readText()

    /** strata's rows as (hole, x, y, collar, unit, from, to) with numbers parsed. */
    private fun rows(csv: String) = csv.lines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("units:") && it != Strata.HEADER }
        .map { l -> l.split(',').let { f -> listOf(f[0], f[4]) + listOf(1, 2, 3, 5, 6).map { f[it].toDouble() } } }

    @Test
    fun importThenExportGivesStrataTheSameSite() {
        val nb = Notebook(Replica("desk"))
        Strata.import(site, nb)
        val (csv, report) = Strata.export(nb.view())
        assertNotNull(csv, report.problems.joinToString())
        assertEquals(40, report.ready.size)
        assertEquals(rows(site).sortedBy { "${it[0]} ${it[5]}" }, rows(csv).sortedBy { "${it[0]} ${it[5]}" })
        assertTrue(csv.lines().contains("units: topsoil, clay, sand, gravel, chalk"))
    }

    @Test
    fun theExportSurvivesSyncToAnotherDevice() {
        val a = Replica("a")
        Strata.import(site, Notebook(a))
        val b = Replica("b")
        a.log.shuffled(java.util.Random(4)).forEach { b.receive(it) }
        assertEquals(Strata.export(Notebook(a).view()).first, Strata.export(Notebook(b).view()).first)
    }

    private fun small(): Pair<Notebook, String> {
        val nb = Notebook(Replica("a"))
        nb.units(listOf("topsoil", "clay", "chalk"))
        val (h, _) = nb.addHole(BOREHOLE, "BH01")
        nb.setHole(h, "x", "10"); nb.setHole(h, "y", "20"); nb.setHole(h, "collar", "50.5")
        return nb to h
    }

    private fun problems(nb: Notebook) = Strata.check(nb.view()).problems.map { it.toString() }

    @Test
    fun aCleanLogExportsWithTheNumbersAsTyped() {
        val (nb, h) = small()
        nb.addInterval(h, "topsoil", "0", "0,45")
        nb.addInterval(h, "clay", "0.45", "6.10")
        val (csv, _) = Strata.export(nb.view())
        assertNotNull(csv)
        assertTrue("BH01,10,20,50.5,topsoil,0,0.45" in csv.lines(), csv)
        assertTrue("BH01,10,20,50.5,clay,0.45,6.1" in csv.lines(), csv)
    }

    @Test
    fun refusesWhatStrataWouldRefuseAndSaysWhy() {
        val (nb, h) = small()
        nb.addInterval(h, "topsoil", "0", "1")
        nb.addInterval(h, "chalk", "1.2", "3")
        nb.addInterval(h, "clay", "3", "4")
        nb.addInterval(h, "basalt", "4", "5")
        val p = problems(nb)
        assertTrue(p.any { "gap between 1 and 1.2 m" in it }, p.toString())
        assertTrue(p.any { "clay below chalk is out of stratigraphic order" in it }, p.toString())
        assertTrue(p.any { "unit 'basalt' is not in the column" in it }, p.toString())
        assertNull(Strata.export(nb.view()).first)
    }

    @Test
    fun overlapsMissingNumbersAndBadNames() {
        val (nb, h) = small()
        nb.addInterval(h, "topsoil", "0", "1")
        nb.addInterval(h, "clay", "0.8", "2")
        nb.setHole(h, "collar", "")
        nb.setHole(h, "name", "BH 01")
        val p = problems(nb)
        assertTrue(p.any { "overlap between 1 and 0.8 m" in it }, p.toString())
        assertTrue(p.any { "collar is missing" in it }, p.toString())
        assertTrue(p.any { "name 'BH 01' is not valid" in it }, p.toString())
    }

    @Test
    fun twoHolesWithOneNameIsAConflictNotAMerge() {
        val a = Replica("a"); val b = Replica("b")
        val na = Notebook(a); val nbB = Notebook(b)
        na.units(listOf("clay", "chalk"))
        a.log.forEach { b.receive(it) }
        for ((nb, x) in listOf(na to "0", nbB to "100")) {
            val (h, _) = nb.addHole(BOREHOLE, "BH07")
            nb.setHole(h, "x", x); nb.setHole(h, "y", "0"); nb.setHole(h, "collar", "10")
            nb.addInterval(h, "clay", "0", "2")
        }
        a.log.forEach { b.receive(it) }; b.log.forEach { a.receive(it) }
        assertEquals(2, na.view().holes.count { it.name == "BH07" })
        assertTrue(problems(na).any { it.startsWith("BH07: 2 holes have this name") }, problems(na).toString())
    }

    @Test
    fun outcropsAndEmptyHolesAreLeftOutNotRefused() {
        val (nb, h) = small()
        nb.addInterval(h, "topsoil", "0", "1")
        nb.addHole(BOREHOLE, "BH02")
        nb.addHole(fieldlog.core.OUTCROP, "OC1")
        val r = Strata.check(nb.view())
        assertTrue(r.ok, r.problems.toString())
        assertEquals(listOf("BH01"), r.ready)
        assertEquals(listOf("BH02 (nothing logged)", "OC1 (outcrop)").sorted(), r.skipped.sorted())
    }

    @Test
    fun numbersAreWrittenExactly() {
        assertEquals("0", Strata.fmt(0.0))
        assertEquals("0", Strata.fmt(-0.0))
        assertEquals("64.7", Strata.fmt(64.70))
        assertEquals("0.0001", Strata.fmt(1e-4))
        assertEquals("1000", Strata.fmt(1e3))
        assertEquals(12.5, parseNumber(" 12,5 "))
        assertNull(parseNumber("12 m"))
        assertNull(parseNumber("1,2,3"))
        assertNull(parseNumber("NaN"))
    }

    @Test
    fun importRejectsWhatStrataRejects() {
        val nb = Notebook(Replica("a"))
        val bad = "units: clay, chalk\nhole,x,y,collar,unit,from,to\nBH01,0,0,10,clai,0,1\n"
        val e = runCatching { Strata.import(bad, nb) }.exceptionOrNull()
        assertEquals("line 3: unknown unit 'clai'", e?.message)
    }
}
