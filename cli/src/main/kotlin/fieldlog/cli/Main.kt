package fieldlog.cli

import fieldlog.core.Bug
import fieldlog.core.Codec
import fieldlog.core.Fuzz
import fieldlog.core.FuzzRun
import fieldlog.core.Notebook
import fieldlog.core.RelayClient
import fieldlog.core.Replica
import fieldlog.core.SiteView
import fieldlog.core.Strata
import fieldlog.core.SyncCursor
import fieldlog.core.syncOnce
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

const val USAGE = """fieldlog: an offline-first field notebook for boreholes, and its tools

  fieldlog relay   [--host 127.0.0.1] [--port 8787] [--data relay.ops]
  fieldlog import  <strata.csv> -o <notebook.ops> [--as DEVICE] [--site NAME]
  fieldlog export  <notebook.ops> [-o out.csv]        refuses, with reasons, if strata would
  fieldlog check   <notebook.ops>
  fieldlog show    <notebook.ops>
  fieldlog push    <notebook.ops> --relay URL         send a notebook to a relay
  fieldlog pull    --relay URL -o <notebook.ops>      everything a relay holds, as a notebook
  fieldlog demo    [-o merged.csv]                    two devices, offline, one relay
  fieldlog fuzz hunt    [--seeds N] [--from S] [--bug NAME]
  fieldlog fuzz mutants [--seeds N]
  fieldlog fuzz run     --seed S [--bug NAME] [--trace]
  fieldlog bugs                                       the deliberate merge bugs

Exit status: 0 ok, 1 refused or a fuzz failure, 2 usage or input error."""

class UsageError(msg: String) : Exception(msg)

class Args(raw: List<String>) {
    val positional = ArrayList<String>()
    private val flags = HashMap<String, String?>()

    init {
        var i = 0
        while (i < raw.size) {
            val a = raw[i]
            if (a.startsWith("-")) {
                val takesValue = a !in setOf("--trace")
                if (takesValue) {
                    if (i + 1 >= raw.size) throw UsageError("$a needs a value")
                    flags[a] = raw[i + 1]; i += 2
                } else {
                    flags[a] = null; i++
                }
            } else {
                positional += a; i++
            }
        }
    }

    fun has(f: String) = f in flags
    fun get(f: String): String? = flags[f]
    fun int(f: String, default: Int) = flags[f]?.let { it.toIntOrNull() ?: throw UsageError("$f '$it' is not a number") } ?: default
    fun long(f: String, default: Long) = flags[f]?.let { it.toLongOrNull() ?: throw UsageError("$f '$it' is not a number") } ?: default
    fun known(vararg ok: String) {
        flags.keys.firstOrNull { it !in ok }?.let { throw UsageError("unknown option $it") }
    }
}

fun main(argv: Array<String>) {
    val code = try {
        run(argv.toList())
    } catch (e: UsageError) {
        System.err.println("fieldlog: ${e.message}\n\n$USAGE")
        2
    } catch (e: IllegalArgumentException) {
        System.err.println("fieldlog: ${e.message}")
        2
    } catch (e: java.io.IOException) {
        System.err.println("fieldlog: ${e.message ?: e::class.simpleName}")
        2
    }
    System.out.flush()
    exitProcess(code)
}

fun run(argv: List<String>): Int {
    if (argv.isEmpty() || argv[0] in setOf("-h", "--help", "help")) {
        println(USAGE); return 0
    }
    val a = Args(argv.drop(1))
    return when (argv[0]) {
        "relay" -> relay(a)
        "import" -> import(a)
        "export" -> export(a)
        "check" -> check(a)
        "show" -> show(a)
        "push" -> push(a)
        "pull" -> pull(a)
        "demo" -> { a.known("-o"); Demo(System.out, a.get("-o")).run() }
        "fuzz" -> fuzz(a)
        "bugs" -> { Bug.entries.forEach { println("%-20s %s".format(it.label, it.what)) }; 0 }
        else -> throw UsageError("unknown command '${argv[0]}'")
    }
}

