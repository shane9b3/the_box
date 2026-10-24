package app.thebox.playback

import android.content.Context
import app.thebox.core.common.BoxLog
import app.thebox.core.storage.FallbackToastGate
import app.thebox.core.storage.LocationFallback
import app.thebox.core.storage.StorageConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (docs/design/m6-storage-locations.md section 5.1, 10, WP-S4) `PlayerEffects.fallbackToastDue` answered from `:app`:
 * whether the "Rewind is saving on this device" toast is due for a (location, reason).
 * - **Once per process** per (location, reason) (owner ruling 1: one toast).
 * - `NOT_READY_YET` is **never** toasted (still connecting, not unreachable).
 * - `SIGN_IN_NEEDED` toasts in the first [StorageConstants.SIGN_IN_TOAST_PROCESSES] (3) processes after it starts, then
 *   never (U-S7): the process counter is device state in `noBackupFilesDir`, never backed up.
 */
class FallbackToastPolicy(private val counters: SignInToastCounters) : FallbackToastGate {
    private val seen = HashSet<Pair<String, LocationFallback>>()

    @Synchronized
    override fun due(locationId: String, reason: LocationFallback): Boolean {
        if (reason == LocationFallback.NOT_READY_YET) return false
        if (!seen.add(locationId to reason)) return false
        if (reason != LocationFallback.SIGN_IN_NEEDED) return true
        val n = counters.processes(locationId)
        if (n >= StorageConstants.SIGN_IN_TOAST_PROCESSES) return false
        counters.setProcesses(locationId, n + 1)
        return true
    }

    /**
     * U-S9 (save-location review 2026-10-24): a sign-in that worked (or a removal) ends the episode, so the next
     * "sign in again" starts a fresh three processes (§5.1 "after it starts"), and may toast again in this process.
     */
    @Synchronized
    override fun signedIn(locationId: String) {
        seen.remove(locationId to LocationFallback.SIGN_IN_NEEDED)
        counters.reset(locationId)
    }
}

/** How many processes toasted "sign in again" for a location. Device state, not backed up. */
interface SignInToastCounters {
    fun processes(locationId: String): Int
    fun setProcesses(locationId: String, count: Int)
    /** U-S9: forgets [locationId]'s count (a new sign-in episode). */
    fun reset(locationId: String) = setProcesses(locationId, 0)
}

/** A tiny text file (`<locationId> <count>` per line) in `noBackupFilesDir`; read once, written on change. */
@Singleton
class FileSignInToastCounters @Inject constructor(@ApplicationContext context: Context) : SignInToastCounters {
    private val file = File(context.noBackupFilesDir, "smb-signin-toasts")
    private val counts: MutableMap<String, Int> by lazy { load() }

    @Synchronized override fun processes(locationId: String): Int = counts[locationId] ?: 0

    @Synchronized override fun setProcesses(locationId: String, count: Int) {
        if (count == 0) { if (counts.remove(locationId) == null) return } else counts[locationId] = count
        try {
            file.writeText(counts.entries.joinToString("\n") { "${it.key} ${it.value}" })
        } catch (e: Exception) {
            BoxLog.w("FallbackToastPolicy", "counter not saved: ${e.javaClass.simpleName}")
        }
    }

    private fun load(): MutableMap<String, Int> {
        val out = HashMap<String, Int>()
        try {
            if (file.exists()) file.readLines().forEach { l ->
                val i = l.lastIndexOf(' ')
                if (i > 0) l.substring(i + 1).toIntOrNull()?.let { out[l.substring(0, i)] = it }
            }
        } catch (e: Exception) {
            BoxLog.w("FallbackToastPolicy", "counter not read: ${e.javaClass.simpleName}")
        }
        return out
    }
}
