package app.thebox.ui.sports

import android.graphics.Paint
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.BarKind
import app.thebox.core.sports.CodeFit
import app.thebox.core.sports.NhlTeamColours
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (CHL commit 0, docs/design/m6-chl-leagues.md §6.4, Q4) The 4-letter-code measurements, taken through the real painters
 * with the bundled fonts at Standard (s = 1) and Large (s = 1.25), so 1 design px = 1 px. The codes are BRAM, FLNT and
 * NIAG (OHL), plus the QMJHL's "NFL" (L2). Every number is printed (the commit-0 record) and asserted against the §6.4
 * rule, so a later font or layout change that breaks a 4-letter fit fails here. A failure at the 18 px floor is a lead
 * escalation (design O3), never a display alias.
 *
 * The capsule and the goal-badge fallbacks are measured against the §6.4 limits as the lead ruled them (O3, review S1/S2:
 * a 4-letter code's fallback text may step down to 16 px), and the sizes WP-5's painters must draw are pinned. The capsule
 * is not drawn yet (WP-5), so its width is the §6.4 formula and its gap is to the score's measured ink.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChlCodeFitMeasureTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val bold = sansTypeface(context, R.font.instrument_sans_bold)
    private val italic = StackedPainter.barTypeface(context)
    private val barlow = sansTypeface(context, R.font.barlow_condensed_black_italic)

    private val sizes = listOf(false, true)
    private fun scale(large: Boolean) = if (large) K.SCORE_LARGE_SCALE else 1f
    private fun sz(large: Boolean) = if (large) "Large" else "Standard"

    /** Within-league partners for the takeover pairs (the widest 3-letter codes of the OHL, and the shortest). */
    private val partners = listOf("KIT", "LDN", "WSR", "SOO", "OSH", "ER", "OS", "NB")

    // ------------------------------------------------------------------ Pill: the power-play tab labels (§4.1 rule)

    private fun field(o: Any, name: String): Float = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.getFloat(o)

    @Test
    fun pillPowerPlayLabels() {
        for (large in sizes) {
            val s = scale(large)
            val pp = PowerPlayPainter(s, 1f, if (large) K.SCORE_LARGE_W else K.SCORE_W, if (large) K.SCORE_LARGE_H else K.SCORE_H, bold)
            val room = field(pp, "room")
            val extra = 2 * field(pp, "gap") + field(pp, "dividerW") + field(pp, "slotW")
            for (code in CODES) {
                val l = pp.buildLabels(code, "KIT", res)
                // 0 = "{CODE} POWER PLAY", 2 = "{CODE} 5-ON-3", 4 = "PP GOAL" (the Pill's PP GOAL label carries no code).
                for (i in listOf(0, 2, 4)) {
                    val block = l.w[i] + if (i == 4) 0f else extra
                    println(
                        "pill pp ${sz(large)} label $i \"$code\": %.0f px, tracked %s, block %.1f of room %.1f, slack %.1f"
                            .format(l.px[i], l.tracked[i], block, room, room - block),
                    )
                    assertTrue("$code label $i ${sz(large)}: px ${l.px[i]} under the floor", l.px[i] >= K.SCORE_TEXT_FLOOR_PX)
                    assertTrue("$code label $i ${sz(large)}: block $block > room $room (design O3)", block <= room + 0.01f)
                }
            }
        }
    }

    // ------------------------------------------------------------------ the logo fallbacks (P6; lead ruling on O3, S1, S2)

    private fun ink(p: Paint, text: String): Float = Rect().also { p.getTextBounds(text, 0, text.length, it) }.width().toFloat()

    /** The least gap, design px, from the capsule's right edge to the ink of the away score (the widest of 0–19). */
    private fun capsuleScoreGap(capsuleW: Float, large: Boolean): Float {
        val s = scale(large)
        val score = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = K.SCORE_TEXT_PX * s; textAlign = Paint.Align.CENTER }
        val cx = (K.SCORE_PAD + K.SCORE_LOGO_W + K.SCORE_LOGO_GAP + K.SCORE_DIGITS_W / 2f) * s // ScoreBugPainter's awayCx
        val capsuleRight = (K.SCORE_PAD + K.SCORE_LOGO_W / 2f) * s + capsuleW * s / 2f
        var least = Float.MAX_VALUE
        for (n in 0..19) {
            val t = n.toString()
            val b = Rect().also { score.getTextBounds(t, 0, t.length, it) }
            val inkLeft = cx - score.measureText(t) / 2f + b.left
            least = minOf(least, (inkLeft - capsuleRight) / s)
        }
        return least
    }

    /**
     * §6.4 as ruled: the capsule is `max(44, text + 2 × 10)` wide, may overhang the 64 px slot by ≤ 4 a side and keeps ≥ 6
     * from the score's ink. Its text steps down from 18 by 2 px; a 4-letter code may go to 16 ([CodeFit.fallbackFloorPx]).
     * The sizes WP-5's capsule must draw are pinned in [CAPSULE_PX].
     */
    @Test
    fun pillCapsuleFitsUnderTheRuledFloor() {
        for (large in sizes) {
            val got = LinkedHashMap<String, Int?>()
            for (code in CODES) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold }
                fun capsuleW(px: Int): Float { paint.textSize = px.toFloat(); return maxOf(K.LOGO_DISC_D, paint.measureText(code) + 2 * K.LOGO_CAPSULE_PAD) }
                fun overhang(px: Int) = ((capsuleW(px) - K.SCORE_LOGO_W) / 2f).coerceAtLeast(0f)
                val px = CodeFit.stepDown(K.LOGO_DISC_TEXT_PX, CodeFit.fallbackFloorPx(code)) {
                    overhang(it) <= K.LOGO_CAPSULE_OVERHANG_MAX && capsuleScoreGap(capsuleW(it), large) >= K.LOGO_CAPSULE_SCORE_GAP_MIN
                }
                for (at in listOf(K.LOGO_DISC_TEXT_PX, K.LOGO_FALLBACK_CODE4_FLOOR_PX)) {
                    println(
                        "pill capsule ${sz(large)} \"$code\" at $at px: text %.1f, capsule %.1f, overhang %.2f a side, gap to the score ink %.2f"
                            .format(paint.apply { textSize = at.toFloat() }.measureText(code), capsuleW(at), overhang(at), capsuleScoreGap(capsuleW(at), large)),
                    )
                }
                println("pill capsule ${sz(large)} \"$code\": fits at ${px ?: "none (O3)"} px")
                got[code] = px
            }
            assertEquals("capsule text px ${sz(large)} (WP-5 must draw these)", CAPSULE_PX, got)
        }
    }

    /**
     * (Review S1, lead ruling) The goal badge's fallback code: its **ink** must fit the badge slot (the Pill's 64 px logo
     * slot; the Stacked takeover's 67.5), stepping down from 24 by 2 px, a 4-letter code to 16. Pinned in [PILL_BADGE_PX] /
     * [STACKED_BADGE_PX] for WP-5's `GoalPainter` / `StackedGoalPainter`.
     */
    @Test
    fun goalBadgeFallbackTextFitsItsSlot() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold }
        for ((slot, expected) in listOf(K.SCORE_LOGO_W to PILL_BADGE_PX, K.STACKED_GOAL_LOGO to STACKED_BADGE_PX)) {
            val got = LinkedHashMap<String, Int?>()
            for (code in CODES) {
                val px = CodeFit.stepDown(K.GOAL_BADGE_TEXT_PX.toInt(), CodeFit.fallbackFloorPx(code)) {
                    paint.textSize = it.toFloat(); ink(paint, code) <= slot
                }
                paint.textSize = K.GOAL_BADGE_TEXT_PX
                val at24 = ink(paint, code)
                paint.textSize = (px ?: CodeFit.fallbackFloorPx(code)).toFloat()
                println("goal badge in slot %.1f \"$code\": ink %.1f at 24 px; fits at ${px ?: "none (O3)"} px (ink %.1f)".format(slot, at24, ink(paint, code)))
                got[code] = px
            }
            assertEquals("badge text px in slot $slot (WP-5 must draw these)", expected, got)
        }
    }

    // ------------------------------------------------------------------ Stacked: rows, bars, takeover

    @Test
    fun stackedRowsBothOrientations() {
        for (large in sizes) {
            val p = StackedPainter(scale(large), large, bold, italic)
            val s = scale(large)
            for (code in CODES) for (mirrored in listOf(false, true)) {
                var least = Float.MAX_VALUE
                for (score in 0..19) least = minOf(least, p.rowGap(code, score.toString(), mirrored) / s)
                val at19 = p.rowGap(code, "19", mirrored) / s
                println("stacked row ${sz(large)} \"$code\" mirrored=$mirrored: gap to \"19\" %.1f, least over 0..19 %.1f design px".format(at19, least))
                assertTrue("$code row ${sz(large)} mirrored=$mirrored: gap $least < 0 (design O3)", least >= 0f)
            }
        }
    }

    private fun bar(p: StackedPainter, kind: BarKind, side: Side, secs: Int, away: String, home: String): BarContent =
        BarContent().also {
            it.kind = kind; it.side = side; it.secs = secs; it.period = null
            p.fillBar(it, null, away, home, res)
        }

    @Test
    fun stackedBarStrings() {
        for (large in sizes) {
            val p = StackedPainter(scale(large), large, bold, italic)
            val s = scale(large)
            // A team bar is never narrower than 333 (rows + clock; StackedFitTest), so 301.5 is not measured for it.
            val team = (K.STACKED_ROWS_W + K.STACKED_CLOCK_W) * s
            val cases = listOf(BarKind.POWER_PLAY, BarKind.TWO_MAN).flatMap { k -> listOf(92, 19 * 60 + 59, 4 * 60 + 59, BarContent.NO_SECS).map { k to it } } +
                listOf(BarKind.PP_GOAL to BarContent.NO_SECS)
            for (code in CODES) for (side in Side.entries) {
                for ((kind, secs) in cases) {
                    val c = if (side == Side.AWAY) bar(p, kind, side, secs, code, "KIT") else bar(p, kind, side, secs, "KIT", code)
                    val text = p.barText(c)
                    for (w in listOf(team)) {
                        val right = p.barTextRight(c)
                        val limit = w - K.STACKED_HAIRLINE_W * s
                        val ink = p.barTextInkMirrored(c, w)
                        println(
                            "stacked bar ${sz(large)} \"$text\" in %.1f: right %.1f of %.1f (slack %.1f); mirrored ink %.1f..%.1f (left slack %.1f, right limit %.1f)"
                                .format(w, right, limit, limit - right, ink.left, ink.right, ink.left - K.STACKED_HAIRLINE_W * s, w - 59f * s),
                        )
                        assertTrue("\"$text\" ${sz(large)}: right $right > $limit (design O3)", right <= limit)
                        assertTrue("\"$text\" ${sz(large)} mirrored: left ${ink.left}", ink.left >= K.STACKED_HAIRLINE_W * s)
                        assertTrue("\"$text\" ${sz(large)} mirrored: right ${ink.right}", ink.right <= w - 59f * s)
                    }
                }
            }
        }
    }

    @Test
    fun stackedTakeoverBadgeAndTally() {
        val widths = listOf(K.STACKED_ROWS_W + K.STACKED_SOG_W, K.STACKED_ROWS_W + K.STACKED_CLOCK_W, K.STACKED_ROWS_W + K.STACKED_SOG_W + K.STACKED_CLOCK_W)
        for (large in sizes) {
            val s = scale(large)
            val g = StackedGoalPainter(s, large, barlow, bold, res.getString(R.string.sports_goal_word), StackedFixtures.SPEC)
            for (w in widths) {
                var dropped = 0
                var n = 0
                for (code in CODES) for (other in partners) for (side in Side.entries) {
                    val away = if (side == Side.AWAY) code else other
                    val home = if (side == Side.HOME) code else other
                    g.begin(side, NhlTeamColours.FALLBACK, null, away, home, 19, 18, w)
                    n++
                    assertTrue("badge $away–$home ${sz(large)} at $w", g.badgeLeft >= 0f && g.badgeRight <= g.wordLeft)
                    if (g.tallyShown) {
                        assertTrue("$away–$home ${sz(large)} at $w: GOAL ${g.wordRight} vs tally ${g.tallyLeft}", g.wordRight <= g.tallyLeft)
                        assertTrue("$away–$home ${sz(large)} at $w: tally ends ${g.tallyRight} of ${w * s}", g.tallyRight <= w * s)
                    } else {
                        dropped++
                        assertTrue("$away–$home ${sz(large)} at $w: GOAL ends ${g.wordRight}", g.wordRight <= w * s - g.padUsed)
                    }
                }
                println("stacked takeover ${sz(large)} at $w: tally dropped in $dropped of $n (4-letter and NFL codes, 19–18)")
            }
        }
    }

    private companion object {
        val CODES = listOf("BRAM", "FLNT", "NIAG", "NFL")
        /** The capsule text px per code at both sizes (pinned from this measurement; WP-5's capsule must match). */
        val CAPSULE_PX: Map<String, Int?> = mapOf("BRAM" to 16, "FLNT" to 18, "NIAG" to 18, "NFL" to 18)
        val PILL_BADGE_PX: Map<String, Int?> = mapOf("BRAM" to 22, "FLNT" to 24, "NIAG" to 24, "NFL" to 24)
        val STACKED_BADGE_PX: Map<String, Int?> = mapOf("BRAM" to 22, "FLNT" to 24, "NIAG" to 24, "NFL" to 24)
    }
}
