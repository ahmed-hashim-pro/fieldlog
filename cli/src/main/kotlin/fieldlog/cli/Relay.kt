package fieldlog.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import fieldlog.core.Codec
import fieldlog.core.Op
import fieldlog.core.OpId
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * The relay: an append-only list of operations, deduplicated by id, saved to
 * a file. It never interprets an operation beyond parsing it, so it can't
 * resolve a conflict, and it can't be the reason two devices disagree.
 */
class Relay(private val store: File? = null) {
    private val ops = ArrayList<Op>()
    private val ids = HashSet<OpId>()
    private var server: HttpServer? = null

    init {
        if (store != null && store.exists()) {
            for (op in Codec.read(store.readText())) if (ids.add(op.id)) ops += op
        }
    }

    val size: Int @Synchronized get() = ops.size

    @Synchronized
    fun append(incoming: List<Op>): Int {
        val fresh = incoming.filter { ids.add(it.id) }
        if (fresh.isEmpty()) return 0
        ops += fresh
        if (store != null) {
            if (!store.exists() || store.length() == 0L) store.writeText(Codec.HEADER + "\n")
            store.appendText(fresh.joinToString("") { Codec.encode(it) + "\n" })
        }
        return fresh.size
    }

    /** Operations from [k] on, and the cursor after them. */
    @Synchronized
    fun since(k: Int): Pair<List<Op>, Int> {
        val start = k.coerceIn(0, ops.size)
        return ops.subList(start, ops.size).toList() to ops.size
    }

    fun start(host: String, port: Int): Int {
        val s = HttpServer.create(InetSocketAddress(host, port), 64)
        s.executor = Executors.newFixedThreadPool(4)
        s.createContext("/ops") { ex -> handle(ex) }
        s.createContext("/") { ex ->
            reply(ex, 200, "fieldlog relay: $size operations\n")
        }
        s.start()
        server = s
        return s.address.port
    }

    fun stop() {
        server?.stop(0)
        (server?.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    private fun handle(ex: HttpExchange) {
        try {
            when (ex.requestMethod) {
                "POST" -> {
                    val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                    val incoming = try {
                        body.lines().filter { it.isNotEmpty() }.map { Codec.decode(it) }
                    } catch (e: IllegalArgumentException) {
                        return reply(ex, 400, "bad operation: ${e.message}\n")
                    }
                    val added = append(incoming)
                    reply(ex, 200, "added $added total $size\n")
                }
                "GET" -> {
                    val q = query(ex.requestURI.rawQuery)
                    val k = q["since"]?.toIntOrNull()?.takeIf { it >= 0 } ?: return reply(ex, 400, "need ?since=<n>\n")
                    val (out, next) = since(k)
                    ex.responseHeaders.add("X-Fieldlog-Next", next.toString())
                    reply(ex, 200, Codec.write(out))
                }
                else -> reply(ex, 405, "POST or GET\n")
            }
        } catch (e: Exception) {
            reply(ex, 500, "${e::class.simpleName}: ${e.message}\n")
        }
    }

    private fun query(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    private fun reply(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
