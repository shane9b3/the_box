package app.thebox.ui.input

import android.content.Context
import android.content.SharedPreferences
import app.thebox.core.input.KeyCapabilities
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * docs/design/m3-guide.md §1.5 (N7): SharedPreferences `keys_seen`, key `codes` = a string set of key codes.
 * [seen] starts empty and is filled by one read on [io] at creation (prefs are never read on main). [record] returns
 * at once for a known code (no write); a new code updates [seen] and is written with `apply()` (main-safe).
 * Thread-safe: mutations and writes happen under one lock, so the last write always holds the full set.
 */
class KeyCapabilitiesImpl(
    context: Context,
    scope: CoroutineScope,
    io: CoroutineDispatcher,
) : KeyCapabilities {
    constructor(context: Context) : this(context, CoroutineScope(SupervisorJob()), Dispatchers.IO)

    private val appContext = context.applicationContext
    private val state = MutableStateFlow<Set<Int>>(emptySet())
    override val seen: StateFlow<Set<Int>> = state.asStateFlow()

    private val lock = Any()
    /** Guarded by [lock]; null until the initial read on [io] finished (codes recorded before then are merged). */
    private var prefs: SharedPreferences? = null

    init {
        scope.launch(io) {
            val p = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = p.getStringSet(KEY, null).orEmpty().mapNotNullTo(HashSet()) { it.toIntOrNull() }
            synchronized(lock) {
                prefs = p
                val merged = state.value + stored
                state.value = merged
                // Codes recorded before the read landed are not on disk yet.
                if (merged.size != stored.size) write(p, merged)
            }
        }
    }

    override fun record(keyCode: Int) {
        if (keyCode in state.value) return
        synchronized(lock) {
            val current = state.value
            if (keyCode in current) return
            val next = current + keyCode
            state.value = next
            prefs?.let { write(it, next) }
        }
    }

    private fun write(p: SharedPreferences, codes: Set<Int>) {
        p.edit().putStringSet(KEY, codes.mapTo(HashSet()) { it.toString() }).apply()
    }

    companion object {
        const val PREFS = "keys_seen"
        const val KEY = "codes"
    }
}
