package pl.etoo.watchbridge

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

const val TAG = "WatchBridge"

/** Prosty, globalny status połączenia dla UI i powiadomienia serwisu. */
object Status {
    @Volatile
    var text: String = "Zatrzymany"
        private set

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun set(t: String) {
        if (t == text) return
        text = t
        Log.i(TAG, "status: $t")
        main.post { listeners.forEach { it(t) } }
    }

    fun add(l: (String) -> Unit) = listeners.add(l)
    fun remove(l: (String) -> Unit) = listeners.remove(l)
}
