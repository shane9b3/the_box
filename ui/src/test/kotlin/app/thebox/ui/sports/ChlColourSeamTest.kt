package app.thebox.ui.sports

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.thebox.core.sports.ChlTeamColours
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.League
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.NhlStackedColours
import app.thebox.core.sports.NhlTeamColours
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.StackedColourTable
import app.thebox.core.sports.TeamColourTable
import app.thebox.ui.theme.BoxDesignScale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/**
 * (CHL commit 0, docs/design/m6-chl-leagues.md §1.5, review B1) The UI colour seam is league-keyed: an OHL view of the
 * Ottawa 67's (OTT) resolves through `ChlTeamColours` / `StackedColourTable(OHL, …)` on the Pill's goal takeover and on the
 * Stacked rows (equality with the CHL lookup; no "≠ the Senators" check, review L4: both are red and black, so a
 * coincidence after WP-4 must not fail it). The NHL's own lookups are unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class ChlColourSeamTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun ohl(v: ScoreBugView): ScoreBugView = v.copy(league = League.OHL)

    @Test
    fun theDefaultHooksAreLeagueKeyed() {
        assertEquals(NhlTeamColours.of("OTT"), GoalHooks.Default.colours(League.NHL, "OTT"))
        assertEquals(ChlTeamColours.of(League.OHL, "OTT"), GoalHooks.Default.colours(League.OHL, "OTT"))
        assertEquals(TeamColourTable.of(League.QMJHL, "NFL"), GoalHooks.Default.colours(League.QMJHL, "NFL"))
        assertEquals(NhlStackedColours.pair("OTT", "TOR"), StackedHooks.Default.colours(League.NHL, "OTT", "TOR"))
        assertEquals(StackedColourTable.pair(League.OHL, "OTT", "KIT"), StackedHooks.Default.colours(League.OHL, "OTT", "KIT"))
    }

    @Test
    fun anOhlOttGoalIsBegunWithTheChlColoursNotTheSenators() {
        val repo = FakeScoreRepository(SportsPrefs())
        val probe = GoalProbe()
        var begun: app.thebox.core.sports.TeamColours? = null
        val hooks = GoalHooks(
            begin = { p, side, c, badge, abbrev, a, h ->
                begun = c
                GoalHooks.Default.begin(p, side, c, badge, abbrev, a, h)
            },
            probe = probe,
            tabAllowed = { _, _, _ -> true },
            background = EmptyCoroutineContext,
        )
        repo.bug.value = ohl(ScoreBugFixtures.view(awayAbbrev = "OTT", homeAbbrev = "KIT", clockSecs = 1200))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            BoxDesignScale {
                ScoreBugWith(repo, ScoreBugFixtures.input(), ScoreBugFixtures.noGeometry, false, Modifier, ScoreBugPlacement::place, hooks)
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2000)
        rule.waitForIdle()
        repo.bug.value = ohl(ScoreBugFixtures.goalView("OTT", Side.AWAY, opponent = "KIT", away = 2, home = 1))
        var guard = 0
        while (begun == null) {
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
            check(++guard < 500) { "the takeover never began" }
        }
        assertEquals(ChlTeamColours.of(League.OHL, "OTT"), begun)
    }

    @Test
    fun anOhlOttStackedBugUsesTheOhlPair() {
        val repo = FakeScoreRepository(SportsPrefs(nhlStyle = NhlBugStyle.STACKED))
        val probe = StackedProbe()
        val v = StackedRunFixtures.view()
        repo.bug.value = ohl(v.copy(away = v.away.copy(abbrev = "OTT"), home = v.home.copy(abbrev = "KIT")))
        rule.mainClock.autoAdvance = false
        val hooks = StackedRunFixtures.hooks(probe, EmptyCoroutineContext)
        val placement = StackedRunFixtures.input().copy(league = League.OHL)
        rule.setContent {
            BoxDesignScale { StackedBug(repo, placement, ScoreBugFixtures.noGeometry, Modifier, ScoreBugPlacement::place, hooks) }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2000)
        rule.waitForIdle()
        val colours = probe.args!!().colours
        assertEquals(StackedColourTable.pair(League.OHL, "OTT", "KIT"), colours)
    }
}
