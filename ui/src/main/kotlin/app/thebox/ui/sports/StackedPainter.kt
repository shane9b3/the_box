package app.thebox.ui.sports

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Typeface
import app.thebox.core.sports.ClockSlot
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedPair
import app.thebox.core.sports.StackedTargets
import java.util.Locale

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
 * **Commit-0 stub:** the signatures are final; [frame] and [fillBar] resolve plain unfitted text, [bakeShadow] returns
 * null and [drawFrame] draws nothing. Stacked WP-2 fills them in.
 */
internal class StackedPainter(
    val s: Float,
    val large: Boolean,
    @Suppress("unused") private val sansBold: Typeface?,
    @Suppress("unused") private val barFont: Typeface?,
) {
    /**
     * (WP-2) The view's resolved words for [t] (`StackedTargets.of(v)`), fitted at this size (§4.2: clock-column strings
     * step down by `STACKED_FIT_STEP_PX` to `STACKED_FIT_MIN_PX`). Called once per shown view, never per frame.
     */
    fun frame(v: ScoreBugView, t: StackedTargets, res: Resources): StackedFrameState {
        val periodText = t.period?.let { SportsText.period(res, it).uppercase(Locale.ROOT) } ?: ""
        val slotText = when (t.clockSlot) {
            ClockSlot.Clock, ClockSlot.Blank -> ""
            ClockSlot.End -> res.getString(app.thebox.ui.R.string.sports_stacked_end)
            ClockSlot.Intermission -> res.getString(app.thebox.ui.R.string.sports_stacked_int)
            ClockSlot.NoUpdate -> res.getString(app.thebox.ui.R.string.sports_no_update)
            ClockSlot.Delayed -> res.getString(app.thebox.ui.R.string.sports_delayed)
        }
        return StackedFrameState(
            awayAbbrev = v.away.abbrev,
            homeAbbrev = v.home.abbrev,
            awayScore = (v.away.score ?: 0).toString(),
            homeScore = (v.home.score ?: 0).toString(),
            awaySog = v.away.sog?.toString() ?: "",
            homeSog = v.home.sog?.toString() ?: "",
            periodText = periodText,
            periodPx = StackedMockPx.PERIOD,
            slot = t.clockSlot,
            slotText = slotText,
            slotPx = StackedMockPx.CLOCK,
            clockSecs = v.clockSecs ?: 0,
            columnDim = t.columnDim,
            targets = t,
        )
    }

    /**
     * (WP-2) Resolves [c]'s words ([BarContent.chars] / [BarContent.len], upper case, §8 notes) and colours
     * ([BarContent.bg], [BarContent.stripeA], [BarContent.stripeB], §5.1) from its kind, side, seconds and period, the
     * game's [pair] (null = the fallback look) and the abbreviations. Called by the runner when it captures a bar content
     * (an open, a change, a text swap, or a seconds update), never per frame.
     *
     * **Commit-0 stub:** no words; the neutral bar's colours.
     */
    @Suppress("UNUSED_PARAMETER")
    fun fillBar(c: BarContent, pair: StackedPair?, awayAbbrev: String, homeAbbrev: String, res: Resources) {
        c.len = 0
        c.bg = K.STACKED_BAR_NEUTRAL
        c.stripeA = pair?.away?.stripe ?: K.STACKED_BAR_NEUTRAL
        c.stripeB = pair?.home?.stripe ?: K.STACKED_BAR_NEUTRAL
    }

    /**
     * (WP-2, §6 B4) The drop shadow for this size: one `ALPHA_8` nine-slice mask (`STACKED_SHADOW_SIGMA`, source
     * ≥ 6σ + 1), baked on a software canvas. Off the main thread; once per size.
     *
     * **Commit-0 stub:** null (no shadow).
     */
    fun bakeShadow(): NineSlice? = null

    /**
     * (WP-2) The whole Stacked bug for one frame, in the coordinates of [StackedDrawArgs] (origin = the top part's
     * top-left, device px). Order: glow (when [StackedDrawArgs.glowOn]), shadow, bar, rows (stripes, logos, codes,
     * scores, the row flash and the scorer's pop), SOG column, clock column, border, then the takeover over the top part.
     * Allocation-free.
     *
     * **Commit-0 stub:** draws nothing.
     */
    @Suppress("UNUSED_PARAMETER")
    fun drawFrame(c: Canvas, a: StackedDrawArgs) = Unit

    companion object {
        /**
         * (§11) The bar's face: the bundled static Instrument Sans Bold Italic (upstream `Instrument/instrument-sans`
         * `fonts/ttf/InstrumentSans-BoldItalic.ttf`); null if it cannot be loaded (the painter then fakes the italic).
         * Loaded only for the Stacked painters, never added to `BoxFonts.Sans`.
         */
        fun barTypeface(context: android.content.Context): Typeface? = sansTypeface(context, app.thebox.ui.R.font.instrument_sans_bold_italic)
    }

    /** Commit-0 stub only: unfitted design-px text sizes (mock × `STACKED_SCALE`). */
    private object StackedMockPx {
        const val PERIOD = app.thebox.core.sports.StackedMock.PERIOD_PX * K.STACKED_SCALE
        const val CLOCK = app.thebox.core.sports.StackedMock.CLOCK_PX * K.STACKED_SCALE
    }
}
