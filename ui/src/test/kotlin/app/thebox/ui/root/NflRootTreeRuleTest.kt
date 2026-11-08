package app.thebox.ui.root

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.fakes.FakeGuideStateMachine
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.League
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsPrefs
import app.thebox.ui.full.PlayerExtras
import app.thebox.ui.full.PlayerHost
import app.thebox.ui.shelltest.FakePlayerHost
import app.thebox.ui.shelltest.FakeShellHost
import app.thebox.ui.sports.SCORE_BUG_TAG
import app.thebox.ui.theme.BoxTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (NFL integration pass, docs/design/m6-nfl.md §4.6, §10.2; the root tree rule of `RootTreeRuleTest`) The NFL bug drawn
 * through the real `AppRoot` → `FullScreenHost` → `ScoreBug` seam, with an NFL follow on the score source:
 * - the NFL branch is taken (the pill is the NFL width) and the bug sits in the key sink, never an ancestor of the video;
 * - no ancestor of `VideoLayer` carries a layer, with the bug and its takeover machinery composed;
 * - the bug's own layer (fade + clip) is a leaf beside the video, not above it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class NflRootTreeRuleTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** A fake player host that also offers the score source (`PlayerExtras.scoreBug`), as `PlayerViewModel` does. */
    private class ScoringPlayerHost(
        val repo: FakeScoreRepository,
        private val base: FakePlayerHost = FakePlayerHost(),
    ) : PlayerHost by base, PlayerExtras {
        override val archiveFailure: StateFlow<ArchiveFailure?> = MutableStateFlow(null)
        override val focusedDetail: StateFlow<ProgrammeInfo?> = MutableStateFlow(null)
        override val scoreBug get() = repo
    }

    private fun showFullWithNflBug(): FakeScoreRepository {
        val repo = FakeScoreRepository(SportsPrefs(league = League.NFL))
        val game = SportsFixtures.nflGame()
        repo.followed.value = SportsFixtures.followedOf(game)
        repo.bug.value = SportsFixtures.nflView(game = game)
        val tuned = TunedState(identity = GuideFixtures.identity(1), rowId = 1, phase = MiniPhase.PLAYING)
        val host = FakeShellHost(FakeGuideStateMachine(GuideUiState(mode = ScreenMode.FULL, tuned = tuned)), player = ScoringPlayerHost(repo))
        rule.setContent { BoxTheme { AppRoot(host, grid = { m -> Spacer(m.graphicsLayer()) }) } }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        return repo
    }

    private fun layoutOf(tag: String): LayoutInfo = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().layoutInfo
    private fun ancestors(info: LayoutInfo): List<LayoutInfo> = generateSequence(info.parentInfo) { it.parentInfo }.toList()
    private fun layerModifier(info: LayoutInfo): Boolean = info.getModifierInfo().any { mi -> LAYER_NAMES.any { mi.modifier::class.java.name.contains(it) } }

    @Test
    fun theNflBugKeepsTheRootTreeRule() {
        showFullWithNflBug()
        val video = layoutOf(VIDEO_LAYER_TAG)
        val bug = layoutOf(SCORE_BUG_TAG)
        val sink = layoutOf(KEY_SINK_TAG)

        // The NFL branch drew the bug: the pill is the NFL width (520 design px = 520 px on a 1920 px window).
        assertEquals(ScoreBugPlacement.width(ScoreBugSize.STANDARD, League.NFL), bug.width.toFloat(), 1f)
        assertTrue("the bug is inside the key sink", ancestors(bug).contains(sink))
        assertFalse("the bug is not an ancestor of the video layer", ancestors(video).contains(bug))

        val videoChain = ancestors(video)
        for (a in videoChain) {
            val names = a.getModifierInfo().map { it.modifier::class.java.simpleName + "/" + (it.extra?.let { e -> e::class.java.simpleName } ?: "-") }
            assertFalse("a video ancestor carries a layer modifier: $names", layerModifier(a))
            val topLevel = a.parentInfo?.parentInfo == null
            if (!topLevel) assertFalse("a video ancestor has a coordinator layer: $names", a.getModifierInfo().any { it.extra != null })
        }
        // The bug's own layer (fade + clip) is its nearest layered ancestor, and it holds no video.
        val own = ancestors(bug).first { layerModifier(it) }
        assertFalse("the bug's layer is not above the video", videoChain.contains(own))
    }

    private companion object {
        val LAYER_NAMES = listOf("GraphicsLayer", "Clip", "Shadow", "RenderEffect", "Alpha")
    }
}
