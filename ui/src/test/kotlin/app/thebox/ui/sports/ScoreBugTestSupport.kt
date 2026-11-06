package app.thebox.ui.sports

import app.thebox.core.sports.BottomBar
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.PlacementInput
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.Side
import app.thebox.core.sports.TeamLine
import app.thebox.ui.full.PlayerUi

/** Views and inputs for the score bug tests (the placer is the real `ScoreBugPlacement.place`). */
internal object ScoreBugFixtures {
    fun view(
        top: CentreText = CentreText.Clock,
        bottom: CentreText? = CentreText.Period(PeriodLabel(PeriodKind.REG, 1)),
        away: Int = 0,
        home: Int = 2,
        dim: Boolean = false,
        goalSeq: Int = 0,
        goalSide: Side? = null,
        awayAbbrev: String = "CGY",
        homeAbbrev: String = "EDM",
        clockSecs: Int? = if (top == CentreText.Clock) 622 else null,
    ) = ScoreBugView(
        away = TeamLine(awayAbbrev, "Calgary", away, null),
        home = TeamLine(homeAbbrev, "Edmonton", home, null),
        top = top, bottom = bottom, dim = dim, goalSeq = goalSeq, goalSide = goalSide, clockSecs = clockSecs,
    )

    val live = view()
    val intermission = view(bottom = CentreText.Intermission(PeriodLabel(PeriodKind.REG, 1)))
    val endOf = view(bottom = CentreText.EndOf(PeriodLabel(PeriodKind.REG, 1)))
    val overtime = view(bottom = CentreText.Period(PeriodLabel(PeriodKind.OT, 1)), away = 3, home = 3)
    val secondOvertime = view(bottom = CentreText.Period(PeriodLabel(PeriodKind.OT, 2)), away = 3, home = 3)
    val shootout = view(top = CentreText.Shootout, bottom = null, away = 3, home = 3)
    val final = view(top = CentreText.Final(PeriodKind.REG), bottom = null, away = 1, home = 4)
    val finalOt = view(top = CentreText.Final(PeriodKind.OT), bottom = null, away = 3, home = 4)
    val finalSo = view(top = CentreText.Final(PeriodKind.SO), bottom = null, away = 3, home = 4)
    val stale = view(bottom = CentreText.NoUpdate, dim = true)
    val delayed = view(bottom = CentreText.Delayed, dim = true)
    val goalHome = view(bottom = CentreText.Goal(Side.HOME), goalSeq = 1, goalSide = Side.HOME)
    val goalAway = view(bottom = CentreText.Goal(Side.AWAY), away = 1, goalSeq = 1, goalSide = Side.AWAY)

    val all: Map<String, ScoreBugView> = linkedMapOf(
        "live" to live, "intermission" to intermission, "end-of" to endOf, "ot" to overtime, "2ot" to secondOvertime,
        "so" to shootout, "final" to final, "final-ot" to finalOt, "final-so" to finalSo, "stale" to stale,
        "delayed" to delayed, "goal-home" to goalHome, "goal-away" to goalAway,
    )

    val noGeometry = PlayerUi.overlayGeometry()

    fun input(
        corner: ScoreCorner = ScoreCorner.TOP_RIGHT,
        size: ScoreBugSize = ScoreBugSize.STANDARD,
        placing: Boolean = false,
        bar: BottomBar = BottomBar.NONE,
    ) = PlacementInput(
        corner = corner, size = size, placing = placing, bar = bar,
        channelList = false, stats = false, groupCard = false, picker = false, numberEntry = false, probing = null,
        toast = false, reminder = false, modal = false, textTrackOn = false, screenOff = false,
    )
}
