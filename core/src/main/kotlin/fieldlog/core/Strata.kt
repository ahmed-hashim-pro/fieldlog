package fieldlog.core

import java.math.BigDecimal

/**
 * The bridge to [strata](https://github.com/ahmed-hashim-pro/strata): export
 * the notebook's boreholes as strata's borehole-log CSV, and import one.
 *
 * strata parses strictly and refuses anything ambiguous, so the export runs
 * the same checks first and refuses with the same reasons. A merged notebook
 * can be perfectly converged and still geologically inconsistent (two people
 * logged the same hole differently); the CRDT's job is that everyone sees the
 * same inconsistency, and this check's job is to say what it is.
 */
object Strata {
    const val HEADER = "hole,x,y,collar,unit,from,to"
    const val MIN_SEPARATION_M = 0.01
    private const val DEPTH_TOLERANCE_M = 1e-6

    data class Problem(val hole: String?, val message: String) {
        override fun toString() = if (hole == null) message else "$hole: $message"
    }

    data class Report(
        /** Boreholes that pass and would be exported, by name. */
        val ready: List<String>,
        /** Holes left out on purpose: outcrops, and boreholes with nothing logged yet. */
        val skipped: List<String>,
        val problems: List<Problem>,
    ) {
        val ok get() = problems.isEmpty() && ready.isNotEmpty()
    }

    fun validName(s: String) = s.isNotEmpty() && s.all { it.isAsciiLetterOrDigit() || it == '_' || it == '-' || it == '.' }

    private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /** Problems with one hole's log, in the words strata would use. */
    fun holeProblems(site: SiteView, h: HoleView): List<String> {
        val out = ArrayList<String>()
        if (!validName(h.name)) out += if (h.name.isEmpty()) "no name" else "name '${h.name}' is not valid (letters, digits, _ - .)"
        for ((label, v) in listOf("x" to h.x, "y" to h.y, "collar" to h.collar)) {
            if (parseNumber(v) == null) out += if (v.isBlank()) "$label is missing" else "$label '$v' is not a number"
        }
        var prevTo: Double? = null
        var prevUnit = -1
        for ((i, iv) in h.intervals.withIndex()) {
            val unit = site.units.indexOf(iv.unit)
            val from = parseNumber(iv.from)
            val to = parseNumber(iv.to)
            val where = "interval ${i + 1} (${iv.unit.ifEmpty { "?" }} ${iv.from.ifEmpty { "?" }}–${iv.to.ifEmpty { "?" }} m)"
            if (unit < 0) out += if (iv.unit.isEmpty()) "$where has no unit" else "$where: unit '${iv.unit}' is not in the column (${site.units.joinToString(", ")})"
            if (from == null || to == null) {
                out += "$where: depths must be numbers"
                prevTo = null
                continue
            }
            if (to <= from) out += "$where is empty or inverted"
            if (i == 0 && kotlin.math.abs(from) > DEPTH_TOLERANCE_M) out += "starts at $from m, must start at 0"
            val p = prevTo
            if (p != null && kotlin.math.abs(from - p) > DEPTH_TOLERANCE_M) {
                out += "${if (from > p) "gap" else "overlap"} between ${fmt(p)} and ${fmt(from)} m"
            }
            if (unit >= 0 && prevUnit >= 0 && unit <= prevUnit) {
                out += "$where: ${iv.unit} below ${site.units[prevUnit]} is out of stratigraphic order"
            }
            if (unit >= 0) prevUnit = unit
            prevTo = to
        }
        return out
    }

    fun check(site: SiteView): Report {
        val problems = ArrayList<Problem>()
        val ready = ArrayList<String>()
        val skipped = ArrayList<String>()
        if (site.units.size < 2) problems += Problem(null, "the column needs at least two units: one with a base, and the basement")
        for (u in site.units) if (!validName(u)) problems += Problem(null, "unit name '$u' is not valid (letters, digits, _ - .)")
        site.units.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { problems += Problem(null, "unit '$it' is listed twice") }

        val logged = site.holes.filter { it.kind == BOREHOLE && it.intervals.isNotEmpty() }
        site.holes.filter { it.kind != BOREHOLE }.forEach { skipped += "${label(it)} (outcrop)" }
        site.holes.filter { it.kind == BOREHOLE && it.intervals.isEmpty() }.forEach { skipped += "${label(it)} (nothing logged)" }

        for ((name, same) in logged.groupBy { it.name }) {
            if (name.isNotEmpty() && same.size > 1) {
                problems += Problem(name, "${same.size} holes have this name; rename one (they were probably logged on two devices)")
            }
        }
        for (h in logged) {
            val ps = holeProblems(site, h)
            ps.forEach { problems += Problem(label(h), it) }
            if (ps.isEmpty()) ready += h.name
        }
        for (i in logged.indices) for (j in 0 until i) {
            val (a, b) = logged[j] to logged[i]
            val ax = parseNumber(a.x); val ay = parseNumber(a.y); val bx = parseNumber(b.x); val by = parseNumber(b.y)
            if (ax != null && ay != null && bx != null && by != null && Math.hypot(ax - bx, ay - by) < MIN_SEPARATION_M) {
                problems += Problem(null, "holes ${label(a)} and ${label(b)} are at the same location")
            }
        }
        if (logged.isEmpty() && problems.isEmpty()) problems += Problem(null, "no logged boreholes to export")
        return Report(ready, skipped, problems)
    }

