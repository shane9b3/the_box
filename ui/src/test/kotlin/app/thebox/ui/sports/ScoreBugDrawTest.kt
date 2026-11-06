package app.thebox.ui.sports

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.SportsPrefs
import app.thebox.ui.full.StableScoreBug
import app.thebox.ui.guide.GuideDebugCounters
import app.thebox.ui.theme.BoxDesignScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/design/m6-score-bug.md §4.7, §8 `ScoreBugDrawTest`, as amended by owner ruling 6 (no local countdown, fix pass
 * 2026-11-05): the bug recomposes, rebuilds its words and redraws **only** when a new snapshot (or state) becomes
 * visible; a minute without one does nothing at all; a host recomposition for an unrelated reason (a fresh but equal
 * `PlacementInput`, as `FullScreenHost` builds at the stats' 2 Hz, U-S2) never recomposes it; and its semantics carry the
 * snapshot's clock, not a ticking second (U-S7). The host is the real [StableScoreBug] wrapper `FullScreenHost` uses.
 * Time is the compose test clock, advanced a second at a time.
 *
 * Robolectric runs no draw pass (legacy or native graphics: checked in the fix pass), so draws are not counted here.
 * Instead a snapshot apply observer proves that nothing writes state while no snapshot arrives: a ticker read only in
 * a draw lambda (the removed clock layer) would show there. `DRAWS_SCORE_BUG` is the device-run counter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class ScoreBugDrawTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs())
    private var hostCompositions = 0
    private var unrelated by mutableIntStateOf(0)

    private fun render(lowEnd: Boolean) {
        rule.setContent {
            BoxDesignScale { Host(lowEnd) }
        }
        rule.waitForIdle()
    }

    /** Stands for `FullScreenHost`: it recomposes when [unrelated] changes and builds a fresh placement each time. */
    @Composable
    private fun Host(lowEnd: Boolean) {
        SideEffect { hostCompositions++ }
        @Suppress("UNUSED_VARIABLE") val read = unrelated
        StableScoreBug(repo, ScoreBugFixtures.input(), lowEnd)
    }

    private fun compositions() = GuideDebugCounters.count(COMPOSITIONS_SCORE_BUG)
    private fun frames() = GuideDebugCounters.count(FRAMES_SCORE_BUG)

    private fun desc(): String =
        rule.onNodeWithTag(SCORE_BUG_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()

    private fun start(lowEnd: Boolean) {
        repo.bug.value = ScoreBugFixtures.live.copy(clockSecs = 1200)
        rule.mainClock.autoAdvance = false
        render(lowEnd)
        rule.mainClock.advanceTimeBy(2000) // fade in, the first draws
        rule.waitForIdle()
    }

    private fun aMinuteWithoutASnapshotDoesNothing(lowEnd: Boolean) {
        start(lowEnd)
        val comp0 = compositions()
        val frames0 = frames()
        val desc0 = desc()
        // Any per-second ticker, even one read only in a draw lambda (the old clock layer), writes snapshot state.
        var applies = 0
        val handle = Snapshot.registerApplyObserver { _, _ -> applies++ }
        try {
            for (i in 1..60) rule.mainClock.advanceTimeBy(1000)
            rule.waitForIdle()
        } finally {
            handle.dispose()
        }
        assertEquals("snapshot state changes in a minute with no new snapshot (a ticker)", 0, applies)
        assertEquals("ScoreBug recompositions with no new snapshot", 0, compositions() - comp0)
        assertEquals("frame rebuilds with no new snapshot", 0, frames() - frames0)
        assertEquals("the clock in the semantics is the snapshot's and does not tick", desc0, desc())
        assertTrue(desc0, desc0.contains("20:00"))
    }

    @Test
    fun aMinuteWithoutASnapshotRecomposesRebuildsAndDrawsNothing() = aMinuteWithoutASnapshotDoesNothing(lowEnd = false)

    @Test
    fun theLowEndVariantIsJustAsStill() = aMinuteWithoutASnapshotDoesNothing(lowEnd = true)

    @Test
    fun eachNewSnapshotRedrawsOnceAndTheClockStepsToItsValue() {
        start(lowEnd = false)
        val comp0 = compositions()
        val frames0 = frames()
        for (i in 1..60) {
            rule.mainClock.advanceTimeBy(1000)
            // A snapshot every 20 s, each with the feed's own clock.
            if (i % 20 == 0) repo.bug.value = ScoreBugFixtures.live.copy(clockSecs = 1200 - i)
        }
        rule.mainClock.advanceTimeBy(1000) // the frame that shows the last one
        rule.waitForIdle()
        assertEquals("one recomposition per snapshot", 3, compositions() - comp0)
        assertEquals("one frame build per snapshot", 3, frames() - frames0)
        assertTrue(desc(), desc().contains("19:00")) // 1200 − 60 s, exactly as the last snapshot said
    }

    @Test
    fun anEqualReEmissionDoesNothing() {
        start(lowEnd = false)
        val comp0 = compositions()
        val frames0 = frames()
        repo.bug.value = ScoreBugFixtures.live.copy(clockSecs = 1200) // equal, a new instance
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertEquals(0, compositions() - comp0)
        assertEquals(0, frames() - frames0)
    }

    @Test
    fun aHostRecompositionForAnUnrelatedReasonNeverRecomposesTheBug() {
        start(lowEnd = false)
        val host0 = hostCompositions
        val comp0 = compositions()
        for (i in 1..10) {
            unrelated = i // e.g. the stats overlay republishing PlayerUiState at 2 Hz
            rule.mainClock.advanceTimeBy(500)
            rule.waitForIdle()
        }
        assertEquals("the stand-in host did recompose", 10, hostCompositions - host0)
        assertEquals("ScoreBug skipped every host recomposition (U-S2)", 0, compositions() - comp0)
    }
}
