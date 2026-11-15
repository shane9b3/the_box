package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.thebox.core.sports.ClockSlot
import app.thebox.core.sports.DownText
import app.thebox.core.sports.FieldZone
import app.thebox.core.sports.NflBarKey
import app.thebox.core.sports.NflBarKind
import app.thebox.core.sports.NflScoreFrame
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.NflScoreSpec
import app.thebox.core.sports.NflScoreTimeline
import app.thebox.core.sports.NflTeamColours
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedColours
import app.thebox.core.sports.StackedNflFrame
import app.thebox.core.sports.StackedNflTargets
import app.thebox.core.sports.StackedPair
import app.thebox.core.sports.TabTail
import java.io.File
import kotlin.math.abs

/**
 * NFL Stacked WP-3's hand-built painter inputs (§9.2 "hand-built frames"): nothing here reads a WP-2 stub
 * (`StackedNflTargets.of`, `StackedNflTimeline`, `NflScoreSpec.STACKED`, `NflStackedColours`).
 *
 * **Re-point at integration (lead):** [SPEC] → `NflScoreSpec.STACKED`; [pair] → `NflStackedColours.pair`; [frame] /
 * [shake] / [wave] / [burn] → `StackedNflTimeline`; [targets] → `StackedNflTargets.of(view)`. The numbers below are the
 * design's own (§4.3 STACKED column, §6.2 table, §4.1 keyframes), so the shots should not move when they are re-pointed
 * unless WP-2 differs from the design.
 */
internal object StackedNflTestKit {
    /** The 32 NFL abbreviations. */
    val TEAMS = listOf(
        "ARI", "ATL", "BAL", "BUF", "CAR", "CHI", "CIN", "CLE", "DAL", "DEN", "DET", "GB", "HOU", "IND", "JAX", "KC",
        "LAC", "LAR", "LV", "MIA", "MIN", "NE", "NO", "NYG", "NYJ", "PHI", "PIT", "SEA", "SF", "TB", "TEN", "WSH",
    )

    private fun c(code: String, stripe: Long, barBg: Long, barA: Long, barB: Long, flash: Float) = StackedColours(
        stripe = stripe.toInt(), barBg = barBg.toInt(), barA = barA.toInt(), barB = barB.toInt(),
        goal = NflTeamColours.of(code), rowFlashAlpha = flash,
    )

    /** §6.2's rows (stripe, bar bg, A, B, flash α) for the teams the screenshot set uses; "swap" = the S3 swap stripe. */
    val KC = c("KC", 0xFFFFB81C, 0xFFBC142E, 0xFFFFB81C, 0xFFEE7487, 0.45f)
    val BUF = c("BUF", 0xFFD13D59, 0xFF002A75, 0xFFC60C30, 0xFF6685BB, 0.50f)
    val IND = c("IND", 0xFFA2AAAD, 0xFF00244F, 0xFFA2AAAD, 0xFF66809F, 0.35f)
    val NE = c("NE", 0xFFD13D59, 0xFF001C38, 0xFFC60C30, 0xFFFFFFFF, 0.50f)
    val NE_SWAP = NE.copy(stripe = 0xFF667A8F.toInt())
    val DAL = c("DAL", 0xFF869397, 0xFF031937, 0xFF869397, 0xFFFFFFFF, 0.30f)
    val DAL_SWAP = DAL.copy(stripe = 0xFF68788E.toInt())
    val NYG = c("NYG", 0xFFBD5264, 0xFF091C54, 0xFFA71930, 0xFF6D7AA3, 0.45f)
    val PHI = c("PHI", 0xFFA5ACAF, 0xFF003F46, 0xFFA5ACAF, 0xFFFFFFFF, 0.35f)
    val LV = c("LV", 0xFFA5ACAF, 0xFF000000, 0xFFA5ACAF, 0xFF666666, 0.35f)
    val PIT = c("PIT", 0xFFFFB612, 0xFF0D141A, 0xFFFFB612, 0xFF707479, 0.45f)
    val CIN = c("CIN", 0xFFFB4F14, 0xFFD04111, 0xFF000000, 0xFFFD9572, 0.40f)
    val DEN_SWAP = c("DEN", 0xFF667A8F, 0xFFD04111, 0xFF002244, 0xFFFD9572, 0.40f)
    val FALLBACK = c("???", 0xFF5CA8F2, 0xFF232935, 0xFF5CA8F2, 0xFF7F838C, 0.35f)

