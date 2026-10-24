package app.thebox.playback

import app.thebox.core.storage.LocationFallback
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `fallbackToastDue` (docs/design/m6-storage-locations.md section 5.1, 10, U-S7): once per process; sign-in for 3 processes. */
class FallbackToastPolicyTest {
    private class Counters : SignInToastCounters {
        val map = HashMap<String, Int>()
        override fun processes(locationId: String) = map[locationId] ?: 0
        override fun setProcesses(locationId: String, count: Int) { map[locationId] = count }
    }

    @Test fun oneToastPerProcessPerLocationAndReason() {
        val p = FallbackToastPolicy(Counters())
        assertTrue(p.due("smb-1", LocationFallback.UNREACHABLE))
        assertFalse(p.due("smb-1", LocationFallback.UNREACHABLE))
        assertTrue("another reason is another toast", p.due("smb-1", LocationFallback.UNSTABLE))
        assertTrue("another place is another toast", p.due("smb-2", LocationFallback.UNREACHABLE))
        assertTrue(p.due("vol-1", LocationFallback.NOT_CONNECTED))
        assertTrue(p.due("smb-1", LocationFallback.NO_SPACE_THERE))
    }

    @Test fun stillConnectingIsNeverToasted() {
        val p = FallbackToastPolicy(Counters())
        assertFalse(p.due("smb-1", LocationFallback.NOT_READY_YET))
        assertFalse(p.due("smb-1", LocationFallback.NOT_READY_YET))
    }

    @Test fun signInNeededToastsInTheFirstThreeProcessesThenNever() {
        val counters = Counters()
        val shown = (1..5).map { FallbackToastPolicy(counters).due("smb-1", LocationFallback.SIGN_IN_NEEDED) }
        assertTrue(shown.toString(), shown == listOf(true, true, true, false, false))
        // The counter is per location: another one starts its own three.
        assertTrue(FallbackToastPolicy(counters).due("smb-2", LocationFallback.SIGN_IN_NEEDED))
        // Within one process a second ask is the once-per-process rule.
        val p = FallbackToastPolicy(Counters())
        assertTrue(p.due("smb-3", LocationFallback.SIGN_IN_NEEDED))
        assertFalse(p.due("smb-3", LocationFallback.SIGN_IN_NEEDED))
    }

    /** U-S9 (save-location review 2026-10-24): a new sign-in starts a fresh three, in this process and the next. */
    @Test fun aNewSignInStartsAFreshThree() {
        val counters = Counters()
        repeat(3) { assertTrue(FallbackToastPolicy(counters).due("smb-1", LocationFallback.SIGN_IN_NEEDED)) }
        val p = FallbackToastPolicy(counters)
        assertFalse(p.due("smb-1", LocationFallback.SIGN_IN_NEEDED))
        p.signedIn("smb-1")
        assertTrue("the same process may toast the new episode", p.due("smb-1", LocationFallback.SIGN_IN_NEEDED))
        assertTrue("a new process counts from one again", FallbackToastPolicy(counters).due("smb-1", LocationFallback.SIGN_IN_NEEDED))
        assertFalse("other reasons keep the once-per-process rule", p.due("smb-1", LocationFallback.UNREACHABLE).let { p.due("smb-1", LocationFallback.UNREACHABLE) })
    }
}
