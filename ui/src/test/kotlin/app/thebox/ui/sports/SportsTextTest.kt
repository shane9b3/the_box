package app.thebox.ui.sports

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.ScoreFacts
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsFixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** (Score bug commit 0, docs/design/m6-score-bug.md §4.5, §5, §6) The words of the score feature. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SportsTextTest {
    private val res: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test
    fun clockIsMssBelowTenMinutesAndMmssFromTen() {
        assertEquals("0:00", SportsText.clock(-3))
        assertEquals("0:07", SportsText.clock(7))
        assertEquals("9:59", SportsText.clock(599))
        assertEquals("10:00", SportsText.clock(600))
        assertEquals("20:00", SportsText.clock(1200))
    }

    @Test
    fun centreLines() {
        val view = SportsFixtures.liveView()
        assertEquals("2nd", SportsText.centre(res, CentreText.Period(PeriodLabel(PeriodKind.REG, 2)), view, 0))
        assertEquals("OT", SportsText.centre(res, CentreText.Period(PeriodLabel(PeriodKind.OT, 1)), view, 0))
        assertEquals("2OT", SportsText.centre(res, CentreText.Period(PeriodLabel(PeriodKind.OT, 2)), view, 0))
        assertEquals("1st INT", SportsText.centre(res, CentreText.Intermission(PeriodLabel(PeriodKind.REG, 1)), view, 0))
        assertEquals("End 1st", SportsText.centre(res, CentreText.EndOf(PeriodLabel(PeriodKind.REG, 1)), view, 0))
        assertEquals("GOAL CGY", SportsText.centre(res, CentreText.Goal(Side.AWAY), view, 0))
        assertEquals("Final/SO", SportsText.centre(res, CentreText.Final(PeriodKind.SO), view, 0))
        assertEquals("SO", SportsText.centre(res, CentreText.Shootout, view, 0))
        assertEquals("No update", SportsText.centre(res, CentreText.NoUpdate, view, 0))
        assertEquals("Delayed", SportsText.centre(res, CentreText.Delayed, view, 0))
        assertEquals("10:22", SportsText.centre(res, CentreText.Clock, view, 622))
    }

    @Test
    fun pickerRowsAndValueLine() {
        val live = SportsFixtures.game()
        assertEquals("CGY 0–2 EDM · 2nd 10:22", SportsText.pickerGame(res, live, clock24 = true))
        val int = SportsFixtures.game(phase = GamePhase.INTERMISSION, period = 1, clock = GameClock(958, running = false, inIntermission = true))
        assertEquals("CGY 0–2 EDM · 1st INT", SportsText.pickerGame(res, int, clock24 = true))
        val fin = SportsFixtures.game(phase = GamePhase.FINAL, finalKind = PeriodKind.OT)
        assertEquals("CGY 0–2 EDM · Final/OT", SportsText.pickerGame(res, fin, clock24 = true))
        assertEquals("Follow a game", SportsText.quickValue(res, ScoreFacts(), clock24 = true))
        val f = SportsFixtures.followedOf(live)
        assertEquals("CGY 0–2 EDM", SportsText.quickValue(res, ScoreFacts(followed = SportsFixtures.liveView(live), game = f), clock24 = true))
        assertEquals("Corner · Top right", res.getString(app.thebox.ui.R.string.sports_picker_corner, SportsText.corner(res, ScoreCorner.TOP_RIGHT)))
        assertEquals("Off", SportsText.delay(res, 0))
        assertEquals("30 s", SportsText.delay(res, 30))
        assertEquals("No internet connection. Check the network, then press OK to try again.", SportsText.failure(res, FailKind.OFFLINE))
    }

    @Test
    fun aPostponedGameSaysSoInsteadOfItsStartTime() {
        val ppd = SportsFixtures.game(phase = GamePhase.POSTPONED, period = null, periodKind = null, clock = null)
        assertEquals("Postponed", SportsText.state(res, ppd, clock24 = true))
        assertEquals("CGY – EDM · Postponed", SportsText.pickerGame(res, ppd, clock24 = true))
    }
}
