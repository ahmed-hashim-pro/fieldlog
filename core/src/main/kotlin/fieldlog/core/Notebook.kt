package fieldlog.core

/** Things a logger marks on a hole. Shown as chips; merged as a set, add-wins. */
val FLAGS = listOf("water-strike", "refusal", "made-ground", "odour", "core-loss")

const val BOREHOLE = "borehole"
const val OUTCROP = "outcrop"

data class IntervalView(val id: String, val unit: String, val from: String, val to: String)

data class HoleView(
    val id: String,
    val name: String,
    val kind: String,
    val x: String,
    val y: String,
    val collar: String,
    val intervals: List<IntervalView>,
    val flags: List<String>,
    val notes: String,
)

data class SiteView(val name: String, val units: List<String>, val holes: List<HoleView>) {
    fun hole(id: String) = holes.firstOrNull { it.id == id }
}

/**
 * The field notebook on top of a [Replica]: a site with a stratigraphic
 * column, holes (boreholes or outcrops) with a location, a log of intervals,
 * flags and free-text notes.
 *
 * Keys in the replica:
 *
 *     site.name, site.units                     LWW fields
 *     holes                                     OR-set of hole ids
 *     hole/<id>.name|kind|x|y|collar            LWW fields
 *     iv/<hole>                                 OR-set of interval ids
 *     interval/<id>.unit|from|to                LWW fields
 *     flags/<hole>                              OR-set of flag names
 *     notes/<hole>                              RGA text
 *
 * Ids are the op id that created the thing, so two loggers can never mint the
 * same one. Two loggers can still both call a hole "BH07"; that is a real
 * conflict, and [Strata.check] reports it rather than merging the two.
 */
class Notebook(val replica: Replica) {
    fun siteName(name: String) = listOf(replica.set(SITE, "name", name))

    fun units(units: List<String>) = listOf(replica.set(SITE, "units", units.joinToString(", ")))

    /** Returns the new hole's id and the operations that made it. */
    fun addHole(kind: String = BOREHOLE, name: String = ""): Pair<String, List<Op>> {
        // A fresh Lamport id is unique across replicas whether or not an op carries it.
        val id = replica.nextId().toString()
        val ops = mutableListOf<Op>()
        ops += replica.set(hole(id), "kind", kind)
        ops += replica.add(HOLES, id)
        if (name.isNotEmpty()) ops += replica.set(hole(id), "name", name)
        return id to ops
    }

    fun removeHole(id: String) = listOfNotNull(replica.remove(HOLES, id))

    /** Bring back a removed hole with everything it had. */
    fun restoreHole(id: String) = listOf(replica.add(HOLES, id))

    fun setHole(id: String, field: String, value: String): List<Op> {
        require(field in HOLE_FIELDS) { "unknown hole field '$field'" }
        return listOf(replica.set(hole(id), field, value))
    }

    fun addInterval(holeId: String, unit: String, from: String, to: String): Pair<String, List<Op>> {
        val id = replica.nextId().toString()
        val ops = listOf(
            replica.set(interval(id), "unit", unit),
            replica.set(interval(id), "from", from),
            replica.set(interval(id), "to", to),
            replica.add(ivs(holeId), id),
        )
        return id to ops
    }

    fun setInterval(id: String, field: String, value: String): List<Op> {
        require(field in INTERVAL_FIELDS) { "unknown interval field '$field'" }
        return listOf(replica.set(interval(id), field, value))
    }

    fun removeInterval(holeId: String, id: String) = listOfNotNull(replica.remove(ivs(holeId), id))

    fun flag(holeId: String, flag: String, on: Boolean): List<Op> =
        if (on) listOf(replica.add(flags(holeId), flag)) else listOfNotNull(replica.remove(flags(holeId), flag))

    fun editNotes(holeId: String, text: String) = replica.replaceText(notes(holeId), text)

    fun insertNotes(holeId: String, pos: Int, s: String) = replica.insertText(notes(holeId), pos, s)

    fun deleteNotes(holeId: String, pos: Int, count: Int) = replica.deleteText(notes(holeId), pos, count)

    fun knownHoles(): List<String> = replica.knownEntities("hole/").map { it.removePrefix("hole/") }

    fun view(): SiteView {
        val units = replica.field(SITE, "units").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val holes = replica.members(HOLES).map { id ->
            fun f(name: String) = replica.field(hole(id), name).orEmpty()
            val intervals = replica.members(ivs(id)).map { iv ->
                fun g(name: String) = replica.field(interval(iv), name).orEmpty()
                IntervalView(iv, g("unit"), g("from"), g("to"))
            }.sortedWith(compareBy<IntervalView>({ parseNumber(it.from) ?: Double.MAX_VALUE }, { it.id }))
            HoleView(
                id, f("name"), f("kind").ifEmpty { BOREHOLE }, f("x"), f("y"), f("collar"),
                intervals, replica.members(flags(id)), replica.text(notes(id)),
            )
        }.sortedWith(compareBy<HoleView>({ it.name.isEmpty() }, { it.name }, { it.id }))
        return SiteView(replica.field(SITE, "name").orEmpty(), units, holes)
    }

    companion object {
        const val SITE = "site"
        const val HOLES = "holes"
        val HOLE_FIELDS = setOf("name", "kind", "x", "y", "collar")
        val INTERVAL_FIELDS = setOf("unit", "from", "to")
        fun hole(id: String) = "hole/$id"
        fun interval(id: String) = "interval/$id"
        fun ivs(holeId: String) = "iv/$holeId"
        fun flags(holeId: String) = "flags/$holeId"
        fun notes(holeId: String) = "notes/$holeId"
    }
}

/**
 * A number as a person types it in the field: surrounding spaces are fine,
 * and so is a decimal comma ("12,5"). Anything else isn't a number.
 */
fun parseNumber(s: String): Double? {
    var t = s.trim()
    if (t.count { it == ',' } == 1 && '.' !in t) t = t.replace(',', '.')
    if (t.isEmpty() || t.any { !(it.isDigit() || it == '.' || it == '-' || it == '+') }) return null
    return t.toDoubleOrNull()?.takeIf { it.isFinite() }
}
