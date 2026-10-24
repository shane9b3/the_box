// WP-S1 (docs/design/m6-storage-locations.md §3.5 "File names and sweeps"; A-N2). Fix pass, core half (2026-10-24): C-S7.
package app.thebox.data.storage.smb

import app.thebox.core.common.BoxLog
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.smb.FaultClass
import app.thebox.core.storage.smb.OpenMode
import app.thebox.core.storage.smb.RemoteShare
import java.io.IOException

/**
 * The SMB sweep (§3.5), run by the keeper on `box-smb` at the first READY of each process, **after** the `.wake` write:
 * - (a) our own `ring-<deviceId>-*` and `test-<deviceId>-*` files that are not this process's (`-<pid>-` names are kept:
 *   this process's open ring, and its older files whose posted delete may still be queued);
 * - (b) another device's `ring-*`/`test-*` whose LastWriteTime is older than our just-written `.wake-<deviceId>`'s by more
 *   than `FOREIGN_STALE_MS`: server time against server time, immune to clock skew (A-N2);
 * - another device's `.wake-*` older than that too (never one younger than 48 h).
 *
 * (C-S7) A foreign file is deleted only through an **exclusive** open with delete-on-close: if the other box still holds
 * it open (NTFS may defer the LastWriteTime of a file held open), the server answers a sharing violation and the file is
 * kept. The sweep **stops at the first SLOW or LINK_LOST** answer: each round trip can take an I/O timeout on a hung
 * server, and the sweep holds `box-smb` (the ring's reconnects, posted CLOSEs, keepalives) while it runs.
 * Any other failed delete or time query is ignored (retried at the next start). Never blocks a tune.
 *
 * [classify] is the link's (`RemoteLink.classify`); [isSharingViolation] recognises the "held open" answer.
 */
class SmbSweeper(
    private val isSharingViolation: (Throwable) -> Boolean = SmbFaults::isSharingViolation,
) {
    data class Result(val deleted: List<String>, val kept: Int, val failures: Int, val stoppedEarly: Boolean = false)

    private class Stop : Exception()

    /** [dir] is the share-relative `…/TheBox-rewind` folder. */
    fun sweep(
        share: RemoteShare, dir: String, deviceId: String, pid: Int,
        classify: (Throwable) -> FaultClass = { SmbFaults.classify(it, Thread.currentThread().isInterrupted, null) },
    ): Result {
        val wakeName = "$WAKE_PREFIX$deviceId"
        val entries = try { share.list(dir, LIST_MAX) } catch (e: IOException) {
            BoxLog.i(TAG) { "sweep list failed: ${SmbFaults.faultName(e)}" }
            return Result(emptyList(), 0, 1)
        }
        val wakeMs = try { share.lastWriteMs("$dir/$wakeName") } catch (e: IOException) {
            if (stops(classify(e))) return Result(emptyList(), 0, 1, stoppedEarly = true)
            null
        }
        val ownPrefixes = listOf("$RING_PREFIX$deviceId-", "$TEST_PREFIX$deviceId-")
        val mine = "$RING_PREFIX$deviceId-$pid-"
        val deleted = ArrayList<String>()
        var kept = 0
        var failures = 0
        var stopped = false
        try {
            for (e in entries) {
                if (e.directory) continue
                val n = e.name
                val own = ownPrefixes.any { n.startsWith(it) }
                val foreign = !own && (n.startsWith(RING_PREFIX) || n.startsWith(TEST_PREFIX) || n.startsWith(WAKE_PREFIX))
                val delete = when {
                    n == wakeName -> false
                    own -> !n.startsWith(mine) && !n.startsWith("$TEST_PREFIX$deviceId-$pid-")
                    foreign -> {
                        if (wakeMs == null) false // no server clock reference: never guess
                        else try { wakeMs - share.lastWriteMs("$dir/$n") > StorageConstants.FOREIGN_STALE_MS } catch (x: IOException) {
                            if (stops(classify(x))) throw Stop()
                            failures++; false
                        }
                    }
                    else -> false // not ours to touch
                }
                if (!delete) { kept++; continue }
                try {
                    if (foreign) {
                        // C-S7: exclusive + delete-on-close, so a file another box holds open is refused, never removed.
                        share.open("$dir/$n", OpenMode.OPEN_EXISTING, deleteOnClose = true, exclusive = true).closeAsync()
                    } else share.delete("$dir/$n")
                    deleted += n
                } catch (x: IOException) {
                    if (foreign && isSharingViolation(x)) { kept++; continue } // still open on the other box
                    if (stops(classify(x))) throw Stop()
                    failures++
                }
            }
        } catch (s: Stop) {
            stopped = true
            failures++
        }
        BoxLog.i(TAG) { "sweep: ${deleted.size} deleted, $kept kept, $failures failed${if (stopped) ", stopped (slow or lost)" else ""}" }
        return Result(deleted, kept, failures, stopped)
    }

    /** C-S7: a slow or lost server ends the sweep (the next start retries); a cancel too. */
    private fun stops(c: FaultClass) = c == FaultClass.SLOW || c == FaultClass.LINK_LOST || c == FaultClass.CANCEL

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