fun load(path: String, name: String = "desk"): Replica {
    val f = File(path)
    if (!f.exists()) throw IllegalArgumentException("$path: no such file")
    val r = Replica(name)
    val ops = try {
        Codec.read(f.readText())
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("$path: ${e.message}")
    }
    ops.forEach { r.receive(it) }
    if (r.waiting > 0) throw IllegalArgumentException("$path: ${r.waiting} operations refer to operations that aren't in the file")
    return r
}

fun save(r: Replica, path: String) = File(path).writeText(Codec.write(r.log))

private fun one(a: Args, what: String): String = when (a.positional.size) {
    1 -> a.positional[0]
    0 -> throw UsageError("missing $what")
    else -> throw UsageError("expected one $what, got ${a.positional.size}")
}

fun relay(a: Args): Int {
    a.known("--host", "--port", "--data")
    if (a.positional.isNotEmpty()) throw UsageError("relay takes no arguments")
    val data = File(a.get("--data") ?: "relay.ops")
    val r = Relay(data)
    val host = a.get("--host") ?: "127.0.0.1"
    val port = r.start(host, a.int("--port", 8787))
    println("fieldlog relay on http://$host:$port  (${r.size} operations in ${data.path})")
    println("an Android emulator reaches this machine's 127.0.0.1 as http://10.0.2.2:$port")
    Thread.currentThread().join()
    return 0
}

fun import(a: Args): Int {
    a.known("-o", "--as", "--site")
    val src = one(a, "strata CSV")
    val out = a.get("-o") ?: throw UsageError("import needs -o <notebook.ops>")
    val r = Replica(a.get("--as") ?: "desk")
    val nb = Notebook(r)
    val text = File(src).takeIf { it.exists() }?.readText() ?: throw IllegalArgumentException("$src: no such file")
    try {
        Strata.import(text, nb)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("$src: ${e.message}")
    }
    nb.siteName(a.get("--site") ?: File(src).nameWithoutExtension)
    save(r, out)
    val v = nb.view()
    println("imported ${v.holes.size} holes and ${v.holes.sumOf { it.intervals.size }} intervals from $src into $out (${r.log.size} operations)")
    return 0
}

fun export(a: Args): Int {
    a.known("-o")
    val v = Notebook(load(one(a, "notebook"))).view()
    val (csv, report) = Strata.export(v)
    if (csv == null) {
        printRefusal(report)
        return 1
    }
    val out = a.get("-o")
    if (out == null) print(csv) else {
        File(out).writeText(csv)
        println("wrote ${report.ready.size} boreholes to $out")
    }
    report.skipped.forEach { System.err.println("left out: $it") }
    return 0
}

fun printRefusal(report: Strata.Report) {
    System.err.println("not exported: strata would refuse this notebook")
    report.problems.forEach { System.err.println("  $it") }
}

fun check(a: Args): Int {
    a.known()
    val v = Notebook(load(one(a, "notebook"))).view()
    val r = Strata.check(v)
    println("${r.ready.size} boreholes ready for strata, ${r.skipped.size} left out, ${r.problems.size} problems")
    r.skipped.forEach { println("  left out: $it") }
    r.problems.forEach { println("  problem:  $it") }
    return if (r.ok) 0 else 1
}

fun show(a: Args): Int {
    a.known()
    print(describe(Notebook(load(one(a, "notebook"))).view()))
    return 0
}

fun describe(v: SiteView, only: Collection<String>? = null): String = buildString {
    append("${v.name.ifEmpty { "(unnamed site)" }}: ${v.holes.size} holes; column ${v.units.joinToString(" / ")}\n")
    for (h in v.holes) {
        if (only != null && h.name !in only) continue
        val depth = h.intervals.lastOrNull()?.to ?: "-"
        append("  %-8s %-8s (%s, %s)  collar %s  %d intervals to %s m%s\n".format(
            h.name.ifEmpty { "?" }, h.kind, h.x, h.y, h.collar, h.intervals.size, depth,
            if (h.flags.isEmpty()) "" else "  [" + h.flags.joinToString(", ") + "]",
        ))
        if (h.notes.isNotEmpty()) append("           notes: \"${h.notes.replace("\n", " / ")}\"\n")
    }
}

