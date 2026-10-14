package fieldlog.core

/** SplitMix64: small, fast, and the same sequence on every JVM and every phone. */
class Rng(seed: Long) {
    private var s = seed

    fun next(): Long {
        s += -0x61c8864680b583ebL
        var z = s
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun int(n: Int): Int = ((next() ushr 1) % n).toInt()
    fun range(lo: Int, hi: Int) = lo + int(hi - lo + 1)
    fun chance(p: Double) = (next() ushr 11).toDouble() / (1L shl 53).toDouble() < p
    fun <T> pick(xs: List<T>): T = xs[int(xs.size)]
    fun <T> shuffle(xs: MutableList<T>) {
        for (i in xs.size - 1 downTo 1) {
            val j = int(i + 1)
            val t = xs[i]; xs[i] = xs[j]; xs[j] = t
        }
    }
}

data class FuzzStats(var edits: Int = 0, var ops: Int = 0, var delivered: Int = 0, var dropped: Int = 0, var duplicated: Int = 0, var offline: Int = 0)

data class FuzzResult(val seed: Long, val failure: String?, val stats: FuzzStats) {
    val ok get() = failure == null
}

/**
 * One fuzz run: a few loggers edit the same notebook while the network
 * drops, duplicates, delays and reorders their operations and devices go
 * offline for a while. Afterwards everyone syncs, and three things must hold:
 *
 * - **local intent**: every edit shows up on the device that made it, at once;
 * - **convergence**: every replica ends in the same state, tombstones included;
 * - **order independence**: a fresh replica given all the operations in a
 *   random order ends in that state too.
 *
 * A run is a pure function of its seed.
 */
class FuzzRun(val seed: Long, val bug: Bug? = null, private val trace: ((String) -> Unit)? = null) {
    private val rng = Rng(seed)
    private val bugs = setOfNotNull(bug)
    private val replicas = List(rng.range(2, 4)) { Replica("r$it", bugs) }
    private val books = replicas.map { Notebook(it) }
    private val online = BooleanArray(replicas.size) { true }
    private val stats = FuzzStats()

    private class Msg(val at: Long, val tie: Long, val to: Int, val op: Op)

    private val inflight = ArrayList<Msg>()
    private var now = 0L

    // Small value pools, so concurrent writes collide on the same fields and text.
    private val names = listOf("BH01", "BH02", "BH03", "BH07", "TP1", "OC-north")
    private val unitPool = listOf("topsoil", "clay", "sand", "gravel", "chalk")
    private val depths = listOf("0", "0.4", "1.2", "2.5", "3", "4,5", "6.1", "8")
    private val snippets = listOf("a", "clay", " ", "é", "µm", "°", "firm ", "\n", "wet", "BH", "Ω")

    fun run(): FuzzResult {
        return try {
            val steps = rng.range(80, 260)
            for (step in 0 until steps) {
                now += rng.range(1, 5)
                deliverDue()
                if (rng.chance(0.03)) toggleOnline()
                if (rng.chance(0.04)) antiEntropy(rng.int(replicas.size), rng.int(replicas.size))
                val r = rng.int(replicas.size)
                val failure = edit(r)
                if (failure != null) return FuzzResult(seed, "local intent: $failure", stats)
            }
            heal()
            FuzzResult(seed, finalChecks(), stats)
        } catch (e: RuntimeException) {
            FuzzResult(seed, "crash: ${e::class.simpleName}: ${e.message}", stats)
        }
    }

    private fun log(s: String) = trace?.invoke("t=%5d  %s".format(now, s))

    private fun send(from: Int, ops: List<Op>) {
        stats.ops += ops.size
        if (!online[from]) return
        for (op in ops) for (to in replicas.indices) {
            if (to == from || !online[to]) continue
            if (rng.chance(0.08)) { stats.dropped++; continue }
            val copies = if (rng.chance(0.08)) 2 else 1
            if (copies == 2) stats.duplicated++
            repeat(copies) { inflight += Msg(now + rng.range(1, 40), rng.next(), to, op) }
        }
    }

    private fun deliverDue() {
        val due = inflight.filter { it.at <= now }.sortedWith(compareBy({ it.at }, { it.tie }))
        inflight.removeAll(due.toSet())
        for (m in due) {
            if (!online[m.to]) { stats.dropped++; continue }
            replicas[m.to].receive(m.op)
            stats.delivered++
        }
    }

    private fun toggleOnline() {
        val r = rng.int(replicas.size)
        online[r] = !online[r]
        if (!online[r]) stats.offline++
        log("${replicas[r].name} goes ${if (online[r]) "online" else "offline"}")
        if (online[r]) for (o in replicas.indices) if (o != r && online[o]) antiEntropy(r, o)
    }

