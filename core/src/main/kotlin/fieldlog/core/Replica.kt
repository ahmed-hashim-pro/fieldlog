package fieldlog.core

/**
 * Deliberately broken variants of the merge. Each one is a mistake that looks
 * fine in a demo; the fuzzer has to catch every one of them.
 */
enum class Bug(val label: String, val what: String) {
    LWW_BY_ARRIVAL("lww-by-arrival", "a field keeps whichever write arrived last, not the latest write"),
    RGA_NO_SKIP("rga-no-skip", "a typed character goes straight after its anchor, ignoring concurrent inserts there"),
    NO_CAUSAL_BUFFER("no-causal-buffer", "an operation is applied before the operations it refers to"),
    REMOVE_UNOBSERVED("remove-unobserved", "a removal also removes concurrent adds it never saw"),
    ACCEPT_DUPLICATES("accept-duplicates", "an operation delivered twice is applied twice"),
    CLOCK_NO_BUMP("clock-no-bump", "receiving an operation doesn't advance the local clock");

    companion object {
        fun byLabel(label: String): Bug =
            entries.firstOrNull { it.label == label }
                ?: throw IllegalArgumentException("unknown bug '$label' (${entries.joinToString { it.label }})")
    }
}

/**
 * One copy of the notebook. It applies operations from anywhere, in any order,
 * any number of times, and ends in the same state as every other replica that
 * has applied the same set.
 *
 * Three CRDTs share one Lamport clock: last-writer-wins registers for fields,
 * observed-remove sets for membership, and RGA sequences for free text.
 */
class Replica(val name: String, val bugs: Set<Bug> = emptySet()) {
    init {
        require(name.isNotEmpty() && name.none { it == '@' || it == '\t' || it == '\n' || it == ',' }) {
            "replica name '$name' must be non-empty without '@', ',', tabs or newlines"
        }
    }

    var clock = 0L
        private set
    private val applied = HashSet<OpId>()
    private val pending = HashMap<OpId, Op>()
    private val waiters = HashMap<OpId, MutableList<Op>>()
    private val ops = ArrayList<Op>()

    private val fields = HashMap<String, HashMap<String, Pair<OpId, String>>>()
    private val sets = HashMap<String, HashMap<String, HashSet<OpId>>>()
    private val texts = HashMap<String, Rga>()

    /** Every operation applied here, in the order it was applied. */
    val log: List<Op> get() = ops

    /** Operations received but waiting for one they depend on. */
    val waiting: Int get() = pending.size

    fun has(id: OpId) = id in applied

    fun nextId() = OpId(++clock, name)

    /**
     * Take in an operation from anywhere. Returns false if it was already
     * here. An operation whose dependencies haven't arrived is held back and
     * applied as soon as they have.
     */
    fun receive(op: Op): Boolean {
        if (Bug.ACCEPT_DUPLICATES !in bugs && (op.id in applied || op.id in pending)) return false
        if (Bug.CLOCK_NO_BUMP !in bugs || op.id.replica == name) clock = maxOf(clock, op.id.counter)
        val missing = if (Bug.NO_CAUSAL_BUFFER in bugs) emptyList() else op.deps.filter { it !in applied }.distinct()
        if (missing.isEmpty()) {
            applyAndRelease(op)
        } else {
            pending[op.id] = op
            for (d in missing) waiters.getOrPut(d) { ArrayList() } += op
        }
        return true
    }

    /** Apply [op], then everything that was only waiting for it, and so on. */
    private fun applyAndRelease(op: Op) {
        val ready = ArrayDeque<Op>()
        ready += op
        while (ready.isNotEmpty()) {
            val o = ready.removeFirst()
            apply(o)
            val released = waiters.remove(o.id) ?: continue
            for (w in released) {
                if (w.id in pending && w.deps.all { it in applied }) {
                    pending.remove(w.id)
                    ready += w
                }
            }
        }
    }

    private fun apply(op: Op) {
        when (op) {
            is SetField -> {
                val m = fields.getOrPut(op.entity) { HashMap() }
                val cur = m[op.field]
                if (cur == null || Bug.LWW_BY_ARRIVAL in bugs || op.id > cur.first) m[op.field] = op.id to op.value
            }
            is Add -> sets.getOrPut(op.set) { HashMap() }.getOrPut(op.elem) { HashSet() }.add(op.id)
            is Remove -> {
                val members = sets[op.set]
                val tags = members?.get(op.elem)
                if (tags != null) {
                    if (Bug.REMOVE_UNOBSERVED in bugs) tags.clear() else tags.removeAll(op.tags.toSet())
                    if (tags.isEmpty()) members.remove(op.elem)
                }
            }
            is Insert -> texts.getOrPut(op.text) { Rga() }.insert(op.id, op.after, op.ch, Bug.RGA_NO_SKIP !in bugs)
            is Delete -> texts.getOrPut(op.text) { Rga() }.delete(op.target)
        }
        // The log holds each operation once, even when a buggy variant applies one twice.
        if (applied.add(op.id)) ops += op
    }

