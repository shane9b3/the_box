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
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.SportsPrefs
import app.thebox.ui.sports.StackedNflRunFixtures as F
import app.thebox.ui.theme.BoxDesignScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * docs/design/m6-nfl-stacked.md §9.2 `StackedNflLogoTest` (WP-4; ST S6 as `StackedLogoTest`): `FollowLogos` asks for each
 * team's logo once, at the takeover badge size (58.5 × s; Large 73.1 × s); the rows draw that bitmap and the takeover's
 * `begin` gets the same one as its badge; a missing logo is null (the painter's `LogoDisc`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class StackedNflLogoTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs(nhlStyle = NhlBugStyle.STACKED))
    private val probe = StackedNflProbe()
    private val calls = F.Calls()
    private var placement by mutableStateOf(F.input())
    private val requests = mutableListOf<Triple<String, Int, Int>>()

    private fun withLogos(v: ScoreBugView, home: Boolean = true) = v.copy(
        away = v.away.copy(logo = "logo/${v.away.abbrev}"),
        home = v.home.copy(logo = if (home) "logo/${v.home.abbrev}" else null),
    )

    private fun render(home: Boolean = true) {
        repo.bug.value = withLogos(F.view(), home)
        rule.mainClock.autoAdvance = false
        val hooks = F.hooks(probe, calls, logoFetch = { url, w, h ->
            requests += Triple(url, w, h)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        })
        rule.setContent {
            BoxDesignScale { StackedNflBug(repo, placement, ScoreBugFixtures.noGeometry, Modifier, ScoreBugPlacement::place, hooks) }
        }
        advance(2_000)
    }

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) { val n = minOf(16L, left); rule.mainClock.advanceTimeBy(n); left -= n }
        rule.waitForIdle()
    }

    @Test
    fun theLogosAreRequestedAtTheBadgeSize() {
        render()
        val std = (K.STACKED_SCALE * app.thebox.core.sports.StackedNflMock.SCORE_BADGE).roundToInt() // 58.5 → 59 (1 design px = 1 px)
        assertEquals(listOf(Triple("logo/KC", std, std), Triple("logo/BUF", std, std)), requests)
        assertEquals("the rows draw the badge-size bitmap (scaled down in the painter)", std, probe.args!!().awayLogo!!.width)

        requests.clear()
        placement = F.input(size = ScoreBugSize.LARGE)
        Snapshot.sendApplyNotifications()
        advance(1_000)
        val large = (STACKED_NFL_BADGE * K.SCORE_LARGE_SCALE).roundToInt() // 73.1 → 73
        assertEquals("Large: 73.1 × s", listOf(Triple("logo/KC", large, large), Triple("logo/BUF", large, large)), requests)
    }

    @Test
    fun theTakeoverBadgeIsThatSameBitmap() {
        render()
        val away = probe.args!!().awayLogo!!
        repo.bug.value = withLogos(F.touchdown())
        advance(500)
        assertTrue(probe.takeoverOn!!.value)
        assertEquals("one request per team, no takeover-time fetch", 2, requests.size)
        assertSame("the badge is the rows' bitmap", away, calls.badges.single())
    }

    @Test
    fun aMissingLogoIsTheDisc() {
        render(home = false)
        assertEquals(1, requests.size)
        assertNull("no bitmap: the painter's LogoDisc", probe.args!!().homeLogo)
        assertFalse("no URL: the disc at once, no wait", probe.args!!().homeLogoPending)
    }

    /** Integration ruling 6 (as `StackedBug`): a logo still loading leaves its slot empty for `LOGO_WAIT_MS`, then the disc. */
    @Test
    fun aSlowLogoWaitsThenTheDisc() {
        repo.bug.value = withLogos(F.view())
        rule.mainClock.autoAdvance = false
        val hooks = F.hooks(probe, calls, logoFetch = { _, _, _ -> kotlinx.coroutines.awaitCancellation() })
        rule.setContent {
            BoxDesignScale { StackedNflBug(repo, placement, ScoreBugFixtures.noGeometry, Modifier, ScoreBugPlacement::place, hooks) }
        }
        advance(200)
        assertNull(probe.args!!().awayLogo)
        assertTrue("loading: the slot waits (no disc flash)", probe.args!!().awayLogoPending && probe.args!!().homeLogoPending)
        advance(K.LOGO_WAIT_MS)
        assertFalse("after LOGO_WAIT_MS: the disc", probe.args!!().awayLogoPending || probe.args!!().homeLogoPending)
    }
}
