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
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import app.thebox.core.sports.ClockSlot
import app.thebox.core.sports.DownText
import app.thebox.core.sports.FieldZone
import app.thebox.core.sports.League
import app.thebox.core.sports.NflBarKey
import app.thebox.core.sports.NflBarKind
import app.thebox.core.sports.NflScoreFrame
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.NflShapes
import app.thebox.core.sports.NhlStackedColours
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedColours
import app.thebox.core.sports.StackedMock
import app.thebox.core.sports.StackedNflFrame
import app.thebox.core.sports.StackedNflMock as M
import app.thebox.core.sports.StackedNflTargets
import app.thebox.core.sports.StackedPair
import app.thebox.core.sports.TabTail
import app.thebox.ui.R
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * (NFL Stacked §2.3, §2.4, §5, §6; WP-3) The NFL Stacked box's painter: the two navy rows (stripe, logo, code, possession
 * ball, timeout pips, score), the clock column, the bar (background, hairline, skewed stripes, words, divider, the flag
 * icon, the red-zone patch) and the nine-slice shadow, drawn into the runner's one bounded layer. Mirrored about the
 * rows for a right-hand corner (§5.3). Allocation-free in [drawFrame] (GA §2.5): every `Paint`, sprite and glyph table is
 * built in the constructor or a bake.
 *
 * [s] is device px per Standard design px (× `SCORE_LARGE_SCALE` for [large]); [sansBold] is Instrument Sans Bold (codes,
 * scores, clock column), [barFont] Instrument Sans Bold Italic (the bar words); null = the system fallback (the bar then
 * fakes the italic with `STACKED_FAUX_ITALIC_SKEW_X`, as `StackedPainter`).
 *
 * **What it draws, from `design/nfl-stacked/StackedNFL.dc.html` at × 0.45** (the hockey `StackedPainter`'s structure; no
 * `clipPath`, `saveLayer`, `setShadowLayer` or real-time blur):
 * - the box: fill `#15191F`, border 1 `#2B3550`, radius 2.7, the baked nine-slice shadow (0 / 5.4 / σ 8.1 / .55);
 * - each row 243 × 54 on `#12284F`: the stripe (3.6, 6.3 with possession; the logo, code, ball and pips move with it, the
 *   score stays), the logo fitted in 50.4 × 43.2 centred in the 54 slot 9 after the stripe (or the `LogoDisc`), the code
 *   (25.2 Bold, .01 em) at stripe + 74.7 in a line box from 11.25, the possession ball (13.5, `#8B4513` + white stroke)
 *   5.4 after the code's advance and centred on its line, the timeout pips (9.9 × 3.0, r .9, gap 2.7, top 40.05) under
 *   the code, the score (35.1 Bold tabular, right-aligned at 228.6 in a 45 slot; 3 digits fitted to 43, floor 24); a
 *   1-px white .22 divider under the away row. Mirrored: the reflection [score][ball][code][logo][stripe] (§5.3);
 * - the clock column (90): the inset line on its rows side, the period (21.6, .02 em) on a line ending 6.3 above the 50.4
 *   slot, the 1-px rule 11.7 in, and the clock (27.9 tabular) or "No update" / "Delayed" (two lines when fitted so);
 * - the bar (33): its background (lerped through a REKEY), two −30° stripes (11.7 wide at 20.7 / 37.8, overhang 4.5), the
 *   words (20 Bold Italic at 63): [flag icon] label | divider | sub, the red-zone patch from the divider, the 1-px white
 *   .22 hairline on its inner edge, the NHL bar's 1-px black .25 text shadow (not on the yellow flag bar).
 *
 * **The takeover.** [StackedNflDrawArgs] carries the sampled [StackedNflDrawArgs.score] but not the score painter or its
 * glow masks, so the runner hands them here once through [takeover] / [glow]; [drawFrame] then draws, as the hockey
 * composite: glow, shadow, box, bar, rows (with the row flash and the pop), clock column, the takeover over the top part,
 * and the border on top. With [takeover] null the runner calls [StackedNflScorePainter.drawGlow] before and
 * [StackedNflScorePainter.drawTakeover] after [drawFrame] itself (the takeover then covers the border's top part).
 */
internal class StackedNflPainter(
    val s: Float,
    val large: Boolean,
    val sansBold: Typeface?,
    val barFont: Typeface?,
) {
    private val white = Color.WHITE

    // ---- geometry, device px
    private val h = K.STACKED_H * s
    private val rowH = h / 2f
    private val rowsW = K.STACKED_ROWS_W * s
    private val clockW = K.STACKED_CLOCK_W * s
    private val radius = StackedMock.BOX_RADIUS * K.STACKED_SCALE * s
    private val line = (K.STACKED_HAIRLINE_W * s).coerceAtLeast(1f)
    private val stripeW = M.STRIPE_W * K.STACKED_SCALE * s
    private val possStripeW = M.POSS_STRIPE_W * K.STACKED_SCALE * s
    private val logoMl = M.LOGO_ML * K.STACKED_SCALE * s
    private val logoSlotW = M.LOGO_SLOT_W * K.STACKED_SCALE * s
    /** The logo's fit box inside the slot (§2.3: 50.4 × 43.2, the hockey row's logo box). */
    private val logoFitW = StackedMock.LOGO_SLOT_W * K.STACKED_SCALE * s
    private val logoFitH = StackedMock.LOGO_H * K.STACKED_SCALE * s
    private val centrePl = M.CENTRE_PL * K.STACKED_SCALE * s
    private val codeTop = CODE_LINE_TOP * s
    private val ballPx = K.STACKED_NFL_BALL * s
    private val ballGap = K.STACKED_NFL_BALL_GAP * s
    private val pipW = K.STACKED_NFL_PIP_W * s
    private val pipH = K.STACKED_NFL_PIP_H * s
    private val pipR = K.STACKED_NFL_PIP_R * s
    private val pipGap = K.STACKED_NFL_PIP_GAP * s
    private val pipTop = K.STACKED_NFL_PIP_TOP * s
    private val scoreSlotW = M.SCORE_SLOT_W * K.STACKED_SCALE * s
    private val scoreRight = rowsW - M.SCORE_MR * K.STACKED_SCALE * s
    private val periodBottom = (M.PERIOD_SLOT_H - M.PERIOD_PAD_B) * K.STACKED_SCALE * s
    private val ruleTop = M.PERIOD_SLOT_H * K.STACKED_SCALE * s
    private val ruleMargin = M.RULE_MARGIN * K.STACKED_SCALE * s
    private val clockCy = (ruleTop + line + h) / 2f
    private val slotH = h - (ruleTop + line)
    private val barH = K.STACKED_BAR_H * s
    private val barStripeAL = StackedMock.BAR_STRIPE_A_L * K.STACKED_SCALE * s
    private val barStripeBL = StackedMock.BAR_STRIPE_B_L * K.STACKED_SCALE * s
    private val barStripeW = StackedMock.BAR_STRIPE_W * K.STACKED_SCALE * s
    private val barOverhang = StackedMock.BAR_STRIPE_OVERHANG * K.STACKED_SCALE * s
    private val barTextL = M.BAR_TEXT_PL * K.STACKED_SCALE * s
    private val barGap = K.STACKED_NFL_BAR_TEXT_GAP * s
    private val dividerW = (M.BAR_DIVIDER_W / 2f * s).coerceAtLeast(1f)
    private val dividerH = K.STACKED_BAR_TEXT_PX * (M.BAR_DIVIDER_H / M.BAR_TEXT_PX) * s
    private val iconW = K.STACKED_NFL_FLAG_ICON_W * s
    private val iconH = K.STACKED_NFL_FLAG_ICON_H * s

    // ---- paints (built once; draw only changes colour, alpha and size)
    private fun text(px: Float, tf: Typeface?, align: Paint.Align, track: Float = 0f, tnum: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = px * s; typeface = tf; color = white; textAlign = align
            if (track != 0f) letterSpacing = track
            if (tnum) fontFeatureSettings = "'tnum'"
        }

    private val codePaint = text(K.STACKED_NFL_CODE_PX, sansBold, Paint.Align.LEFT, CODE_TRACK_EM)
    private val codeMirroredPaint = Paint(codePaint).apply { textAlign = Paint.Align.RIGHT }
    private val scorePaint = text(M.SCORE_PX * K.STACKED_SCALE, sansBold, Paint.Align.RIGHT, tnum = true)
    private val scoreMirroredPaint = Paint(scorePaint).apply { textAlign = Paint.Align.LEFT }
    private val periodPaint = text(M.PERIOD_PX * K.STACKED_SCALE, sansBold, Paint.Align.CENTER, PERIOD_TRACK_EM)
    private val slotPaint = text(K.STACKED_NFL_CLOCK_PX, sansBold, Paint.Align.CENTER)
    private val clockPaint = text(K.STACKED_NFL_CLOCK_PX, sansBold, Paint.Align.LEFT, tnum = true)
    private val barPaint = text(K.STACKED_BAR_TEXT_PX, barFont ?: sansBold, Paint.Align.LEFT, BAR_TRACK_EM).apply {
        if (barFont == null) textSkewX = K.STACKED_FAUX_ITALIC_SKEW_X
    }
    /** The sub part (field, "1 LEFT", "KC +6"): tabular, as the mock's `font-variant-numeric`. */
    private val barSubPaint = Paint(barPaint).apply { fontFeatureSettings = "'tnum'" }
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansBold }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.BLACK; alpha = (K.STACKED_SHADOW_ALPHA * 255f).roundToInt()
    }
    private val fm = Paint.FontMetrics()
    private val bounds = Rect()
    private val disc = LogoDisc(s, sansBold)

    private val box = RectF()
    private val rect = RectF()
    private val logoDst = RectF()
    /** A bar stripe: the CSS `skewX(−30deg)` parallelogram, its unskewed left edge at x = 0, its top at y = 0. */
    private val stripePath = Path()

    private val scoreOffset: Float
    private val codeBaseline: Float
    private val codeCy: Float
    private val barOffset: Float

    // ---- the clock's digit table (tabular: a new value never moves a digit)
    /** The clock's size in design px: 27.9, stepped down only if "00:00" would not fit the column. */
    val clockPx: Float
    private val digitAdvance = FloatArray(10)
    private val digitCell: Float
    private val colonAdvance: Float
    private val clockCap: Float
    private val clockBuf = CharArray(8)

    // ---- sprites, baked once per size (ARGB for the two-colour ball; ALPHA_8 for the flag icon, tinted per draw)
    private val ball: Bitmap = bakeBall()
    private val flagIcon: Bitmap = bakeFlagIcon()
    private val spriteDst = RectF()

    // ---- per-row caches (draw path: recomputed only when the shown string object changes; no allocation)
    private var awayScoreRef: String? = null
    private var awayScorePx = 0f
    private var homeScoreRef: String? = null
    private var homeScorePx = 0f
    private var awayCodeRef: String? = null
    private var awayCodePx = 0f
    private var awayCodeAdv = 0f
    private var homeCodeRef: String? = null
    private var homeCodePx = 0f
    private var homeCodeAdv = 0f

    /** The fallback look (a null [StackedNflDrawArgs.colours]), resolved once. */
    private val fallback: StackedPair = NhlStackedColours.pair(null, null)

    /** The shadow [bakeShadow] last made; [drawFrame] draws it (the draw args carry none). */
    @Volatile private var shadow: NineSlice? = null

    /**
     * The takeover's painter and its baked glow (see the class comment): set by the runner when it builds or re-bakes
     * them, so [drawFrame] draws the takeover in the composite's order. Null = the runner draws them itself.
     */
    @Volatile var takeover: StackedNflScorePainter? = null
    @Volatile var glow: GlowMasks? = null

    init {
        scorePaint.getTextBounds("0", 0, 1, bounds)
        scoreOffset = -(bounds.top + bounds.bottom) / 2f
        // The code's line box (`line-height: 1`, the mock's 56 / 800) starts 11.25 below the row's top; its baseline is
        // where CSS centres the content area in that box.
        codePaint.getFontMetrics(fm)
        val codeLine = K.STACKED_NFL_CODE_PX * s
        codeBaseline = codeTop + (codeLine - (fm.descent - fm.ascent)) / 2f - fm.ascent
        codeCy = codeTop + codeLine / 2f
        barPaint.getFontMetrics(fm)
        barOffset = -(fm.ascent + fm.descent) / 2f

        var px = K.STACKED_NFL_CLOCK_PX
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

    /** The possession ball: the mock's 24-unit football, filled `#8B4513`, stroked white 1.6 / 24, at 1.5× its 13.5 px. */
    private fun bakeBall(): Bitmap {
        val a = SPRITE_SCALE
        val px = ceil(ballPx * a).toInt().coerceAtLeast(1)
        val bmp = createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(px / NflShapes.BALL_VIEWBOX, px / NflShapes.BALL_VIEWBOX)
        val path = NflTabPainter.ballPath()
        val outline = Path().apply {
            moveTo(NflShapes.BALL_START[0], NflShapes.BALL_START[1])
            val q = NflShapes.BALL_CUBICS
            var i = 0
            while (i < q.size) { cubicTo(q[i], q[i + 1], q[i + 2], q[i + 3], q[i + 4], q[i + 5]); i += 6 }
            close()
        }
        c.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = BALL_FILL })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = NflShapes.BALL_STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        })
        return bmp
    }

    /** The flag icon (viewBox 30 × 34: pole `M4 2V32` stroke 3 round, cloth `M6 3C12 1 16 7 26 4V18C16 21 12 15 6 17Z`). */
    private fun bakeFlagIcon(): Bitmap {
        val a = SPRITE_SCALE
        val w = ceil(iconW * a).toInt().coerceAtLeast(1)
        val ih = ceil(iconH * a).toInt().coerceAtLeast(1)
        val bmp = createBitmap(w, ih, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        c.scale(w / FLAG_VIEW_W, ih / FLAG_VIEW_H)
        c.drawLine(4f, 2f, 4f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND; color = Color.BLACK
        })
        val cloth = Path().apply {
            moveTo(6f, 3f); cubicTo(12f, 1f, 16f, 7f, 26f, 4f); lineTo(26f, 18f); cubicTo(16f, 21f, 12f, 15f, 6f, 17f); close()
        }
        c.drawPath(cloth, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.BLACK })
        return bmp
    }

    private fun columnBudget(): Float = clockW - 2 * WORD_BREATHING * s
    private fun clockBudget(): Float = clockW - 2 * CLOCK_BREATHING * s

    // ------------------------------------------------------------------ words and fit (built per view, never per frame)

    /** [startPx] stepped down until [text]'s ink fits the clock column (less the word breathing), floor 18. Design px. */
    fun fitColumn(text: String, startPx: Float, track: Float = 0f): Float {
        var px = startPx
        measurePaint.letterSpacing = track
        while (px > K.STACKED_FIT_MIN_PX) {
            if (inkWidth(text, px) <= columnBudget()) break
            px -= K.STACKED_FIT_STEP_PX
        }
        measurePaint.letterSpacing = 0f
        return px.coerceAtLeast(K.STACKED_FIT_MIN_PX)
    }

    private fun inkWidth(text: String, px: Float): Float {
        measurePaint.textSize = px * s
        val r = Rect()
        measurePaint.getTextBounds(text, 0, text.length, r)
        return r.width().toFloat()
    }

    /**
     * The lower slot's word ("No update", "Delayed"), fitted as the hockey box's (ST fix pass): one line if it fits at the
     * start size, else broken at its first space onto two lines at one fitted size when that is larger.
     */
    fun fitSlot(text: String): SlotFit {
        val start = K.STACKED_NFL_CLOCK_PX
        if (text.isEmpty()) return SlotFit(start)
        val one = fitColumn(text, start)
        val oneFits = inkWidth(text, one) <= columnBudget()
        val brk = text.indexOf(' ')
        if (brk <= 0 || brk == text.length - 1 || (oneFits && one >= start)) return SlotFit(one)
        val a = text.substring(0, brk)
        val b = text.substring(brk + 1)
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
                val dy1 = (-block / 2f - ra.top) / s
                return SlotFit(px, brk, dy1, dy1 + pitch / s)
            }
            if (px <= K.STACKED_FIT_MIN_PX) return SlotFit(one)
            px = (px - K.STACKED_FIT_STEP_PX).coerceAtLeast(K.STACKED_FIT_MIN_PX)
        }
    }

    /**
     * The resolved words of [v] for the layout [targets] (`StackedNflTargets.of(v)`): codes, scores ("" when null),
     * the period slot ("4TH", `SportsText.period(res, label, NFL)` upper-cased), the lower slot's words and sizes, fitted.
     * Once per shown view, never per frame.
     */
    fun frame(v: ScoreBugView, targets: StackedNflTargets, res: Resources): StackedNflFrameState {
        val p = targets.period
        val periodText = if (p == null) "" else SportsText.period(res, p, League.NFL).uppercase(Locale.ROOT)
        val slotText = when (targets.clockSlot) {
            ClockSlot.Clock, ClockSlot.Blank -> ""
            ClockSlot.End -> res.getString(R.string.sports_stacked_end)
            ClockSlot.Intermission -> res.getString(R.string.sports_stacked_int)
            ClockSlot.NoUpdate -> res.getString(R.string.sports_no_update)
            ClockSlot.Delayed -> res.getString(R.string.sports_delayed)
        }
        val periodStart = M.PERIOD_PX * K.STACKED_SCALE
        val fit = if (targets.clockSlot == ClockSlot.Clock || slotText.isEmpty()) null else fitSlot(slotText)
        return StackedNflFrameState(
            awayAbbrev = v.away.abbrev,
            homeAbbrev = v.home.abbrev,
            awayScore = v.away.score?.toString().orEmpty(),
            homeScore = v.home.score?.toString().orEmpty(),
            periodText = periodText,
            periodPx = if (periodText.isEmpty()) periodStart else fitColumn(periodText, periodStart, PERIOD_TRACK_EM),
            slot = targets.clockSlot,
            slotText = slotText,
            slotPx = when {
                targets.clockSlot == ClockSlot.Clock -> clockPx
                fit == null -> K.STACKED_NFL_CLOCK_PX
                else -> fit.px
            },
            clockSecs = v.clockSecs ?: 0,
            columnDim = targets.columnDim,
            targets = targets,
            slotBreak = fit?.brk ?: -1,
            slotDy1 = fit?.dy1 ?: 0f,
            slotDy2 = fit?.dy2 ?: 0f,
        )
    }

    /**
     * Resolves [key]'s words and colours into [content] (§2.4, §2.6, §8.4): [NflBarContent.label] / [NflBarContent.sub],
     * [NflBarContent.redZone], [NflBarContent.flag], the colours from [pair] (the side's `barBg` / `barA` / `barB`; the
     * neutral bar with the away / home row stripes; the flag bar's fixed `STACKED_NFL_FLAG_*`), and
     * [NflBarContent.wordsAdvance]. Sets [NflBarContent.key] to [key]; leaves [NflBarContent.dim] as the runner set it.
     * Called by the runner per OPEN / REKEY, never per frame.
     */
    fun fillBar(content: NflBarContent, key: NflBarKey, pair: StackedPair?, awayAbbrev: String, homeAbbrev: String, res: Resources) {
        val p = pair ?: fallback
        val side = key.side
        val team: StackedColours? = when (side) {
            Side.AWAY -> p.away
            Side.HOME -> p.home
            null -> null
        }
        val abbrev = if (side == Side.HOME) homeAbbrev else awayAbbrev
        var label = ""
        var sub = ""
        var red = false
        when (key.kind) {
            NflBarKind.PLAY -> {
                label = key.down?.let { down(res, it) } ?: ""
                val t = key.tail
                if (t is TabTail.RedZone) {
                    sub = res.getString(R.string.sports_tab_red_zone)
                    red = true
                } else {
                    sub = field(res, t, side, awayAbbrev, homeAbbrev)
                }
            }
            NflBarKind.PAT -> label = res.getString(R.string.sports_tab_xp)
            // RC9: a kick-off never reads RED ZONE, whatever the key carries.
            NflBarKind.KICKOFF -> {
                label = res.getString(R.string.sports_stacked_nfl_kickoff, abbrev)
                sub = field(res, key.tail, side, awayAbbrev, homeAbbrev)
            }
            NflBarKind.FLAG -> label = res.getString(R.string.sports_stacked_nfl_flag)
            NflBarKind.TIMEOUT -> {
                label = res.getString(R.string.sports_stacked_nfl_timeout, abbrev)
                sub = res.getString(R.string.sports_stacked_nfl_left, (key.left ?: 0).coerceIn(0, K.NFL_TIMEOUTS_MAX))
            }
            NflBarKind.END_PERIOD -> {
                val period = key.period?.let { SportsText.period(res, it, League.NFL) } ?: ""
                label = res.getString(R.string.sports_stacked_nfl_end_of, period).trim()
            }
            NflBarKind.HALFTIME -> label = res.getString(R.string.sports_stacked_nfl_halftime)
            NflBarKind.FINAL -> label = SportsText.final(res, key.finalKind)
            NflBarKind.NO_UPDATE -> label = res.getString(R.string.sports_stacked_no_update)
            NflBarKind.DELAYED -> label = res.getString(R.string.sports_stacked_delayed)
            NflBarKind.SCORE -> {
                label = res.getString(scoreWord(key.score ?: NflScoreKind.TOUCHDOWN))
                if (key.points != null) sub = res.getString(R.string.sports_stacked_nfl_points, abbrev, key.points)
            }
        }
        label = label.uppercase(Locale.ROOT)
        sub = sub.uppercase(Locale.ROOT)
        content.key = key
        content.labelLen = minOf(label.length, NflBarContent.MAX)
        label.toCharArray(content.label, 0, 0, content.labelLen)
        content.subLen = minOf(sub.length, NflBarContent.MAX)
        sub.toCharArray(content.sub, 0, 0, content.subLen)
        content.redZone = red && content.subLen > 0
        content.flag = key.kind == NflBarKind.FLAG
        when {
            content.flag -> {
                content.bg = K.STACKED_NFL_FLAG_BG
                content.a = K.STACKED_NFL_FLAG_FG
                content.b = K.STACKED_NFL_FLAG_B
                content.fg = K.STACKED_NFL_FLAG_FG
            }
            isTeamKind(key.kind) && team != null -> {
                content.bg = team.barBg
                content.a = team.barA
                content.b = team.barB
                content.fg = white
            }
            else -> {
                content.bg = K.STACKED_BAR_NEUTRAL
                content.a = p.away.stripe
                content.b = p.home.stripe
                content.fg = white
            }
        }
        content.wordsAdvance = wordsAdvance(content)
    }

    /** The bar's words' advance (device px): [icon + gap] label [gap divider gap sub]. Allocation-free. */
    private fun wordsAdvance(b: NflBarContent): Float {
        var w = 0f
        if (b.flag) w += iconW + barGap
        w += barPaint.measureText(b.label, 0, b.labelLen)
        if (b.subLen > 0) w += 2 * barGap + dividerW + barSubPaint.measureText(b.sub, 0, b.subLen)
        return w
    }

    /** The down label (§3.4): "1ST & 10", "4TH & GOAL", "3RD", "EXTRA POINT" for the try, "KICKOFF". */
    private fun down(res: Resources, d: DownText): String = when (d) {
        is DownText.Down -> {
            val n = res.getString(
                when (d.down) { 1 -> R.string.sports_down_1; 2 -> R.string.sports_down_2; 3 -> R.string.sports_down_3; else -> R.string.sports_down_4 },
            )
            when {
                d.goal -> res.getString(R.string.sports_down_goal, n)
                d.distance != null -> res.getString(R.string.sports_down_and, n, d.distance)
                else -> n
            }
        }
        DownText.Pat -> res.getString(R.string.sports_tab_xp)
        DownText.Kickoff -> res.getString(R.string.sports_down_kickoff)
    }

    /**
     * The field after the divider: "KC 35" (the yard line on its team's side: OWN = the bar's side, OPP = the other), "50"
     * at midfield, "" when there is no field or no side to name it by (S-D10's neutral bar shows the label alone).
     */
    private fun field(res: Resources, t: TabTail?, side: Side?, awayAbbrev: String, homeAbbrev: String): String {
        if (t !is TabTail.Field) return ""
        if (t.zone == FieldZone.MIDFIELD) return res.getString(R.string.sports_stacked_nfl_mid)
        if (side == null) return ""
        val ownSide = if (t.zone == FieldZone.OWN) side else if (side == Side.AWAY) Side.HOME else Side.AWAY
        val who = if (ownSide == Side.AWAY) awayAbbrev else homeAbbrev
        return res.getString(R.string.sports_stacked_nfl_field, who, t.yard.coerceIn(0, 99))
    }

    /** The drop shadow's nine-slice for this size (ST §6), baked off the main thread; [drawFrame] draws the latest bake. */
    fun bakeShadow(): NineSlice? = NineSlice(s, large).also { shadow = it }

    // ------------------------------------------------------------------ the composite

    /**
     * The whole box for [a] (see [StackedNflDrawArgs] for the coordinates). Order: the glow (an attached [takeover]),
     * shadow, box fill, bar, rows (stripes, logos, codes, balls, pips, scores, the row flash and the pop), clock column,
     * the takeover over the top part, the border. Allocation-free.
     */
    fun drawFrame(c: Canvas, a: StackedNflDrawArgs) {
        val st = a.state ?: return
        val f = a.frame ?: return
        val pair = a.colours ?: fallback
        val w = f.boxW * s
        val bh = if (f.barClosed) 0f else f.barH * s
        val top = if (a.up) -bh else 0f
        val bottom = if (a.up) h else h + bh
        val mirrored = a.mirrored
        val l = if (mirrored) rowsW - w else 0f
        box.set(l, top, l + w, bottom)

        val g = a.score
        val scoring = a.takeoverOn && g != null && !g.done && a.scoreSide != null
        val tk = takeover
        val drawTakeover = scoring && tk != null && tk.colours != null

        // 1. The glow, around the whole box, from the exact-box masks (an attached takeover only).
        if (drawTakeover) glow?.let { tk!!.drawGlow(c, g!!, it, l, top) }
        // 2. The drop shadow, stretched to the box as it is now.
        shadow?.draw(c, l, top, l + w, bottom, shadowPaint)
        // 3. The box fill: the clock column's `#15191F`.
        fill.color = K.STACKED_COL_BG
        c.drawRoundRect(box, radius, radius, fill)
        // 4. The bar.
        if (bh > 0f) drawBar(c, a, f, bh, l, l + w)
        // 5. The rows.
        drawRows(c, a, st, f, pair, if (scoring) g else null)
        // 6. The clock column.
        if (f.clockWidth > 0f) drawClockColumn(c, st, f, mirrored)
        // 7. The takeover over the top part.
        if (drawTakeover) {
            tk!!.extent(top, bottom)
            tk.drawTakeover(c, g!!, mirrored)
        }
        // 8. The border, 1 px `#2B3550`, inside the box's edge.
        stroke.strokeWidth = line
        stroke.color = K.STACKED_BORDER
        box.inset(line / 2f, line / 2f)
        c.drawRoundRect(box, radius - line / 2f, radius - line / 2f, stroke)
        box.inset(-line / 2f, -line / 2f)
    }

    /**
     * The bar ([l]..[r], device px): background (lerped through a REKEY), the red-zone patch, the two stripes, the words,
     * the hairline, under its clip. Mirrored, the stripes and the words are anchored to the bar's right end and move the
     * other way; the stripes keep their "/" lean, the words read left to right, right-aligned (§5.3).
     */
    private fun drawBar(c: Canvas, a: StackedNflDrawArgs, f: StackedNflFrame, bh: Float, l: Float, r: Float) {
        val cur = a.bar ?: return
        val prev = a.barPrev
        val bg = if (prev != null && f.barBgMix < 1f) lerpColour(prev.bg, cur.bg, f.barBgMix) else cur.bg
        val regionTop = if (a.up) -bh else h
        val regionBottom = regionTop + bh
        val contentTop = if (a.up) regionTop else regionBottom - barH
        val m = a.mirrored
        // The flag bar's shake moves the words, divider and stripes (mirrored: the other way).
        val shake = (if (cur.flag) f.shakeDx else 0f) * s * (if (m) -1f else 1f)
        val textAlpha = f.barTextAlpha * (if (cur.dim) K.STACKED_DIM_ALPHA else 1f)
        val words = cur.labelLen > 0
        // The words' left edge: 63 from the bar's outer end; the REKEY slide starts on the screen-edge side (Rec 5).
        val tx = (if (m) r - barTextL - f.barTextDx * s - cur.wordsAdvance else l + barTextL + f.barTextDx * s) + shake
        c.withClip(l, regionTop, r, regionBottom) {
            fill.color = bg or OPAQUE
            drawRoundRect(box, radius, radius, fill)
            // The red-zone patch: from the divider to the bar's end, under the sub part (R26), with the words' alpha.
            if (cur.redZone && words && textAlpha > 0f) {
                val dx = tx + labelEnd(cur)
                fill.color = argb(textAlpha, RED_ZONE_SUB)
                drawRect(dx + barGap, regionTop, r, regionBottom, fill)
            }
            val stripeTop = contentTop - barOverhang
            fill.color = cur.a or OPAQUE
            val ax = (if (m) r - barStripeAL - barStripeW - f.stripeAX * s else l + barStripeAL + f.stripeAX * s) + shake
            withTranslation(ax, stripeTop) { drawPath(stripePath, fill) }
            fill.color = cur.b or OPAQUE
            val bx = (if (m) r - barStripeBL - barStripeW - f.stripeBX * s else l + barStripeBL + f.stripeBX * s) + shake
            withTranslation(bx, stripeTop) { drawPath(stripePath, fill) }
            if (textAlpha > 0f && words) drawBarWords(this, cur, contentTop + barH / 2f, tx, textAlpha, f.waveDeg)
            // The hairline on the bar's inner edge (ST S2: every bar).
            fill.color = argb(K.STACKED_BAR_HAIRLINE_ALPHA, 0xFFFFFF)
            if (a.up) drawRect(l, regionBottom - line, r, regionBottom, fill) else drawRect(l, regionTop, r, regionTop + line, fill)
        }
    }

    /** The label's end from the words' left edge (icon, gap and label advance), device px. */
    private fun labelEnd(b: NflBarContent): Float =
        (if (b.flag) iconW + barGap else 0f) + barPaint.measureText(b.label, 0, b.labelLen)

    /** The bar's words centred on [cy] from [x]: [icon] label | sub, with the hard text shadow off the flag bar. */
    private fun drawBarWords(c: Canvas, b: NflBarContent, cy: Float, x: Float, alpha: Float, waveDeg: Float) {
        var pen = x
        val baseline = cy + barOffset
        val fg = b.fg and 0xFFFFFF
        if (b.flag) {
            spritePaint.color = argb(alpha, fg)
            val iy = cy - iconH / 2f
            c.withTranslation(pen, iy) {
                rotate(waveDeg, K.STACKED_NFL_WAVE_PIVOT_X * s, K.STACKED_NFL_WAVE_PIVOT_Y * s)
                spriteDst.set(0f, 0f, iconW, iconH)
                drawBitmap(flagIcon, null, spriteDst, spritePaint)
            }
            pen += iconW + barGap
        }
        val shadowOn = !b.flag
        val dy = K.STACKED_BAR_TEXT_SHADOW_DY * s
        if (shadowOn) {
            barPaint.color = argb(alpha * K.STACKED_TEXT_SHADOW_ALPHA, 0x000000)
            c.drawText(b.label, 0, b.labelLen, pen, baseline + dy, barPaint)
        }
        barPaint.color = argb(alpha, fg)
        c.drawText(b.label, 0, b.labelLen, pen, baseline, barPaint)
        if (b.subLen <= 0) return
        pen += barPaint.measureText(b.label, 0, b.labelLen) + barGap
        fill.color = if (b.flag) argb(alpha * FLAG_DIVIDER_ALPHA, 0x000000) else argb(alpha * DIVIDER_ALPHA, 0xFFFFFF)
        c.drawRect(pen, cy - dividerH / 2f, pen + dividerW, cy + dividerH / 2f, fill)
        pen += dividerW + barGap
        if (shadowOn) {
            barSubPaint.color = argb(alpha * K.STACKED_TEXT_SHADOW_ALPHA, 0x000000)
            c.drawText(b.sub, 0, b.subLen, pen, baseline + dy, barSubPaint)
        }
        barSubPaint.color = argb(alpha, fg)
        c.drawText(b.sub, 0, b.subLen, pen, baseline, barSubPaint)
    }

    private fun drawRows(
        c: Canvas,
        a: StackedNflDrawArgs,
        st: StackedNflFrameState,
        f: StackedNflFrame,
        pair: StackedPair,
        g: NflScoreFrame?,
    ) {
        val scorer = if (g != null) a.scoreSide else null
        c.withClip(0f, 0f, rowsW, h) {
            fill.color = K.STACKED_ROW_BG
            drawRoundRect(box, radius, radius, fill)
            // The row flash (§4.3): the scorer's stripe colour over its row at the envelope × the C7 peak (or the still
            // version's fixed tint), under the stripe and the row's contents.
            if (g != null && scorer != null && g.rowFlashAlpha > 0f) {
                val sc = if (scorer == Side.AWAY) pair.away else pair.home
                val peak = if (a.still) K.STACKED_STILL_ROW_TINT else sc.rowFlashAlpha
                fill.color = argb(g.rowFlashAlpha * peak, sc.stripe)
                val y0 = if (scorer == Side.AWAY) 0f else rowH
                withClip(0f, y0, rowsW, y0 + rowH) { drawRoundRect(box, radius, radius, fill) }
            }
        }
        val awayStripe = lerp(stripeW, possStripeW, f.stripeAway)
        val homeStripe = lerp(stripeW, possStripeW, f.stripeHome)
        val m = a.mirrored
        fill.color = pair.away.stripe or OPAQUE
        if (m) c.withClip(rowsW - awayStripe, 0f, rowsW, rowH) { drawRoundRect(box, radius, radius, fill) }
        else c.withClip(0f, 0f, awayStripe, rowH) { drawRoundRect(box, radius, radius, fill) }
        fill.color = pair.home.stripe or OPAQUE
        if (m) c.withClip(rowsW - homeStripe, rowH, rowsW, h) { drawRoundRect(box, radius, radius, fill) }
        else c.withClip(0f, rowH, homeStripe, h) { drawRoundRect(box, radius, radius, fill) }
        // The divider under the away row (its border-bottom, across the stripe too).
        fill.color = argb(K.STACKED_BAR_HAIRLINE_ALPHA, 0xFFFFFF)
        c.drawRect(0f, rowH - line, rowsW, rowH, fill)

        val popSide = if (g != null && g.popOn) scorer else null
        val popColour = if (popSide != null) {
            val goal = (if (popSide == Side.AWAY) pair.away else pair.home).goal
            lerpColour(goal.pop, white, g!!.popMix)
        } else white
        val popScale = (g?.popScale ?: 1f).coerceIn(POP_SCALE_MIN, POP_SCALE_MAX)
        val t = st.targets
        row(c, 0f, Side.AWAY, awayStripe, st.awayAbbrev, a.awayLogo, st.awayScore, f, t, m, popSide == Side.AWAY, popScale, popColour, pair.away.stripe)
        row(c, rowH, Side.HOME, homeStripe, st.homeAbbrev, a.homeLogo, st.homeScore, f, t, m, popSide == Side.HOME, popScale, popColour, pair.home.stripe)
    }

    /**
     * One row from [y0]: logo, code, ball, pips and score. Unmirrored x's are measured from the rows' left edge; mirrored
     * every position is reflected (x ↦ rowsW − x) and the alignments swap; glyphs, logos and the ball are never flipped.
     */
    private fun row(
        c: Canvas, y0: Float, side: Side, stripe: Float, abbrev: String, logo: Bitmap?, score: String,
        f: StackedNflFrame, t: StackedNflTargets, m: Boolean, pop: Boolean, popScale: Float, popColour: Int, stripeColour: Int,
    ) {
        val cy = y0 + rowH / 2f
        logo(c, rowX(stripe + logoMl + logoSlotW / 2f, m), cy, abbrev, logo, stripeColour)
        // The code (a 4+ letter code steps down to fit, §5.4; cached per string).
        val codePx: Float
        val codeAdv: Float
        if (side == Side.AWAY) {
            if (abbrev !== awayCodeRef) { awayCodeRef = abbrev; awayCodePx = codeFitPx(abbrev); awayCodeAdv = codeAdvance(abbrev, awayCodePx) }
            codePx = awayCodePx; codeAdv = awayCodeAdv
        } else {
            if (abbrev !== homeCodeRef) { homeCodeRef = abbrev; homeCodePx = codeFitPx(abbrev); homeCodeAdv = codeAdvance(abbrev, homeCodePx) }
            codePx = homeCodePx; codeAdv = homeCodeAdv
        }
        val anchor = stripe + logoMl + logoSlotW + centrePl
        val cp = if (m) codeMirroredPaint else codePaint
        cp.textSize = codePx * s
        cp.color = white
        c.drawText(abbrev, rowX(anchor, m), y0 + codeBaseline, cp)
        // The possession ball, 5.4 after the code's advance, centred on the code's line.
        val ba = if (side == Side.AWAY) f.ballAwayAlpha else f.ballHomeAlpha
        if (ba > 0f) {
            val bs = if (side == Side.AWAY) f.ballAwayScale else f.ballHomeScale
            val bd = (if (side == Side.AWAY) f.ballAwayDeg else f.ballHomeDeg) * (if (m) -1f else 1f)
            val bl = anchor + codeAdv + ballGap
            val bcx = rowX(bl + ballPx / 2f, m)
            val bcy = y0 + codeCy
            spritePaint.color = argb(ba, 0xFFFFFF)
            c.withTranslation(bcx, bcy) {
                rotate(bd)
                scale(bs, bs)
                spriteDst.set(-ballPx / 2f, -ballPx / 2f, ballPx / 2f, ballPx / 2f)
                drawBitmap(ball, null, spriteDst, spritePaint)
            }
        }
        // The timeout pips (row content, never dimmed): hidden when the count is unknown.
        val count = if (side == Side.AWAY) t.awayTimeouts else t.homeTimeouts
        val slots = t.timeoutSlots.coerceIn(0, StackedNflFrame.PIPS)
        if (count != null && slots > 0) {
            val lit = if (side == Side.AWAY) f.pipAway else f.pipHome
            val burn = if (side == Side.AWAY) f.pipAwayBurn else f.pipHomeBurn
            val py = y0 + pipTop
            for (i in 0 until slots) {
                val v = lit[i].coerceIn(0f, 1f)
                var colour = lerpColour(K.STACKED_NFL_PIP_LIT, white, 1f - v)
                var alpha = lerp(K.STACKED_NFL_PIP_USED_ALPHA, 1f, v)
                val b = burn[i].coerceIn(0f, 1f)
                if (b > 0f) {
                    colour = lerpColour(colour, white, b)
                    alpha = alpha + (PIP_BURN_ALPHA - alpha) * b
                }
                fill.color = argb(alpha, colour)
                val px = anchor + i * (pipW + pipGap)
                val left = if (m) rowsW - px - pipW else px
                rect.set(left, py, left + pipW, py + pipH)
                c.drawRoundRect(rect, pipR, pipR, fill)
            }
        }
        // The score: right-aligned at 228.6 (mirrored: left-aligned at its reflection); 3 digits fitted (§5.4).
        if (score.isEmpty()) return
        val spx = if (side == Side.AWAY) {
            if (score !== awayScoreRef) { awayScoreRef = score; awayScorePx = scoreFitPx(score) }
            awayScorePx
        } else {
            if (score !== homeScoreRef) { homeScoreRef = score; homeScorePx = scoreFitPx(score) }
            homeScorePx
        }
        val sp = if (m) scoreMirroredPaint else scorePaint
        sp.textSize = spx * s
        val sy = cy + scoreOffset * (spx / SCORE_PX)
        val sx = rowX(scoreRight, m)
        if (!pop) {
            sp.color = white
            c.drawText(score, sx, sy, sp)
        } else {
            sp.color = popColour
            val pcx = rowX(scoreRight - scoreSlotW / 2f, m)
            c.withScale(popScale, popScale, pcx, cy) { drawText(score, sx, sy, sp) }
            sp.color = white
        }
    }

    /** The score's size (design px): 35.1, or scaled to fit 43 when wider (three digits), floor 24 (§5.4). */
    private fun scoreFitPx(score: String): Float {
        measurePaint.textSize = SCORE_PX * s
        measurePaint.fontFeatureSettings = "'tnum'"
        val w = measurePaint.measureText(score)
        measurePaint.fontFeatureSettings = null
        val room = SCORE_FIT_W * s
        return if (w <= room) SCORE_PX else (SCORE_PX * room / w).coerceAtLeast(K.STACKED_NFL_SCORE_FLOOR_PX)
    }

    /** The code's size (design px): 25.2 for 2–3 letters; a longer one steps down 2 px until it fits, floor 18 (§5.4). */
    private fun codeFitPx(abbrev: String): Float {
        if (abbrev.length <= 3) return K.STACKED_NFL_CODE_PX
        var px = K.STACKED_NFL_CODE_PX
        val room = scoreRight - scoreSlotW - CODE_SCORE_GAP_MIN * s - (possStripeW + logoMl + logoSlotW + centrePl) - ballGap - ballPx
        while (px > CODE_MIN_PX && codeAdvance(abbrev, px) > room) px = (px - CODE_STEP_PX).coerceAtLeast(CODE_MIN_PX)
        return px
    }

    private fun codeAdvance(abbrev: String, px: Float): Float {
        val old = codePaint.textSize
        codePaint.textSize = px * s
        val w = codePaint.measureText(abbrev)
        codePaint.textSize = old
        return w
    }

    private fun rowX(x: Float, mirrored: Boolean): Float = if (mirrored) rowsW - x else x

    /** The logo fitted in 50.4 × 43.2 about ([cx], [cy]), or the fallback disc (a 4+ letter code: the stripe disc). */
    private fun logo(c: Canvas, cx: Float, cy: Float, abbrev: String, b: Bitmap?, stripeColour: Int) {
        if (b != null) {
            val k = minOf(logoFitH / b.height.coerceAtLeast(1), logoFitW / b.width.coerceAtLeast(1))
            val lw = b.width * k
            val lh = b.height * k
            logoDst.set(cx - lw / 2f, cy - lh / 2f, cx + lw / 2f, cy + lh / 2f)
            c.drawBitmap(b, null, logoDst, bmpPaint)
        } else if (disc.isCapsule(abbrev)) {
            disc.drawStripeDisc(c, cx, cy, stripeColour or OPAQUE)
        } else {
            disc.draw(c, cx, cy, abbrev)
        }
    }

    /** The clock column beside the rows: contents anchored to its rows-side edge, revealed by a clip of the open width. */
    private fun drawClockColumn(c: Canvas, st: StackedNflFrameState, f: StackedNflFrame, mirrored: Boolean) {
        val open = f.clockWidth * clockW
        val x0 = if (mirrored) -clockW else rowsW
        val cl = if (mirrored) -open else x0
        c.withClip(cl, 0f, cl + open, h) {
            val alpha = f.clockAlpha * (if (st.columnDim) K.STACKED_DIM_ALPHA else 1f)
            if (alpha <= 0f) return@withClip
            fill.color = argb(f.clockAlpha, K.STACKED_INSET_LINE)
            if (mirrored) drawRect(x0 + clockW - line, 0f, x0 + clockW, h, fill) else drawRect(x0, 0f, x0 + line, h, fill)
            val cx = x0 + clockW / 2f
            if (st.periodText.isNotEmpty()) {
                periodPaint.textSize = st.periodPx * s
                periodPaint.color = argb(alpha, 0xFFFFFF)
                periodPaint.getFontMetrics(fm)
                drawText(st.periodText, cx, periodBottom - fm.descent, periodPaint)
            }
            fill.color = argb(alpha, K.STACKED_RULE)
            drawRect(x0 + ruleMargin, ruleTop, x0 + clockW - ruleMargin, ruleTop + line, fill)
            when (st.slot) {
                ClockSlot.Clock -> drawClock(this, st.clockSecs, cx, alpha)
                ClockSlot.Blank -> Unit
                else -> if (st.slotText.isNotEmpty()) {
                    slotPaint.textSize = st.slotPx * s
                    slotPaint.color = argb(alpha, 0xFFFFFF)
                    val brk = st.slotBreak
                    if (brk > 0) {
                        drawText(st.slotText, 0, brk, cx, clockCy + st.slotDy1 * s, slotPaint)
                        drawText(st.slotText, brk + 1, st.slotText.length, cx, clockCy + st.slotDy2 * s, slotPaint)
                    } else {
                        slotPaint.getFontMetrics(fm)
                        drawText(st.slotText, cx, clockCy - (fm.ascent + fm.descent) / 2f, slotPaint)
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

    /** The bar's words as [fillBar] resolved them: "label | sub" (or the label alone). */
    internal fun barText(b: NflBarContent): String =
        String(b.label, 0, b.labelLen) + if (b.subLen > 0) " | " + String(b.sub, 0, b.subLen) else ""

    /**
     * The bar words' ink x extent at rest (device px from the bar's left end [l], the bar [r] wide; mirrored: right-aligned
     * 63 from [r]), the italic's overhang and the icon included.
     */
    internal fun barInk(b: NflBarContent, l: Float, r: Float, mirrored: Boolean): RectF {
        val x = if (mirrored) r - barTextL - b.wordsAdvance else l + barTextL
        val out = RectF(x, 0f, x, 0f)
        var pen = x
        if (b.flag) { out.union(RectF(pen, 0f, pen + iconW, 1f)); pen += iconW + barGap }
        val path = Path()
        val t = RectF()
        barPaint.getTextPath(b.label, 0, b.labelLen, pen, 0f, path)
        path.computeBounds(t, true)
        out.union(t)
        if (b.subLen > 0) {
            pen += barPaint.measureText(b.label, 0, b.labelLen) + 2 * barGap + dividerW
            path.reset()
            barSubPaint.getTextPath(b.sub, 0, b.subLen, pen, 0f, path)
            path.computeBounds(t, true)
            out.union(t)
        }
        return out
    }

    /**
     * A row's element extents for a [stripe]-wide stripe, device px from the rows' left (mirrored as drawn): the code's
     * advance, the ball, the pips (three slots) and the score's ink for [score] at its fitted size.
     */
    internal fun rowExtents(abbrev: String, score: String, stripe: Float, mirrored: Boolean): RowExtents {
        val px = codeFitPx(abbrev)
        val adv = codeAdvance(abbrev, px)
        val anchor = stripe + logoMl + logoSlotW + centrePl
        fun span(a: Float, b: Float) = if (mirrored) (rowsW - b) to (rowsW - a) else a to b
        val codeInk = Rect()
        val cp = Paint(codePaint).apply { textSize = px * s; textAlign = Paint.Align.LEFT }
        cp.getTextBounds(abbrev, 0, abbrev.length, codeInk)
        val code = span(anchor + codeInk.left, anchor + codeInk.right)
        val ballL = anchor + adv + ballGap
        val spx = scoreFitPx(score)
        val sp = Paint(scorePaint).apply { textSize = spx * s; textAlign = Paint.Align.LEFT }
        val sw = sp.measureText(score)
        val si = Rect()
        sp.getTextBounds(score, 0, score.length, si)
        val scoreInk = if (mirrored) (rowsW - scoreRight + si.left) to (rowsW - scoreRight + si.right)
        else (scoreRight - sw + si.left) to (scoreRight - sw + si.right)
        return RowExtents(
            code = code,
            ball = span(ballL, ballL + ballPx),
            pips = span(anchor, anchor + 3 * pipW + 2 * pipGap),
            score = scoreInk,
            scorePx = spx,
            codePx = px,
        )
    }

    internal class RowExtents(
        val code: Pair<Float, Float>,
        val ball: Pair<Float, Float>,
        val pips: Pair<Float, Float>,
        val score: Pair<Float, Float>,
        val scorePx: Float,
        val codePx: Float,
    )

    internal val possStripeWidth: Float get() = possStripeW
    internal val stripeWidth: Float get() = stripeW
    internal val rowsWidth: Float get() = rowsW
    internal val clockTextBudget: Float get() = clockBudget()
    internal val columnTextBudget: Float get() = columnBudget()
    /** The pips' height and top in a row, device px. */
    internal val pipHeight: Float get() = pipH
    internal val pipTopY: Float get() = pipTop

    /** The clock's drawn width for [secs], device px. */
    internal fun clockWidth(secs: Int): Float = (clockChars(secs, CharArray(8)) - 1) * digitCell + colonAdvance

    /** The ink width of [text] at [px] design px in the clock column's period or slot paint, device px. */
    internal fun columnWidth(text: String, px: Float, period: Boolean = false): Float {
        val p = Paint(if (period) periodPaint else slotPaint)
        p.textSize = px * s
        val r = Rect()
        p.getTextBounds(text, 0, text.length, r)
        return r.width().toFloat()
    }

    companion object {
        private const val DIGITS = "0123456789"
        private const val OPAQUE = 0xFF shl 24
        private const val CODE_TRACK_EM = 0.01f
        private const val PERIOD_TRACK_EM = 0.02f
        private const val BAR_TRACK_EM = 0.01f
        /** The code's line box top in the row: (120 − (56 + 8 + 6)) / 2 mock = 25 → 11.25 (§2.3). */
        private const val CODE_LINE_TOP = 11.25f
        /** A 4+ letter code (§5.4): 2-px steps, floor 18, keeping 12 to the score slot. */
        private const val CODE_MIN_PX = 18f
        private const val CODE_STEP_PX = 2f
        private const val CODE_SCORE_GAP_MIN = 12f
        /** The score (§2.3, §5.4): 35.1, three digits fitted to 43. */
        private const val SCORE_PX = M.SCORE_PX * K.STACKED_SCALE
        private const val SCORE_FIT_W = 43f
        /** The pop's scale is clamped here (the timeline's spring overshoots to 1.5). */
        private const val POP_SCALE_MIN = 0.5f
        private const val POP_SCALE_MAX = 1.6f
        /** The burn's white flash: the mock's `toBurn` 50 % opacity. */
        private const val PIP_BURN_ALPHA = 0.15f
        /** The divider: white .5 on a team or neutral bar, black .4 on the flag bar (§2.4). */
        private const val DIVIDER_ALPHA = 0.5f
        private const val FLAG_DIVIDER_ALPHA = 0.4f
        /** The red-zone sub patch (§6.3): `mixBlack(#C8102E, .172)`. */
        const val RED_ZONE_SUB = 0xFFA60D26.toInt()
        /** The possession ball's fill (§6.3). */
        const val BALL_FILL = 0xFF8B4513.toInt()
        /** The flag icon's viewBox. */
        private const val FLAG_VIEW_W = 30f
        private const val FLAG_VIEW_H = 34f
        /** The sprites are baked at this multiple of their drawn size (as `NFL_ATLAS_SCALE`). */
        private const val SPRITE_SCALE = 1.5f
        private const val CLOCK_BREATHING = 5f
        private const val WORD_BREATHING = 6f
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

        private fun isTeamKind(k: NflBarKind) = k == NflBarKind.PLAY || k == NflBarKind.PAT || k == NflBarKind.KICKOFF ||
            k == NflBarKind.TIMEOUT || k == NflBarKind.SCORE

        /** The five score words (`sports_nfl_*`) by kind. */
        internal fun scoreWord(k: NflScoreKind): Int = when (k) {
            NflScoreKind.TOUCHDOWN -> R.string.sports_nfl_td
            NflScoreKind.EXTRA_POINT -> R.string.sports_nfl_xp
            NflScoreKind.FIELD_GOAL -> R.string.sports_nfl_fg
            NflScoreKind.SAFETY -> R.string.sports_nfl_safety
            NflScoreKind.TWO_POINT -> R.string.sports_nfl_2pt
        }

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
