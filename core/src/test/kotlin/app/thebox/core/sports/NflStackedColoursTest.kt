package app.thebox.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import app.thebox.core.sports.SportsConstants as K

/**
 * docs/design/m6-nfl-stacked.md §6, §9.1 `NflStackedColoursTest` (WP-2): the §6.2 table as a golden for all 32 teams and
 * the fallback (every column), every ST §5 rule (stripe ≥ 3 on the row and the neutral bar, white ≥ 4.5 on every bar,
 * A / B ≥ 2 against the bar, A–B ≥ 1.5, C7 α on the .05 grid, true goal colours), the look-alike swap over all 32 × 31
 * ordered pairs (all distinct after `pair()`, the away swaps exactly DAL @ LV and DAL @ PHI), the §2.6 bar colours
 * (team, neutral, the yellow flag bar's fixed stripe B) and the hairline rule. The NHL and CHL tables are untouched.
 */
class NflStackedColoursTest {
    private val white = 0xFFFFFFFF.toInt()
    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
    private fun argb(rgb: Int) = (0xFF shl 24) or rgb
    private fun ratio(a: Int, b: Int) = Contrast.ratio(a, b)

    private val teams: List<String> = NflTeamColours.TABLE.keys.sorted()
    private val all: List<String?> = teams + listOf<String?>(null)
    private fun name(a: String?) = a ?: "fallback"

