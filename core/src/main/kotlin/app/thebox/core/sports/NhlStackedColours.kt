// Contract (Stacked commit 0): docs/design/m6-score-bug-stacked-theme.md §5. The types and the `of` / `pair` signatures are
// fixed for WP-1 (the derivation), WP-2 (the painters) and WP-3 (`StackedHooks.colours`); changes go through the lead.
package app.thebox.core.sports

import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt
import app.thebox.core.sports.SportsConstants as K

/**
 * (Stacked §5.1) One team's Stacked colours, ARGB ints, derived (never tabled) from `NhlTeamColours`:
 * - [stripe]: the row stripe (and the neutral bar's stripe for this side), ≥ `STACKED_STRIPE_MIN` against the row navy;
 * - [barBg]: the team's power-play bar background, `mixBlack(tab, STACKED_BAR_BG_BLACK)` (white text ≥ 4.5 : 1);
 * - [barA] / [barB]: the team bar's two skewed stripes (S2 candidate rules);
 * - [goal]: the takeover's panel, lead, glow and pop (`NhlTeamColours.of`, ruling 12; the true colours, never swapped);
 * - [rowFlashAlpha]: the row flash's peak α for [stripe] (C7, S11), between .30 and .55.
 *
 * The rows, columns, border and neutral bar are fixed (`STACKED_ROW_BG`, `STACKED_COL_BG`, `STACKED_BORDER`,
 * `STACKED_BAR_NEUTRAL`), not per team.
 */
data class StackedColours(
    val stripe: Int,
    val barBg: Int,
    val barA: Int,
    val barB: Int,
    val goal: TeamColours,
    val rowFlashAlpha: Float,
)

/** (Stacked §5.2) A game's two teams after the stripe-collision rule (S3): use these, never two separate [NhlStackedColours.of]. */
data class StackedPair(val away: StackedColours, val home: StackedColours)

/**
 * (Stacked §5; WP-1) The Stacked colour derivation, pure; asserted for all 32 teams and the fallback (`NhlStackedColoursTest`).
 *
 * Per team, from its `NhlTeamColours` (base, accent, tab, pop):
 * - **stripe**: whichever of base / accent contrasts more with `STACKED_ROW_BG`, mixed toward white in
 *   `STACKED_STRIPE_WHITE_STEP` steps (each channel rounded half to even, from the source each time) until it is
 *   ≥ `STACKED_STRIPE_MIN` against the row. The **other** stripe (the S3 swap) is the other colour under the same rule.
 * - **barBg** = `mixBlack(tab, STACKED_BAR_BG_BLACK)`;
 * - **barA**: the first of [the team colour that is not `tab`, `mixBlack(tab, STACKED_BAR_A_BLACK)`,
 *   `towardWhite(tab, STACKED_BAR_B_WHITE)`, white] that is ≥ `STACKED_BAR_STRIPE_MIN` against barBg;
 * - **barB**: the first of [`towardWhite(tab, STACKED_BAR_B_WHITE)`, the other team colour, white] that is
 *   ≥ `STACKED_BAR_STRIPE_MIN` against barBg and ≥ `STACKED_BAR_AB_MIN` against barA;
 * - **rowFlashAlpha** (C7): from `STACKED_ROW_FLASH_ALPHA` down in `STACKED_ROW_FLASH_STEP` steps until both white and
 *   the team's `pop` are ≥ `STACKED_C7_MIN` against the stripe at that α over the row navy (channels rounded half to
 *   even, as an 8-bit blend). Computed for the primary and for the swapped stripe.
 *
 * In either list, when no candidate passes, the last one (white) is used; the test shows that never happens.
 */
object NhlStackedColours {
    /**
     * The team's own resolution (trimmed, upper-cased; unknown or null → the fallback), before any collision swap.
     * Resolved once per team: allocates nothing beyond the key.
     */
    fun of(abbrev: String?): StackedColours = resolve(abbrev).primary

