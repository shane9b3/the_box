package app.thebox.ui.sports

import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.SportsFixtures
import app.thebox.ui.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * docs/design/m6-score-bug.md §5.2 (owner request 2026-11-06, larger for 10-foot readability): the card's texts fit their
 * slots without an ellipsis, measured with the bundled Instrument Sans at the card's real sizes (1 design px = 1 px here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameCardFitTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val semi = sansTypeface(context, R.font.instrument_sans_semibold)
    private val bold = sansTypeface(context, R.font.instrument_sans_bold)

    private fun width(text: String, px: Int, face: android.graphics.Typeface?, tnum: Boolean = false): Float =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = px.toFloat()
            if (tnum) fontFeatureSettings = "tnum"
        }.measureText(text)

    /** All 32 NHL common names (2026-27), plus whatever the recorded fixtures carry. */
    private val names: Set<String> = buildSet {
        addAll(
            listOf(
                "Ducks", "Bruins", "Sabres", "Flames", "Hurricanes", "Blackhawks", "Avalanche", "Blue Jackets", "Stars",
                "Red Wings", "Oilers", "Panthers", "Kings", "Wild", "Canadiens", "Predators", "Devils", "Islanders",
                "Rangers", "Senators", "Flyers", "Penguins", "Sharks", "Kraken", "Blues", "Lightning", "Maple Leafs",
                "Mammoth", "Canucks", "Golden Knights", "Capitals", "Jets",
            ),
        )
        for (path in listOf(SportsFixtures.SCORE_2026_11_05, SportsFixtures.SCORE_2026_11_04, SportsFixtures.LANDING_LIVE_INT)) {
            Regex("\"commonName\":\\{\"default\":\"([^\"]+)\"").findAll(SportsFixtures.text(path)).forEach { add(it.groupValues[1]) }
        }
    }

    @Test
    fun everyCommonNameFitsAt32SemiboldWithoutAnEllipsis() {
        assertTrue("fixtures supplied names", names.containsAll(listOf("Golden Knights", "Blue Jackets", "Maple Leafs", "Canadiens", "Mammoth")))
        val limit = GameCardMetrics.NAME_W.toFloat()
        for (n in names) {
            val w = width(n, GameCardMetrics.NAME_PX, semi)
            assertTrue("$n is $w px, the name column is $limit", w <= limit)
        }
    }

    @Test
    fun theScoreFitsItsColumn() {
        val w = width("88", GameCardMetrics.SCORE_PX, bold, tnum = true)
        assertTrue("two digits are $w px", w <= GameCardMetrics.SCORE_W)
    }

    @Test
    fun theLongestStateAndChipFitTheHeader() {
        val live = SportsFixtures.game(
            phase = GamePhase.LIVE, period = 5, periodKind = PeriodKind.OT, otNumber = 2,
            clock = GameClock(622, running = true, inIntermission = false),
        )
        val p = SportsText.periodOf(live)!!
        assertTrue(PeriodLabel(PeriodKind.OT, 2) == p)
        val detail = res.getString(R.string.sports_card_live_line, SportsText.period(res, p), SportsText.clock(622))
        val state = GameCardMetrics.DOT + GameCardMetrics.DOT_GAP + width(res.getString(R.string.sports_live), GameCardMetrics.STATE_PX, semi) +
            GameCardMetrics.DOT_GAP + width(detail, GameCardMetrics.STATE_PX, semi)
        val chips = listOf(R.string.sports_card_action_stop, R.string.sports_card_action_follow, R.string.sports_card_following)
            .maxOf { 2 * GameCardMetrics.CHIP_PAD_H + width(res.getString(it), GameCardMetrics.CHIP_PX, semi) }
        val other = listOf("1st INT", "Final/OT", "Final/SO", "Postponed", "10:30 PM", "22:30").maxOf { width(it, GameCardMetrics.STATE_PX, semi) }
        assertTrue("live state ($detail) is $state px, chip $chips px, header ${GameCardMetrics.INNER_W}", state + 16 + chips <= GameCardMetrics.INNER_W)
        assertTrue("other states ($other px) and chip $chips", other + 16 + chips <= GameCardMetrics.INNER_W)
    }

    @Test
    fun theCardsTextStaysAtOrAboveTheFloorAndTheRowsFit() {
        val sizes = listOf(GameCardMetrics.NAME_PX, GameCardMetrics.SCORE_PX, GameCardMetrics.STATE_PX, GameCardMetrics.CHIP_PX)
        assertTrue(sizes.all { it >= 20 })
        val used = 2 * GameCardMetrics.PAD + GameCardMetrics.HEADER_H + GameCardMetrics.HEADER_GAP + 2 * GameCardMetrics.ROW_H + GameCardMetrics.ROW_GAP
        assertTrue("$used of ${GameCardMetrics.CARD_H}", used <= GameCardMetrics.CARD_H)
    }
}
