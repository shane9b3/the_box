package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.full.PickerChoice
import app.thebox.core.sports.BarKind
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.NhlTeamColours
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.SportsFixtures
import app.thebox.ui.R
import app.thebox.ui.full.PlayerText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (CHL WP-5, docs/design/m6-chl-leagues.md §6.4) The fit rules for every place a team code or name is drawn, for all 61
 * codes (2, 3 and 4 letters, and the QMJHL's "NFL") and the longest names, measured through the real painters with the
 * bundled fonts at Standard and Large: the cards (nickname, code plate), the quick-bar picker rows, the Pill's power-play
 * labels, the Stacked rows (unmirrored and mirrored, the exact reflection `[score][code][logo][stripe]`), the Stacked bar
 * strings and takeover tally, the fallback capsule and the goal-badge fallback text. A failure at the floor is a lead
 * escalation (design O3), never a display alias.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChlFitTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val semi = sansTypeface(context, R.font.instrument_sans_semibold)
    private val medium = sansTypeface(context, R.font.instrument_sans_medium)
    private val bold = sansTypeface(context, R.font.instrument_sans_bold)
    private val italic = StackedPainter.barTypeface(context)
    private val barlow = sansTypeface(context, R.font.barlow_condensed_black_italic)

    private val sizes = listOf(false, true)
    private fun scale(large: Boolean) = if (large) K.SCORE_LARGE_SCALE else 1f
    private fun width(text: String, px: Int, face: android.graphics.Typeface?): Float =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = px.toFloat() }.measureText(text)

    // ------------------------------------------------------------------ the cards

    @Test
    fun theTeamCountIs61WithTheExpectedCodeLengths() {
        assertEquals(listOf(20, 23, 18), listOf(ChlTestTeams.OHL.size, ChlTestTeams.WHL.size, ChlTestTeams.QMJHL.size))
        assertEquals(listOf("ER", "NB", "OS"), ChlTestTeams.OHL.map { it.code }.filter { it.length == 2 })
        assertEquals(listOf("BRAM", "FLNT", "NIAG"), ChlTestTeams.ALL.map { it.code }.filter { it.length == 4 })
    }

    @Test
    fun everyNicknameFitsTheCardNameColumnAt32SemiboldWithoutAnEllipsis() {
        val limit = GameCardMetrics.NAME_W.toFloat()
        for (t in ChlTestTeams.ALL) {
            val line = SportsFixtures.chlTeam(t.code, t.nickname)
            assertEquals("a card draws the nickname", t.nickname, cardName(line))
            val w = width(cardName(line), GameCardMetrics.NAME_PX, semi)
            assertTrue("${t.league} ${t.nickname} is $w px, the name column is $limit", w <= limit)
        }
        // The longest nicknames the design names, and that "City Nickname" does not fit (so the card must use the nickname).
        for (n in listOf("Thunderbirds", "Winterhawks", "Saguenéens")) assertTrue(n, width(n, GameCardMetrics.NAME_PX, semi) <= limit)
        assertFalse(width("Blainville-Boisbriand Armada", GameCardMetrics.NAME_PX, semi) <= limit)
        assertTrue("Golden Knights is the NHL's longest", width("Thunderbirds", GameCardMetrics.NAME_PX, semi) <= width("Golden Knights", GameCardMetrics.NAME_PX, semi))
    }

    @Test
    fun everyCodeFitsTheLogoPlateUntilTheLogoLoads() {
        for (code in ChlTestTeams.CODES) {
            val w = width(code, 22, semi)
            assertTrue("$code is $w px on the ${GameCardMetrics.PLATE_W} px plate", w <= GameCardMetrics.PLATE_W - 12)
        }
    }

    // ------------------------------------------------------------------ the picker rows

    /** The row's text column: 496 − 2 × 16 padding − 2 × 14 row padding − 24 tick − 12 gap = 400, at 26 Medium. */
    private val pickerLimit = 496f - 32f - 28f - 24f - 12f

    @Test
    fun theLongestPickerRowsFit() {
        val states = listOf(
            SportsFixtures.chlGame(phase = GamePhase.LIVE, period = 5, periodKind = PeriodKind.OT, otNumber = 2, clock = GameClock(1199, true, false)),
            SportsFixtures.chlGame(phase = GamePhase.FINAL, period = 5, periodKind = PeriodKind.SO, finalKind = PeriodKind.SO, clock = null),
            SportsFixtures.chlGame(phase = GamePhase.PRE, period = null, periodKind = null, clock = null, startTbd = true),
            SportsFixtures.chlGame(phase = GamePhase.INTERMISSION, period = 1, clock = GameClock(0, false, true)),
        )
        for (league in listOf(League.OHL, League.WHL, League.QMJHL)) {
            val codes = ChlTestTeams.of(league).map { it.code }
            // Every code against the longest codes of the league (4 letters where the league has them, else 3).
            val partners = codes.sortedByDescending { width(it, 26, medium) }.take(2)
            for (code in codes) for (partner in partners) for (state in states) {
                val g = state.copy(
                    id = state.id.copy(league = league),
                    away = SportsFixtures.chlTeam(code, code, 10), home = SportsFixtures.chlTeam(partner, partner, 10),
                )
                val label = PlayerText.choice(res, PickerChoice.ScoreGame(g, followed = false), 0, clock24 = false, league = league)
                val w = width(label, 26, medium)
                assertTrue("$league \"$label\" is $w px, the row's text column is $pickerLimit", w <= pickerLimit)
            }
        }
        for (league in listOf(League.OHL, League.WHL, League.QMJHL)) {
            val rows = listOf(PickerChoice.ScoreLeagueRow(league), PickerChoice.ScoreChlRow(league))
            for (r in rows) {
                val label = PlayerText.choice(res, r, 0)
                assertTrue("\"$label\"", width(label, 26, medium) <= pickerLimit)
            }
        }
    }

    // ------------------------------------------------------------------ Pill

    private fun field(o: Any, name: String): Float = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.getFloat(o)

    @Test
    fun everyCodeFitsThePillPowerPlayTabLabels() {
        for (large in sizes) {
            val s = scale(large)
            val pp = PowerPlayPainter(s, 1f, if (large) K.SCORE_LARGE_W else K.SCORE_W, if (large) K.SCORE_LARGE_H else K.SCORE_H, bold)
            val room = field(pp, "room")
            val extra = 2 * field(pp, "gap") + field(pp, "dividerW") + field(pp, "slotW")
            for (code in ChlTestTeams.CODES) {
                val l = pp.buildLabels(code, "KIT", res)
                for (i in listOf(0, 2, 4)) {
                    val block = l.w[i] + if (i == 4) 0f else extra
                    assertTrue("$code label $i large=$large: px ${l.px[i]} under the floor", l.px[i] >= K.SCORE_TEXT_FLOOR_PX)
                    assertTrue("$code label $i large=$large: block $block > room $room", block <= room + 0.01f)
                }
            }
        }
    }

    // ------------------------------------------------------------------ the capsule and the disc

    private fun paintedExtents(draw: (Canvas) -> Unit, w: Int = 200, h: Int = 120): Rect {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(b))
        var l = w; var t = h; var r = -1; var bt = -1
        for (y in 0 until h) for (x in 0 until w) if ((b.getPixel(x, y) ushr 24) > 0) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bt) bt = y
        }
        return Rect(l, t, r + 1, bt + 1)
    }

    @Test
    fun theCapsuleTextSizesAreThePinnedOnesAndTheLimitsHold() {
        val pinned = mapOf("BRAM" to 16, "FLNT" to 18, "NIAG" to 18)
        for (large in sizes) {
            val s = scale(large)
            val disc = LogoDisc(s, bold)
            for ((code, px) in pinned) assertEquals("$code capsule text px", px, disc.capsuleTextPx(code))
            for (code in ChlTestTeams.CODES) {
                assertEquals("$code is a capsule only when longer than 3 characters", code.length > 3, disc.isCapsule(code))
            }
            // What is drawn: BRAM's capsule is 67 wide (16 px text), the slot overhang is under 4 a side, 44 tall.
            val e = paintedExtents({ disc.draw(it, 100f, 60f, "BRAM") })
            assertEquals("BRAM capsule width, large=$large", 67f * s, e.width().toFloat(), 2f)
            assertEquals("BRAM capsule height, large=$large", 44f * s, e.height().toFloat(), 2f)
            assertTrue("overhang ${(e.width() / s - K.SCORE_LOGO_W) / 2f} <= ${K.LOGO_CAPSULE_OVERHANG_MAX}", (e.width() / s - K.SCORE_LOGO_W) / 2f <= K.LOGO_CAPSULE_OVERHANG_MAX + 0.6f)
            // The disc of every other code is exactly today's 44.
            val d = paintedExtents({ disc.draw(it, 100f, 60f, "ER") })
            assertEquals("disc width, large=$large", 44f * s, d.width().toFloat(), 2f)
            assertEquals("disc height, large=$large", 44f * s, d.height().toFloat(), 2f)
        }
    }

    @Test
    fun theCapsuleKeepsItsGapToTheScoreInk() {
        // The same measure as ChlCodeFitMeasureTest, on the capsule LogoDisc really draws.
        for (large in sizes) {
            val s = scale(large)
            val disc = LogoDisc(s, bold)
            val score = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = K.SCORE_TEXT_PX * s; textAlign = Paint.Align.CENTER }
            val cx = (K.SCORE_PAD + K.SCORE_LOGO_W + K.SCORE_LOGO_GAP + K.SCORE_DIGITS_W / 2f) * s
            val slotCx = (K.SCORE_PAD + K.SCORE_LOGO_W / 2f) * s
            for (code in listOf("BRAM", "FLNT", "NIAG")) {
                val e = paintedExtents({ disc.draw(it, slotCx, 80f * s, code) }, w = 400, h = 200)
                var least = Float.MAX_VALUE
                for (n in 0..19) {
                    val t = n.toString()
                    val bb = Rect().also { score.getTextBounds(t, 0, t.length, it) }
                    least = minOf(least, (cx - score.measureText(t) / 2f + bb.left) - e.right)
                }
                assertTrue("$code gap ${least / s} < ${K.LOGO_CAPSULE_SCORE_GAP_MIN} (large=$large)", least / s >= K.LOGO_CAPSULE_SCORE_GAP_MIN - 1f)
            }
        }
    }

    // ------------------------------------------------------------------ the goal badge fallback text

    @Test
    fun theBadgeFallbackTextFitsItsSlotWhereverItIsDrawn() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold }
        val expected = mapOf("BRAM" to 22, "FLNT" to 24, "NIAG" to 24, "NFL" to 24, "ER" to 24, "BAR" to 24)
        for ((code, px) in expected) {
            assertEquals("$code Pill badge px", px, goalBadgeFallbackPx(bold, code, K.SCORE_LOGO_W))
            assertEquals("$code Stacked badge px", px, goalBadgeFallbackPx(bold, code, K.STACKED_GOAL_LOGO))
        }
        for (code in ChlTestTeams.CODES) for (slot in listOf(K.SCORE_LOGO_W, K.STACKED_GOAL_LOGO)) {
            val px = goalBadgeFallbackPx(bold, code, slot)
            assertTrue("$code px $px below the floor", px >= if (code.length >= 4) K.LOGO_FALLBACK_CODE4_FLOOR_PX else 24)
            if (code.length >= 4) {
                paint.textSize = px.toFloat()
                val b = Rect().also { paint.getTextBounds(code, 0, code.length, it) }
                assertTrue("$code ink ${b.width()} at $px px in slot $slot", b.width() <= slot)
            } else {
                assertEquals("a code of 3 or fewer characters keeps today's size", 24, px)
            }
        }
    }

    @Test
    fun theGoalPaintersDrawTheFallbackAtTheSteppedSize() {
        for (large in sizes) {
            val s = scale(large)
            val stacked = StackedGoalPainter(s, large, barlow, bold, res.getString(R.string.sports_goal_word), StackedFixtures.SPEC)
            for ((code, px) in mapOf("BRAM" to 22, "FLNT" to 24, "NFL" to 24, "ER" to 24)) {
                stacked.begin(Side.AWAY, NhlTeamColours.FALLBACK, null, code, "KIT", 1, 0, K.STACKED_ROWS_W + K.STACKED_CLOCK_W)
                assertEquals("Stacked badge text $code large=$large", px * s, badgeTextSize(stacked), 0.01f)
            }
            val pill = GoalPainter(s, (if (large) K.SCORE_LARGE_W else K.SCORE_W) * s, (if (large) K.SCORE_LARGE_H else K.SCORE_H) * s, barlow, bold, res.getString(R.string.sports_goal_word))
            for ((code, px) in mapOf("BRAM" to 22, "FLNT" to 24, "NFL" to 24, "ER" to 24)) {
                pill.begin(Side.AWAY, NhlTeamColours.FALLBACK, null, code, 1, 0)
                assertEquals("Pill badge text $code large=$large", px * s, badgeTextSize(pill), 0.01f)
            }
        }
    }

    private fun badgeTextSize(o: Any): Float {
        val p = o.javaClass.getDeclaredField("badgeText").apply { isAccessible = true }.get(o) as Paint
        return p.textSize
    }

    // ------------------------------------------------------------------ Stacked

    @Test
    fun everyCodeFitsAStackedRowBothOrientations() {
        for (large in sizes) {
            val p = StackedPainter(scale(large), large, bold, italic)
            val s = scale(large)
            for (code in ChlTestTeams.CODES) for (mirrored in listOf(false, true)) {
                var least = Float.MAX_VALUE
                for (score in 0..19) least = minOf(least, p.rowGap(code, score.toString(), mirrored) / s)
                assertTrue("$code row large=$large mirrored=$mirrored: gap $least < 0", least >= 0f)
            }
        }
    }

    private fun bar(p: StackedPainter, kind: BarKind, side: Side, secs: Int, away: String, home: String): BarContent =
        BarContent().also {
            it.kind = kind; it.side = side; it.secs = secs; it.period = null
            p.fillBar(it, null, away, home, res)
        }

    @Test
    fun everyCodeFitsTheStackedBarStrings() {
        for (large in sizes) {
            val p = StackedPainter(scale(large), large, bold, italic)
            val s = scale(large)
            val w = (K.STACKED_ROWS_W + K.STACKED_CLOCK_W) * s
            val cases = listOf(BarKind.POWER_PLAY, BarKind.TWO_MAN).flatMap { k -> listOf(92, 19 * 60 + 59, BarContent.NO_SECS).map { k to it } } +
                listOf(BarKind.PP_GOAL to BarContent.NO_SECS)
            for (code in ChlTestTeams.CODES) for (side in Side.entries) for ((kind, secs) in cases) {
                val c = if (side == Side.AWAY) bar(p, kind, side, secs, code, "KIT") else bar(p, kind, side, secs, "KIT", code)
                val text = p.barText(c)
                val limit = w - K.STACKED_HAIRLINE_W * s
                assertTrue("\"$text\" large=$large: right ${p.barTextRight(c)} > $limit", p.barTextRight(c) <= limit)
                val ink = p.barTextInkMirrored(c, w)
                assertTrue("\"$text\" large=$large mirrored: left ${ink.left}", ink.left >= K.STACKED_HAIRLINE_W * s)
                assertTrue("\"$text\" large=$large mirrored: right ${ink.right}", ink.right <= w - 59f * s)
            }
        }
    }

    @Test
    fun theTakeoverBadgeAndTallyFitForEveryWithinLeaguePairSample() {
        val widths = listOf(K.STACKED_ROWS_W + K.STACKED_SOG_W, K.STACKED_ROWS_W + K.STACKED_CLOCK_W, K.STACKED_ROWS_W + K.STACKED_SOG_W + K.STACKED_CLOCK_W)
        for (large in sizes) {
            val s = scale(large)
            val g = StackedGoalPainter(s, large, barlow, bold, res.getString(R.string.sports_goal_word), StackedFixtures.SPEC)
            for (league in listOf(League.OHL, League.WHL, League.QMJHL)) {
                val codes = ChlTestTeams.of(league).map { it.code }
                // Every code against the widest and the narrowest codes of its league, on both sides.
                val widest = codes.sortedByDescending { width(it, 24, bold) }.take(2)
                val narrowest = codes.sortedBy { width(it, 24, bold) }.take(2)
                for (w in widths) for (code in codes) for (other in (widest + narrowest).distinct()) for (side in Side.entries) {
                    if (other == code) continue
                    val away = if (side == Side.AWAY) code else other
                    val home = if (side == Side.HOME) code else other
                    g.begin(side, NhlTeamColours.FALLBACK, null, away, home, 19, 18, w)
                    assertTrue("badge $away–$home large=$large at $w", g.badgeLeft >= 0f && g.badgeRight <= g.wordLeft)
                    if (g.tallyShown) {
                        assertTrue("$away–$home at $w: GOAL ${g.wordRight} vs tally ${g.tallyLeft}", g.wordRight <= g.tallyLeft)
                        assertTrue("$away–$home at $w: tally ends ${g.tallyRight} of ${w * s}", g.tallyRight <= w * s)
                    } else {
                        assertTrue("$away–$home at $w: GOAL ends ${g.wordRight}", g.wordRight <= w * s - g.padUsed)
                    }
                }
            }
        }
    }
}
