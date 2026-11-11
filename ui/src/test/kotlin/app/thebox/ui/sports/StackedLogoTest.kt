package app.thebox.ui.sports

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.SportsPrefs
import app.thebox.ui.theme.BoxDesignScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * docs/design/m6-score-bug-stacked-theme.md §10.2 `StackedLogoTest` (Stacked WP-3; S6, the `ScoreBugLogoTest` analogue):
 * `FollowLogos.start` asks for each team's logo once, at the **takeover** size (`STACKED_GOAL_LOGO` 67.5 × s; Large
 * 84.4 × s), and the rows draw that same bitmap scaled down while the badge uses it as it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class StackedLogoTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs(nhlStyle = NhlBugStyle.STACKED))
    private val probe = StackedProbe()
    private var placement by mutableStateOf(StackedRunFixtures.input())
    private val requests = mutableListOf<Triple<String, Int, Int>>()

    private fun withLogos(v: ScoreBugView) =
        v.copy(away = v.away.copy(logo = "logo/${v.away.abbrev}"), home = v.home.copy(logo = "logo/${v.home.abbrev}"))

    private fun render() {
        repo.bug.value = withLogos(StackedRunFixtures.view())
        rule.mainClock.autoAdvance = false
        val hooks = StackedRunFixtures.hooks(probe, logoFetch = { url, w, h ->
            requests += Triple(url, w, h)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        })
        rule.setContent {
            BoxDesignScale { StackedBug(repo, placement, ScoreBugFixtures.noGeometry, Modifier, ScoreBugPlacement::place, hooks) }
        }
        advance(2_000)
    }

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) { val n = minOf(16L, left); rule.mainClock.advanceTimeBy(n); left -= n }
        rule.waitForIdle()
    }

    @Test
    fun theLogosAreRequestedAtTheTakeoverSize() {
        render()
        val std = (K.STACKED_GOAL_LOGO * 1f).roundToInt() // 1 design px = 1 px here
        assertEquals(listOf(Triple("logo/CGY", std, std), Triple("logo/EDM", std, std)), requests)
        val rowLogo = probe.args!!().logos!!.away.image!!
        assertEquals("the rows draw the takeover-size bitmap (scaled down in the painter)", std, rowLogo.width)

        requests.clear()
        placement = StackedRunFixtures.input(size = ScoreBugSize.LARGE)
        Snapshot.sendApplyNotifications()
        advance(1_000)
        val large = (K.STACKED_GOAL_LOGO * K.SCORE_LARGE_SCALE).roundToInt() // 84.4 → 84
        assertEquals("Large: 84.4 × s", listOf(Triple("logo/CGY", large, large), Triple("logo/EDM", large, large)), requests)
    }

    @Test
    fun theTakeoverBadgeIsThatSameBitmap() {
        render()
        val away = probe.args!!().logos!!.away.image!!
        repo.bug.value = withLogos(StackedRunFixtures.view(away = 1, goalSeq = 1, goalSide = Side.AWAY))
        advance(500)
        assertTrue(probe.takeoverOn!!.value)
        assertEquals("one request per team, no takeover-time fetch", 2, requests.size)
        assertSame("the badge is drawn from the rows' bitmap", away, probe.args!!().logos!!.away.image)
    }
}
