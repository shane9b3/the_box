package app.thebox.core.sports.hub

import app.thebox.core.sports.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-nfl-hub.md §2.2, §6.1: every kind's direction, the 3rd-down rate, Text and Clock never highlighted. */
class StatRulesTest {
    private fun row(kind: StatKind, a: StatValue, h: StatValue) = StatRow(kind, a, h)
    private fun n(v: Int) = StatValue.Count(v)

    @Test fun lowerIsBetterExactlyForTheFiveKinds() {
        val lower = setOf(StatKind.PIM, StatKind.GIVEAWAYS, StatKind.TURNOVERS, StatKind.POINTS_ALLOWED_PG, StatKind.YARDS_ALLOWED_PG)
        for (k in StatKind.entries) assertEquals(k in lower, StatRules.lowerIsBetter(k), k.name)
    }

    @Test fun higherWinsUnlessLowerIsBetter() {
        assertEquals(Side.AWAY, StatRules.better(row(StatKind.SOG, n(30), n(20))))
        assertEquals(Side.HOME, StatRules.better(row(StatKind.PIM, n(10), n(4))))
        assertEquals(Side.HOME, StatRules.better(row(StatKind.TOTAL_YARDS, n(250), n(310))))
        assertEquals(Side.AWAY, StatRules.better(row(StatKind.TURNOVERS, n(0), n(2))))
        assertEquals(Side.AWAY, StatRules.better(row(StatKind.POINTS_PG, StatValue.Decimal(24.1), StatValue.Decimal(22.6))))
        assertEquals(Side.HOME, StatRules.better(row(StatKind.YARDS_ALLOWED_PG, StatValue.Decimal(351.1), StatValue.Decimal(340.4))))
        assertEquals(Side.HOME, StatRules.better(row(StatKind.FACEOFF_PCT, StatValue.Pct(0.45), StatValue.Pct(0.55))))
        assertEquals(Side.AWAY, StatRules.better(row(StatKind.POWER_PLAY, StatValue.Fraction(2, 4), StatValue.Fraction(1, 3))), "made, as the panel did")
        assertEquals(Side.HOME, StatRules.better(row(StatKind.PASS_YARDS, n(-2), n(5))), "negative passing yards")
    }

    @Test fun tiesTextAndClockAreNeverHighlighted() {
        assertNull(StatRules.better(row(StatKind.HITS, n(12), n(12))))
        assertNull(StatRules.better(row(StatKind.RECORD, StatValue.Text("6-4"), StatValue.Text("4-5-1"))))
        assertNull(StatRules.better(row(StatKind.LAST_FIVE, StatValue.Text("3-2"), StatValue.Text("4-1"))))
        assertNull(StatRules.better(row(StatKind.POSSESSION, StatValue.Clock(1709), StatValue.Clock(1891))))
        assertNull(StatRules.better(row(StatKind.SOG, n(3), StatValue.Text("3"))), "mixed types")
    }

    @Test fun thirdDownComparesTheRateThenMade() {
        fun td(am: Int, ao: Int, hm: Int, ho: Int) = StatRules.better(row(StatKind.THIRD_DOWN, StatValue.Fraction(am, ao, dash = true), StatValue.Fraction(hm, ho, dash = true)))
        assertEquals(Side.AWAY, td(4, 12, 2, 10), "33 % against 20 %")
        assertEquals(Side.HOME, td(5, 15, 3, 6), "a higher count but a lower rate loses")
        assertEquals(Side.HOME, td(2, 4, 4, 8), "equal rates: more made wins")
        assertNull(td(3, 9, 3, 9))
        assertNull(td(0, 0, 3, 9), "no attempts: no comparison")
        assertNull(td(3, 9, 0, 0))
    }

    @Test fun fractionDashIsOnlyPresentation() {
        assertTrue(StatValue.Fraction(1, 3) == StatValue.Fraction(1, 3, dash = false))
        assertFalse(StatValue.Fraction(1, 3) == StatValue.Fraction(1, 3, dash = true))
    }
}
