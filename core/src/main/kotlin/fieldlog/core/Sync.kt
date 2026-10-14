package fieldlog.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to a relay. The relay is a store-and-forward log with no authority:
 * it keeps every operation anyone sent it, in arrival order, and hands back
 * the ones after a cursor. Merging happens on the devices.
 *
 *     POST /ops            body: one op per line     -> "added <n> total <m>"
 *     GET  /ops?since=<k>  -> header line, then ops k.. ; X-Fieldlog-Next: <cursor>
 */
class RelayClient(baseUrl: String, private val timeoutMs: Int = 5000) {
    private val base = baseUrl.trimEnd('/')

    fun push(ops: List<Op>): Int {
        if (ops.isEmpty()) return 0
        val c = open("$base/ops", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        c.outputStream.use { it.write(ops.joinToString("") { Codec.encode(it) + "\n" }.toByteArray()) }
        val body = read(c)
        return body.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
    }

    /** Ops from [since] on, and the cursor to ask from next time. */
    fun pull(since: Long): Pair<List<Op>, Long> {
        val c = open("$base/ops?since=$since", "GET")
        val body = read(c)
        val next = c.getHeaderField("X-Fieldlog-Next")?.toLongOrNull() ?: throw IOException("relay sent no cursor")
        return Codec.read(body) to next
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        return c
    }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        if (code !in 200..299) throw IOException("relay answered $code: ${text.trim()}")
        return text
    }
}

/**
 * Where a device is in the conversation with the relay. Both numbers only
 * grow; losing them is harmless (the relay and the replica both ignore
 * operations they already have), it only costs a bigger transfer.
 */
data class SyncCursor(var pushed: Int = 0, var pulled: Long = 0)

data class SyncReport(val sent: Int, val received: Int, val newHere: Int)

/**
 * One round of sync: send this device's operations the relay hasn't
 * acknowledged, then apply everything the relay got since last time.
 * Throws IOException when offline; nothing is lost, the next round retries.
 */
fun syncOnce(replica: Replica, client: RelayClient, cursor: SyncCursor): SyncReport {
    val log = replica.log
    val mine = ArrayList<Op>()
    for (i in cursor.pushed until log.size) if (log[i].id.replica == replica.name) mine += log[i]
    val upTo = log.size
    client.push(mine)
    cursor.pushed = upTo
    val (ops, next) = client.pull(cursor.pulled)
    var fresh = 0
    for (op in ops) if (replica.receive(op)) fresh++
    cursor.pulled = next
    return SyncReport(mine.size, ops.size, fresh)
}
