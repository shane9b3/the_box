package app.thebox.ui.sports

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.sports.DayState
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsFixtures.team
import app.thebox.ui.theme.BoxTheme
import app.thebox.ui.theme.DesignTokens
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Sports tab at 1920 x 1080 (docs/design/m6-score-bug.md §5.2, owner request 2026-11-06): a live card focused, a
 * followed live game in overtime, an intermission, a pre-game, a final and a postponed card. Writes
 * `ui/build/sports-tab/sports-tab.png` (review by eye; not a golden).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SportsTabScreenshotTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File("build/sports-tab").apply { mkdirs() }

    @Test
    fun writeTheSportsTab() {
        val live = SportsFixtures.game(id = "L1", away = team("VGK", "Golden Knights", 3), home = team("TOR", "Maple Leafs", 2), startUtcMs = 3_000)
        val ot = SportsFixtures.game(
            id = "L2", away = team("CBJ", "Blue Jackets", 4), home = team("MTL", "Canadiens", 4), startUtcMs = 4_000,
            period = 5, periodKind = PeriodKind.OT, otNumber = 2, clock = GameClock(622, running = true, inIntermission = false),
        )
        val int = SportsFixtures.game(
            id = "L3", away = team("CAR", "Hurricanes", 1), home = team("CHI", "Blackhawks", 0), startUtcMs = 5_000,
            period = 1, clock = GameClock(0, running = false, inIntermission = true),
        )
        val pre = SportsFixtures.game(
            id = "P1", phase = GamePhase.PRE, away = team("SEA", "Kraken"), home = team("TBL", "Lightning"), startUtcMs = 1_793_990_400_000L,
            period = null, periodKind = null, clock = null,
        )
        val fin = SportsFixtures.game(
            id = "X1", phase = GamePhase.FINAL, away = team("UTA", "Mammoth", 2), home = team("EDM", "Oilers", 3), startUtcMs = 1_000,
            period = 4, periodKind = PeriodKind.OT, otNumber = 1, finalKind = PeriodKind.OT, clock = null,
        )
        val ppd = SportsFixtures.game(
            id = "D1", phase = GamePhase.POSTPONED, away = team("BOS", "Bruins"), home = team("NYR", "Rangers"), startUtcMs = 2_000,
            period = null, periodKind = null, clock = null,
        )
        val repo = FakeScoreRepository()
        repo.followed.value = SportsFixtures.followedOf(ot)
        repo.day.value = DayState.Board(SportsFixtures.board(live, ot, int, pre, fin, ppd), android.os.SystemClock.elapsedRealtime(), false)
        val vm = SportsViewModel(repo, FakeSettingsStore(), { android.os.SystemClock.elapsedRealtime() }) { LocalDate.of(2026, 11, 5) }
        rule.setContent {
            BoxTheme {
                Box(Modifier.fillMaxSize().background(DesignTokens.Colors.Canvas)) { SportsScreen(vm, clock24 = false) }
            }
        }
        rule.waitForIdle()
        // NATIVE graphics mode starts in touch mode, where the Compose view takes no focus; leave it first (D-pad mode).
        rule.runOnUiThread {
            val dv = rule.activity.window.decorView
            runCatching {
                org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
                    dv.rootView.parent, "ensureTouchMode", org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
                )
            }.onFailure { System.err.println("touch-mode: $it") }
            dv.requestFocus()
        }
        rule.waitForIdle()
        // Focus the first live card (L1); the followed game L2 then shows "✓ Following".
        rule.onNodeWithTag(sportsCardTag("L1")).requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag(sportsCardTag("L1")).assertIsFocused()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        assertEquals(1920, bmp.width)
        File(out, "sports-tab.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(File(out, "sports-tab.png").length() > 5_000)
        rule.onNodeWithTag(SPORTS_FOLLOWING_TAG + "L2", useUnmergedTree = true).assertExists()
    }
}