    /**
     * The game's pair with the §5.2 rule applied: if the two stripes collide (CIELAB ΔE76 < `STACKED_STRIPE_DE_MIN`), the
     * **home** team's stripe uses its other colour (lightened by the stripe rule); if that still collides, home keeps its
     * primary and the **away** team uses its other colour. The swapped stripe is used everywhere that team's stripe is
     * (row stripe, neutral bar stripes, row flash with its own C7 α); [StackedColours.goal] keeps the true colours. Cached
     * per pair.
     */
    fun pair(away: String?, home: String?): StackedPair {
        val a = index(away)
        val h = index(home)
        val slot = a * SLOTS + h
        PAIRS.get(slot)?.let { return it }
        val made = derivePair(TEAMS[a], TEAMS[h])
        return if (PAIRS.compareAndSet(slot, null, made)) made else PAIRS.get(slot) ?: made
    }

    // ------------------------------------------------------------------ the derivation

    /** One team, resolved once: its primary colours and the swapped (other-colour) variant for S3. */
    internal class Resolved(val abbrev: String?, val primary: StackedColours, val swapped: StackedColours)

    /** The collision rule on two resolved teams. */
    private fun derivePair(away: Resolved, home: Resolved): StackedPair {
        if (!collides(away.primary.stripe, home.primary.stripe)) return StackedPair(away.primary, home.primary)
        if (!collides(away.primary.stripe, home.swapped.stripe)) return StackedPair(away.primary, home.swapped)
        return StackedPair(away.swapped, home.primary)
    }

    /** (S3) Two stripes look alike: ΔE76 below `STACKED_STRIPE_DE_MIN`. */
    internal fun collides(a: Int, b: Int): Boolean = deltaE76(a, b) < K.STACKED_STRIPE_DE_MIN

    internal fun derive(abbrev: String?, goal: TeamColours): Resolved {
        val row = K.STACKED_ROW_BG
        val primarySrc = if (Contrast.ratio(goal.accent, row) > Contrast.ratio(goal.base, row)) goal.accent else goal.base
        val otherSrc = if (primarySrc == goal.accent) goal.base else goal.accent
        val stripe = lighten(primarySrc)
        val other = lighten(otherSrc)

        val tab = goal.tab
        val barBg = NhlTeamColours.mixBlack(tab, K.STACKED_BAR_BG_BLACK)
        val notTab = if (tab == goal.base) goal.accent else goal.base
        val light = towardWhite(tab, K.STACKED_BAR_B_WHITE)
        val barA = firstOf(barBg, null, notTab, NhlTeamColours.mixBlack(tab, K.STACKED_BAR_A_BLACK), light, WHITE)
        val barB = firstOf(barBg, barA, light, notTab, WHITE)

        val primary = StackedColours(stripe, barBg, barA, barB, goal, rowFlashAlpha(stripe, goal.pop))
        val swapped = primary.copy(stripe = other, rowFlashAlpha = rowFlashAlpha(other, goal.pop))
        return Resolved(abbrev, primary, swapped)
    }

    /** [src] toward white in 5 % steps until ≥ `STACKED_STRIPE_MIN` against the row navy (white always passes). */
    internal fun lighten(src: Int): Int {
        var c = src
        var step = 0
        while (Contrast.ratio(c, K.STACKED_ROW_BG) < K.STACKED_STRIPE_MIN && step < WHITE_STEPS) {
            step++
            c = towardWhite(src, step * K.STACKED_STRIPE_WHITE_STEP)
        }
        return c
    }

    /** The first of [candidates] ≥ `STACKED_BAR_STRIPE_MIN` against [bg] and (when [a] is set) ≥ `STACKED_BAR_AB_MIN` against it. */
    private fun firstOf(bg: Int, a: Int?, vararg candidates: Int): Int {
        for (c in candidates) {
            if (Contrast.ratio(c, bg) < K.STACKED_BAR_STRIPE_MIN) continue
            if (a != null && Contrast.ratio(c, a) < K.STACKED_BAR_AB_MIN) continue
            return c
        }
        return candidates.last()
    }

    /** (C7, S11) The row flash's peak α for [stripe]: .55 down in .05 steps until white and [pop] pass on the blend. */
    internal fun rowFlashAlpha(stripe: Int, pop: Int): Float {
        var n = 0
        while (!c7(stripe, pop, flashAlphaOf(n)) && flashAlphaOf(n + 1) > 0f) n++
        return flashAlphaOf(n)
    }