    /** The matchups of §9.2 (away first), as their pairs after the §6.2 collision rule. */
    val PAIRS: Map<String, StackedPair> = linkedMapOf(
        "KC-BUF" to StackedPair(KC, BUF),
        "IND-NE" to StackedPair(IND, NE),
        "DAL-NYG" to StackedPair(DAL, NYG),
        "BUF-NE" to StackedPair(BUF, NE_SWAP),
        "DAL-PHI" to StackedPair(DAL_SWAP, PHI),
        "LV-PIT" to StackedPair(LV, PIT),
        "CIN-DEN" to StackedPair(CIN, DEN_SWAP),
    )

    /** Any code's pair (for the fit tests): the fallback colours; the words are what is measured there. */
    fun pair(away: String, home: String): StackedPair = PAIRS["$away-$home"] ?: StackedPair(FALLBACK, FALLBACK)

    /** §4.3's STACKED column (WP-2 puts the same numbers into `NflScoreSpec.STACKED`). */
    val SPEC: NflScoreSpec = NflScoreSpec.PILL.copy(
        streak1Alpha = 0.55f, streak2Alpha = 0.30f, streakFrom = -90f, streakTo = 450f,
        runFrom = -36f, runTo = 450f, runY = 38.25f, runPx = 31.5f, runAlpha = 0.45f,
        arcP0X = 13.5f, arcP0Y = 72f, arcP1X = 135f, arcP1Y = -4.5f, arcP2X = 238.5f, arcP2Y = 23.85f,
        arcS0 = 1f, arcS1 = 0.75f, arcS2 = 0.45f, arcR0 = 0f, arcR1 = 360f, arcR2 = 720f, arcAlpha = 0.6f,
        postsX = 234f, postsY = 22.5f, postsW = 40.5f, postsH = 54f, postsDy = 7.2f,
        flashAlpha = 0.40f, badgeDx = -22.5f, badgeMidDx = 1.8f, letterDrop = 27f, letterOvershoot = -2.25f,
        pointsDx = 16.2f, pointsMidDx = -1.35f, tabPulseCycles = 0, rowFlashMs = 1_200f,
        endMs = 4_800f, pairSecondMs = 4_800f, pairEndMs = 9_600f, stillFrameBMs = 1_200f,
    )

    /** The takeover at [t] under [SPEC] through the real `NflScoreTimeline` (its shared keyframes are final). */
    fun score(t: Float, first: NflScoreKind, second: NflScoreKind? = null, still: Boolean = false, out: NflScoreFrame = NflScoreFrame()): NflScoreFrame =
        when {
            still -> NflScoreTimeline.sampleStill(SPEC, first, second, t, out)
            second != null -> NflScoreTimeline.samplePair(SPEC, first, second, t, out)
            else -> NflScoreTimeline.sample(SPEC, first, t, out)
        }

