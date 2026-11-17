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
import app.thebox.core.sports.NflStackedColours
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedMock
import app.thebox.core.sports.StackedNflFrame
import app.thebox.core.sports.StackedNflGeometry as G
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
 * **The takeover** (integration ruling 1): the runner hands the score painter, its exact-box glow and the shadow through
 * [StackedNflDrawArgs] ([StackedNflDrawArgs.takeover], [StackedNflDrawArgs.glow], [StackedNflDrawArgs.shadow]) and
 * [drawFrame] draws, as the hockey composite: glow, shadow, box, bar, rows (with the row flash and the pop), clock
 * column, the takeover over the top part, and the border on top. Nothing else draws the takeover.
 */
internal class StackedNflPainter(
    val s: Float,
    val large: Boolean,
    val sansBold: Typeface?,
    val barFont: Typeface?,
) {
    private val white = Color.WHITE

    // ---- geometry, device px (every number from `SportsConstants` / `StackedNflGeometry`: one rule, one place)
    private val h = K.STACKED_H * s
    private val rowH = h / 2f
    private val rowsW = K.STACKED_ROWS_W * s
    private val clockW = K.STACKED_CLOCK_W * s
    private val radius = StackedMock.BOX_RADIUS * K.STACKED_SCALE * s
    private val line = (K.STACKED_HAIRLINE_W * s).coerceAtLeast(1f)
    private val stripeW = K.STACKED_NFL_STRIPE_W * s
    private val possStripeW = K.STACKED_NFL_POSS_STRIPE_W * s
    private val logoSlotW = K.STACKED_NFL_LOGO_SLOT_W * s
    /** The logo's fit box inside the slot (§2.3: 50.4 × 43.2, the hockey row's logo box). */
    private val logoFitW = K.STACKED_NFL_LOGO_FIT_W * s
    private val logoFitH = K.STACKED_NFL_LOGO_FIT_H * s
    private val codeTop = K.STACKED_NFL_CODE_TOP * s
    private val ballPx = K.STACKED_NFL_BALL * s
    private val pipW = K.STACKED_NFL_PIP_W * s
    private val pipH = K.STACKED_NFL_PIP_H * s
    private val pipR = K.STACKED_NFL_PIP_R * s
    private val pipTop = K.STACKED_NFL_PIP_TOP * s
    private val scoreSlotW = K.STACKED_NFL_SCORE_W * s
    private val scoreRight = G.SCORE_RIGHT * s
    private val periodBottom = (K.STACKED_NFL_PERIOD_SLOT_H - K.STACKED_NFL_PERIOD_PAD_B) * s
    private val ruleTop = K.STACKED_NFL_PERIOD_SLOT_H * s
    private val ruleMargin = K.STACKED_NFL_RULE_MARGIN * s
    private val clockCy = (ruleTop + line + h) / 2f
    private val slotH = h - (ruleTop + line)
    private val barH = K.STACKED_BAR_H * s
    private val barStripeW = K.STACKED_NFL_BAR_STRIPE_W * s
    private val barOverhang = K.STACKED_NFL_BAR_STRIPE_OVERHANG * s
    private val barGap = K.STACKED_NFL_BAR_TEXT_GAP * s
    private val dividerW = (K.STACKED_NFL_BAR_DIVIDER_W * s).coerceAtLeast(1f)
    private val dividerH = K.STACKED_NFL_BAR_DIVIDER_H * s
    private val iconW = K.STACKED_NFL_FLAG_ICON_W * s
    private val iconH = K.STACKED_NFL_FLAG_ICON_H * s
    /** `StackedNflGeometry.barGroup`'s output (draw path: preallocated). */
    private val group = FloatArray(G.G_SIZE)

    // ---- paints (built once; draw only changes colour, alpha and size)
    private fun text(px: Float, tf: Typeface?, align: Paint.Align, track: Float = 0f, tnum: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = px * s; typeface = tf; color = white; textAlign = align
            if (track != 0f) letterSpacing = track
            if (tnum) fontFeatureSettings = "'tnum'"
        }

    private val codePaint = text(K.STACKED_NFL_CODE_PX, sansBold, Paint.Align.LEFT, CODE_TRACK_EM)
    private val codeMirroredPaint = Paint(codePaint).apply { textAlign = Paint.Align.RIGHT }
    private val scorePaint = text(K.STACKED_NFL_SCORE_PX, sansBold, Paint.Align.RIGHT, tnum = true)
    private val scoreMirroredPaint = Paint(scorePaint).apply { textAlign = Paint.Align.LEFT }
    private val periodPaint = text(K.STACKED_NFL_PERIOD_PX, sansBold, Paint.Align.CENTER, PERIOD_TRACK_EM)
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

    /** The fallback look (a null [StackedNflDrawArgs.colours]): the NFL table's own fallback pair, resolved once. */
    private val fallback: StackedPair = NflStackedColours.pair(null, null)

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

    /** The possession ball: the mock's 24-unit football, filled `#8B4513`, stroked white 1.6 / 24, at 1.5× its 21 px (13.5 before owner feedback 2026-11-16). */
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
        c.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = K.STACKED_NFL_BALL_FILL })
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
    private fun clockBudget(): Float = clockW - 2 * K.STACKED_NFL_CLOCK_AIR * s

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
        val periodStart = K.STACKED_NFL_PERIOD_PX
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
            // Owner ruling 2026-11-16: a two-point try (the key's tail) reads "2-PT TRY".
            NflBarKind.PAT -> label = res.getString(if (key.tail == TabTail.TwoPointTry) R.string.sports_tab_2pt_try else R.string.sports_tab_xp)
            // RC9: a kick-off never reads RED ZONE, whatever the key carries.
            NflBarKind.KICKOFF -> {
                label = res.getString(R.string.sports_stacked_nfl_kickoff, abbrev)
                sub = field(res, key.tail, side, awayAbbrev, homeAbbrev)
            }
            NflBarKind.FLAG -> label = res.getString(R.string.sports_stacked_nfl_flag)
            NflBarKind.TIMEOUT -> {
                label = res.getString(R.string.sports_stacked_nfl_timeout, abbrev)
                sub = rootFmt(res, R.string.sports_stacked_nfl_left, (key.left ?: 0).coerceIn(0, K.NFL_TIMEOUTS_MAX))
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
                key.points?.let { pts -> sub = rootFmt(res, R.string.sports_stacked_nfl_points, abbrev, pts) }
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
        // §2.6: the one colour rule (`NflStackedColours.bar`): team bar, the yellow flag bar, or the neutral bar.
        val colours = NflStackedColours.bar(key, p)
        content.bg = colours.bg
        content.a = colours.a
        content.b = colours.b
        content.fg = colours.fg
        content.labelW = barPaint.measureText(content.label, 0, content.labelLen)
        content.subW = if (content.subLen > 0) barSubPaint.measureText(content.sub, 0, content.subLen) else 0f
        content.wordsAdvance = wordsAdvance(content)
    }

    /**
     * (Review fix U-N6) A bar format with digits, in `Locale.ROOT`, so an Arabic-locale device never mixes Arabic-Indic
     * digits (fallback glyphs) with the box's Western score digits. Only the Stacked bar; the Pill is unchanged.
     */
    private fun rootFmt(res: Resources, id: Int, vararg args: Any): String = String.format(Locale.ROOT, res.getString(id), *args)

    /** The bar's words' advance (device px): `StackedNflGeometry.barGroupW` of the measured label and sub. */
    private fun wordsAdvance(b: NflBarContent): Float =
        G.barGroupW(b.flag, labelW(b) / s, subW(b) / s) * s

    // U-N1: the widths cached by fillBar; a hand-filled content (tests) is measured.
    private fun labelW(b: NflBarContent): Float = if (b.labelW >= 0f) b.labelW else barPaint.measureText(b.label, 0, b.labelLen)
    private fun subW(b: NflBarContent): Float = if (b.subW >= 0f) b.subW else if (b.subLen > 0) barSubPaint.measureText(b.sub, 0, b.subLen) else 0f

    /** The down label (§3.4): "1ST & 10", "4TH & GOAL", "3RD", "EXTRA POINT" for the try, "KICKOFF". */
    private fun down(res: Resources, d: DownText): String = when (d) {
        is DownText.Down -> {
            val n = res.getString(
                when (d.down) { 1 -> R.string.sports_down_1; 2 -> R.string.sports_down_2; 3 -> R.string.sports_down_3; else -> R.string.sports_down_4 },
            )
            when {
                d.goal -> res.getString(R.string.sports_down_goal, n)
                d.distance != null -> rootFmt(res, R.string.sports_down_and, n, d.distance!!)
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
        // C-N4: a neutral key's tail is relative to the away side (StackedNflTargets.neutralTail).
        val rel = side ?: Side.AWAY
        val ownSide = if (t.zone == FieldZone.OWN) rel else if (rel == Side.AWAY) Side.HOME else Side.AWAY
        val who = if (ownSide == Side.AWAY) awayAbbrev else homeAbbrev
        return rootFmt(res, R.string.sports_stacked_nfl_field, who, t.yard.coerceIn(0, 99))
    }

    /**
     * The drop shadow's nine-slice for this size (ST §6), baked off the main thread by the runner, which hands this
     * painter's own bake back through [StackedNflDrawArgs.shadow].
     */
    fun bakeShadow(): NineSlice? = NineSlice(s, large)

    // ------------------------------------------------------------------ the composite

    /**
     * The whole box for [a] (see [StackedNflDrawArgs] for the coordinates), in the hockey composite's order
     * (`StackedPainter.drawFrame`): the glow ([StackedNflDrawArgs.takeover]'s exact-box masks, when
     * [StackedNflDrawArgs.glowOn]), the shadow ([StackedNflDrawArgs.shadow]), box fill, bar, rows (stripes, logos, codes,
     * balls, pips, scores, the row flash and the pop), clock column, the takeover over the top part, and the border on
     * top (the mock's border is outside the content, so the takeover never covers it). Allocation-free.
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
        // §5.3: mirrored, the rows hug the box's right edge, so the top part spans [rowsW - w, rowsW].
        val l = if (mirrored) rowsW - w else 0f
        box.set(l, top, l + w, bottom)

        val g = a.score
        val scoring = a.takeoverOn && g != null && !g.done && a.scoreSide != null
        val tk = a.takeover
        val drawTakeover = scoring && tk != null && tk.colours != null

        // 1. The glow around the whole box (the pinned box under the pin), from the exact-box masks.
        if (drawTakeover && a.glowOn) a.glow?.let { tk!!.drawGlow(c, g!!, it, l, top) }
        // 2. The drop shadow, stretched to the box as it is now.
        a.shadow?.draw(c, l, top, l + w, bottom, shadowPaint)
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
        // 8. The border, 1 px `#2B3550`, inside the box's edge, on top of everything.
        stroke.strokeWidth = line
        stroke.color = K.STACKED_BORDER
        box.inset(line / 2f, line / 2f)
        c.drawRoundRect(box, radius - line / 2f, radius - line / 2f, stroke)
        box.inset(-line / 2f, -line / 2f)
    }

    /**
     * The bar ([l]..[r], device px): background (lerped through a REKEY), the red-zone patch, the two stripes, the words,
     * the hairline, under its clip. Positions are `StackedNflGeometry`'s (bar-local design px, x [s]): the stripes
     * ([G.barStripeX]) and the words group ([G.barGroup]: reading order on both sides, right-aligned when mirrored, the
     * red-zone patch from the divider to the bar's end); the REKEY slide and the flag shake are offsets through [G.dx]
     * (section 5.3: the slide starts on the screen-edge side, Rec 5).
     */
    private fun drawBar(c: Canvas, a: StackedNflDrawArgs, f: StackedNflFrame, bh: Float, l: Float, r: Float) {
        val cur = a.bar ?: return
        val prev = a.barPrev
        val bg = if (prev != null && f.barBgMix < 1f) lerpColour(prev.bg, cur.bg, f.barBgMix) else cur.bg
        val regionTop = if (a.up) -bh else h
        val regionBottom = regionTop + bh
        val contentTop = if (a.up) regionTop else regionBottom - barH
        val m = a.mirrored
        val barW = (r - l) / s
        // The flag bar's shake moves the words, divider and stripes (mirrored: the other way).
        val shake = G.dx(if (cur.flag) f.shakeDx else 0f, m) * s
        val textAlpha = f.barTextAlpha * (if (cur.dim) K.STACKED_DIM_ALPHA else 1f)
        val words = cur.labelLen > 0
        val gr = G.barGroup(barW, cur.flag, labelW(cur) / s, subW(cur) / s, m, group)
        val x0 = l + G.dx(f.barTextDx, m) * s + shake
        c.withClip(l, regionTop, r, regionBottom) {
            fill.color = bg or OPAQUE
            drawRoundRect(box, radius, radius, fill)
            // The red-zone patch under the sub part (R26), with the words' alpha.
            if (cur.redZone && words && textAlpha > 0f && !gr[G.G_RED_FROM].isNaN()) {
                fill.color = argb(textAlpha, NflStackedColours.RED_ZONE_SUB)
                // Left corners to the bar's end; mirrored a gap past the sub, moving with the words (ruling 4).
                val redEnd = (if (m) x0 else l) + gr[G.G_RED_TO] * s
                drawRect(x0 + gr[G.G_RED_FROM] * s, regionTop, redEnd, regionBottom, fill)
            }
            val stripeTop = contentTop - barOverhang
            fill.color = cur.a or OPAQUE
            val ax = l + G.barStripeX(K.STACKED_NFL_BAR_STRIPE_A_X, f.stripeAX, barW, m) * s + shake
            withTranslation(ax, stripeTop) { drawPath(stripePath, fill) }
            fill.color = cur.b or OPAQUE
            val bx = l + G.barStripeX(K.STACKED_NFL_BAR_STRIPE_B_X, f.stripeBX, barW, m) * s + shake
            withTranslation(bx, stripeTop) { drawPath(stripePath, fill) }
            if (textAlpha > 0f && words) drawBarWords(this, cur, contentTop + barH / 2f, x0, gr, textAlpha, f.waveDeg)
            // The hairline on the bar's inner edge (ST S2: every bar).
            fill.color = argb(K.STACKED_BAR_HAIRLINE_ALPHA, 0xFFFFFF)
            if (a.up) drawRect(l, regionBottom - line, r, regionBottom, fill) else drawRect(l, regionTop, r, regionTop + line, fill)
        }
    }

    /**
     * The bar's words centred on [cy] at the group positions [gr] (bar-local design px, from [x0] in device px):
     * [icon] label | sub, with the hard text shadow off the flag bar.
     */
    private fun drawBarWords(c: Canvas, b: NflBarContent, cy: Float, x0: Float, gr: FloatArray, alpha: Float, waveDeg: Float) {
        val baseline = cy + barOffset
        val fg = b.fg and 0xFFFFFF
        if (b.flag && !gr[G.G_ICON].isNaN()) {
            spritePaint.color = argb(alpha, fg)
            val iy = cy - iconH / 2f
            c.withTranslation(x0 + gr[G.G_ICON] * s, iy) {
                rotate(waveDeg, K.STACKED_NFL_WAVE_PIVOT_X * s, K.STACKED_NFL_WAVE_PIVOT_Y * s)
                spriteDst.set(0f, 0f, iconW, iconH)
                drawBitmap(flagIcon, null, spriteDst, spritePaint)
            }
        }
        val shadowOn = !b.flag
        val dy = K.STACKED_BAR_TEXT_SHADOW_DY * s
        val lx = x0 + gr[G.G_LABEL] * s
        if (shadowOn) {
            barPaint.color = argb(alpha * K.STACKED_TEXT_SHADOW_ALPHA, 0x000000)
            c.drawText(b.label, 0, b.labelLen, lx, baseline + dy, barPaint)
        }
        barPaint.color = argb(alpha, fg)
        c.drawText(b.label, 0, b.labelLen, lx, baseline, barPaint)
        if (b.subLen <= 0 || gr[G.G_SUB].isNaN()) return
        val dx = x0 + gr[G.G_DIVIDER] * s
        fill.color = if (b.flag) argb(alpha * K.STACKED_NFL_FLAG_DIVIDER_ALPHA, 0x000000)
        else argb(alpha * K.STACKED_NFL_BAR_DIVIDER_ALPHA, 0xFFFFFF)
        c.drawRect(dx, cy - dividerH / 2f, dx + dividerW, cy + dividerH / 2f, fill)
        val sx = x0 + gr[G.G_SUB] * s
        if (shadowOn) {
            barSubPaint.color = argb(alpha * K.STACKED_TEXT_SHADOW_ALPHA, 0x000000)
            c.drawText(b.sub, 0, b.subLen, sx, baseline + dy, barSubPaint)
        }
        barSubPaint.color = argb(alpha, fg)
        c.drawText(b.sub, 0, b.subLen, sx, baseline, barSubPaint)
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
        val awayStripe = G.stripeW(f.stripeAway.coerceIn(0f, 1f)) * s
        val homeStripe = G.stripeW(f.stripeHome.coerceIn(0f, 1f)) * s
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
        row(c, 0f, Side.AWAY, awayStripe, st.awayAbbrev, a.awayLogo, a.awayLogoPending, st.awayScore, f, t, m, popSide == Side.AWAY, popScale, popColour, pair.away.stripe)
        row(c, rowH, Side.HOME, homeStripe, st.homeAbbrev, a.homeLogo, a.homeLogoPending, st.homeScore, f, t, m, popSide == Side.HOME, popScale, popColour, pair.home.stripe)
    }

    /**
     * One row from [y0]: logo, code, ball, pips and score, at `StackedNflGeometry`'s row-local positions ([G.logoX],
     * [G.codeX], [G.ballX], [G.pipX], [G.SCORE_RIGHT]; design px x [s]) for a [stripe]-wide stripe (device px). Mirrored,
     * every position is reflected about the rows ([G.rowX]) and the alignments swap; glyphs, logos and the ball are never
     * flipped (from the screen edge: stripe, logo, code, ball, score, then the clock column).
     */
    private fun row(
        c: Canvas, y0: Float, side: Side, stripe: Float, abbrev: String, logo: Bitmap?, logoPending: Boolean, score: String,
        f: StackedNflFrame, t: StackedNflTargets, m: Boolean, pop: Boolean, popScale: Float, popColour: Int, stripeColour: Int,
    ) {
        val cy = y0 + rowH / 2f
        val sw = stripe / s
        logo(c, G.rowX(G.logoX(sw), K.STACKED_NFL_LOGO_SLOT_W, m) * s + logoSlotW / 2f, cy, abbrev, logo, logoPending, stripeColour)
        // The code (a code too wide for its room steps down to fit, section 5.4; cached per string).
        val codePx: Float
        val codeAdv: Float
        if (side == Side.AWAY) {
            if (abbrev != awayCodeRef) { awayCodeRef = abbrev; awayCodePx = codeFitPx(abbrev); awayCodeAdv = codeAdvance(abbrev, awayCodePx) }
            codePx = awayCodePx; codeAdv = awayCodeAdv
        } else {
            if (abbrev != homeCodeRef) { homeCodeRef = abbrev; homeCodePx = codeFitPx(abbrev); homeCodeAdv = codeAdvance(abbrev, homeCodePx) }
            codePx = homeCodePx; codeAdv = homeCodeAdv
        }
        val codeX = G.codeX(sw)
        val cp = if (m) codeMirroredPaint else codePaint
        cp.textSize = codePx * s
        cp.color = white
        // Left-aligned at its start; mirrored, right-aligned at the reflected start.
        c.drawText(abbrev, if (m) (K.STACKED_ROWS_W - codeX) * s else codeX * s, y0 + codeBaseline, cp)
        // The possession ball, 5.4 after the code's advance, centred on the code's line.
        val ba = if (side == Side.AWAY) f.ballAwayAlpha else f.ballHomeAlpha
        if (ba > 0f) {
            val bs = if (side == Side.AWAY) f.ballAwayScale else f.ballHomeScale
            val bd = G.deg(if (side == Side.AWAY) f.ballAwayDeg else f.ballHomeDeg, m)
            val bl = G.rowX(G.ballX(codeX, codeAdv / s), K.STACKED_NFL_BALL, m) * s
            val bcx = bl + ballPx / 2f
            val bcy = y0 + (G.BALL_TOP + K.STACKED_NFL_BALL / 2f) * s
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
                    alpha += (K.STACKED_NFL_BURN_ALPHA - alpha) * b
                }
                fill.color = argb(alpha, colour)
                val left = G.rowX(G.pipX(codeX, i), K.STACKED_NFL_PIP_W, m) * s
                rect.set(left, py, left + pipW, py + pipH)
                c.drawRoundRect(rect, pipR, pipR, fill)
            }
        }
        // The score: right-aligned at 228.6 (mirrored: left-aligned at its reflection); 3 digits fitted (section 5.4).
        if (score.isEmpty()) return
        val spx = if (side == Side.AWAY) {
            if (score != awayScoreRef) { awayScoreRef = score; awayScorePx = scoreFitPx(score) }
            awayScorePx
        } else {
            if (score != homeScoreRef) { homeScoreRef = score; homeScorePx = scoreFitPx(score) }
            homeScorePx
        }
        val sp = if (m) scoreMirroredPaint else scorePaint
        sp.textSize = spx * s
        val sy = cy + scoreOffset * (spx / K.STACKED_NFL_SCORE_PX)
        val sx = if (m) rowsW - scoreRight else scoreRight
        if (!pop) {
            sp.color = white
            c.drawText(score, sx, sy, sp)
        } else {
            sp.color = popColour
            val pcx = if (m) rowsW - scoreRight + scoreSlotW / 2f else scoreRight - scoreSlotW / 2f
            c.withScale(popScale, popScale, pcx, cy) { drawText(score, sx, sy, sp) }
            sp.color = white
        }
    }

    /** The score's size (design px): `StackedNflGeometry.scorePx` (35.1 in the 45 slot; wider: fitted to 43, floor 24). */
    private fun scoreFitPx(score: String): Float {
        measurePaint.fontFeatureSettings = "'tnum'"
        val px = G.scorePx(s) { dev -> measurePaint.textSize = dev; measurePaint.measureText(score) }
        measurePaint.fontFeatureSettings = null
        return px / s
    }

    /** The code's size (design px): `StackedNflGeometry.codePx` (25.2, 2-px steps to 18; 18 when even that is too wide). */
    private fun codeFitPx(abbrev: String): Float {
        val old = codePaint.textSize
        val px = G.codePx(s) { dev -> codePaint.textSize = dev; codePaint.measureText(abbrev) }
        codePaint.textSize = old
        return (px ?: K.STACKED_FIT_MIN_PX * s) / s
    }

    private fun codeAdvance(abbrev: String, px: Float): Float {
        val old = codePaint.textSize
        codePaint.textSize = px * s
        val w = codePaint.measureText(abbrev)
        codePaint.textSize = old
        return w
    }

    /**
     * The logo fitted in 50.4 x 43.2 about ([cx], [cy]); with no bitmap, nothing while [pending] (the follow's logo is
     * loading, under `LOGO_WAIT_MS`, as the NHL Stacked bug), else the fallback disc (a 4+ letter code: the stripe disc).
     */
    private fun logo(c: Canvas, cx: Float, cy: Float, abbrev: String, b: Bitmap?, pending: Boolean, stripeColour: Int) {
        if (b != null) {
            val k = minOf(logoFitH / b.height.coerceAtLeast(1), logoFitW / b.width.coerceAtLeast(1))
            val lw = b.width * k
            val lh = b.height * k
            logoDst.set(cx - lw / 2f, cy - lh / 2f, cx + lw / 2f, cy + lh / 2f)
            c.drawBitmap(b, null, logoDst, bmpPaint)
        } else if (pending) {
            return
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
        val gr = G.barGroup((r - l) / s, b.flag, labelW(b) / s, subW(b) / s, mirrored, FloatArray(G.G_SIZE))
        val x = l + gr[G.G_LABEL] * s
        val out = RectF(x, 0f, x, 0f)
        if (b.flag) out.union(RectF(l + gr[G.G_ICON] * s, 0f, l + gr[G.G_ICON] * s + iconW, 1f))
        val path = Path()
        val t = RectF()
        barPaint.getTextPath(b.label, 0, b.labelLen, x, 0f, path)
        path.computeBounds(t, true)
        out.union(t)
        if (b.subLen > 0) {
            path.reset()
            barSubPaint.getTextPath(b.sub, 0, b.subLen, l + gr[G.G_SUB] * s, 0f, path)
            path.computeBounds(t, true)
            out.union(t)
        }
        return out
    }

    /**
     * A row's element extents for a [stripe]-wide stripe, device px from the rows' left (mirrored as drawn): the code's
     * ink, the ball, the pips (three slots) and the score's ink for [score] at its fitted size.
     */
    internal fun rowExtents(abbrev: String, score: String, stripe: Float, mirrored: Boolean): RowExtents {
        val px = codeFitPx(abbrev)
        val adv = codeAdvance(abbrev, px)
        val codeX = G.codeX(stripe / s) * s
        fun span(a: Float, b: Float) = if (mirrored) (rowsW - b) to (rowsW - a) else a to b
        val codeInk = Rect()
        val cp = Paint(codePaint).apply { textSize = px * s; textAlign = Paint.Align.LEFT }
        cp.getTextBounds(abbrev, 0, abbrev.length, codeInk)
        val code = span(codeX + codeInk.left, codeX + codeInk.right)
        val ballL = G.ballX(codeX / s, adv / s) * s
        val spx = scoreFitPx(score)
        val sp = Paint(scorePaint).apply { textSize = spx * s; textAlign = Paint.Align.LEFT }
        val sw = sp.measureText(score)
        val si = Rect()
        sp.getTextBounds(score, 0, score.length, si)
        val scoreInk = if (mirrored) (rowsW - scoreRight + si.left) to (rowsW - scoreRight + si.right)
        else (scoreRight - sw + si.left) to (scoreRight - sw + si.right)
        val pipsEnd = G.pipX(codeX / s, StackedNflFrame.PIPS - 1) * s + pipW
        return RowExtents(
            code = code,
            ball = span(ballL, ballL + ballPx),
            pips = span(codeX, pipsEnd),
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
        /** The pop's scale is clamped here (the timeline's spring overshoots to 1.5). */
        private const val POP_SCALE_MIN = 0.5f
        private const val POP_SCALE_MAX = 1.6f
        /** The flag icon's viewBox. */
        private const val FLAG_VIEW_W = 30f
        private const val FLAG_VIEW_H = 34f
        /** The sprites are baked at this multiple of their drawn size (as `NFL_ATLAS_SCALE`). */
        private const val SPRITE_SCALE = 1.5f
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
