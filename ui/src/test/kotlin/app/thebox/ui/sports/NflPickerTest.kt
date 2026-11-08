package app.thebox.ui.sports

import androidx.test.core.app.ApplicationProvider
import app.thebox.core.full.PickerChoice
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.NflBreak
import app.thebox.core.sports.RemoveReason
import app.thebox.core.sports.ScoreEvent
import app.thebox.core.sports.SportsFixtures
import app.thebox.ui.full.PlayerText
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (NFL WP-5, docs/design/m6-nfl.md §7.3, S4) The picker sheet's NFL words: the League row, the week divider (never a raw
 * `w…` key), ESPN's failure text, an NFL game's row and the toast when ESPN drops the followed game. The machine's rows are
 * in core's `NflScoreDividerTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NflPickerTest {
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val saved = TimeZone.getDefault()
    @Before fun zone() = TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    @After fun unzone() = TimeZone.setDefault(saved)

    private fun label(c: PickerChoice, league: League = League.NHL) = PlayerText.choice(res, c, 0, clock24 = false, league = league)

    @Test fun theLeagueRowNamesTheShownLeague() {
        assertEquals("League · NFL", label(PickerChoice.ScoreLeagueRow(League.NFL)))
        assertEquals("League · NHL", label(PickerChoice.ScoreLeagueRow(League.NHL)))
        assertEquals("NFL", PlayerText.leagueName(res, League.NFL))
    }

    @Test fun theDividerIsTheWeekLabelNeverARawKey() {
        assertEquals("Week 9", label(PickerChoice.ScoreDayDivider("w2026-2-9"), League.NFL))
        assertEquals("Week 9", label(PickerChoice.ScoreDayDivider("w2026-2-9"))) // a week key reads as a week in either league
        assertEquals("Divisional Round", label(PickerChoice.ScoreDayDivider("w2026-3-2"), League.NFL))
        assertFalse(label(PickerChoice.ScoreDayDivider("w2026-9-99"), League.NFL).startsWith("w2026"))
        // the NHL's date divider is unchanged
        assertEquals("Wed, Nov 4", label(PickerChoice.ScoreDayDivider("2026-11-04")).replace("Wed, Nov 4", "Wed, Nov 4"))
        assertEquals("Today", label(PickerChoice.ScoreTodayDivider))
    }

    @Test fun theCurrentWeekReadsThisWeekAndTheFailureCarriesItsLeague() {
        assertEquals("This week", label(PickerChoice.ScoreDayDivider("w2026-2-9", current = true)))
        assertEquals("This week", label(PickerChoice.ScoreDayDivider("", current = true)))
        assertEquals(res.getString(app.thebox.ui.R.string.sports_failed_espn), label(PickerChoice.ScoreFailed(FailKind.IO, League.NFL)))
    }

    @Test fun theFailureNamesEspnForTheNfl() {
        assertEquals(res.getString(app.thebox.ui.R.string.sports_failed_espn), label(PickerChoice.ScoreFailed(FailKind.TIMEOUT), League.NFL))
        assertEquals(res.getString(app.thebox.ui.R.string.sports_failed), label(PickerChoice.ScoreFailed(FailKind.TIMEOUT)))
        assertEquals(res.getString(app.thebox.ui.R.string.sports_no_network), label(PickerChoice.ScoreFailed(FailKind.OFFLINE), League.NFL))
    }

    @Test fun anNflGameRow() {
        val live = SportsFixtures.nflGame()
        assertEquals("KC 24–17 BUF · 4th 3:42", label(PickerChoice.ScoreGame(live, followed = false), League.NFL))
        val half = SportsFixtures.nflGame(
            phase = GamePhase.INTERMISSION, period = 2, clock = GameClock(0, false, true),
            detail = SportsFixtures.nflDetail(breakKind = NflBreak.HALFTIME),
        )
        assertEquals("KC 24–17 BUF · Halftime", label(PickerChoice.ScoreGame(half, followed = true), League.NFL))
        val sunday = ZonedDateTime.of(2026, 11, 8, 16, 25, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val sched = SportsFixtures.nflGame(phase = GamePhase.SCHEDULED, period = null, periodKind = null, clock = null, detail = null, startUtcMs = sunday)
        assertEquals("KC – BUF · 4:25 PM", SportsText.pickerGame(res, sched, clock24 = false, nowMs = sunday - 3_600_000L))
        assertEquals("KC – BUF · Sun 4:25 PM", SportsText.pickerGame(res, sched, clock24 = false, nowMs = sunday - 4 * 86_400_000L))
    }

    @Test fun theToastWhenEspnDropsTheFollowedGameNamesEspn() {
        val f = SportsFixtures.followedOf(SportsFixtures.nflGame())
        assertEquals(
            "ESPN no longer lists KC – BUF. Follow another game in Sports.",
            SportsText.toast(res, ScoreEvent.Removed(RemoveReason.NOT_LISTED, f)),
        )
        val nhl = SportsFixtures.followedOf(SportsFixtures.game())
        assertEquals(
            "NHL.com no longer lists CGY – EDM. Follow another game in Sports.",
            SportsText.toast(res, ScoreEvent.Removed(RemoveReason.NOT_LISTED, nhl)),
        )
    }
}
