package app.thebox.ui.input

import android.content.Context
import app.thebox.core.input.KeyCapabilities
import kotlinx.coroutines.flow.StateFlow

/** WP-D stub (docs/design/m3-guide.md §1.5): SharedPreferences `keys_seen`; writes only a newly seen code (N7). */
class KeyCapabilitiesImpl(private val context: Context) : KeyCapabilities {
    override val seen: StateFlow<Set<Int>> get() = TODO("WP-D")
    override fun record(keyCode: Int): Unit = TODO("WP-D")
}
