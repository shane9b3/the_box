package app.thebox.ui.sports

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import app.thebox.core.sports.League
import app.thebox.core.sports.NflBarKey
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.StackedNflTargets
import app.thebox.core.sports.StackedPair
import app.thebox.core.sports.SportsConstants as K
import java.util.Locale

/**
 * (NFL Stacked §2.3, §2.4, §5, §6; WP-3) The NFL Stacked box's painter: the two navy rows (stripe, logo, code, possession
 * ball, timeout pips, score), the clock column, the bar (background, hairline, skewed stripes, words, divider, the flag
 * icon, the red-zone patch) and the nine-slice shadow, drawn into the runner's one bounded layer. Mirrored about the
 * rows for a right-hand corner (§5.3). Allocation-free in [drawFrame] (GA §2.5): every `Paint`, sprite and glyph table is
 * built in the constructor or a bake.
 *
 * [s] is device px per Standard design px (× `SCORE_LARGE_SCALE` for [large]); [sansBold] is Instrument Sans Bold (codes,
 * scores, clock column), [barFont] Instrument Sans Bold Italic (the bar words); null = the system fallback.
 *
 * **Commit-0 stub:** [frame] resolves the plain words only (no fitting), [fillBar] records the key and the neutral
 * colours, [bakeShadow] bakes nothing and [drawFrame] draws nothing. Commit 0 never builds it (`StackedNflBug` draws the
 * NFL Pill until WP-4).
 */
internal class StackedNflPainter(
    val s: Float,
    val large: Boolean,
    val sansBold: Typeface?,
    val barFont: Typeface?,
) {
    /**
     * The resolved words of [v] for the layout [targets] (`StackedNflTargets.of(v)`): codes, scores ("" when null),
     * the period slot ("4TH", `SportsText.period(res, label, NFL)` upper-cased), the lower slot's words and sizes, fitted.
     * Once per shown view, never per frame.
     */
    fun frame(v: ScoreBugView, targets: StackedNflTargets, res: Resources): StackedNflFrameState {
        // WP-3: the §2.3 fit rules and the slot words.
        val p = targets.period
        return StackedNflFrameState(
            awayAbbrev = v.away.abbrev,
            homeAbbrev = v.home.abbrev,
            awayScore = v.away.score?.toString().orEmpty(),
            homeScore = v.home.score?.toString().orEmpty(),
            periodText = if (p == null) "" else SportsText.period(res, p, League.NFL).uppercase(Locale.ROOT),
            periodPx = K.STACKED_NFL_CLOCK_PX,
            slot = targets.clockSlot,
            slotText = "",
            slotPx = K.STACKED_NFL_CLOCK_PX,
            clockSecs = v.clockSecs ?: 0,
            columnDim = targets.columnDim,
            targets = targets,
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
        // WP-3: the words and colours of every NflBarKind.
        content.key = key
        content.labelLen = 0
        content.subLen = 0
        content.redZone = false
        content.flag = false
        content.bg = K.STACKED_BAR_NEUTRAL
        content.a = pair?.away?.stripe ?: 0
        content.b = pair?.home?.stripe ?: 0
        content.fg = Color.WHITE
        content.wordsAdvance = 0f
    }

    /** The drop shadow's nine-slice for this size (ST §6), baked off the main thread; null = drawn without it. */
    fun bakeShadow(): NineSlice? {
        // WP-3: the nine-slice bake.
        return null
    }

    /** The whole box for [a] (see [StackedNflDrawArgs] for the coordinates). Allocation-free. */
    fun drawFrame(c: Canvas, a: StackedNflDrawArgs) {
        // WP-3: rows, clock column, bar, shadow.
    }
}
