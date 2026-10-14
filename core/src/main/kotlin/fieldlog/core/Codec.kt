package fieldlog.core

/**
 * One operation per line, tab-separated, so the op log, the relay's store and
 * the sync protocol all share one format that a person can read with `less`.
 *
 *     S  <id>  <entity>  <field>  <value>
 *     A  <id>  <set>     <elem>
 *     R  <id>  <set>     <elem>   <tag,tag,...>
 *     I  <id>  <text>    <after|->  <char>
 *     D  <id>  <text>    <target>
 *
 * Backslash, tab, newline and carriage return are escaped inside fields.
 */
object Codec {
    const val HEADER = "fieldlog-ops 1"

    fun encode(op: Op): String = when (op) {
        is SetField -> join("S", op.id.toString(), op.entity, op.field, op.value)
        is Add -> join("A", op.id.toString(), op.set, op.elem)
        is Remove -> join("R", op.id.toString(), op.set, op.elem, op.tags.joinToString(","))
        is Insert -> join("I", op.id.toString(), op.text, op.after?.toString() ?: "-", op.ch)
        is Delete -> join("D", op.id.toString(), op.text, op.target.toString())
    }

    fun decode(line: String): Op {
        val f = line.split('\t').map(::unescape)
        fun need(n: Int) = require(f.size == n) { "expected ${n} fields for '${f[0]}', got ${f.size}: $line" }
        val id = { OpId.parse(f[1]) }
        return when (f[0]) {
            "S" -> { need(5); SetField(id(), f[2], f[3], f[4]) }
            "A" -> { need(4); Add(id(), f[2], f[3]) }
            "R" -> {
                need(5)
                Remove(id(), f[2], f[3], if (f[4].isEmpty()) emptyList() else f[4].split(',').map(OpId::parse))
            }
            "I" -> { need(5); Insert(id(), f[2], if (f[3] == "-") null else OpId.parse(f[3]), f[4]) }
            "D" -> { need(4); Delete(id(), f[2], OpId.parse(f[3])) }
            else -> throw IllegalArgumentException("unknown op kind '${f[0]}'")
        }
    }

    /** A whole op log: the header line, then one op per line. */
    fun write(ops: Iterable<Op>): String = buildString {
        append(HEADER).append('\n')
        for (op in ops) append(encode(op)).append('\n')
    }

    fun read(text: String): List<Op> {
        val lines = text.lines().filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        require(lines[0] == HEADER) { "not a fieldlog op log (first line should be '$HEADER')" }
        return lines.drop(1).mapIndexed { i, l ->
            try {
                decode(l)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("line ${i + 2}: ${e.message}")
            }
        }
    }

    private fun join(vararg fields: String) = fields.joinToString("\t") { escape(it) }

    private fun escape(s: String): String {
        if (s.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return s
        val b = StringBuilder()
        for (c in s) when (c) {
            '\\' -> b.append("\\\\")
            '\t' -> b.append("\\t")
            '\n' -> b.append("\\n")
            '\r' -> b.append("\\r")
            else -> b.append(c)
        }
        return b.toString()
    }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> b.append('\\')
                    't' -> b.append('\t')
                    'n' -> b.append('\n')
                    'r' -> b.append('\r')
                    else -> throw IllegalArgumentException("bad escape '\\${s[i + 1]}'")
                }
                i += 2
            } else {
                b.append(c); i++
            }
        }
        return b.toString()
    }
}
