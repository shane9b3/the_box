package app.thebox.ui.full

import app.thebox.core.full.PillKind
import app.thebox.core.full.PillState
import app.thebox.core.full.ScrubFocus
import app.thebox.core.full.ScrubScope
import app.thebox.core.full.ScrubState
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.playback.timeshift.TimeRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** Fix pass S11 (docs/review/2026-10-19-timeshift-review-ux.md): the rewind window's text sizes, with real text widths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrubWindowLegibilityTest {
    private val ch = GuideFixtures.channel(1, archiveDays = 0)
    private val now = PlayerOverlayStates.NOW_MS

    private fun renderer() = ScrubBarRenderer(1f).apply {
        zone = TimeZone.getTimeZone("UTC")
        clock24 = true
        setLabels("Catch-up", "Live")
        setWindowLabels("Rewind from %1\$s", "LIVE", "Not saved")
    }

    private fun window(): ScrubState = PlayerOverlayStates.scrub(ch).copy(
        scope = ScrubScope.REWIND_WINDOW, barFromMs = now - 1_800_000, barToMs = now, windowFromMs = now - 1_800_000,
        savedFromMs = now - 1_200_000, archiveFromMs = now - 1_200_000, liveEdgeMs = now, playheadMs = now - 600_000, atMs = now,
        advancing = false, notSaved = TimeRanges.EMPTY, pill = PillState(PillKind.PAUSED, behindMs = 600_000, atMs = now, behindGrows = true),
        focus = ScrubFocus.BAR,
    )

    private fun RecordingCanvas() = TimeshiftUiTest.RecordingCanvas()

    /**
     * Fix pass S11 (the 2026-10-17 legibility ruling): window-mode text is at least 22 px (the live time 24 px), and the
     * programme title is cut short of the live time instead of running into it.
     */
    @Test
    fun rewindWindowTextIsLegibleAndTheTitleClearsTheLiveTime() {
        val r = renderer()
        val p = PlayerOverlayStates.scrub(ch).programme!!
        val title = "A very long programme title that would run right across the live time at the end of the bar"
        // The programme starts 1 min before live, so its title begins near the right end, by the live time.
        val start = now - 60_000L
        val late = p.copy(title = title, rawStartMin = (start / 60_000L).toInt(), startMin = (start / 60_000L).toInt(), endMin = (start / 60_000L).toInt() + 60)
        val c = RecordingCanvas().also { r.draw(it, window().copy(programme = late), now, 1792f, 80f) }
        assertTrue(c.drawn.joinToString { "${it.text}@${it.size}" }, c.drawn.all { it.size >= 22f })
        fun hm(ms: Long) = String.format(java.util.Locale.ROOT, "%02d:%02d", (ms / 3_600_000) % 24, (ms / 60_000) % 60)
        val clock = c.drawn.single { it.text == hm(now) }
        assertEquals(24f, clock.size)
        val t = c.drawn.firstOrNull { title.startsWith(it.text) && it.text.isNotEmpty() }
        if (t != null) {
            assertTrue("title cut short", t.text.length < title.length)
            assertTrue("title ${t.left}..${t.right} clears the live time at ${clock.left}", t.right <= clock.left)
        }
        // A title far from the live time is drawn whole.
        val early = late.copy(title = "News", rawStartMin = late.rawStartMin - 20, startMin = late.startMin - 20)
        val e = RecordingCanvas().also { r.draw(it, window().copy(programme = early), now, 1792f, 80f) }
        assertTrue(e.texts.toString(), "News" in e.texts)
    }

}