    /** What the app's sync does on reconnect: each side sends the other its whole log, in a scrambled order. */
    private fun antiEntropy(a: Int, b: Int) {
        if (a == b || !online[a] || !online[b]) return
        for ((from, to) in listOf(a to b, b to a)) {
            val ops = replicas[from].log.toMutableList()
            rng.shuffle(ops)
            for (op in ops) {
                replicas[to].receive(op)
                stats.delivered++
            }
        }
    }

    private fun heal() {
        for (i in online.indices) online[i] = true
        now += 1000
        deliverDue()
        for (round in 0 until 2) for (a in replicas.indices) for (b in replicas.indices) {
            if (a < b) antiEntropy(a, b)
            if (rng.chance(0.3)) antiEntropy(b, a)
        }
    }

    private fun finalChecks(): String? {
        replicas.forEach { if (it.waiting > 0) return "stuck: ${it.name} still holds ${it.waiting} operations waiting on others" }
        val dumps = replicas.map { it.dump() }
        val views = books.map { it.view() }
        for (i in 1 until replicas.size) {
            if (dumps[i] != dumps[0]) {
                val how = if (views[i] == views[0]) "same visible notebook, different state underneath" else firstDifference(views[0], views[i])
                return "convergence: ${replicas[0].name} and ${replicas[i].name} diverged: $how"
            }
        }
        val all = LinkedHashMap<OpId, Op>()
        replicas.forEach { r -> r.log.forEach { all.putIfAbsent(it.id, it) } }
        val ops = all.values.toMutableList()
        rng.shuffle(ops)
        val fresh = Replica("fresh", bugs)
        ops.forEach { fresh.receive(it) }
        if (fresh.dump() != dumps[0]) {
            return "order independence: the same ${ops.size} operations in another order give a different notebook"
        }
        return null
    }

    private fun firstDifference(a: SiteView, b: SiteView): String {
        if (a.name != b.name) return "site name '${a.name}' vs '${b.name}'"
        if (a.units != b.units) return "column ${a.units} vs ${b.units}"
        if (a.holes.map { it.id } != b.holes.map { it.id }) return "holes ${a.holes.map { it.id }} vs ${b.holes.map { it.id }}"
        for ((x, y) in a.holes.zip(b.holes)) {
            if (x.notes != y.notes) return "notes of ${x.id}: ${quote(x.notes)} vs ${quote(y.notes)}"
            for ((field, get) in listOf<Pair<String, (HoleView) -> Any>>(
                "name" to { it.name }, "kind" to { it.kind }, "x" to { it.x }, "y" to { it.y },
                "collar" to { it.collar }, "flags" to { it.flags },
            )) if (get(x) != get(y)) return "${x.id}.$field '${get(x)}' vs '${get(y)}'"
            if (x.intervals.map { it.id } != y.intervals.map { it.id }) return "intervals of ${x.id}: ${x.intervals.map { it.id }} vs ${y.intervals.map { it.id }}"
            for ((i, j) in x.intervals.zip(y.intervals)) if (i != j) return "interval ${i.id}: ${i.unit} ${i.from}-${i.to} vs ${j.unit} ${j.from}-${j.to}"
        }
        return "views differ"
    }

    private fun quote(s: String) = "\"" + s.replace("\n", "\\n").take(60) + "\""

    // ---- edits, each checked for local intent ----

