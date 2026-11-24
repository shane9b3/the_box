package app.thebox.settings

import android.content.Context
import app.thebox.core.settings.state.GuideDomain
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.TextSize
import app.thebox.core.settings.UiTextScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * (Owner request 2026-11-24, docs/decisions/2026-11-24-text-sizes.md) The "Text size" row at the app's start. The
 * settings store loads on IO and the first frame does not wait for it, so the chosen size is mirrored into a one-key
 * preferences file that is read here, synchronously, before any text is measured. The mirror follows the stored value
 * (a change in Settings, a restored backup) and is read again at the next start only: [UiTextScale.running] never
 * changes inside a process.
 */
object TextSizeBoot {
    private const val FILE = "boot"
    private const val KEY = "text_size"

    /**
     * Before `Application.super.onCreate()`: Hilt builds the injected singletons there, and the guide's text measurer
     * is one of them.
     */
    fun read(context: Context) {
        UiTextScale.running = parse(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))
    }

    fun mirror(context: Context, store: SettingsStore, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            store.loaded.first { it }
            store.flow(GuideDomain).map { it.textSize }.distinctUntilChanged().collect { size -> write(context, size) }
        }
    }

    /**
     * Writes the mirror and waits for the disk (`commit`, never `apply`: "Restart now" ends the process with
     * `Runtime.exit`, which does not wait for queued preference writes). Not on the main thread. `RestartNow` calls it
     * itself before it exits, so the restart never depends on the collector above having run (review of 98ac38c).
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun write(context: Context, size: TextSize): Boolean {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.getString(KEY, null) == size.name) return true
        return prefs.edit().putString(KEY, size.name).commit()
    }

    fun parse(stored: String?): TextSize = TextSize.entries.firstOrNull { it.name == stored } ?: TextSize.DEFAULT
}
