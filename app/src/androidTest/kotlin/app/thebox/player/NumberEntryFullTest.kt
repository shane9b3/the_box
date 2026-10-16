package app.thebox.player

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.guide.Overlay
import app.thebox.player.api.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * I-M4-6: number entry in FULL, docs/design/m4-player-ux.md §3.10.4, §5.5. Switch `numwait=1000`.
 * Depends on WP-A (`NumberEntryImpl.setCommitAfterMs`, S7 passive bar), WP-C (settings source), WP-D (preview with logo).
 */
@RunWith(AndroidJUnit4::class)
class NumberEntryFullTest : PlayerTestBase() {
    override val playerSwitches = "numwait=1000"

    @Test
    fun digitsCommitAfterTheWaitAndShowThePassiveBar() {
        fullOn(1)
        awaitController("playing") { it is PlaybackState.Playing }
        toBareVideo()
        digits("123", intervalMs = 200)
        val tLast = SystemClock.elapsedRealtime()
        // Integration 2026-10-15 (test fix): each digit keeps the previous preview until its own lookup lands
        // (NUMBER_PREVIEW_MS after the digit, M3 §3.9.4), so "123" briefly shows the preview of "12"; await the one for 123.
        val o = awaitUi("number preview for 123", 1_000) { u -> (u.overlay as? Overlay.Number)?.let { it.digits == "123" && it.preview?.displayName?.endsWith(" 123") == true } == true }.overlay as Overlay.Number
        log("I-M4-6 preview ${o.digits} → ${o.preview?.displayName} logo=${o.preview?.logoUrl}")
        assertEquals("123", o.digits)
        assertTrue("preview names channel 123", o.preview!!.displayName.endsWith(" 123"))
        assertTrue("preview has a logo", !o.preview!!.logoUrl.isNullOrBlank()) // WP-D behaviour (logo drawn from it)
        val target = o.preview!!
        // Commit: the controller is asked for channel 123 (Connecting) 1 s ± 150 ms after the last digit.
        var commitAt = -1L
        while (SystemClock.elapsedRealtime() - tLast < 3_000) {
            if (controller.state.value.target?.identity == target.identity) { commitAt = SystemClock.elapsedRealtime() - tLast; break }
            Thread.sleep(10)
        }
        log("I-M4-6 commit after $commitAt ms")
        assertTrue("number entry committed", commitAt >= 0)
        assertTrue("commit 1 s ± 150 ms after the last digit ($commitAt ms)", abs(commitAt - 1_000) <= 150) // WP-A/WP-C behaviour (numwait)
        awaitPlayer("passive bar for 123 (S7)", 2_000) { it.info?.channel?.identity == target.identity && it.info?.active == false } // WP-A behaviour
    }
}