    private fun label(h: HoleView) = h.name.ifEmpty { "unnamed hole ${h.id}" }

    /** The CSV strata reads, or null with the problems if strata would refuse it. */
    fun export(site: SiteView): Pair<String?, Report> {
        val report = check(site)
        if (!report.ok) return null to report
        val csv = buildString {
            append("# ${site.name.ifEmpty { "fieldlog site" }}: exported by fieldlog\n")
            append("units: ${site.units.joinToString(", ")}\n")
            append(HEADER).append('\n')
            for (h in site.holes.filter { it.kind == BOREHOLE && it.intervals.isNotEmpty() }.sortedBy { it.name }) {
                val loc = listOf(h.x, h.y, h.collar).joinToString(",") { fmt(parseNumber(it)!!) }
                for (iv in h.intervals) {
                    append("${h.name},$loc,${iv.unit},${fmt(parseNumber(iv.from)!!)},${fmt(parseNumber(iv.to)!!)}\n")
                }
            }
        }
        return csv to report
    }

    /**
     * Shortest plain decimal that reads back as the same double: what the
     * logger typed, minus trailing zeros, never rounded and never "1.0E-4".
     */
    fun fmt(d: Double): String = BigDecimal(d.toString()).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }

    /**
     * Load a strata CSV into [nb]: the column, and every hole with its
     * intervals. Fails on anything strata itself would reject, with its line.
     */
    fun import(text: String, nb: Notebook): List<Op> {
        val ops = ArrayList<Op>()
        val lines = text.lines().mapIndexed { i, l -> i + 1 to l.trim() }.filter { it.second.isNotEmpty() && !it.second.startsWith("#") }
        fun fail(ln: Int, msg: String): Nothing = throw IllegalArgumentException("line $ln: $msg")
        val (uln, first) = lines.firstOrNull() ?: throw IllegalArgumentException("no data: expected a 'units:' line")
        if (!first.startsWith("units:")) fail(uln, "expected 'units: <top>, ..., <basement>' first")
        val units = first.removePrefix("units:").split(',').map { it.trim() }
        units.firstOrNull { !validName(it) }?.let { fail(uln, "unit name '$it' is not valid") }
        if (units.size < 2) fail(uln, "need at least two units")
        val header = lines.getOrNull(1) ?: throw IllegalArgumentException("missing header '$HEADER'")
        if (header.second.replace(" ", "") != HEADER) fail(header.first, "expected header '$HEADER'")
        ops += nb.units(units)
        var current: String? = null
        var currentId = ""
        val seen = HashSet<String>()
        for ((ln, row) in lines.drop(2)) {
            val f = row.split(',').map { it.trim() }
            if (f.size != 7) fail(ln, "expected 7 fields ($HEADER), got ${f.size}")
            if (!validName(f[0])) fail(ln, "hole id '${f[0]}' is not valid")
            for (k in listOf(1, 2, 3, 5, 6)) f[k].toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail(ln, "'${f[k]}' is not a finite number")
            if (f[4] !in units) fail(ln, "unknown unit '${f[4]}'")
            if (f[0] != current) {
                if (!seen.add(f[0])) fail(ln, "hole ${f[0]} already ended; keep a hole's rows together")
                val (id, made) = nb.addHole(BOREHOLE, f[0])
                ops += made
                ops += nb.setHole(id, "x", f[1]) + nb.setHole(id, "y", f[2]) + nb.setHole(id, "collar", f[3])
                current = f[0]
                currentId = id
            }
            ops += nb.addInterval(currentId, f[4], f[5], f[6]).second
        }
        return ops
    }
}
