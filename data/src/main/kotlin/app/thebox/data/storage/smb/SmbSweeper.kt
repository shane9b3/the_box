// WP-S1 (docs/design/m6-storage-locations.md §3.5 "File names and sweeps"; A-N2).
package app.thebox.data.storage.smb

import app.thebox.core.common.BoxLog
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.smb.RemoteShare
import java.io.IOException

/**
 * The SMB sweep (§3.5), run by the keeper on `box-smb` at the first READY of each process, **after** the `.wake` write:
 * - (a) our own `ring-<deviceId>-*` and `test-<deviceId>-*` files that are not this process's (`-<pid>-` names are kept:
 *   this process's open ring, and its older files whose posted delete may still be queued);
 * - (b) another device's `ring-*`/`test-*` whose LastWriteTime is older than our just-written `.wake-<deviceId>`'s by more
 *   than `FOREIGN_STALE_MS`: server time against server time, immune to clock skew (A-N2);
 * - another device's `.wake-*` older than that too (never one younger than 48 h).
 * A failed delete or time query is ignored (retried at the next start). Never blocks a tune.
 */
class SmbSweeper {
    data class Result(val deleted: List<String>, val kept: Int, val failures: Int)

    /** [dir] is the share-relative `…/TheBox-rewind` folder. */
    fun sweep(share: RemoteShare, dir: String, deviceId: String, pid: Int): Result {
        val wakeName = "$WAKE_PREFIX$deviceId"
        val entries = try { share.list(dir, LIST_MAX) } catch (e: IOException) {
            BoxLog.i(TAG) { "sweep list failed: ${SmbFaults.faultName(e)}" }
            return Result(emptyList(), 0, 1)
        }
        val wakeMs = try { share.lastWriteMs("$dir/$wakeName") } catch (_: IOException) { null }
        val ownPrefixes = listOf("$RING_PREFIX$deviceId-", "$TEST_PREFIX$deviceId-")
        val mine = "$RING_PREFIX$deviceId-$pid-"
        val deleted = ArrayList<String>()
        var kept = 0
        var failures = 0
        for (e in entries) {
            if (e.directory) continue
            val n = e.name
            val own = ownPrefixes.any { n.startsWith(it) }
            val delete = when {
                n == wakeName -> false
                own -> !n.startsWith(mine) && !n.startsWith("$TEST_PREFIX$deviceId-$pid-")
                n.startsWith(RING_PREFIX) || n.startsWith(TEST_PREFIX) || n.startsWith(WAKE_PREFIX) -> {
                    if (wakeMs == null) false // no server clock reference: never guess
                    else try { wakeMs - share.lastWriteMs("$dir/$n") > StorageConstants.FOREIGN_STALE_MS } catch (_: IOException) { failures++; false }
                }
                else -> false // not ours to touch
            }
            if (!delete) { kept++; continue }
            try { share.delete("$dir/$n"); deleted += n } catch (_: IOException) { failures++ }
        }
        BoxLog.i(TAG) { "sweep: ${deleted.size} deleted, $kept kept, $failures failed" }
        return Result(deleted, kept, failures)
    }

    companion object {
        private const val TAG = "SmbSweep"
        const val RING_PREFIX = "ring-"
        const val TEST_PREFIX = "test-"
        const val WAKE_PREFIX = ".wake-"
        const val LIST_MAX = 4_096

        /** `ring-<deviceId>-<pid>-<n>.bin` (§3.5). */
        fun ringName(deviceId: String, pid: Int, n: Int): String = "$RING_PREFIX$deviceId-$pid-$n.bin"
        /** The Test's file (§7.8): `test-<deviceId>-<pid>-<nanoTime>.bin`, so this process's sweep keeps a running Test. */
        fun testName(deviceId: String, pid: Int, nano: Long): String = "$TEST_PREFIX$deviceId-$pid-$nano.bin"
        fun wakeName(deviceId: String): String = "$WAKE_PREFIX$deviceId"
    }
}
