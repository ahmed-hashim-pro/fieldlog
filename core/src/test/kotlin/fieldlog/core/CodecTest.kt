package fieldlog.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CodecTest {
    private val a = OpId(3, "pixel-a")

    @Test
    fun everyOpRoundTrips() {
        val ops = listOf(
            SetField(a, "hole/1@x", "name", "BH\t07\nnew \\ line"),
            Add(OpId(4, "b"), "flags/1@x", "water-strike"),
            Remove(OpId(5, "b"), "holes", "1@x", listOf(a, OpId(9, "c"))),
            Remove(OpId(6, "b"), "holes", "1@x", emptyList()),
            Insert(OpId(7, "b"), "notes/1@x", null, "é"),
            Insert(OpId(8, "b"), "notes/1@x", OpId(7, "b"), "\n"),
            Delete(OpId(9, "b"), "notes/1@x", OpId(7, "b")),
        )
        assertEquals(ops, Codec.read(Codec.write(ops)))
        for (op in ops) assertEquals(1, Codec.encode(op).lines().size, "one op, one line")
    }

    @Test
    fun rejectsWhatItCannotRead() {
        assertFailsWith<IllegalArgumentException> { Codec.read("something else\n") }
        val e = assertFailsWith<IllegalArgumentException> { Codec.read("${Codec.HEADER}\nX\t1@a\n") }
        assertEquals("line 2: unknown op kind 'X'", e.message)
        assertFailsWith<IllegalArgumentException> { Codec.decode("S\t1@a\tonly-three") }
        assertFailsWith<IllegalArgumentException> { Codec.decode("A\tnot-an-id\ts\te") }
    }
}
