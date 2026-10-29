package app.thebox.data.storage.smb

import app.thebox.core.playback.MonoClock
import app.thebox.core.storage.BrowseErrorKind
import app.thebox.core.storage.smb.FaultClass
import app.thebox.core.storage.smb.RemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeoutException

/**
 * Save-location closure fix pass (2026-10-28): what a hostile LAN host can send while the viewer browses or the keeper
 * sweeps. SEC-S1: a share listing whose server-sized array cannot be allocated. SEC-S2: a directory listing without end.
 */
class SmbListingBoundsTest {
    /** SEC-S1: dcerpc's `new ShareInfo1[EntriesRead]` fails; the page gets LIST_FAILED and the process lives. */
    @Test fun aShareCountTooLargeToAllocateIsAFailedListingNotACrash() {
        val r = SmbShareLister().guarded(signedInAsUser = false) { throw OutOfMemoryError("Failed to allocate a 268435440 byte allocation") }
        assertTrue(r.shares.isEmpty())
        assertEquals(BrowseErrorKind.LIST_FAILED, r.error?.kind)
        // The ordinary path is unchanged.
        assertEquals(listOf("Media", "TV"), SmbShareLister().guarded(false) { listOf("TV" to 0, "Media" to 0, "IPC$" to 3) }.shares)
    }

    /** SEC-S2: a server that never says NO_MORE_FILES: at most `max` entries are kept, and the read stops there. */
    @Test fun anEndlessListingStopsAtItsCap() {
        var pulled = 0
        val endless = generateSequence(0) { it + 1 }.map { pulled++; "f$it" }.iterator()
        val clock = MonoClock { 0L }
        val out = boundedList(endless, 4_096, deadlineMonoMs = 1_000L, clock = clock) { RemoteEntry(it, directory = false, hidden = false, sizeBytes = 0L) }
        assertEquals(4_096, out.size)
        assertEquals(4_096, pulled)
    }

    /** SEC-S2: a server that trickles entries (each answer inside the I/O timeout) ends at the listing's budget, SLOW. */
    @Test fun aTricklingListingEndsAtItsBudget() {
        var now = 0L
        val clock = MonoClock { now }
        val trickle = generateSequence(0) { it + 1 }.map { now += 500; "f$it" }.iterator()
        val e = assertThrows(TimeoutException::class.java) {
            boundedList(trickle, Int.MAX_VALUE, deadlineMonoMs = 16_000L, clock = clock) { RemoteEntry(it, false, false, 0L) }
        }
        assertTrue(now <= 16_500L)
        assertEquals(FaultClass.SLOW, SmbFaults.classify(SmbIoException(e), interrupted = false, transportAlive = true))
    }

    @Test fun dotEntriesAreSkippedNotCounted() {
        val names = listOf(".", "..", "a", "b", "c").iterator()
        val out = boundedList(names, 2, Long.MAX_VALUE, MonoClock { 0L }) { if (it == "." || it == "..") null else RemoteEntry(it, true, false, 0L) }
        assertEquals(listOf("a", "b"), out.map { it.name })
    }
}
