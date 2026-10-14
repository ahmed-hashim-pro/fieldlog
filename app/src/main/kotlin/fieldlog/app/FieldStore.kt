package fieldlog.app

import fieldlog.core.Codec
import fieldlog.core.Notebook
import fieldlog.core.Op
import fieldlog.core.Replica
import fieldlog.core.SyncCursor
import java.io.File
import java.util.Properties

/**
 * The notebook on this device: the op log in a file, appended to after every
 * change, and the sync position next to it. Plain JVM code with no Android
 * types, so it runs in unit tests.
 *
 * Nothing here talks to the network. The view model takes [outgoing] to the
 * relay and brings [incoming] back, so all changes to the replica happen on
 * one thread.
 */
class FieldStore(private val dir: File, defaultName: () -> String) {
    private val opsFile = File(dir, "notebook.ops")
    private val metaFile = File(dir, "device.properties")
    private val meta = Properties()

    var replica: Replica
        private set
    var notebook: Notebook
        private set
    val cursor = SyncCursor()
    private var saved = 0

    var relayUrl: String
        get() = meta.getProperty("relay", DEFAULT_RELAY)
        set(v) { meta.setProperty("relay", v.trim()); saveMeta() }

    var online: Boolean
        get() = meta.getProperty("online", "true") == "true"
        set(v) { meta.setProperty("online", v.toString()); saveMeta() }

    init {
        dir.mkdirs()
        if (metaFile.exists()) metaFile.reader().use { meta.load(it) }
        if (meta.getProperty("device") == null) meta.setProperty("device", defaultName())
        cursor.pushed = meta.getProperty("pushed", "0").toInt()
        cursor.pulled = meta.getProperty("pulled", "0").toLong()
        replica = Replica(meta.getProperty("device"))
        notebook = Notebook(replica)
        if (opsFile.exists()) Codec.read(opsFile.readText()).forEach { replica.receive(it) }
        saved = replica.log.size
        if (cursor.pushed > saved) cursor.pushed = 0
        saveMeta()
    }

    val device: String get() = replica.name

    /** The device name is part of every op id, so it can only change before this device has written anything. */
    val canRename: Boolean get() = replica.log.none { it.id.replica == replica.name }

    fun rename(name: String) {
        check(canRename) { "this device has already written operations as ${replica.name}" }
        val fresh = Replica(name)
        replica.log.forEach { fresh.receive(it) }
        replica = fresh
        notebook = Notebook(fresh)
        meta.setProperty("device", name)
        saveMeta()
    }

    fun <T> edit(block: (Notebook) -> T): T {
        val result = block(notebook)
        persist()
        return result
    }

    /** Operations made here that the relay hasn't acknowledged. */
    val unsent: Int get() = replica.log.subList(cursor.pushed.coerceAtMost(replica.log.size), replica.log.size).count { it.id.replica == replica.name }

    /** What to send, and the log position to mark as sent once the relay has it. */
    fun outgoing(): Pair<List<Op>, Int> {
        val log = replica.log
        val from = cursor.pushed.coerceAtMost(log.size)
        return log.subList(from, log.size).filter { it.id.replica == replica.name } to log.size
    }

    fun pushed(upTo: Int) {
        cursor.pushed = maxOf(cursor.pushed, upTo)
        saveMeta()
    }

    /** Apply what the relay sent; returns how many operations were new here. */
    fun incoming(ops: List<Op>, next: Long): Int {
        var fresh = 0
        for (op in ops) if (replica.receive(op)) fresh++
        cursor.pulled = maxOf(cursor.pulled, next)
        persist()
        return fresh
    }

    private fun persist() {
        val log = replica.log
        if (log.size > saved) {
            if (!opsFile.exists() || opsFile.length() == 0L) opsFile.writeText(Codec.HEADER + "\n")
            opsFile.appendText(log.subList(saved, log.size).joinToString("") { Codec.encode(it) + "\n" })
            saved = log.size
        }
        saveMeta()
    }

    private fun saveMeta() {
        meta.setProperty("pushed", cursor.pushed.toString())
        meta.setProperty("pulled", cursor.pulled.toString())
        val tmp = File(dir, "device.properties.tmp")
        tmp.writer().use { meta.store(it, "fieldlog device state") }
        tmp.renameTo(metaFile)
    }

    companion object {
        /** The development machine, as an Android emulator sees it. */
        const val DEFAULT_RELAY = "http://10.0.2.2:8787"

        fun randomName(): String {
            val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
            return "phone-" + (1..5).map { alphabet.random() }.joinToString("")
        }
    }
}