    /**
     * §6.2, verbatim: team, base, accent, stripe, stripe:row, swap stripe, bar bg, white:bar, A, A:bar, B, B:bar, ΔE row,
     * flash α, pop.
     */
    private val table = """
        ARI #97233F #000000 #B15A6F 3.16 #737373 #7D1D34 10.01 #000000 2.10 #C17B8C 3.09 52.6 0.45 #C68695
        ATL #A71930 #000000 #BD5264 3.16 #737373 #8A1528 9.47 #000000 2.22 #CA7583 2.88 64.3 0.45 #CF808D
        BAL #241773 #9E7C0C #9E7C0C 3.70 #7C74AB #1E135F 15.99 #9E7C0C 4.07 #FFFFFF 15.99 28.6 0.40 #B1963D
        BUF #00338D #C60C30 #D13D59 3.14 #597AB5 #002A75 13.22 #C60C30 2.20 #6685BB 3.55 24.1 0.50 #E0798D
        CAR #0085CA #101820 #0085CA 3.61 #707479 #006EA7 5.54 #101820 3.23 #66B6DF 2.46 31.9 0.40 #40A4D7
        CHI #0B162A #C83803 #CE4C1C 3.24 #6D737F #091223 18.71 #C83803 3.58 #FFFFFF 18.71 18.4 0.50 #DB7E5B
        CIN #FB4F14 #000000 #FB4F14 4.32 #737373 #D04111 4.72 #000000 4.45 #FD9572 2.19 100.1 0.40 #FC6937
        CLE #311D00 #FF3C00 #FF3C00 4.09 #837766 #291800 17.14 #FF3C00 4.82 #FFFFFF 17.14 42.1 0.45 #FF6333
        DAL #041E42 #869397 #869397 4.60 #68788E #031937 17.53 #869397 5.54 #FFFFFF 17.53 9.0 0.30 #8C989C
        DEN #FB4F14 #002244 #FB4F14 4.32 #667A8F #D04111 4.72 #002244 3.39 #FD9572 2.19 100.1 0.40 #FC6937
        DET #0076B6 #B0B7BC #B0B7BC 7.17 #0D7DBA #006297 6.57 #B0B7BC 3.24 #FFFFFF 6.57 26.8 0.40 #B0B7BC
        GB #203731 #FFB612 #FFB612 8.29 #6E7D79 #1A2E29 14.32 #FFB612 8.15 #798783 3.82 31.7 0.45 #FFB612
        HOU #03202F #A71930 #BD5264 3.16 #687982 #021A27 17.80 #A71930 2.39 #687982 3.94 20.1 0.45 #CF808D
        IND #002C5F #A2AAAD #A2AAAD 6.16 #597697 #00244F 15.38 #A2AAAD 6.51 #66809F 3.77 3.9 0.35 #A2AAAD
        JAX #006778 #D7A22A #D7A22A 6.30 #267E8C #005563 8.47 #D7A22A 3.67 #FFFFFF 8.47 30.8 0.40 #D7A22A
        KC #E31837 #FFB81C #FFB81C 8.41 #E31837 #BC142E 6.41 #FFB81C 3.70 #EE7487 2.28 84.4 0.45 #FFB81C
        LAC #0080C6 #FFC20E #FFC20E 9.00 #0080C6 #006AA4 5.84 #FFC20E 3.61 #FFFFFF 5.84 30.4 0.45 #FFC20E
        LAR #003594 #FFA300 #FFA300 7.27 #4D72B4 #002C7B 12.76 #FFA300 6.37 #6686BF 3.48 26.5 0.40 #FFA300
        LV #000000 #A5ACAF #A5ACAF 6.32 #737373 #000000 21.00 #A5ACAF 9.12 #666666 3.66 32.0 0.35 #A5ACAF
        MIA #008E97 #FC4C02 #FC4C02 4.28 #008E97 #00767D 5.40 #66BBC1 2.43 #FFFFFF 5.40 44.9 0.40 #FC6728
        MIN #4F2683 #FFC62F #FFC62F 9.27 #8467A8 #411F6C 12.83 #FFC62F 8.17 #957DB5 3.59 29.6 0.45 #FFC62F
        NE #002244 #C60C30 #D13D59 3.14 #667A8F #001C38 17.17 #C60C30 2.86 #FFFFFF 17.17 9.6 0.50 #E0798D
        NO #101820 #D3BC8D #D3BC8D 7.87 #707479 #0D141A 18.55 #D3BC8D 10.02 #707479 3.94 25.2 0.40 #D3BC8D
        NYG #0B2265 #A71930 #BD5264 3.16 #6D7AA3 #091C54 16.08 #A71930 2.16 #6D7AA3 3.80 14.4 0.45 #CF808D
        NYJ #125740 #000000 #4D8170 3.25 #737373 #0F4835 10.51 #719A8C 3.35 #FFFFFF 10.51 45.7 0.45 #7DA396
        PHI #004C54 #A5ACAF #A5ACAF 6.32 #4D8287 #003F46 11.67 #A5ACAF 5.07 #FFFFFF 11.67 28.6 0.35 #A5ACAF
        PIT #101820 #FFB612 #FFB612 8.29 #707479 #0D141A 18.55 #FFB612 10.56 #707479 3.94 25.2 0.45 #FFB612
        SEA #002244 #69BE28 #69BE28 6.24 #667A8F #001C38 17.17 #69BE28 7.35 #667A8F 3.88 9.6 0.35 #69BE28
        SF #AA0000 #B3995D #B3995D 5.29 #C44D4D #8D0000 9.85 #B3995D 3.58 #FFFFFF 9.85 82.6 0.30 #B3995D
        TB #D50A0A #34302B #DB2F2F 3.09 #7B7875 #B00808 7.28 #E66C6C 2.33 #FFFFFF 7.28 93.5 0.55 #E87878
        TEN #0C2340 #4B92DB #4B92DB 4.46 #6D7B8C #0A1D35 16.93 #4B92DB 5.19 #FFFFFF 16.93 11.4 0.30 #5D9DDF
        WSH #5A1414 #FFB612 #FFB612 8.29 #946666 #4B1111 15.14 #FFB612 8.61 #9C7272 3.65 46.9 0.45 #FFB612
        fallback #2A3140 #5CA8F2 #5CA8F2 5.78 #757983 #232935 14.58 #5CA8F2 5.79 #7F838C 3.84 18.9 0.35 #5CA8F2
    """.trimIndent().lines().map { it.trim().split(Regex("\\s+")) }

    private fun parse(h: String): Int = argb(h.removePrefix("#").toInt(16))

