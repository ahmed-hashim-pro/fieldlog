package fieldlog.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fieldlog.core.Notebook
import fieldlog.core.RelayClient
import fieldlog.core.SiteView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the store. Every change to the replica runs on the main thread; only
 * the HTTP calls go to a background thread. While "sync" is on, it syncs every
 * few seconds and right after each edit.
 */
class FieldViewModel(app: Application) : AndroidViewModel(app) {
    val store = FieldStore(app.filesDir) { FieldStore.randomName() }

    /** Bumped on every change, so the UI re-reads the notebook. */
    var version by mutableIntStateOf(0)
        private set
    var status by mutableStateOf("")
        private set
    var online by mutableStateOf(store.online)
        private set

    private var syncing = false
    private var again = false

    val view: SiteView get() = store.notebook.view()

    init {
        viewModelScope.launch {
            while (true) {
                if (online) sync()
                delay(SYNC_EVERY_MS)
            }
        }
        refreshStatus()
    }

    fun <T> edit(block: (Notebook) -> T): T {
        val r = store.edit(block)
        version++
        refreshStatus()
        if (online) viewModelScope.launch { sync() }
        return r
    }

    fun switchOnline(on: Boolean) {
        store.online = on
        online = on
        refreshStatus()
        if (on) viewModelScope.launch { sync() }
    }

    fun setRelay(url: String) {
        store.relayUrl = url
        if (online) viewModelScope.launch { sync() }
    }

    fun rename(name: String) {
        store.rename(name)
        version++
        refreshStatus()
    }

    suspend fun sync() {
        if (syncing) { again = true; return }
        syncing = true
        try {
            do {
                again = false
                val (ops, upTo) = store.outgoing()
                val url = store.relayUrl
                val since = store.cursor.pulled
                val (incoming, next) = withContext(Dispatchers.IO) {
                    val client = RelayClient(url, 4000)
                    client.push(ops)
                    client.pull(since)
                }
                if (!online) break
                store.pushed(upTo)
                if (store.incoming(incoming, next) > 0) version++
                lastSync = Date()
                error = null
            } while (again)
        } catch (e: IOException) {
            error = e.message ?: e::class.java.simpleName
        } finally {
            syncing = false
            refreshStatus()
        }
    }

    private var lastSync: Date? = null
    private var error: String? = null

    private fun refreshStatus() {
        val unsent = store.unsent
        val pending = if (unsent == 0) "all changes sent" else "$unsent changes not sent"
        status = when {
            !online -> "offline · $pending"
            error != null -> "can't reach relay · $pending"
            lastSync != null -> "synced ${TIME.format(lastSync!!)} · $pending"
            else -> "connecting · $pending"
        }
    }

    companion object {
        const val SYNC_EVERY_MS = 3000L
        private val TIME = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    }
}
