package app.thebox.ui.sports

import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.SportsFixtures
import app.thebox.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (NFL WP-5, docs/design/m6-nfl.md §7.2, §10.2) The 32 NFL short names fit the card's name column at 32 SemiBold, and the
 * NFL header words fit beside the action chip, measured with the bundled Instrument Sans as `GameCardFitTest` does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NflGameCardFitTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val semi = sansTypeface(context, R.font.instrument_sans_semibold)

    private fun width(text: String, px: Int): Float =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = semi; textSize = px.toFloat() }.measureText(text)

    /** ESPN's `shortDisplayName` for all 32 teams. */
    private val names = listOf(
        "Cardinals", "Falcons", "Ravens", "Bills", "Panthers", "Bears", "Bengals", "Browns", "Cowboys", "Broncos", "Lions",
        "Packers", "Texans", "Colts", "Jaguars", "Chiefs", "Raiders", "Chargers", "Rams", "Dolphins", "Vikings", "Patriots",
        "Saints", "Giants", "Jets", "Eagles", "Steelers", "49ers", "Seahawks", "Buccaneers", "Titans", "Commanders",
    )

    @Test fun thereAre32NamesAndEachFitsTheNameColumn() {
        assertEquals(32, names.toSet().size)
        val limit = GameCardMetrics.NAME_W.toFloat()
        for (n in names) {
            val w = width(n, GameCardMetrics.NAME_PX)
            assertTrue("$n is $w px, the name column is $limit", w <= limit)
        }
    }

    @Test fun theCardDrawsTheShortNameAndFallsBackToTheCommonName() {
        val kc = SportsFixtures.nflTeam("KC", "Kansas City Chiefs", "Chiefs")
        assertEquals("Chiefs", cardName(kc))
        assertEquals("Flames", cardName(SportsFixtures.team("CGY", "Flames")))
    }

    @Test fun theLongestNflHeaderWordsFitBesideTheChip() {
        val chips = listOf(R.string.sports_card_action_stop, R.string.sports_card_action_follow, R.string.sports_card_following)
            .maxOf { 2 * GameCardMetrics.CHIP_PAD_H + width(context.getString(it), GameCardMetrics.CHIP_PX) }
        val live = GameCardMetrics.DOT + GameCardMetrics.DOT_GAP + width(context.getString(R.string.sports_live), GameCardMetrics.STATE_PX) +
            GameCardMetrics.DOT_GAP + width(context.getString(R.string.sports_card_live_line, "2OT", "10:22"), GameCardMetrics.STATE_PX)
        val others = listOf("Wed 12:30 PM", "Thu 10:00 PM", "Sun 12:30 PM", "Halftime", "End 3rd", "Final/OT", "Postponed")
            .maxOf { width(it, GameCardMetrics.STATE_PX) }
        assertTrue("live $live + chip $chips", live + 16 + chips <= GameCardMetrics.INNER_W)
        assertTrue("others $others + chip $chips", others + 16 + chips <= GameCardMetrics.INNER_W)
    }
}