    private fun edit(r: Int): String? {
        val nb = books[r]
        val who = replicas[r].name
        val view = nb.view()
        val holes = view.holes
        val roll = rng.int(100)
        stats.edits++
        fun did(ops: List<Op>, what: String) { log("$who $what"); send(r, ops) }

        when {
            roll < 8 || holes.isEmpty() -> {
                val (id, ops) = nb.addHole(if (rng.chance(0.15)) OUTCROP else BOREHOLE, rng.pick(names))
                did(ops, "adds hole $id")
                if (nb.view().hole(id) == null) return "$who added hole $id and can't see it"
            }
            roll < 10 -> {
                val h = rng.pick(holes)
                did(nb.removeHole(h.id), "removes hole ${h.id}")
                if (nb.view().hole(h.id) != null) return "$who removed hole ${h.id} and still sees it"
            }
            roll < 12 -> {
                val gone = nb.knownHoles().filter { view.hole(it) == null }
                if (gone.isEmpty()) return null
                val id = rng.pick(gone)
                did(nb.restoreHole(id), "restores hole $id")
                if (nb.view().hole(id) == null) return "$who restored hole $id and can't see it"
            }
            roll < 28 -> {
                val h = rng.pick(holes)
                val field = rng.pick(listOf("name", "x", "y", "collar", "kind"))
                val value = when (field) {
                    "name" -> rng.pick(names)
                    "kind" -> rng.pick(listOf(BOREHOLE, OUTCROP))
                    else -> rng.pick(listOf("120", "340.5", "52.3", "0", "1e3", "12,5", ""))
                }
                did(nb.setHole(h.id, field, value), "sets ${h.id}.$field = '$value'")
                if (nb.replica.field(Notebook.hole(h.id), field) != value) return "$who set ${h.id}.$field to '$value' and sees '${nb.replica.field(Notebook.hole(h.id), field)}'"
            }
            roll < 40 -> {
                val h = rng.pick(holes)
                val (id, ops) = nb.addInterval(h.id, rng.pick(unitPool), rng.pick(depths), rng.pick(depths))
                did(ops, "logs interval $id in ${h.id}")
                if (nb.view().hole(h.id)?.intervals?.none { it.id == id } != false) return "$who logged interval $id in ${h.id} and can't see it"
            }
            roll < 52 -> {
                val iv = holes.flatMap { it.intervals }.takeIf { it.isNotEmpty() }?.let { rng.pick(it) } ?: return null
                val field = rng.pick(listOf("unit", "from", "to"))
                val value = if (field == "unit") rng.pick(unitPool) else rng.pick(depths)
                did(nb.setInterval(iv.id, field, value), "sets interval ${iv.id}.$field = '$value'")
                if (nb.replica.field(Notebook.interval(iv.id), field) != value) return "$who set interval ${iv.id}.$field to '$value' and sees '${nb.replica.field(Notebook.interval(iv.id), field)}'"
            }
            roll < 56 -> {
                val h = holes.filter { it.intervals.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { rng.pick(it) } ?: return null
                val iv = rng.pick(h.intervals)
                did(nb.removeInterval(h.id, iv.id), "removes interval ${iv.id}")
                if (nb.view().hole(h.id)?.intervals?.any { it.id == iv.id } == true) return "$who removed interval ${iv.id} and still sees it"
            }
            roll < 66 -> {
                val h = rng.pick(holes)
                val flag = rng.pick(FLAGS.take(3))
                val on = flag !in h.flags || rng.chance(0.2)
                did(nb.flag(h.id, flag, on), "${if (on) "flags" else "unflags"} ${h.id} $flag")
                val has = flag in (nb.view().hole(h.id)?.flags ?: emptyList())
                if (has != on) return "$who ${if (on) "flagged" else "unflagged"} ${h.id} $flag and sees it ${if (has) "set" else "clear"}"
            }
            roll < 88 -> {
                // Notes on the first two holes only, so typing collides.
                val h = rng.pick(holes.take(2))
                val before = h.notes
                val pos = if (rng.chance(0.5)) before.length else rng.int(before.length + 1)
                val s = rng.pick(snippets)
                did(nb.insertNotes(h.id, pos, s), "types ${quote(s)} at $pos in notes of ${h.id}")
                val after = nb.view().hole(h.id)?.notes
                val want = before.substring(0, pos) + s + before.substring(pos)
                if (after != want) return "$who typed ${quote(s)} at $pos in ${h.id}: wanted ${quote(want)}, sees ${quote(after ?: "")}"
            }
            roll < 96 -> {
                val h = rng.pick(holes.take(2))
                val before = h.notes
                if (before.isEmpty()) return null
                val pos = rng.int(before.length)
                val n = minOf(rng.range(1, 4), before.length - pos)
                did(nb.deleteNotes(h.id, pos, n), "deletes $n at $pos in notes of ${h.id}")
                val after = nb.view().hole(h.id)?.notes
                val want = before.removeRange(pos, pos + n)
                if (after != want) return "$who deleted $n at $pos in ${h.id}: wanted ${quote(want)}, sees ${quote(after ?: "")}"
            }
            roll < 98 -> {
                val units = unitPool.filter { rng.chance(0.7) }
                did(nb.units(units), "sets the column to $units")
            }
            else -> {
                val name = rng.pick(listOf("Riverside", "North field", "Plot 4"))
                did(nb.siteName(name), "names the site '$name'")
                if (nb.view().name != name) return "$who named the site '$name' and sees '${nb.view().name}'"
            }
        }
        return null
    }
}

data class MutantRow(val label: String, val firstSeed: Long?, val failed: Int, val seeds: Int, val kinds: List<String>)

object Fuzz {
    fun hunt(from: Long, seeds: Int, bug: Bug? = null, stopAtFirst: Boolean = false): List<FuzzResult> {
        val out = ArrayList<FuzzResult>()
        for (s in from until from + seeds) {
            val r = FuzzRun(s, bug).run()
            out += r
            if (stopAtFirst && !r.ok) break
        }
        return out
    }

    fun mutants(seeds: Int): List<MutantRow> = (listOf<Bug?>(null) + Bug.entries).map { bug ->
        val rs = hunt(1, seeds, bug)
        val failed = rs.filter { !it.ok }
        MutantRow(
            bug?.label ?: "correct",
            failed.firstOrNull()?.seed,
            failed.size,
            seeds,
            failed.mapNotNull { it.failure?.substringBefore(':') }.distinct().sorted(),
        )
    }
}