fun push(a: Args): Int {
    a.known("--relay")
    val url = a.get("--relay") ?: throw UsageError("push needs --relay URL")
    val r = load(one(a, "notebook"))
    val added = RelayClient(url).push(r.log)
    println("sent ${r.log.size} operations to $url; ${added} were new there")
    return 0
}

fun pull(a: Args): Int {
    a.known("--relay", "-o", "--as")
    if (a.positional.isNotEmpty()) throw UsageError("pull takes no arguments")
    val url = a.get("--relay") ?: throw UsageError("pull needs --relay URL")
    val out = a.get("-o") ?: throw UsageError("pull needs -o <notebook.ops>")
    val r = Replica(a.get("--as") ?: "desk")
    val (ops, _) = RelayClient(url).pull(0)
    ops.forEach { r.receive(it) }
    save(r, out)
    println("pulled ${ops.size} operations from $url into $out: ${Notebook(r).view().holes.size} holes")
    return 0
}

fun fuzz(a: Args): Int {
    val sub = a.positional.firstOrNull() ?: throw UsageError("fuzz needs hunt, mutants or run")
    if (a.positional.size > 1) throw UsageError("unexpected '${a.positional[1]}'")
    val bug = a.get("--bug")?.let { Bug.byLabel(it) }
    return when (sub) {
        "hunt" -> {
            a.known("--seeds", "--from", "--bug")
            val seeds = a.int("--seeds", 1000)
            val from = a.long("--from", 1)
            val t0 = System.nanoTime()
            val rs = Fuzz.hunt(from, seeds, bug)
            val failed = rs.filter { !it.ok }
            val secs = (System.nanoTime() - t0) / 1e9
            println("%s: %d seeds (%d..%d) in %.1fs: %d failed".format(bug?.label ?: "correct", seeds, from, from + seeds - 1, secs, failed.size))
            failed.take(5).forEach { println("  seed ${it.seed}: ${it.failure}") }
            if (failed.isNotEmpty()) println("replay one with: fieldlog fuzz run --seed ${failed[0].seed}${bug?.let { " --bug ${it.label}" } ?: ""} --trace")
            if (failed.isEmpty()) 0 else 1
        }
        "mutants" -> {
            a.known("--seeds")
            val seeds = a.int("--seeds", 1000)
            val t0 = System.nanoTime()
            val rows = Fuzz.mutants(seeds)
            println("%-20s %-8s %-11s %-13s %s".format("variant", "result", "first seed", "seeds failed", "caught by"))
            var ok = true
            for (r in rows) {
                val correct = r.label == "correct"
                val result = when {
                    correct && r.failed == 0 -> "clean"
                    correct -> "FAILED"
                    r.failed > 0 -> "caught"
                    else -> "MISSED"
                }
                if (result == "FAILED" || result == "MISSED") ok = false
                val pct = if (correct) "${r.failed} of ${r.seeds}" else "%d%%".format(Math.round(100.0 * r.failed / r.seeds))
                println("%-20s %-8s %-11s %-13s %s".format(r.label, result, r.firstSeed?.toString() ?: "-", pct,
                    if (correct && r.failed == 0) "every check held" else r.kinds.joinToString(", ").ifEmpty { "-" }))
            }
            println("%.1fs".format((System.nanoTime() - t0) / 1e9))
            if (ok) 0 else 1
        }
        "run" -> {
            a.known("--seed", "--bug", "--trace")
            val seed = a.get("--seed")?.toLongOrNull() ?: throw UsageError("run needs --seed N")
            val r = FuzzRun(seed, bug, if (a.has("--trace")) { s: String -> println(s) } else null).run()
            val s = r.stats
            println("seed $seed${bug?.let { " with ${it.label}" } ?: ""}: ${s.edits} edits, ${s.ops} operations, ${s.delivered} deliveries, ${s.dropped} dropped, ${s.duplicated} duplicated, ${s.offline} times offline")
            println(if (r.ok) "ok: every replica converged" else "FAILED: ${r.failure}")
            if (r.ok) 0 else 1
        }
        else -> throw UsageError("fuzz needs hunt, mutants or run")
    }
}

fun digest(r: Replica): String =
    MessageDigest.getInstance("SHA-256").digest(r.dump().toByteArray()).take(6).joinToString("") { "%02x".format(it) }
