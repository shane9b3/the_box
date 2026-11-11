package app.thebox.ui.sports

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.thebox.core.sports.Box as DesignBox
import app.thebox.core.sports.GoalSpec
import app.thebox.core.sports.GoalTimeline
import app.thebox.core.sports.NhlStackedColours
import app.thebox.core.sports.OverlayGeometry
import app.thebox.core.sports.PlacementInput
import app.thebox.core.sports.ScoreBugSource
import app.thebox.core.sports.StackedFrame
import app.thebox.core.sports.StackedPair
import app.thebox.core.sports.StackedTimeline
import app.thebox.core.sports.StackedTrack
import app.thebox.core.sports.StackedTransition
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * (Stacked §2.3; Stacked WP-3) The Stacked NHL score bug: its own shell (the 150-ms fade on its own leaf
 * `graphicsLayer { alpha; clip = true }` sized footprint + 2 M, the `SCORE_SLIDE_MS` corner slide, `FollowLogos` loaded at
 * the takeover size, the place hint, the shared [LiveText] live region `SCORE_BUG_LIVE_TAG`), its `StackedHolder` and
 * `StackedRun` (the layout tracks of `StackedTimeline`, the pin, S1 rules 1–7), drawing through one
 * `Spacer.drawBehind { StackedPainter.drawFrame(...) }` node tagged `SCORE_BUG_TAG` on the **drawn** box. Reached only
 * through `ScoreBug(...)`'s style branch (NHL and `NhlBugStyle.STACKED`); [placement] always carries `style = STACKED`.
 * No `lowEnd` parameter (ruling 20); the still version follows `motionOff()`.
 *
 * **Commit-0 stub:** draws the Pill ([ScoreBugWith], exactly as the Pill branch does) until Stacked WP-3 lands, so choosing
 * Stacked is safe in the meantime. The placement's sizes are the Pill's until Stacked WP-1 (`ScoreBugPlacement` stub).
 */
@Composable
internal fun StackedBug(
    source: ScoreBugSource,
    placement: PlacementInput,
    geometry: OverlayGeometry,
    modifier: Modifier,
    place: (PlacementInput, OverlayGeometry) -> DesignBox?,
    @Suppress("UNUSED_PARAMETER") hooks: StackedHooks,
) {
    ScoreBugWith(source, placement, geometry, lowEnd = false, modifier, place, GoalHooks.Default)
}

/**
 * (Stacked §2.3) The Stacked bug's test seams, as [GoalHooks] / [NflHooks]; the defaults are the real objects. Owned by
 * Stacked WP-3, which may add seams (the defaults must stay the real objects).
 *
 * - [colours]: the game's pair (`NhlStackedColours.pair`, S3's collision rule applied).
 * - [sampler]: the layout tracks (`StackedTimeline.sample`); [goalSampler]: the takeover under `GoalSpec.STACKED`.
 * - [bakeGlow]: the exact-box glow ([StackedGoalPainter.bakeGlow], w × h device px), off the main thread.
 * - [logoFetch]: the followed game's logo loader; null = the shell's image loader.
 * - [context]: added to the effects' coroutine context (tests put a `MotionDurationScale` here); empty in production.
 * - [background]: where the painters are built and the masks baked; tests pass [EmptyCoroutineContext].
 * - [probe]: what a test can observe; null in production.
 */
internal class StackedHooks(
    val colours: (String?, String?) -> StackedPair = NhlStackedColours::pair,
    val sampler: StackedSampler = StackedSampler.Real,
    val goalSampler: GoalSampler = GoalSampler { t, still, out ->
        if (still) GoalTimeline.sampleStill(GoalSpec.STACKED, t, out) else GoalTimeline.sample(GoalSpec.STACKED, t, out)
    },
    val bakeGlow: (StackedGoalPainter, Float, Float) -> GlowMasks? = { p, w, h -> p.bakeGlow(w, h) },
    val logoFetch: LogoFetch? = null,
    val context: CoroutineContext = EmptyCoroutineContext,
    val background: CoroutineContext = Dispatchers.Default,
    val probe: StackedProbe? = null,
) {
    companion object { val Default = StackedHooks() }
}

/** (Stacked §2.3) Samples one layout track into a preallocated frame (draw path; a `fun interface`, so nothing boxes). */
internal fun interface StackedSampler {
    fun sample(track: StackedTrack, kind: StackedTransition, tMs: Float, from: StackedFrame, to: Boolean, out: StackedFrame): StackedFrame

    companion object {
        val Real = StackedSampler { track, kind, t, from, to, out -> StackedTimeline.sample(track, kind, t, from, to, out) }
    }
}

/**
 * (Stacked §10.2) What a test can observe of the Stacked runner (`StackedDrawTest`, `StackedSeamTest`, `StackedBoundsTest`,
 * `StackedLogoTest`). Owned by Stacked WP-3, which fills it in. **Commit-0 stub:** [args] is never set.
 */
internal class StackedProbe {
    /** Fills and returns what the next draw would pass `StackedPainter.drawFrame` (shared holder: copy what you keep). */
    var args: (() -> StackedDrawArgs)? = null
}
