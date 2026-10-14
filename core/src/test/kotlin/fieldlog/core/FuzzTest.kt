package fieldlog.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FuzzTest {
    private val seeds = System.getProperty("fuzz.seeds", "300").toInt()

    @Test
    fun theCorrectMergeHoldsOnEverySeed() {
        val failed = Fuzz.hunt(1, seeds).filter { !it.ok }
        assertTrue(failed.isEmpty(), failed.take(3).joinToString("\n") { "seed ${it.seed}: ${it.failure}" })
    }

    @Test
    fun everyDeliberateBugIsCaught() {
        for (bug in Bug.entries) {
            val first = Fuzz.hunt(1, seeds, bug, stopAtFirst = true).lastOrNull { !it.ok }
            assertTrue(first != null, "${bug.label} survived $seeds seeds")
        }
    }

    @Test
    fun aRunIsAPureFunctionOfItsSeed() {
        for (s in listOf(1L, 17L, 99L)) {
            val a = FuzzRun(s).run()
            val b = FuzzRun(s).run()
            assertEquals(a, b)
            val lines1 = ArrayList<String>(); val lines2 = ArrayList<String>()
            FuzzRun(s, trace = { lines1 += it }).run(); FuzzRun(s, trace = { lines2 += it }).run()
            assertEquals(lines1, lines2)
            assertTrue(lines1.isNotEmpty())
        }
    }

    @Test
    fun theFuzzerReallyStressesTheNetwork() {
        val total = Fuzz.hunt(1, 50).map { it.stats }
        assertTrue(total.sumOf { it.dropped } > 0)
        assertTrue(total.sumOf { it.duplicated } > 0)
        assertTrue(total.sumOf { it.offline } > 0)
    }
}