    /** C7 at peak α [alpha]: white and [pop] each ≥ `STACKED_C7_MIN` on [stripe] at [alpha] over the row navy. */
    internal fun c7(stripe: Int, pop: Int, alpha: Float): Boolean {
        val blend = over(stripe, alpha.toDouble(), K.STACKED_ROW_BG)
        return Contrast.ratio(WHITE, blend) >= K.STACKED_C7_MIN && Contrast.ratio(pop, blend) >= K.STACKED_C7_MIN
    }

    /** `STACKED_ROW_FLASH_ALPHA − n × STACKED_ROW_FLASH_STEP`, without accumulating float error. */
    private fun flashAlphaOf(n: Int): Float = K.STACKED_ROW_FLASH_ALPHA - n * K.STACKED_ROW_FLASH_STEP

    /** [fg] at [alpha] over the opaque [bg], each channel rounded half to even (an 8-bit blend). */
    internal fun over(fg: Int, alpha: Double, bg: Int): Int {
        fun mix(shift: Int): Int {
            val f = (fg shr shift) and 0xFF
            val b = (bg shr shift) and 0xFF
            return round(b + (f - b) * alpha).toInt() shl shift
        }
        return (0xFF shl 24) or mix(16) or mix(8) or mix(0)
    }

    /** [argb] mixed toward white by [t] (0 = itself, 1 = white), each channel rounded half to even. */
    internal fun towardWhite(argb: Int, t: Double): Int {
        fun mix(shift: Int): Int {
            val v = (argb shr shift) and 0xFF
            return round(v + (255 - v) * t).toInt() shl shift
        }
        return (0xFF shl 24) or mix(16) or mix(8) or mix(0)
    }

    // ------------------------------------------------------------------ CIELAB ΔE76 (sRGB, D65)

    /** CIE 1976 colour difference between two opaque sRGB colours (alpha ignored). */
    internal fun deltaE76(a: Int, b: Int): Double {
        val la = lab(a)
        val lb = lab(b)
        val dl = la[0] - lb[0]
        val da = la[1] - lb[1]
        val db = la[2] - lb[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    /** sRGB → linear → XYZ (D65) → CIELAB (L*, a*, b*). */
    internal fun lab(argb: Int): DoubleArray {
        val r = lin((argb shr 16) and 0xFF)
        val g = lin((argb shr 8) and 0xFF)
        val b = lin(argb and 0xFF)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / XN
        val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / YN
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / ZN
        val fx = labF(x)
        val fy = labF(y)
        val fz = labF(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    private fun lin(c8: Int): Double {
        val c = c8 / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun labF(t: Double): Double = if (t > LAB_E) cbrt(t) else (LAB_K * t + 16) / 116

    // ------------------------------------------------------------------ the tables

    private const val WHITE = 0xFFFFFFFF.toInt()
    /** 20 steps of 5 %: the last one is white, which always passes. */
    private const val WHITE_STEPS = 20
    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883
    private const val LAB_E = 216.0 / 24_389.0
    private const val LAB_K = 24_389.0 / 27.0

    /** The 32 teams in a fixed order, then the fallback (index [FALLBACK_INDEX]). */
    private val KEYS: List<String> = NhlTeamColours.TABLE.keys.sorted()
    private val INDEX: Map<String, Int> = KEYS.withIndex().associate { (i, k) -> k to i }
    private val FALLBACK_INDEX = KEYS.size
    private val SLOTS = KEYS.size + 1

    /** Every team resolved once, then the fallback. */
    private val TEAMS: Array<Resolved> = Array(SLOTS) { i ->
        if (i < KEYS.size) derive(KEYS[i], NhlTeamColours.of(KEYS[i])) else derive(null, NhlTeamColours.FALLBACK)
    }

    /** The pair cache, filled on first use (racing writers make equal values; the first one wins). */
    private val PAIRS = AtomicReferenceArray<StackedPair?>(SLOTS * SLOTS)

    private fun index(abbrev: String?): Int {
        val key = abbrev?.trim()?.uppercase() ?: return FALLBACK_INDEX
        return INDEX[key] ?: FALLBACK_INDEX
    }

    private fun resolve(abbrev: String?): Resolved = TEAMS[index(abbrev)]

    /** (Test) The resolved team, primary and swapped. */
    internal fun resolved(abbrev: String?): Resolved = resolve(abbrev)
}