    /**
     * A layout at rest (or part-way, by fraction): the clock column [clock] open, the bar [bar] open, the possession side's
     * ball and wide stripe, each side's pips lit up to its count.
     */
    fun frame(
        clock: Float = 1f,
        bar: Float = 1f,
        poss: Side? = null,
        awayLit: Int = 3,
        homeLit: Int = 2,
        text: Float = bar,
    ): StackedNflFrame = StackedNflFrame().apply {
        clockWidth = clock; clockAlpha = clock
        barHeight = bar; barClosed = bar <= 0f
        stripeAX = K.STACKED_BAR_STRIPE_TRAVEL * (1f - text); stripeBX = stripeAX
        barTextAlpha = text; barTextDx = K.STACKED_NFL_REKEY_TEXT_DX * (1f - text); barBgMix = 1f
        stripeAway = if (poss == Side.AWAY) 1f else 0f
        stripeHome = if (poss == Side.HOME) 1f else 0f
        ballAwayAlpha = stripeAway; ballHomeAlpha = stripeHome
        for (i in 0 until StackedNflFrame.PIPS) {
            pipAway[i] = if (i < awayLit) 1f else 0f
            pipHome[i] = if (i < homeLit) 1f else 0f
        }
    }

    private fun easeInOut(x: Float): Float = x * x * (3f - 2f * x)

    /** §4.1 FLAG shake at [t] ms since the flag REKEY: 2 × 500 from 150, 0 → −2.7 → +2.7 → −1.8 → +1.8 → 0. */
    fun shake(t: Float): Float {
        val u = t - K.STACKED_NFL_SHAKE_DELAY_MS
        if (u < 0f || u >= K.STACKED_NFL_SHAKE_MS * K.STACKED_NFL_SHAKE_CYCLES) return 0f
        val p = (u % K.STACKED_NFL_SHAKE_MS) / K.STACKED_NFL_SHAKE_MS
        val keys = floatArrayOf(0f, -K.STACKED_NFL_SHAKE_DX1, K.STACKED_NFL_SHAKE_DX1, -K.STACKED_NFL_SHAKE_DX2, K.STACKED_NFL_SHAKE_DX2, 0f)
        val seg = minOf((p / 0.2f).toInt(), 4)
        val q = easeInOut((p - seg * 0.2f) / 0.2f)
        return keys[seg] + (keys[seg + 1] - keys[seg]) * q
    }

    /** §4.1 FLAG wave at [t]: −8° ⇄ +8°, 600 per half, ease-in-out. */
    fun wave(t: Float): Float {
        val half = K.STACKED_NFL_WAVE_MS.toFloat()
        val n = (t / half).toInt()
        val p = easeInOut((t - n * half) / half)
        val from = if (n % 2 == 0) -K.STACKED_NFL_WAVE_DEG else K.STACKED_NFL_WAVE_DEG
        return from + (-2f * from) * p
    }

    /** §4.1 BURN at [t] for the pip at [idx]: (lit, burn flash) — 4 × 400 white flashes, then 400 ease to used. */
    fun burn(f: StackedNflFrame, side: Side, idx: Int, t: Float): StackedNflFrame {
        val lit = if (side == Side.AWAY) f.pipAway else f.pipHome
        val fl = if (side == Side.AWAY) f.pipAwayBurn else f.pipHomeBurn
        val cyc = K.STACKED_NFL_BURN_CYCLE_MS.toFloat()
        val flashes = cyc * K.STACKED_NFL_BURN_CYCLES
        if (t < flashes) {
            val p = (t % cyc) / cyc
            lit[idx] = 1f
            fl[idx] = easeInOut(if (p < 0.5f) p * 2f else 2f - p * 2f)
        } else {
            lit[idx] = 1f - easeInOut(((t - flashes) / cyc).coerceIn(0f, 1f))
            fl[idx] = 0f
        }
        return f
    }

    /** Hand-built targets (only what the painter reads: the pips' counts and slots). */
    fun targets(away: Int? = 3, home: Int? = 2, slots: Int = 3, poss: Side? = null, bar: NflBarKey? = null, open: Boolean = true) =
        StackedNflTargets(
            clockOpen = open, bar = bar, possSide = poss, period = PeriodLabel(PeriodKind.REG, 4), clockSlot = ClockSlot.Clock,
            columnDim = false, barDim = false, awayTimeouts = away, homeTimeouts = home, timeoutSlots = slots, burnSide = null,
        )

