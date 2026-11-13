package app.thebox.ui.sports

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.withClip
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import app.thebox.core.sports.BarKind
import app.thebox.core.sports.ClockSlot
import app.thebox.core.sports.GoalFrame
import app.thebox.core.sports.NhlStackedColours
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedColours
import app.thebox.core.sports.StackedFrame
import app.thebox.core.sports.StackedMock as M
import app.thebox.core.sports.StackedPair
import app.thebox.core.sports.StackedTargets
import app.thebox.ui.R
import app.thebox.ui.theme.DesignTokens
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * (Stacked §4.2, §6; Stacked WP-2) The Stacked box's whole drawing in plain `android.graphics`, for one size: the rows,
 * the SOG and clock columns, the bar, the border, the nine-slice drop shadow, and (through [StackedGoalPainter]) the
 * takeover. [s] is device px per design px with the Large factor folded in (Large = density × `SCORE_LARGE_SCALE`);
 * [large] picks the Large-only constants (`STACKED_SHADOW_SRC_LARGE`). [sansBold] is Instrument Sans Bold
 * (`R.font.instrument_sans_bold`); [barFont] is the real Instrument Sans Bold Italic
 * (`sansTypeface(context, R.font.instrument_sans_bold_italic)`, §11), and when it is null the bar uses [sansBold] with
 * `textSkewX = STACKED_FAUX_ITALIC_SKEW_X` (N2's fallback).
 *
 * Built off the main thread by the runner (Stacked WP-3), once per size; [drawFrame] allocates nothing (§6, GA §2.5);
 * no `clipPath`, no `saveLayer`, no `setShadowLayer`, no real-time blur.
 *
 * **The mock** (`design/nhl-stacked/Stacked.dc.html`) at × `STACKED_SCALE`, with the lead's amendments (bar text and
 * tally 20 px, no team-name line, team-true colours):
 * - the box: fill `#15191F` (the columns show it), border 1 `#2B3550`, radius 2.7, shadow 0 / 5.4 / σ 8.1 / .55;
 * - each row 243 × 54 on `#12284F`: the stripe (3.6, 6.3 on the power play; the logo and code move with it, as the
 *   flex row does), the logo slot (margin 9.9, 50.4 wide, logo 43.2 tall), the code (padding 13.5, 27 px), the score
 *   (right-aligned in a 45.9 slot, 15.3 from the row's end, 35.1 px, tabular); a 1-px white .22 divider under the away
 *   row;
 * - the SOG column (58.5): an inset line, the digits (24.3 px) centred on each row, "SOG" (18 px, `#C3C7D0`, tracking
 *   .08 em) on the row line; its contents fade and slide by `sogDx` and are revealed by a `clipRect` of the open width;
 * - the clock column (90): an inset line, the period (21.6 px, tracking .02 em; its line box ends 6.3 above the 50.4
 *   slot's bottom), a 1-px `#5B6170` rule 11.7 in from each side, the clock (28.8 px) or the slot text centred below;
 * - the bar (33): its own background, two skewed stripes (−30°, 11.7 wide, 4.5 overhang, at 20.7 / 37.8), the words
 *   (20 px italic, tracking .01 em, at 63, the time 11.7 after the label), a 1-px white .22 hairline on its inner edge.
 *   Its contents are anchored to its outer edge, so they drop in with the leading edge.
 *
 * Rounded corners: only the regions that touch a box corner (the row backgrounds, the row stripes, the bar) are a
 * `clipRect` of the region over a `drawRoundRect` of the whole box (N3); the columns are the box fill itself.
 */
internal class StackedPainter(
    val s: Float,
    val large: Boolean,
    private val sansBold: Typeface?,
    private val barFont: Typeface?,
) {
    private val white = Color.WHITE
    private val accent = DesignTokens.Colors.Accent.toArgb()

    // ---- geometry, device px
    private val h = K.STACKED_H * s
    private val rowH = h / 2f
    private val rowsW = K.STACKED_ROWS_W * s
    private val radius = M.BOX_RADIUS * K.STACKED_SCALE * s
    private val line = (K.STACKED_HAIRLINE_W * s).coerceAtLeast(1f)
    private val stripeW = M.STRIPE_W * K.STACKED_SCALE * s
    private val ppStripeW = M.PP_STRIPE_W * K.STACKED_SCALE * s
    private val logoMl = M.LOGO_ML * K.STACKED_SCALE * s
    private val logoSlotW = M.LOGO_SLOT_W * K.STACKED_SCALE * s
    private val logoH = M.LOGO_H * K.STACKED_SCALE * s
    private val codePl = M.CODE_PL * K.STACKED_SCALE * s
    private val scoreSlotW = M.SCORE_SLOT_W * K.STACKED_SCALE * s
    private val scoreRight = rowsW - M.SCORE_MR * K.STACKED_SCALE * s
    private val sogW = K.STACKED_SOG_W * s
    private val clockW = K.STACKED_CLOCK_W * s
    private val periodBottom = (M.PERIOD_SLOT_H - M.PERIOD_PAD_B) * K.STACKED_SCALE * s
    private val ruleTop = M.PERIOD_SLOT_H * K.STACKED_SCALE * s
    private val ruleMargin = M.RULE_MARGIN * K.STACKED_SCALE * s
    private val clockCy = (ruleTop + line + h) / 2f
    private val barH = K.STACKED_BAR_H * s
    private val barStripeAL = M.BAR_STRIPE_A_L * K.STACKED_SCALE * s
    private val barStripeBL = M.BAR_STRIPE_B_L * K.STACKED_SCALE * s
    private val barStripeW = M.BAR_STRIPE_W * K.STACKED_SCALE * s
    private val barOverhang = M.BAR_STRIPE_OVERHANG * K.STACKED_SCALE * s
    private val barTextL = M.BAR_TEXT_PL * K.STACKED_SCALE * s
    private val barTextGap = M.BAR_TEXT_GAP * K.STACKED_SCALE * s

    // ---- paints (built once; draw only changes colour, alpha and size)
    private fun text(px: Float, tf: Typeface?, align: Paint.Align, track: Float = 0f, tnum: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = px * s; typeface = tf; color = white; textAlign = align
            if (track != 0f) letterSpacing = track
            if (tnum) fontFeatureSettings = "'tnum'"
        }

    private val codePaint = text(M.CODE_PX * K.STACKED_SCALE, sansBold, Paint.Align.LEFT, CODE_TRACK_EM)
    private val scorePaint = text(M.SCORE_PX * K.STACKED_SCALE, sansBold, Paint.Align.RIGHT, tnum = true)
    private val sogPaint = text(M.SOG_DIGITS_PX * K.STACKED_SCALE, sansBold, Paint.Align.CENTER, tnum = true)
    private val sogLabelPaint = text(K.STACKED_SOG_LABEL_PX, sansBold, Paint.Align.CENTER, M.SOG_LABEL_TRACK_EM)
    private val periodPaint = text(M.PERIOD_PX * K.STACKED_SCALE, sansBold, Paint.Align.CENTER, PERIOD_TRACK_EM)
    private val slotPaint = text(M.CLOCK_PX * K.STACKED_SCALE, sansBold, Paint.Align.CENTER)
    // (Fix pass 2026-11-11, review U B1) Tabular figures, as the mock's `font-variant-numeric: tabular-nums` and §4.2's
    // "64 / 700 tabular": Instrument Sans Bold's proportional "0" is 0.682 em, its `tnum` digits are all 0.600 em.
    private val clockPaint = text(M.CLOCK_PX * K.STACKED_SCALE, sansBold, Paint.Align.LEFT, tnum = true)
    private val barPaint = text(K.STACKED_BAR_TEXT_PX, barFont ?: sansBold, Paint.Align.LEFT, BAR_TRACK_EM).apply {
        if (barFont == null) textSkewX = K.STACKED_FAUX_ITALIC_SKEW_X
    }
    private val barTimePaint = Paint(barPaint).apply { fontFeatureSettings = "'tnum'" }
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansBold }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.BLACK; alpha = (K.STACKED_SHADOW_ALPHA * 255f).roundToInt()
    }
    private val fm = Paint.FontMetrics()
    private val bounds = Rect()
    private val disc = LogoDisc(s, sansBold)

    private val box = RectF()
    private val logoDst = RectF()
    /** A bar stripe: the CSS `skewX(−30deg)` parallelogram, its unskewed left edge at x = 0, its top at y = 0. */
    private val stripePath = Path()

    private val scoreOffset: Float
    private val sogOffset: Float
    private val sogLabelOffset: Float
    private val codeOffset: Float
    private val barOffset: Float

    // ---- the clock's digit table (tabular: a new value never moves a digit; no allocation per tick)
    /** The clock's size in design px: 28.8, stepped down only if "00:00" would not fit the column. */
    val clockPx: Float
    private val digitAdvance = FloatArray(10)
    private val digitCell: Float
    private val colonAdvance: Float
    private val clockCap: Float
    private val clockBuf = CharArray(8)
    private val barSecsBuf = CharArray(8)
    /** The lower slot of the clock column (below the rule), device px: its height, for a two-line slot string. */
    private val slotH = h - (ruleTop + line)
    /** (Review U N2) The SOG label, resolved from `sports_stacked_sog` by [frame]; "SOG" until the first frame. */
    private var sogLabel = "SOG"

    /** The fallback look (a null [StackedDrawArgs.colours]), resolved once. */
    private val fallback: StackedPair = NhlStackedColours.pair(null, null)

    init {
        scorePaint.getTextBounds("0", 0, 1, bounds)
        scoreOffset = -(bounds.top + bounds.bottom) / 2f
        sogPaint.getTextBounds("0", 0, 1, bounds)
        sogOffset = -(bounds.top + bounds.bottom) / 2f
        sogLabelPaint.getTextBounds("S", 0, 1, bounds)
        sogLabelOffset = -(bounds.top + bounds.bottom) / 2f
        codePaint.getFontMetrics(fm)
        codeOffset = -(fm.ascent + fm.descent) / 2f
        barPaint.getFontMetrics(fm)
        barOffset = -(fm.ascent + fm.descent) / 2f

        var px = M.CLOCK_PX * K.STACKED_SCALE
        fun widest(): Float {
            clockPaint.textSize = px * s
            var w = 0f
            for (d in 0..9) w = maxOf(w, clockPaint.measureText(DIGITS, d, d + 1))
            return 4 * w + clockPaint.measureText(":")
        }
        while (px - K.STACKED_FIT_STEP_PX >= K.STACKED_FIT_MIN_PX && widest() > clockBudget()) px -= K.STACKED_FIT_STEP_PX
        clockPx = px
        clockPaint.textSize = px * s
        var cell = 0f
        for (d in 0..9) {
            digitAdvance[d] = clockPaint.measureText(DIGITS, d, d + 1)
            cell = maxOf(cell, digitAdvance[d])
        }
        digitCell = cell
        colonAdvance = clockPaint.measureText(":")
        clockPaint.getTextBounds("0", 0, 1, bounds)
        clockCap = -(bounds.top + bounds.bottom) / 2f

        val sh = barH + 2 * barOverhang
        val k = tan(Math.toRadians(-K.STACKED_BAR_SKEW_DEG.toDouble())).toFloat() * sh / 2f
        val w = barStripeW
        stripePath.moveTo(k, 0f)
        stripePath.lineTo(k + w, 0f)
        stripePath.lineTo(w - k, sh)
        stripePath.lineTo(-k, sh)
        stripePath.close()
    }

    /** The clock column's budget for words (ink), device px: the column less [WORD_BREATHING] a side. */
    private fun columnBudget(): Float = clockW - 2 * WORD_BREATHING * s

    /** The clock column's budget for the clock (tabular advance), device px: the column less [CLOCK_BREATHING] a side. */
    private fun clockBudget(): Float = clockW - 2 * CLOCK_BREATHING * s

    // ------------------------------------------------------------------ words and fit (§4.2 fit rules)

    /**
     * [startPx] stepped down by `STACKED_FIT_STEP_PX` until [text] fits the clock column (less [COLUMN_BREATHING] a
     * side), never below `STACKED_FIT_MIN_PX`; no ellipsis. Design px.
     */
    fun fitColumn(text: String, startPx: Float, track: Float = 0f): Float {
        var px = startPx
        measurePaint.letterSpacing = track
        while (px > K.STACKED_FIT_MIN_PX) {
            if (inkWidth(text, px) <= columnBudget()) break
            px -= K.STACKED_FIT_STEP_PX
        }
        return px.coerceAtLeast(K.STACKED_FIT_MIN_PX)
    }

    /**
     * [text]'s ink width at [px] design px in [measurePaint] (device px). Centred text is placed by its advance, whose
     * side bearings are symmetric enough that the ink is what must clear the column's edges.
     */
    private fun inkWidth(text: String, px: Float): Float {
        measurePaint.textSize = px * s
        val r = Rect()
        measurePaint.getTextBounds(text, 0, text.length, r)
        return r.width().toFloat()
    }

    /**
     * (Fix pass 2026-11-11, review U S1) The lower slot's word ("END", "INT", "Delayed", "No update"), fitted. One line
     * by [fitColumn]; but a string with a space that does not fit on one line at the start size breaks at its **first**
     * space onto two lines at one fitted size (each line's ink inside the column's breathing, the block's ink inside the
     * slot's), the block centred on the slot, whenever that gives a larger size than one line or the only fit. The words
     * are never changed or shortened (§8: the slot stays the slot). Called once per shown view, never per frame.
     */
    fun fitSlot(text: String): SlotFit {
        val start = M.CLOCK_PX * K.STACKED_SCALE
        if (text.isEmpty()) return SlotFit(start)
        val one = fitColumn(text, start)
        val oneFits = inkWidth(text, one) <= columnBudget()
        val brk = text.indexOf(' ')
        if (brk <= 0 || brk == text.length - 1 || (oneFits && one >= start)) return SlotFit(one)
        val a = text.substring(0, brk)
        val b = text.substring(brk + 1)
        measurePaint.letterSpacing = 0f
        val ra = Rect()
        val rb = Rect()
        var px = start
        while (true) {
            measurePaint.textSize = px * s
            measurePaint.getTextBounds(a, 0, a.length, ra)
            measurePaint.getTextBounds(b, 0, b.length, rb)
            val pitch = px * s * SLOT_LINE_PITCH_EM
            val block = pitch + rb.bottom - ra.top
            if (maxOf(ra.width(), rb.width()) <= columnBudget() && block <= slotH - 2 * WORD_BREATHING * s) {
                if (oneFits && one >= px) return SlotFit(one)
                // The baselines relative to the slot's centre (design px): the block's ink is centred on it.
                val dy1 = (-block / 2f - ra.top) / s
                return SlotFit(px, brk, dy1, dy1 + pitch / s)
            }
            if (px <= K.STACKED_FIT_MIN_PX) return SlotFit(one) // two lines fit nowhere: one line at the floor
            px = (px - K.STACKED_FIT_STEP_PX).coerceAtLeast(K.STACKED_FIT_MIN_PX)
        }
    }

    /**
     * (WP-2) The view's resolved words for [t] (`StackedTargets.of(v)`), fitted at this size (§4.2: clock-column strings
     * step down by `STACKED_FIT_STEP_PX` to `STACKED_FIT_MIN_PX`). Called once per shown view, never per frame.
     */
    fun frame(v: ScoreBugView, t: StackedTargets, res: Resources): StackedFrameState {
        val periodText = t.period?.let { SportsText.period(res, it).uppercase(Locale.ROOT) } ?: ""
        val slotText = when (t.clockSlot) {
            ClockSlot.Clock, ClockSlot.Blank -> ""
            ClockSlot.End -> res.getString(R.string.sports_stacked_end)
            ClockSlot.Intermission -> res.getString(R.string.sports_stacked_int)
            ClockSlot.NoUpdate -> res.getString(R.string.sports_no_update)
            ClockSlot.Delayed -> res.getString(R.string.sports_delayed)
        }
        val periodStart = M.PERIOD_PX * K.STACKED_SCALE
        val slotStart = M.CLOCK_PX * K.STACKED_SCALE
        sogLabel = res.getString(R.string.sports_stacked_sog)
        val fit = if (t.clockSlot == ClockSlot.Clock || slotText.isEmpty()) null else fitSlot(slotText)
        return StackedFrameState(
            awayAbbrev = v.away.abbrev,
            homeAbbrev = v.home.abbrev,
            awayScore = (v.away.score ?: 0).toString(),
            homeScore = (v.home.score ?: 0).toString(),
            awaySog = v.away.sog?.toString() ?: "",
            homeSog = v.home.sog?.toString() ?: "",
            periodText = periodText,
            periodPx = if (periodText.isEmpty()) periodStart else fitColumn(periodText, periodStart, PERIOD_TRACK_EM),
            slot = t.clockSlot,
            slotText = slotText,
            slotPx = when {
                t.clockSlot == ClockSlot.Clock -> clockPx
                fit == null -> slotStart
                else -> fit.px
            },
            clockSecs = v.clockSecs ?: 0,
            columnDim = t.columnDim,
            targets = t,
            slotBreak = fit?.brk ?: -1,
            slotDy1 = fit?.dy1 ?: 0f,
            slotDy2 = fit?.dy2 ?: 0f,
        )
    }

    /**
     * (WP-2) Resolves [c]'s words ([BarContent.chars] / [BarContent.len], upper case, §8 notes) and colours
     * ([BarContent.bg], [BarContent.stripeA], [BarContent.stripeB], §5.1) from its kind, side, seconds and period, the
     * game's [pair] (null = the fallback look) and the abbreviations. Called by the runner when it captures a bar content
     * (an open, a change, a text swap, or a seconds update), never per frame.
     *
     * A power play's time ("1:37") follows its label after one space; [drawFrame] draws it tabular, 11.7 after the label
     * (the mock's `gap:26px`).
     */
    fun fillBar(c: BarContent, pair: StackedPair?, awayAbbrev: String, homeAbbrev: String, res: Resources) {
        val p = pair ?: fallback
        val team: StackedColours? = when (c.side) {
            Side.AWAY -> p.away
            Side.HOME -> p.home
            null -> null
        }
        val abbrev = if (c.side == Side.HOME) homeAbbrev else awayAbbrev
        val words = when (c.kind) {
            BarKind.POWER_PLAY -> res.getString(R.string.sports_pp_label, abbrev) + time(c.secs)
            BarKind.TWO_MAN -> res.getString(R.string.sports_pp_two_man, abbrev) + time(c.secs)
            BarKind.PP_GOAL -> res.getString(R.string.sports_stacked_pp_goal, abbrev)
            BarKind.INTERMISSION -> {
                val period = c.period?.let { SportsText.period(res, it) } ?: ""
                res.getString(R.string.sports_stacked_intermission, period).trim()
            }
            BarKind.SHOOTOUT -> res.getString(R.string.sports_stacked_shootout)
            BarKind.FINAL -> SportsText.final(res, c.period?.kind)
            BarKind.NO_UPDATE -> res.getString(R.string.sports_stacked_no_update)
            BarKind.DELAYED -> res.getString(R.string.sports_stacked_delayed)
        }.uppercase(Locale.ROOT)
        val n = minOf(words.length, BarContent.MAX_CHARS)
        words.toCharArray(c.chars, 0, 0, n)
        c.len = n
        // (Review U N3) The label's advance, measured once here rather than on every frame.
        val split = timeSplit(c)
        c.labelAdvance = barPaint.measureText(c.chars, 0, if (split < 0) c.len else split)
        c.wordsAdvance = c.labelAdvance +
            if (split < 0) 0f else barTextGap + barTimePaint.measureText(c.chars, split + 1, c.len - split - 1)
        if (isTeamKind(c.kind) && team != null) {
            c.bg = team.barBg
            c.stripeA = team.barA
            c.stripeB = team.barB
        } else {
            c.bg = K.STACKED_BAR_NEUTRAL
            c.stripeA = p.away.stripe
            c.stripeB = p.home.stripe
        }
    }

    /** " m:ss" for a power play's [secs], "" when unknown. Allocates (called from [fillBar] only). */
    private fun time(secs: Int): String {
        if (secs == BarContent.NO_SECS || secs < 0) return ""
        val n = clockChars(secs, barSecsBuf)
        return " " + String(barSecsBuf, 0, n)
    }

    /**
     * (WP-2, §6 B4) The drop shadow for this size: one `ALPHA_8` nine-slice mask (`STACKED_SHADOW_SIGMA`, source
     * ≥ 6σ + 1), baked on a software canvas. Off the main thread; once per size.
     */
    fun bakeShadow(): NineSlice? = NineSlice(s, large)

    // ------------------------------------------------------------------ the composite

    /**
     * (WP-2) The whole Stacked bug for one frame, in the coordinates of [StackedDrawArgs] (origin = the top part's
     * top-left, device px). Order: glow (when [StackedDrawArgs.glowOn]), shadow, bar, rows (stripes, logos, codes,
     * scores, the row flash and the scorer's pop), SOG column, clock column, the takeover over the top part, then the
     * border (the mock's border is outside the box's content, so the takeover never covers it) and the place ring.
     * Allocation-free.
     */
    fun drawFrame(c: Canvas, a: StackedDrawArgs) {
        val body = a.body ?: return
        val f = a.f ?: return
        val pair = a.colours ?: fallback
        val w = f.boxW * s
        val bh = if (f.barClosed) 0f else f.barH * s
        val top = if (a.up) -bh else 0f
        val bottom = if (a.up) h else h + bh
        // (Owner request 2026-11-13) Mirrored, the rows stay at [0, rowsW] and the columns open to their left.
        val mirrored = a.mirrored
        val l = if (mirrored) rowsW - w else 0f
        box.set(l, top, l + w, bottom)

        val goal = a.goal
        val g = a.g
        val takeover = goal != null && g != null && !g.done && goal.colours != null

        // 1. The glow, around the whole box (the bar included), from the exact-box masks.
        if (takeover && a.glowOn) a.glow?.let { goal!!.drawGlow(c, g!!, it, l, top) }
        // 2. The drop shadow, stretched to the box as it is now (left-right symmetric, so one bake serves both sides).
        a.shadow?.draw(c, l, top, l + w, bottom, shadowPaint)
        // 3. The box fill: the columns' `#15191F` (and the bar's corners under its own fill).
        fill.color = K.STACKED_COL_BG
        c.drawRoundRect(box, radius, radius, fill)
        // 4. The bar.
        if (bh > 0f) drawBar(c, a, f, bh, l, l + w)
        // 5. The rows.
        drawRows(c, a, body, f, pair, if (takeover) goal else null, if (takeover) g else null)
        // 6. The SOG column.
        if (f.sogWidth > 0f) drawSog(c, body, f, mirrored)
        // 7. The clock column.
        if (f.clockWidth > 0f) drawClockColumn(c, body, f, mirrored)
        // 8. The takeover over the top part (its own coordinates start at the top part's left edge).
        if (takeover) {
            goal!!.extent(top, bottom)
            if (mirrored) c.withTranslation(l, 0f) { goal.drawTakeover(this, g!!, true) } else goal.drawTakeover(c, g!!)
        }
        // 9. The border, 1 px `#2B3550`, inside the box's edge.
        stroke.strokeWidth = line
        stroke.color = K.STACKED_BORDER
        box.inset(line / 2f, line / 2f)
        c.drawRoundRect(box, radius - line / 2f, radius - line / 2f, stroke)
        box.inset(-line / 2f, -line / 2f)
        // 10. Place mode's ring (as the pill's).
        if (a.placing) {
            val rw = K.PLACE_RING_W * s
            stroke.strokeWidth = rw
            stroke.color = accent
            box.inset(-rw / 2f, -rw / 2f)
            c.drawRoundRect(box, radius + rw / 2f, radius + rw / 2f, stroke)
            box.inset(rw / 2f, rw / 2f)
        }
    }

    /**
     * The bar ([l]..[r], device px): background (lerped through a change), the two stripes, the words, the hairline, under
     * its clip. Mirrored, the stripes and the words are anchored to the bar's right end (the rows' outer edge) and move
     * the other way; the stripes keep their "/" lean and the words read left to right, right-aligned.
     */
    private fun drawBar(c: Canvas, a: StackedDrawArgs, f: StackedFrame, bh: Float, l: Float, r: Float) {
        val cur = a.bar ?: return
        val prev = a.barPrev
        val words = if (!f.barSwapped && prev != null) prev else cur
        val bg = if (prev != null && f.barBgMix < 1f) lerpColour(prev.bg, cur.bg, f.barBgMix) else cur.bg
        val regionTop = if (a.up) -bh else h
        val regionBottom = regionTop + bh
        // Contents are anchored to the bar's outer edge: bottom-anchored below the box, top-anchored above it.
        val contentTop = if (a.up) regionTop else regionBottom - barH
        val m = a.mirrored
        c.withClip(l, regionTop, r, regionBottom) {
            fill.color = bg or OPAQUE
            drawRoundRect(box, radius, radius, fill)
            val stripeTop = contentTop - barOverhang
            fill.color = words.stripeA or OPAQUE
            val ax = if (m) r - barStripeAL - barStripeW - f.stripeAX * s else barStripeAL + f.stripeAX * s
            withTranslation(ax, stripeTop) { drawPath(stripePath, fill) }
            fill.color = words.stripeB or OPAQUE
            val bx = if (m) r - barStripeBL - barStripeW - f.stripeBX * s else barStripeBL + f.stripeBX * s
            withTranslation(bx, stripeTop) { drawPath(stripePath, fill) }
            val alpha = f.barTextAlpha * (if (words.dim) K.STACKED_DIM_ALPHA else 1f)
            val tx = if (m) r - barTextL - f.barTextDx * s - words.wordsAdvance else barTextL + f.barTextDx * s
            if (alpha > 0f && words.len > 0) drawBarWords(this, words, contentTop + barH / 2f + barOffset, tx, alpha)
            // The hairline on the bar's inner edge (S2: every bar).
            fill.color = argb(K.STACKED_BAR_HAIRLINE_ALPHA, 0xFFFFFF)
            if (a.up) drawRect(l, regionBottom - line, r, regionBottom, fill) else drawRect(l, regionTop, r, regionTop + line, fill)
        }
    }

    /** The bar's words at ([x], [baseline]): the label, then a power play's time (tabular) 11.7 after it; hard shadows. */
    private fun drawBarWords(c: Canvas, b: BarContent, baseline: Float, x: Float, alpha: Float) {
        val split = timeSplit(b)
        val labelEnd = if (split < 0) b.len else split
        val dy = K.STACKED_BAR_TEXT_SHADOW_DY * s
        val shadow = argb(alpha * K.STACKED_TEXT_SHADOW_ALPHA, 0x000000)
        val text = argb(alpha, 0xFFFFFF)
        barPaint.color = shadow
        c.drawText(b.chars, 0, labelEnd, x, baseline + dy, barPaint)
        barPaint.color = text
        c.drawText(b.chars, 0, labelEnd, x, baseline, barPaint)
        if (split >= 0) {
            val tx = x + b.labelAdvance + barTextGap
            barTimePaint.color = shadow
            c.drawText(b.chars, split + 1, b.len - split - 1, tx, baseline + dy, barTimePaint)
            barTimePaint.color = text
            c.drawText(b.chars, split + 1, b.len - split - 1, tx, baseline, barTimePaint)
        }
    }

    /** The space before a power play's time in [b] (its last space), or −1 when the bar has no time. */
    private fun timeSplit(b: BarContent): Int {
        if (b.secs == BarContent.NO_SECS || b.secs < 0) return -1
        if (b.kind != BarKind.POWER_PLAY && b.kind != BarKind.TWO_MAN) return -1
        for (i in b.len - 1 downTo 0) if (b.chars[i] == ' ') return i
        return -1
    }

    private fun drawRows(
        c: Canvas,
        a: StackedDrawArgs,
        body: StackedFrameState,
        f: StackedFrame,
        pair: StackedPair,
        goal: StackedGoalPainter?,
        g: GoalFrame?,
    ) {
        // The row background (its left corners are the box's).
        c.withClip(0f, 0f, rowsW, h) {
            fill.color = K.STACKED_ROW_BG
            drawRoundRect(box, radius, radius, fill)
            // The row flash (§7.2): the scorer's stripe colour over its row, at the envelope × the C7 peak (or the still
            // version's fixed tint), under the stripe and the row's contents (it is the row's background in the mock).
            if (goal != null && g != null && g.rowFlashAlpha > 0f) {
                val side = goal.side
                val sc = if (side == Side.AWAY) pair.away else pair.home
                val peak = if (a.still) K.STACKED_STILL_ROW_TINT else sc.rowFlashAlpha
                fill.color = argb(g.rowFlashAlpha * peak, sc.stripe)
                val y0 = if (side == Side.AWAY) 0f else rowH
                withClip(0f, y0, rowsW, y0 + rowH) { drawRoundRect(box, radius, radius, fill) }
            }
        }
        val awayStripe = lerp(stripeW, ppStripeW, f.stripeAway)
        val homeStripe = lerp(stripeW, ppStripeW, f.stripeHome)
        // The stripes are on the rows' outer edge: the left, or the right when mirrored.
        val m = a.mirrored
        fill.color = pair.away.stripe or OPAQUE
        if (m) {
            c.withClip(rowsW - awayStripe, 0f, rowsW, rowH) { drawRoundRect(box, radius, radius, fill) }
        } else {
            c.withClip(0f, 0f, awayStripe, rowH) { drawRoundRect(box, radius, radius, fill) }
        }
        fill.color = pair.home.stripe or OPAQUE
        if (m) {
            c.withClip(rowsW - homeStripe, rowH, rowsW, h) { drawRoundRect(box, radius, radius, fill) }
        } else {
            c.withClip(0f, rowH, homeStripe, h) { drawRoundRect(box, radius, radius, fill) }
        }
        // The divider under the away row (its border-bottom, across the stripe too).
        fill.color = argb(K.STACKED_BAR_HAIRLINE_ALPHA, 0xFFFFFF)
        c.drawRect(0f, rowH - line, rowsW, rowH, fill)

        val popSide = if (goal != null && g != null && g.popOn) goal.side else null
        val popColour = if (popSide != null) lerpColour(goal!!.colours!!.pop, white, g!!.popMix) else white
        val popScale = g?.popScale ?: 1f
        row(c, rowH / 2f, awayStripe, body.awayAbbrev, a.logos?.away, a, body.awayScore, popSide == Side.AWAY, popScale, popColour)
        row(c, rowH * 1.5f, homeStripe, body.homeAbbrev, a.logos?.home, a, body.homeScore, popSide == Side.HOME, popScale, popColour)
    }

    /**
     * One row's logo, code and score, centred on [cy]. The contents are one left-to-right block beside the stripe
     * ([stripe] wide): after it (the logo and code move with it, the score keeps its place) or, mirrored, before it (the
     * reading order kept; the score moves with the stripe, the logo keeps its place).
     */
    private fun row(
        c: Canvas, cy: Float, stripe: Float, abbrev: String, slot: FollowLogos.Slot?, a: StackedDrawArgs,
        score: String, pop: Boolean, popScale: Float, popColour: Int,
    ) {
        val start = if (a.mirrored) 0f else stripe
        val scoreRight = if (a.mirrored) this.scoreRight - stripe else this.scoreRight
        val logoCx = start + logoMl + logoSlotW / 2f
        logo(c, logoCx, cy, abbrev, slot, a.logos != null, a.logoWaited)
        codePaint.color = white
        c.drawText(abbrev, start + logoMl + logoSlotW + codePl, cy + codeOffset, codePaint)
        if (!pop) {
            scorePaint.color = white
            c.drawText(score, scoreRight, cy + scoreOffset, scorePaint)
        } else {
            // The pop scales the score's box (the 45.9 slot) about its centre, as the CSS transform does.
            scorePaint.color = popColour
            val cx = scoreRight - scoreSlotW / 2f
            c.withScale(popScale, popScale, cx, cy) { drawText(score, scoreRight, cy + scoreOffset, scorePaint) }
            scorePaint.color = white
        }
    }

    /**
     * The logo (the takeover-size bitmap, S6, drawn down to 43.2 tall and at most the 50.4 slot wide, `FILTER_BITMAP`), or
     * the fallback disc as the pill decides it (no holder, failed, or waited past `LOGO_WAIT_MS`).
     */
    private fun logo(c: Canvas, cx: Float, cy: Float, abbrev: String, slot: FollowLogos.Slot?, hasHolder: Boolean, waited: Boolean) {
        val img = slot?.image
        if (img != null) {
            val b: Bitmap = img.asAndroidBitmap()
            val k = minOf(logoH / b.height.coerceAtLeast(1), logoSlotW / b.width.coerceAtLeast(1))
            val lw = b.width * k
            val lh = b.height * k
            logoDst.set(cx - lw / 2f, cy - lh / 2f, cx + lw / 2f, cy + lh / 2f)
            c.drawBitmap(b, null, logoDst, bmpPaint)
        } else if (!hasHolder || slot!!.failed || waited) {
            disc.draw(c, cx, cy, abbrev)
        }
    }

    /**
     * The SOG column beside the rows: its contents are anchored to the rows' edge (the inset line there) and revealed by a
     * clip of the open width, so it opens away from the rows: rightwards, or leftwards when [mirrored].
     */
    private fun drawSog(c: Canvas, body: StackedFrameState, f: StackedFrame, mirrored: Boolean) {
        val open = f.sogWidth * sogW
        val cl = if (mirrored) -open else rowsW
        c.withClip(cl, 0f, cl + open, h) {
            val alpha = f.sogAlpha
            if (alpha <= 0f) return@withClip
            // The contents' left edge. Mirrored, the column's right edge is the rows' left edge (0), and sogDx moves the
            // contents the other way (toward the rows on both sides).
            val x = if (mirrored) -sogW - f.sogDx * s else rowsW + f.sogDx * s
            fill.color = argb(alpha, K.STACKED_INSET_LINE)
            if (mirrored) drawRect(x + sogW - line, 0f, x + sogW, h, fill) else drawRect(x, 0f, x + line, h, fill)
            val cx = x + sogW / 2f
            sogPaint.color = argb(alpha, 0xFFFFFF)
            if (body.awaySog.isNotEmpty()) drawText(body.awaySog, cx, rowH / 2f + sogOffset, sogPaint)
            if (body.homeSog.isNotEmpty()) drawText(body.homeSog, cx, rowH * 1.5f + sogOffset, sogPaint)
            sogLabelPaint.color = argb(alpha, K.STACKED_SOG_LABEL)
            drawText(sogLabel, cx, rowH + sogLabelOffset, sogLabelPaint)
        }
    }

    /**
     * The clock column after the SOG column's open width: its contents are anchored to its inner edge (the inset line)
     * and revealed by a clip of the open width, rightwards, or leftwards when [mirrored].
     */
    private fun drawClockColumn(c: Canvas, body: StackedFrameState, f: StackedFrame, mirrored: Boolean) {
        val open = f.clockWidth * clockW
        val sog = f.sogWidth * sogW
        // [x0]: the contents' left edge (a full clockW wide); [cl]: the clip's left edge.
        val x0 = if (mirrored) -sog - clockW else rowsW + sog
        val cl = if (mirrored) -sog - open else x0
        c.withClip(cl, 0f, cl + open, h) {
            val alpha = f.clockAlpha * (if (body.columnDim) K.STACKED_DIM_ALPHA else 1f)
            if (alpha <= 0f) return@withClip
            // (Review U N1) The inset line is the column's edge, not its content: it fades with the column but is not
            // dimmed with a stale or delayed column's words.
            fill.color = argb(f.clockAlpha, K.STACKED_INSET_LINE)
            if (mirrored) drawRect(x0 + clockW - line, 0f, x0 + clockW, h, fill) else drawRect(x0, 0f, x0 + line, h, fill)
            val cx = x0 + clockW / 2f
            if (body.periodText.isNotEmpty()) {
                periodPaint.textSize = body.periodPx * s
                periodPaint.color = argb(alpha, 0xFFFFFF)
                periodPaint.getFontMetrics(fm)
                drawText(body.periodText, cx, periodBottom - fm.descent, periodPaint)
            }
            fill.color = argb(alpha, K.STACKED_RULE)
            drawRect(x0 + ruleMargin, ruleTop, x0 + clockW - ruleMargin, ruleTop + line, fill)
            when (body.slot) {
                ClockSlot.Clock -> drawClock(this, body.clockSecs, cx, alpha)
                ClockSlot.Blank -> Unit
                else -> if (body.slotText.isNotEmpty()) {
                    slotPaint.textSize = body.slotPx * s
                    slotPaint.color = argb(alpha, 0xFFFFFF)
                    val brk = body.slotBreak
                    if (brk > 0) {
                        // Two lines (fix pass, review U S1): broken at the first space, the block centred on the slot.
                        drawText(body.slotText, 0, brk, cx, clockCy + body.slotDy1 * s, slotPaint)
                        drawText(body.slotText, brk + 1, body.slotText.length, cx, clockCy + body.slotDy2 * s, slotPaint)
                    } else {
                        slotPaint.getFontMetrics(fm)
                        drawText(body.slotText, cx, clockCy - (fm.ascent + fm.descent) / 2f, slotPaint)
                    }
                }
            }
        }
    }

    /** `m:ss` / `mm:ss` digit by digit from the table (tabular), centred on [cx] in the clock slot. */
    private fun drawClock(c: Canvas, secs: Int, cx: Float, alpha: Float) {
        val n = clockChars(secs, clockBuf)
        val width = (n - 1) * digitCell + colonAdvance
        var x = cx - width / 2f
        val y = clockCy + clockCap
        clockPaint.color = argb(alpha, 0xFFFFFF)
        for (i in 0 until n) {
            val ch = clockBuf[i]
            if (ch == ':') {
                c.drawText(clockBuf, i, 1, x, y, clockPaint)
                x += colonAdvance
            } else {
                c.drawText(clockBuf, i, 1, x + (digitCell - digitAdvance[ch - '0']) / 2f, y, clockPaint)
                x += digitCell
            }
        }
    }

    // ------------------------------------------------------------------ test seams (allocate; never called in draw)

    /** The bar's words' right edge in device px from the box's left, the italic's measured overhang included. */
    internal fun barTextRight(b: BarContent): Float {
        val split = timeSplit(b)
        val labelEnd = if (split < 0) b.len else split
        var right = barTextL + barPaint.measureText(b.chars, 0, labelEnd)
        var last = String(b.chars, 0, labelEnd)
        var p = barPaint
        if (split >= 0) {
            right += barTextGap + barTimePaint.measureText(b.chars, split + 1, b.len - split - 1)
            last = String(b.chars, split + 1, b.len - split - 1)
            p = barTimePaint
        }
        val r = Rect()
        p.getTextBounds(last, 0, last.length, r)
        val overhang = (r.right - p.measureText(last)).coerceAtLeast(0f)
        return right + overhang
    }

    /**
     * (Owner request 2026-11-13) The mirrored bar's words' ink (glyph outlines, the italic's overhang included) as
     * [drawFrame] places them at rest in a bar whose right end is [r] (device px), x only: `RectF.left` / `right`.
     */
    internal fun barTextInkMirrored(b: BarContent, r: Float): RectF {
        val split = timeSplit(b)
        val labelEnd = if (split < 0) b.len else split
        val x = r - barTextL - b.wordsAdvance
        val out = RectF()
        val path = Path()
        barPaint.getTextPath(b.chars, 0, labelEnd, x, 0f, path)
        path.computeBounds(out, true)
        if (split >= 0) {
            val t = RectF()
            path.reset()
            barTimePaint.getTextPath(b.chars, split + 1, b.len - split - 1, x + b.labelAdvance + barTextGap, 0f, path)
            path.computeBounds(t, true)
            out.union(t)
        }
        return out
    }

    /** The bar's words as [fillBar] resolved them. */
    internal fun barText(b: BarContent): String = String(b.chars, 0, b.len)

    /**
     * Free device px between the end of the code's ink and the start of the score's ink in one row (the stripe at its
     * power-play width, the widest case); negative = they overlap. [mirrored]: as a mirrored row places them (the block
     * before the stripe: the code from the rows' left edge, the score a stripe's width further in).
     */
    internal fun rowGap(abbrev: String, score: String, mirrored: Boolean = false): Float {
        val r = Rect()
        codePaint.getTextBounds(abbrev, 0, abbrev.length, r)
        val codeRight = (if (mirrored) 0f else ppStripeW) + logoMl + logoSlotW + codePl + r.right
        scorePaint.getTextBounds(score, 0, score.length, r)
        val scoreLeft = (if (mirrored) scoreRight - ppStripeW else scoreRight) - scorePaint.measureText(score) + r.left
        return scoreLeft - codeRight
    }

    /** The score's ink left of its 45.9 slot (device px; > 0 = it spills out of the slot). */
    internal fun scoreSpill(score: String): Float {
        val r = Rect()
        scorePaint.getTextBounds(score, 0, score.length, r)
        return (scoreRight - scoreSlotW) - (scoreRight - scorePaint.measureText(score) + r.left)
    }

    /** Free device px either side of [text] in the SOG column at the digits' size (negative = clipped). */
    internal fun sogSlack(text: String, label: Boolean = false): Float {
        val p = if (label) sogLabelPaint else sogPaint
        val r = Rect()
        p.getTextBounds(text, 0, text.length, r)
        return (sogW - r.width()) / 2f
    }

    /** The ink width of [text] at [px] design px in the clock column's paint (tracked for the period), device px. */
    internal fun columnWidth(text: String, px: Float, period: Boolean = false): Float {
        val p = Paint(if (period) periodPaint else slotPaint)
        p.textSize = px * s
        val r = Rect()
        p.getTextBounds(text, 0, text.length, r)
        return r.width().toFloat()
    }

    /** The clock's drawn width for [secs], device px. */
    internal fun clockWidth(secs: Int): Float = (clockChars(secs, CharArray(8)) - 1) * digitCell + colonAdvance

    /** The clock column's text budget for words, device px. */
    internal val columnTextBudget: Float get() = columnBudget()

    /** The clock column's budget for the clock's advance, device px. */
    internal val clockTextBudget: Float get() = clockBudget()

    /** The lower slot's ink budget, device px (its height less the breathing above and below). */
    internal val slotTextHeightBudget: Float get() = slotH - 2 * WORD_BREATHING * s

    /** The clock's ink for [secs] exactly as [drawClock] places it, x relative to the column's centre (device px; allocates). */
    internal fun clockInk(secs: Int): RectF {
        val buf = CharArray(8)
        val n = clockChars(secs, buf)
        var x = -((n - 1) * digitCell + colonAdvance) / 2f
        val out = RectF()
        val path = Path()
        val r = RectF()
        for (i in 0 until n) {
            val ch = buf[i]
            val gx = if (ch == ':') x else x + (digitCell - digitAdvance[ch - '0']) / 2f
            path.reset()
            clockPaint.getTextPath(buf, i, 1, gx, clockCap, path)
            path.computeBounds(r, true)
            if (i == 0) out.set(r) else out.union(r)
            x += if (ch == ':') colonAdvance else digitCell
        }
        return out
    }

    /** Which of this painter's text paints [inkBounds] measures with. */
    internal enum class Ink { CODE, SCORE, SOG, SOG_LABEL, PERIOD, SLOT, BAR, BAR_TIME }

    /**
     * [text]'s exact ink (the glyph outlines' float bounds) as this painter's [ink] paint draws it at origin ([x], [y]),
     * with that paint's alignment and tracking, device px; [px] overrides its size (design px). For the screenshot test's
     * geometry probes (allocates).
     */
    internal fun inkBounds(ink: Ink, text: String, x: Float, y: Float, px: Float? = null): RectF {
        val p = Paint(
            when (ink) {
                Ink.CODE -> codePaint
                Ink.SCORE -> scorePaint
                Ink.SOG -> sogPaint
                Ink.SOG_LABEL -> sogLabelPaint
                Ink.PERIOD -> periodPaint
                Ink.SLOT -> slotPaint
                Ink.BAR -> barPaint
                Ink.BAR_TIME -> barTimePaint
            },
        )
        if (px != null) p.textSize = px * s
        // The alignment is applied here, from the advance, so the path's origin is always the text's left.
        val left = when (p.textAlign) {
            Paint.Align.CENTER -> x - p.measureText(text) / 2f
            Paint.Align.RIGHT -> x - p.measureText(text)
            else -> x
        }
        p.textAlign = Paint.Align.LEFT
        val path = Path()
        p.getTextPath(text, 0, text.length, left, y, path)
        return RectF().also { path.computeBounds(it, true) }
    }

    /** Test seam: the y offsets [drawFrame] adds to a row's centre for the score and the SOG digits, and the label's. */
    internal val scoreBaselineOffset: Float get() = scoreOffset
    internal val sogBaselineOffset: Float get() = sogOffset
    internal val sogLabelBaselineOffset: Float get() = sogLabelOffset
    internal val codeBaselineOffset: Float get() = codeOffset
    internal val barBaselineOffset: Float get() = barOffset

    companion object {
        /**
         * (§11) The bar's face: the bundled static Instrument Sans Bold Italic (upstream `Instrument/instrument-sans`
         * `fonts/ttf/InstrumentSans-BoldItalic.ttf`); null if it cannot be loaded (the painter then fakes the italic).
         * Loaded only for the Stacked painters, never added to `BoxFonts.Sans`.
         */
        fun barTypeface(context: android.content.Context): Typeface? = sansTypeface(context, R.font.instrument_sans_bold_italic)

        private const val DIGITS = "0123456789"
        private const val OPAQUE = 0xFF shl 24
        /** The mock's letter-spacings (em): the team code (.01), the period (.02), the bar words (.01). */
        private const val CODE_TRACK_EM = 0.01f
        private const val PERIOD_TRACK_EM = 0.02f
        private const val BAR_TRACK_EM = 0.01f
        /**
         * (Fix pass 2026-11-11, review U B1 / S1; was 1 for both) Design px kept free each side of the clock column's
         * text, which also clears the 1-px inset line on its left and the 1-px border on the box's right:
         * [CLOCK_BREATHING] for the clock's tabular advance ("19:58" is ≈ 77 of the 80 left at 28.8; the mock has ≈ 5 px of
         * air), [WORD_BREATHING] for a word's ink (the period, "END", "INT", "Delayed", each line of a two-line
         * "No update"), and above and below a two-line block in the lower slot.
         */
        private const val CLOCK_BREATHING = 5f
        private const val WORD_BREATHING = 6f
        /** A two-line slot string's baseline-to-baseline distance, em (a tight display stack). */
        private const val SLOT_LINE_PITCH_EM = 1.05f

        /** `m:ss` / `mm:ss` for [secs] (0..99:59) into [out]; returns the length. Allocation-free. */
        private fun clockChars(secs: Int, out: CharArray): Int {
            val v = secs.coerceIn(0, 99 * 60 + 59)
            val m = v / 60
            val r = v % 60
            var n = 0
            if (m >= 10) out[n++] = '0' + m / 10
            out[n++] = '0' + m % 10
            out[n++] = ':'
            out[n++] = '0' + r / 10
            out[n++] = '0' + r % 10
            return n
        }

        private fun isTeamKind(k: BarKind) = k == BarKind.POWER_PLAY || k == BarKind.TWO_MAN || k == BarKind.PP_GOAL

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

        fun argb(a: Float, rgb: Int): Int = (Math.round(a.coerceIn(0f, 1f) * 255f) shl 24) or (rgb and 0xFFFFFF)

        /** Opaque [a] → [b] by [t] (0 = a), per channel. */
        fun lerpColour(a: Int, b: Int, t: Float): Int {
            val f = t.coerceIn(0f, 1f)
            fun ch(shift: Int): Int {
                val x = (a shr shift) and 0xFF
                val y = (b shr shift) and 0xFF
                return Math.round(x + (y - x) * f) shl shift
            }
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }
    }
}

/**
 * (Fix pass 2026-11-11) A fitted lower-slot word ([StackedPainter.fitSlot]): its size [px] (design px); [brk] the space it
 * breaks at onto two lines (−1 = one line); [dy1] / [dy2] the two lines' baselines relative to the slot's centre (design
 * px, Standard scale folded out).
 */
internal class SlotFit(val px: Float, val brk: Int = -1, val dy1: Float = 0f, val dy2: Float = 0f)

/**
 * (Fix pass 2026-11-11, review U N11) Loads the Stacked bar's Bold Italic (88 KB) into `ResourcesCompat`'s font cache off
 * the main thread, so the composition that first builds a [StackedPainter] finds it cached instead of reading the file
 * there. `MainActivity` calls it on a background thread after the first frame; a no-op once cached, harmless when Stacked
 * is never shown.
 */
object StackedFonts {
    fun preload(context: android.content.Context) {
        StackedPainter.barTypeface(context)
    }
}