    // ---- local edits: each makes an operation, applies it here, and returns it to send ----

    fun set(entity: String, field: String, value: String): Op = local(SetField(nextId(), entity, field, value))

    fun add(set: String, elem: String): Op = local(Add(nextId(), set, elem))

    /** Removes [elem] as this replica sees it; concurrent adds elsewhere survive. */
    fun remove(set: String, elem: String): Op? {
        val tags = sets[set]?.get(elem) ?: return null
        return local(Remove(nextId(), set, elem, tags.sorted()))
    }

    fun insertText(text: String, pos: Int, s: String): List<Op> {
        val rga = texts.getOrPut(text) { Rga() }
        var after = rga.anchorBefore(pos)
        val out = ArrayList<Op>()
        for (ch in codePoints(s)) {
            val op = local(Insert(nextId(), text, after, ch))
            out += op
            after = op.id
        }
        return out
    }

    fun deleteText(text: String, pos: Int, count: Int): List<Op> {
        val rga = texts[text] ?: return emptyList()
        return rga.visibleIds(pos, count).map { local(Delete(nextId(), text, it)) }
    }

    /** Edit [text] to read [new], touching only the part that changed. */
    fun replaceText(text: String, new: String): List<Op> {
        val old = codePoints(text(text))
        val nw = codePoints(new)
        var p = 0
        while (p < old.size && p < nw.size && old[p] == nw[p]) p++
        var s = 0
        while (s < old.size - p && s < nw.size - p && old[old.size - 1 - s] == nw[nw.size - 1 - s]) s++
        val out = ArrayList<Op>()
        out += deleteText(text, p, old.size - p - s)
        out += insertText(text, p, nw.subList(p, nw.size - s).joinToString(""))
        return out
    }

    private fun local(op: Op): Op {
        receive(op)
        return op
    }

    // ---- reads ----

    fun field(entity: String, field: String): String? = fields[entity]?.get(field)?.second

    fun members(set: String): List<String> = sets[set]?.keys?.sorted() ?: emptyList()

    fun contains(set: String, elem: String): Boolean = sets[set]?.containsKey(elem) == true

    fun text(text: String): String = texts[text]?.text() ?: ""

    /** Entities that have ever had a field set; used to offer "restore". */
    fun knownEntities(prefix: String): List<String> = fields.keys.filter { it.startsWith(prefix) }.sorted()

    /**
     * The complete state, tombstones and tags included, in a canonical form.
     * Two replicas that converged print the same dump; two that only look
     * the same don't.
     */
    fun dump(): String = buildString {
        for (e in fields.keys.sorted()) for ((f, v) in fields[e]!!.toSortedMap()) append("F $e.$f=${v.second} @${v.first}\n")
        for (s in sets.keys.sorted()) for ((m, tags) in sets[s]!!.toSortedMap()) {
            if (tags.isNotEmpty()) append("M $s $m ${tags.sorted().joinToString(",")}\n")
        }
        for (t in texts.keys.sorted()) append("T $t ${texts[t]!!.dump()}\n")
    }

    companion object {
        fun codePoints(s: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < s.length) {
                val n = Character.charCount(s.codePointAt(i))
                out += s.substring(i, i + n)
                i += n
            }
            return out
        }
    }
}

/**
 * Replicated Growable Array. Characters form a tree by "inserted after"; the
 * text is a depth-first walk with newer siblings first. Kept as a flat list,
 * where inserting means: go to the anchor, then skip every node with a newer
 * id, since those were inserted concurrently at the same spot (or after them).
 */
internal class Rga {
    private class Node(val id: OpId, val ch: String, var deleted: Boolean = false)

    private val seq = ArrayList<Node>()
    private val byId = HashMap<OpId, Node>()

    fun insert(id: OpId, after: OpId?, ch: String, skip: Boolean) {
        var i = if (after == null) 0 else seq.indexOfFirst { it.id == after } + 1
        if (skip) while (i < seq.size && seq[i].id > id) i++
        val n = Node(id, ch)
        seq.add(i, n)
        byId[id] = n
    }

    fun delete(target: OpId) {
        byId[target]?.deleted = true
    }

    fun text(): String = buildString { for (n in seq) if (!n.deleted) append(n.ch) }

    /** The id of the visible character just before visible position [pos], or null for the start. */
    fun anchorBefore(pos: Int): OpId? {
        if (pos <= 0) return null
        var seen = 0
        for (n in seq) if (!n.deleted) {
            seen++
            if (seen == pos) return n.id
        }
        return seq.lastOrNull { !it.deleted }?.id
    }

    fun visibleIds(pos: Int, count: Int): List<OpId> =
        seq.asSequence().filter { !it.deleted }.drop(pos).take(count).map { it.id }.toList()

    fun dump(): String = seq.joinToString(" ") { (if (it.deleted) "~" else "") + it.id + "=" + it.ch.codePointAt(0) }
}