    @Suppress("LongParameterList")
    fun state(
        p: StackedNflPainter,
        away: String = "KC",
        home: String = "BUF",
        awayScore: String = "24",
        homeScore: String = "17",
        period: String = "4TH",
        slot: ClockSlot = ClockSlot.Clock,
        slotText: String = "",
        clockSecs: Int = 222,
        dim: Boolean = false,
        targets: StackedNflTargets = targets(),
    ): StackedNflFrameState {
        val fit = if (slot == ClockSlot.Clock || slotText.isEmpty()) null else p.fitSlot(slotText)
        return StackedNflFrameState(
            awayAbbrev = away, homeAbbrev = home, awayScore = awayScore, homeScore = homeScore,
            periodText = period, periodPx = if (period.isEmpty()) 21.6f else p.fitColumn(period, 21.6f, 0.02f),
            slot = slot, slotText = slotText, slotPx = fit?.px ?: p.clockPx, clockSecs = clockSecs, columnDim = dim,
            targets = targets, slotBreak = fit?.brk ?: -1, slotDy1 = fit?.dy1 ?: 0f, slotDy2 = fit?.dy2 ?: 0f,
        )
    }

    // ---- bar keys
    fun play(side: Side?, down: Int, dist: Int?, goal: Boolean = false, tail: TabTail? = null) =
        NflBarKey(NflBarKind.PLAY, side, down = DownText.Down(down, dist, goal), tail = tail)
    fun field(zone: FieldZone, yard: Int) = TabTail.Field(zone, yard)

    // ---- logos: the mock's two PNGs for KC and BUF when present, else a synthetic team-colour oval
    private val ASSETS = mapOf(
        "KC" to "3bbd50edac45d15e8b4888e874011dbf.png",
        "BUF" to "a93d56206deaee9924dace155c9d15af.png",
    )

    fun logo(code: String, px: Int): Bitmap {
        ASSETS[code]?.let { name ->
            val f = listOf(File("../design/nfl-stacked/assets/$name"), File("design/nfl-stacked/assets/$name")).firstOrNull { it.isFile }
            val src = f?.let { BitmapFactory.decodeFile(it.path) }
            if (src != null) {
                val k = minOf(px.toFloat() / src.width, px.toFloat() / src.height)
                val w = maxOf(1, Math.round(src.width * k))
                val h = maxOf(1, Math.round(src.height * k))
                return Bitmap.createScaledBitmap(src, w, h, true)
            }
        }
        val colour = NflTeamColours.of(code).base
        return Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).apply {
            val c = Canvas(this)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val b = px * 0.06f
            p.color = 0xFFF2F2F2.toInt()
            c.drawOval(px * 0.08f, px * 0.12f, px * 0.92f, px * 0.88f, p)
            p.color = colour or (0xFF shl 24)
            c.drawOval(px * 0.08f + b, px * 0.12f + b, px * 0.92f - b, px * 0.88f - b, p)
            p.color = Color.WHITE
            c.drawRect(px * 0.36f, px * 0.45f, px * 0.64f, px * 0.55f, p)
        }
    }

    const val DARK = 0x01010101
    const val GREY = 0xFF808080.toInt()
    const val TRANSPARENT = 0x00000000

    /** The lead's contact-sheet folder (outside the repo); created on demand. */
    val SHEETS = File("/private/tmp/claude-501/-Users-shawnjohnson-Projects-The-Box/dedf5c50-b6f4-4a8c-a23c-fadfe67ec05f/scratchpad/nfl-stacked/wp3")

    fun near(a: Int, b: Int, tol: Int) =
        abs(Color.red(a) - Color.red(b)) <= tol && abs(Color.green(a) - Color.green(b)) <= tol && abs(Color.blue(a) - Color.blue(b)) <= tol
}