    @Test
    fun theSection62TableIsTheDerivation() {
        assertEquals(33, table.size)
        assertEquals(teams + "fallback", table.map { it[0] })
        for (r in table) {
            val a: String? = r[0].takeUnless { it == "fallback" }
            val g = NflTeamColours.of(a)
            val c = NflStackedColours.of(a)
            val sw = NflStackedColours.resolved(a).swapped
            val n = r[0]
            assertEquals(r[1], hex(g.base), "$n base")
            assertEquals(r[2], hex(g.accent), "$n accent")
            assertEquals(r[3], hex(c.stripe), "$n stripe")
            assertEquals(r[4].toDouble(), ratio(c.stripe, K.STACKED_ROW_BG), 0.005, "$n stripe:row")
            assertEquals(r[5], hex(sw.stripe), "$n swap stripe")
            assertEquals(r[6], hex(c.barBg), "$n bar bg")
            assertEquals(r[7].toDouble(), ratio(white, c.barBg), 0.005, "$n white:bar")
            assertEquals(r[8], hex(c.barA), "$n A")
            assertEquals(r[9].toDouble(), ratio(c.barA, c.barBg), 0.005, "$n A:bar")
            assertEquals(r[10], hex(c.barB), "$n B")
            assertEquals(r[11].toDouble(), ratio(c.barB, c.barBg), 0.005, "$n B:bar")
            assertEquals(r[12].toDouble(), NhlStackedColours.deltaE76(c.barBg, K.STACKED_ROW_BG), 0.05, "$n ΔE row")
            assertEquals(r[13].toFloat(), c.rowFlashAlpha, 1e-4f, "$n flash α")
            assertEquals(r[14], hex(c.goal.pop), "$n pop")
            // The stripe's source column: base or accent, lightened by the stripe rule.
            assertEquals(NhlStackedColours.derive(a, g).primary, c, "$n is NhlStackedColours.derive")
        }
    }

    /** Every per-colour rule (ST §5, C7), for one team's colours (primary or swapped). */
    private fun assertRules(c: StackedColours, what: String) {
        assertTrue(ratio(c.stripe, K.STACKED_ROW_BG) >= K.STACKED_STRIPE_MIN, "$what: stripe ${hex(c.stripe)} on the row")
        assertTrue(ratio(c.stripe, K.STACKED_BAR_NEUTRAL) >= K.STACKED_STRIPE_MIN, "$what: stripe on the neutral bar")
        val b = NhlStackedColours.over(c.stripe, c.rowFlashAlpha.toDouble(), K.STACKED_ROW_BG)
        assertTrue(ratio(white, b) >= K.STACKED_C7_MIN, "$what: C7 white at α ${c.rowFlashAlpha}")
        assertTrue(ratio(c.goal.pop, b) >= K.STACKED_C7_MIN, "$what: C7 pop at α ${c.rowFlashAlpha}")
        assertTrue(c.rowFlashAlpha in 0.2999f..0.5501f, "$what: flash α ${c.rowFlashAlpha}")
        val steps = (K.STACKED_ROW_FLASH_ALPHA - c.rowFlashAlpha) / K.STACKED_ROW_FLASH_STEP
        assertEquals(Math.round(steps).toFloat(), steps, 1e-3f, "$what: α on the .05 grid")
        if (c.rowFlashAlpha < K.STACKED_ROW_FLASH_ALPHA - 1e-4f) {
            assertFalse(NhlStackedColours.c7(c.stripe, c.goal.pop, c.rowFlashAlpha + K.STACKED_ROW_FLASH_STEP), "$what: α is the first that passes")
        }
    }

    @Test
    fun everyTeamPassesEveryRule() {
        for (a in all) {
            val c = NflStackedColours.of(a)
            val r = NflStackedColours.resolved(a)
            assertEquals(c, r.primary)
            assertRules(c, name(a))
            assertRules(r.swapped, "${name(a)} swapped")
            // White words ≥ 4.5 on every team bar (lowest: CIN, DEN 4.72).
            assertTrue(ratio(white, c.barBg) >= 4.5, "${name(a)}: white on ${hex(c.barBg)}")
            assertEquals(NhlTeamColours.mixBlack(c.goal.tab, K.STACKED_BAR_BG_BLACK), c.barBg, name(a))
            // NFL S1: the tab is the base for all 32, so the bar is the base darkened.
            assertEquals(c.goal.base, c.goal.tab, "${name(a)}: tab = base")
            assertTrue(ratio(c.barA, c.barBg) >= K.STACKED_BAR_STRIPE_MIN, "${name(a)}: A")
            assertTrue(ratio(c.barB, c.barBg) >= K.STACKED_BAR_STRIPE_MIN, "${name(a)}: B")
            assertTrue(ratio(c.barA, c.barB) >= K.STACKED_BAR_AB_MIN, "${name(a)}: A/B")
            // The takeover keeps the true colours (R12, SR2); the swap touches only the stripe and its flash α.
            assertEquals(NflTeamColours.of(a), c.goal)
            assertEquals(c.goal, r.swapped.goal)
            assertEquals(c.barBg, r.swapped.barBg); assertEquals(c.barA, r.swapped.barA); assertEquals(c.barB, r.swapped.barB)
        }
    }

