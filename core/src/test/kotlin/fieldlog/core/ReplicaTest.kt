package fieldlog.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplicaTest {
    private fun exchange(vararg rs: Replica) {
        for (from in rs) for (to in rs) if (from !== to) from.log.forEach { to.receive(it) }
    }

    @Test
    fun concurrentWritesToOneFieldPickTheSameWinnerEverywhere() {
        val a = Replica("a"); val b = Replica("b")
        a.set("hole/1", "collar", "52.30")
        b.set("hole/1", "collar", "52.36")
        exchange(a, b)
        assertEquals(a.field("hole/1", "collar"), b.field("hole/1", "collar"))
        assertEquals("52.36", a.field("hole/1", "collar"), "equal counters: replica name breaks the tie")
    }

    @Test
    fun aWriteThatSawAnotherWinsOverIt() {
        val a = Replica("z"); val b = Replica("a")
        a.set("e", "f", "first")
        a.log.forEach { b.receive(it) }
        b.set("e", "f", "second")
        exchange(a, b)
        assertEquals("second", a.field("e", "f"))
    }

    @Test
    fun aConcurrentAddSurvivesARemove() {
        val a = Replica("a"); val b = Replica("b")
        a.add("flags/h", "water-strike")
        exchange(a, b)
        a.remove("flags/h", "water-strike")
        b.add("flags/h", "water-strike")
        exchange(a, b)
        assertTrue(a.contains("flags/h", "water-strike"))
        assertEquals(a.dump(), b.dump())
    }

    @Test
    fun concurrentTypingAtOneSpotInterleavesByWordNotByLetter() {
        val a = Replica("a"); val b = Replica("b")
        a.insertText("n", 0, "wet ")
        b.insertText("n", 0, "firm ")
        exchange(a, b)
        assertEquals(a.text("n"), b.text("n"))
        assertTrue(a.text("n") == "wet firm " || a.text("n") == "firm wet ", a.text("n"))
    }

    @Test
    fun anOperationWaitsForTheOneItRefersTo() {
        val a = Replica("a")
        a.insertText("n", 0, "ab")
        a.deleteText("n", 0, 1)
        val b = Replica("b")
        for (op in a.log.reversed()) b.receive(op)
        assertEquals(0, b.waiting)
        assertEquals("b", b.text("n"))
        assertEquals(a.dump(), b.dump())
    }

    @Test
    fun duplicatesAreIgnored() {
        val a = Replica("a")
        a.insertText("n", 0, "x")
        val b = Replica("b")
        assertTrue(b.receive(a.log[0]))
        assertFalse(b.receive(a.log[0]))
        assertEquals("x", b.text("n"))
    }

    @Test
    fun replaceTextOnlyTouchesWhatChanged() {
        val a = Replica("a")
        a.replaceText("n", "Clay, firm, brown.")
        val before = a.log.size
        val ops = a.replaceText("n", "Clay, stiff, brown.")
        assertEquals("Clay, stiff, brown.", a.text("n"))
        assertEquals(4 + 5, ops.size, "delete 'firm', insert 'stiff'")
        assertEquals(before + 9, a.log.size)
    }

    @Test
    fun charactersOutsideTheBasicPlaneStayWhole() {
        val a = Replica("a")
        a.replaceText("n", "pick 🔨 here")
        a.replaceText("n", "pick 🔨🔨 here")
        assertEquals("pick 🔨🔨 here", a.text("n"))
        val b = Replica("b")
        a.log.forEach { b.receive(it) }
        assertEquals(a.text("n"), b.text("n"))
    }

    @Test
    fun receivingAdvancesTheClockSoLocalEditsAreLater() {
        val a = Replica("a"); val b = Replica("b")
        repeat(10) { a.set("e", "f", "a$it") }
        exchange(a, b)
        b.set("e", "f", "mine")
        assertEquals("mine", b.field("e", "f"))
    }
}
