package app.thebox.ui.root

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.FollowedGame
import app.thebox.core.sports.League
import app.thebox.core.sports.RemoveReason
import app.thebox.core.sports.ScoreEvent
import app.thebox.ui.R
import app.thebox.ui.full.PlayerUi
import app.thebox.ui.sports.SportsText
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (CHL emulator pass 2, defect 1) Every sports toast (`SportsText.toast`, every `ScoreEvent`, every league) is laid out
 * whole in the real toast ([ToastView]: 900 px wide at most, 2 lines) on a 1,920 × 1,080 TV, with realistic 3- and 4-letter
 * team codes and a wide worst case; native fonts. The too-old toast used to end "…or pick it again in Sport…".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SportsToastFitTest {
    @get:Rule val rule = createComposeRule()
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val text = mutableStateOf<String?>(null)

    private fun show(t: String) {
        if (text.value == null) {
            text.value = t
            rule.setContent {
                BoxTheme {
                    Box(Modifier.fillMaxSize()) {
                        text.value?.let { ToastView(it, Modifier.align(Alignment.BottomStart).padding(start = PlayerUi.TOAST_L.dp, bottom = 96.dp)) }
                    }
                }
            }
        } else {
            text.value = t
        }
        rule.waitForIdle()
    }

    private fun layout(): TextLayoutResult {
        val node = rule.onNodeWithTag(TOAST_TAG, useUnmergedTree = true).fetchSemanticsNode()
        val out = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single()
    }

    /** Null when [t] is laid out whole in the toast, else why not. */
    private fun cut(t: String): String? {
        show(t)
        rule.onNodeWithTag(TOAST_TAG, useUnmergedTree = true).assertTextEquals(t)
        val l = layout()
        return when {
            l.lineCount > 2 -> "${l.lineCount} lines"
            (0 until l.lineCount).any { l.isLineEllipsized(it) } -> "ellipsised"
            l.hasVisualOverflow -> "overflows"
            l.getLineEnd(l.lineCount - 1) != t.length -> "ends at ${l.getLineEnd(l.lineCount - 1)} of ${t.length}"
            else -> null
        }
    }

    /** Every event the repository raises for a game of [league] between [away] and [home]. */
    private fun events(league: League, away: String, home: String): List<ScoreEvent> {
        val g = FollowedGame(league, "1", 0L, away, home)
        return listOf(
            ScoreEvent.Followed(g, first = true), ScoreEvent.Followed(g, first = false), ScoreEvent.FollowedFinal(g),
            ScoreEvent.FollowTooOld(g, final = false), ScoreEvent.FollowTooOld(g, final = true),
        ) + RemoveReason.entries.map { ScoreEvent.Removed(it, g) }
    }

    @Test
    fun bothTooOldToastsReadWhole() {
        for ((away, home) in CODES) for (final in listOf(false, true)) {
            val t = SportsText.toast(res, ScoreEvent.FollowTooOld(FollowedGame(League.OHL, "1", 0L, away, home), final))
            assertNull("too old final=$final: $t", cut(t))
        }
        // The reason and the next step survive the shortening.
        val t = res.getString(R.string.sports_toast_too_old, "BRAM", "KIT")
        assertTrue(t, t.contains("more than 8 hours ago by this box's clock") && t.contains("date and time") && t.contains("pick it again"))
    }

    @Test
    fun everySportsToastReadsWholeForEveryLeague() {
        val cut = ArrayList<String>()
        for (league in League.entries) for ((away, home) in CODES) for (e in events(league, away, home)) {
            val t = SportsText.toast(res, e)
            cut(t)?.let { cut += "$it: $t" }
        }
        assertEquals(cut.distinct().joinToString("\n"), 0, cut.size)
    }

    private companion object {
        /** Realistic CHL / NHL codes (3 and 4 letters) and a wide worst case. */
        val CODES = listOf("BRAM" to "KIT", "MISS" to "SHAW", "VGK" to "WSH", "WWWW" to "MMMM")
    }
}