    @Test
    fun theSection62MinimaHold() {
        val cs = teams.map { NflStackedColours.of(it) }
        assertEquals(3.09, cs.minOf { ratio(it.stripe, K.STACKED_ROW_BG) }, 0.005, "stripe on the row (TB)")
        assertEquals(4.72, cs.minOf { ratio(white, it.barBg) }, 0.005, "white on the bar (CIN, DEN)")
        assertTrue(cs.minOf { ratio(it.barA, it.barBg) } >= 2.10 - 0.005, "A ≥ 2.10")
        assertTrue(cs.minOf { ratio(it.barB, it.barBg) } >= 2.19 - 0.005, "B ≥ 2.19")
        assertTrue(cs.minOf { ratio(it.barA, it.barB) } >= 1.51 - 0.005, "A–B ≥ 1.51")
        assertEquals(0.30f, cs.minOf { it.rowFlashAlpha }, 1e-4f)
        assertEquals(0.55f, cs.maxOf { it.rowFlashAlpha }, 1e-4f)
        val onNeutral = teams.minOf { ratio(NflStackedColours.of(it).stripe, K.STACKED_BAR_NEUTRAL) }
        val onNeutralSwap = teams.minOf { ratio(NflStackedColours.resolved(it).swapped.stripe, K.STACKED_BAR_NEUTRAL) }
        assertEquals(3.25, onNeutral, 0.01, "stripe on the neutral bar")
        assertEquals(3.17, onNeutralSwap, 0.01, "swap stripe on the neutral bar")
    }

    @Test
    fun theBarsThatLookLikeTheRowsAreTheSevenListed() {
        // §6.2 (Rec 2): ΔE76 < 20 between the bar and the row navy.
        val alike = teams.filter { NhlStackedColours.deltaE76(NflStackedColours.of(it).barBg, K.STACKED_ROW_BG) < K.STACKED_STRIPE_DE_MIN }
        assertEquals(setOf("IND", "DAL", "NE", "SEA", "TEN", "NYG", "CHI"), alike.toSet())
        assertTrue(NhlStackedColours.deltaE76(NflStackedColours.of(null).barBg, K.STACKED_ROW_BG) < K.STACKED_STRIPE_DE_MIN, "fallback")
        assertEquals(7.8, NhlStackedColours.deltaE76(K.STACKED_BAR_NEUTRAL, K.STACKED_ROW_BG), 0.05, "neutral bar")
        // 22 bars under 1.5 : 1 by luminance.
        assertEquals(22, teams.count { ratio(NflStackedColours.of(it).barBg, K.STACKED_ROW_BG) < 1.5 })
    }

    // ------------------------------------------------------------------ the look-alike swap

    @Test
    fun everyOrderedPairIsDistinctAfterPair() {
        var collidedBefore = 0
        val awaySwaps = mutableSetOf<Pair<String, String>>()
        val unordered = mutableSetOf<Set<String>>()
        for (a in teams) for (h in teams) {
            if (a == h) continue
            val p = NflStackedColours.pair(a, h)
            val dE = NhlStackedColours.deltaE76(p.away.stripe, p.home.stripe)
            assertTrue(dE >= K.STACKED_STRIPE_DE_MIN, "$a @ $h: ΔE $dE (${hex(p.away.stripe)} / ${hex(p.home.stripe)})")
            val pa = NflStackedColours.of(a)
            val ph = NflStackedColours.of(h)
            if (NhlStackedColours.collides(pa.stripe, ph.stripe)) {
                collidedBefore++
                unordered += setOf(a, h)
                if (p.away != pa) {
                    assertEquals(ph, p.home, "$a @ $h: home keeps its primary")
                    assertEquals(NflStackedColours.resolved(a).swapped, p.away)
                    awaySwaps += a to h
                } else {
                    assertEquals(NflStackedColours.resolved(h).swapped, p.home, "$a @ $h: home swaps")
                }
            } else {
                assertEquals(StackedPair(pa, ph), p, "$a @ $h: no collision, no swap")
            }
            // The true goal colours never swap.
            assertEquals(NflTeamColours.of(a), p.away.goal)
            assertEquals(NflTeamColours.of(h), p.home.goal)
        }
        // §6.2: 59 unordered pairs, 118 ordered; 116 by the home swap, DAL @ LV and DAL @ PHI by the away swap.
        assertEquals(59, unordered.size)
        assertEquals(118, collidedBefore)
        assertEquals(setOf("DAL" to "LV", "DAL" to "PHI"), awaySwaps)
    }

    @Test
    fun theRoutineCollisionsCollide() {
        for ((x, y) in listOf("BUF" to "NE", "NE" to "NYG", "CIN" to "CLE", "KC" to "LAC", "GB" to "MIN", "DAL" to "PHI")) {
            for ((a, h) in listOf(x to y, y to x)) {
                assertTrue(NhlStackedColours.collides(NflStackedColours.of(a).stripe, NflStackedColours.of(h).stripe), "$a @ $h")
            }
        }
        // KC @ BUF (the mock's game): no collision; gold and red stripes (S-D5).
        val p = NflStackedColours.pair("KC", "BUF")
        assertEquals(StackedPair(NflStackedColours.of("KC"), NflStackedColours.of("BUF")), p)
        assertEquals("#FFB81C", hex(p.away.stripe))
        assertEquals("#D13D59", hex(p.home.stripe))
    }

    @Test
    fun pairsAreCachedCaseInsensitiveAndTheFallbackResolves() {
        val p = NflStackedColours.pair("BUF", "NE")
        assertSame(p, NflStackedColours.pair("BUF", "NE"))
        assertSame(p, NflStackedColours.pair(" buf", "ne "))
        assertNotSame(p, NflStackedColours.pair("NE", "BUF"))
        assertEquals(NflStackedColours.of(null), NflStackedColours.of("XYZ"))
        assertEquals(NflTeamColours.FALLBACK, NflStackedColours.of(null).goal)
        val u = NflStackedColours.pair("XXX", null)
        assertTrue(NhlStackedColours.deltaE76(u.away.stripe, u.home.stripe) >= K.STACKED_STRIPE_DE_MIN)
        for (t in teams) for ((a, h) in listOf(t to null, null to t)) {
            val q = NflStackedColours.pair(a, h)
            assertTrue(NhlStackedColours.deltaE76(q.away.stripe, q.home.stripe) >= K.STACKED_STRIPE_DE_MIN, "${name(a)} @ ${name(h)}")
        }
        // StackedColourTable's NFL arm is this object (commit 0 note 4).
        assertSame(NflStackedColours.pair("KC", "BUF"), StackedColourTable.pair(League.NFL, "KC", "BUF"))
    }

    @Test
    fun theNhlAndChlTablesAreUntouched() {
        // NHL codes that are also NFL codes resolve from their own leagues' tables.
        for (code in listOf("BUF", "DAL", "SEA", "PIT", "MIN", "DET", "WSH")) {
            assertEquals(NhlStackedColours.derive(code, NhlTeamColours.of(code)).primary, NhlStackedColours.of(code), code)
            assertEquals(NhlTeamColours.of(code), StackedColourTable.pair(League.NHL, code, null).away.goal, code)
            assertEquals(NflTeamColours.of(code), StackedColourTable.pair(League.NFL, code, null).away.goal, code)
        }
        assertSame(NhlStackedColours.pair("EDM", "TOR"), StackedColourTable.pair(League.NHL, "EDM", "TOR"))
    }

    // ------------------------------------------------------------------ §2.6 the bar colours

    @Test
    fun theBarColoursFollowTheKind() {
        val p = NflStackedColours.pair("KC", "BUF")
        for (kind in listOf(NflBarKind.PLAY, NflBarKind.PAT, NflBarKind.KICKOFF, NflBarKind.TIMEOUT, NflBarKind.SCORE)) {
            for (side in Side.entries) {
                val c = if (side == Side.AWAY) p.away else p.home
                assertEquals(NflBarColours(c.barBg, c.barA, c.barB, white), NflStackedColours.bar(NflBarKey(kind, side), p), "$kind $side")
            }
            // S-D10: a team kind with no side is the neutral bar.
            assertEquals(NflBarColours(K.STACKED_BAR_NEUTRAL, p.away.stripe, p.home.stripe, white), NflStackedColours.bar(NflBarKey(kind, null), p), "$kind neutral")
        }
        for (kind in listOf(NflBarKind.END_PERIOD, NflBarKind.HALFTIME, NflBarKind.FINAL, NflBarKind.NO_UPDATE, NflBarKind.DELAYED)) {
            assertEquals(NflBarColours(K.STACKED_BAR_NEUTRAL, p.away.stripe, p.home.stripe, white), NflStackedColours.bar(NflBarKey(kind, null), p), "$kind")
        }
        // A swapped home stripe is the one the neutral bar uses.
        val q = NflStackedColours.pair("BUF", "NE")
        assertEquals(NflStackedColours.resolved("NE").swapped.stripe, NflStackedColours.bar(NflBarKey(NflBarKind.HALFTIME, null), q).b)
        // The flag bar ignores the teams.
        assertSame(NflStackedColours.FLAG, NflStackedColours.bar(NflBarKey(NflBarKind.FLAG, Side.HOME), p))
    }

    @Test
    fun theFlagBarIsTheFixedYellowWithItsOwnStripeB() {
        val f = NflStackedColours.FLAG
        assertEquals("#FFD100", hex(f.bg)); assertEquals("#111111", hex(f.a)); assertEquals("#806800", hex(f.b)); assertEquals("#111111", hex(f.fg))
        // §6.3: text 12.92; the derived-stripe rules hold for the fixed stripes (A, B ≥ 2 on the bar; A–B ≥ 1.5).
        assertEquals(12.92, ratio(f.fg, f.bg), 0.005)
        assertEquals(3.69, ratio(f.b, f.bg), 0.005)
        assertEquals(3.51, ratio(f.a, f.b), 0.005)
        assertTrue(ratio(f.a, f.bg) >= K.STACKED_BAR_STRIPE_MIN)
        // The exception (S-D6): the mock's white stripe B fails on the yellow, so B is mixBlack(bg, .5).
        assertTrue(ratio(white, f.bg) < K.STACKED_BAR_STRIPE_MIN, "white on yellow ${ratio(white, f.bg)}")
        assertEquals(1.46, ratio(white, f.bg), 0.005)
        assertEquals(NhlTeamColours.mixBlack(f.bg, 0.5), f.b)
    }

    @Test
    fun theHairlineAndTheRedZoneSub() {
        // The hairline: 1 px white .22 at the bar's inner edge, on every bar (S-D9); its blend over each bar is lighter.
        for (a in all) {
            val bg = NflStackedColours.of(a).barBg
            val line = NhlStackedColours.over(white, K.STACKED_BAR_HAIRLINE_ALPHA.toDouble(), bg)
            assertTrue(Contrast.ratio(line, bg) > 1.0, "${name(a)}: the hairline shows on the bar")
        }
        assertEquals(1f, K.STACKED_HAIRLINE_W)
        assertEquals(0.22f, K.STACKED_BAR_HAIRLINE_ALPHA)
        // §6.3: the red-zone sub patch.
        assertEquals("#A60D26", hex(NflStackedColours.RED_ZONE_SUB))
        assertEquals(7.76, ratio(white, NflStackedColours.RED_ZONE_SUB), 0.005)
        assertEquals(7.5, NhlStackedColours.deltaE76(NflStackedColours.RED_ZONE_SUB, NflStackedColours.of("KC").barBg), 0.05, "beside KC's bar")
        // §6.3 fixed colours.
        assertEquals(14.56, ratio(white, K.STACKED_ROW_BG), 0.005)
        assertEquals(15.30, ratio(white, K.STACKED_BAR_NEUTRAL), 0.005)
        assertEquals(10.18, ratio(K.STACKED_NFL_PIP_LIT, K.STACKED_ROW_BG), 0.005)
    }
}
