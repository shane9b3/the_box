package app.thebox.ui.sports

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import app.thebox.core.sports.Box as DesignBox
import app.thebox.core.sports.League
import app.thebox.core.sports.NflScoreFrame
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.NflScoreSpec
import app.thebox.core.sports.NflScoreTimeline
import app.thebox.core.sports.OverlayGeometry
import app.thebox.core.sports.PlacementInput
import app.thebox.core.sports.ScoreBugSource
import app.thebox.core.sports.StackedColourTable
import app.thebox.core.sports.StackedNflFrame
import app.thebox.core.sports.StackedNflTimeline
import app.thebox.core.sports.StackedNflTrack
import app.thebox.core.sports.StackedNflTransition
import app.thebox.core.sports.StackedPair
import app.thebox.ui.guide.GuideDebugCounters
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The NFL score bug in the Stacked style (docs/design/m6-nfl-stacked.md §4, §5; WP-4): its own copy of the shell (the
 * fade, the corner slide, `FollowLogos`, the place hint, the live region), the layout tracks of `StackedNflTimeline`
 * keyed on `StackedNflTargets`, and the takeover (`NflScoreTimeline` under `NflScoreSpec.STACKED`), drawing through
 * [StackedNflPainter.drawFrame] and [StackedNflScorePainter] into one bounded layer (footprint + 2 M). Reached only
 * through `ScoreBug(...)`'s style branch (NFL and `NhlBugStyle.STACKED`); [placement] always carries `league = NFL` and
 * `style = STACKED`.
 *
 * **Commit-0 stub (behaviour-preserving):** draws today's NFL Pill ([NflBug] with [NflHooks.Default], placed by the
 * Pill-size placement stub), so NFL + Stacked looks exactly as NFL + Pill until WP-4 replaces the body. It bumps
 * [COMPOSITIONS_STACKED_NFL_BUG] so the seam tests can tell the branch was taken.
 */
@Composable
internal fun StackedNflBug(
    source: ScoreBugSource,
    placement: PlacementInput,
    geometry: OverlayGeometry,
    modifier: Modifier,
    place: (PlacementInput, OverlayGeometry) -> DesignBox?,
    hooks: StackedNflHooks,
) {
    if (GuideDebugCounters.ENABLED) SideEffect { GuideDebugCounters.bump(COMPOSITIONS_STACKED_NFL_BUG) }
    // WP-4: the runner; until then the NFL Pill.
    NflBug(source, placement, geometry, modifier, place, NflHooks.Default)
}

/**
 * (NFL Stacked §8.1; WP-4) The NFL Stacked bug's seams, as `StackedHooks` for hockey. Defaults are the real objects.
 *
 * - [colours]: the game's pair (`StackedColourTable.pair(League.NFL, …)` → `NflStackedColours`).
 * - [sampler]: one layout track (`StackedNflTimeline.sample`); [scoreSampler]: the takeover (`NflScoreTimeline` under
 *   `NflScoreSpec.STACKED`, the still version when `still`).
 * - [bakeGlow]: the score painter's exact-box glow bake, off the main thread.
 * - [logoFetch]: the followed game's logo loader; null = the shell's image loader.
 * - [context]: added to the effects' coroutine context (tests put a `MotionDurationScale` here); empty in production.
 * - [background]: where the painters are built and the masks baked; tests pass [EmptyCoroutineContext].
 * - [probe]: what a test can observe; null in production.
 */
internal class StackedNflHooks(
    val colours: (String?, String?) -> StackedPair = { a, h -> StackedColourTable.pair(League.NFL, a, h) },
    val sampler: StackedNflSampler = StackedNflSampler.Real,
    val scoreSampler: NflScoreSampler = NflScoreSampler.Real,
    val bakeGlow: (StackedNflScorePainter, Float, Float) -> GlowMasks? = { p, w, h -> p.bakeGlow(w, h) },
    val logoFetch: LogoFetch? = null,
    val context: CoroutineContext = EmptyCoroutineContext,
    val background: CoroutineContext = Dispatchers.Default,
    val probe: StackedNflProbe? = null,
) {
    companion object { val Default = StackedNflHooks() }
}

/** (NFL Stacked §4.1) Samples one layout track into a preallocated frame (draw path; a `fun interface`, so nothing boxes). */
internal fun interface StackedNflSampler {
    fun sample(track: StackedNflTrack, kind: StackedNflTransition, tMs: Float, from: StackedNflFrame, to: Boolean, out: StackedNflFrame): StackedNflFrame

    companion object {
        val Real = StackedNflSampler { track, kind, t, from, to, out -> StackedNflTimeline.sample(track, kind, t, from, to, out) }
        /** The pin's sampler (ST B4): the layout channels at the end of the transition. */
        val Pinned = StackedNflSampler { track, kind, t, from, to, out -> StackedNflTimeline.samplePinned(track, kind, t, from, to, out) }
    }
}

/**
 * (NFL Stacked §4.3, §4.4) Samples the takeover into a preallocated frame (draw path; no allocation): [first] alone, or a
 * §5.7 pair with [second]; [still] = the Reduce-animations version.
 */
internal fun interface NflScoreSampler {
    fun sample(tMs: Float, still: Boolean, first: NflScoreKind, second: NflScoreKind?, out: NflScoreFrame): NflScoreFrame

    companion object {
        val Real = NflScoreSampler { t, still, first, second, out ->
            val spec = NflScoreSpec.STACKED
            when {
                still -> NflScoreTimeline.sampleStill(spec, first, second, t, out)
                second != null -> NflScoreTimeline.samplePair(spec, first, second, t, out)
                else -> NflScoreTimeline.sample(spec, first, t, out)
            }
        }
    }
}

/**
 * (NFL Stacked §9.2; WP-4) What a test can observe of the NFL Stacked runner (`StackedNflDrawTest`, `StackedNflSeamTest`,
 * `StackedNflBoundsTest`, `StackedNflLogoTest`). Owned by WP-4, which adds what its tests need.
 */
internal class StackedNflProbe {
    /** Fills and returns what the next draw would pass `StackedNflPainter.drawFrame` (shared holder: copy what you keep). */
    var args: (() -> StackedNflDrawArgs)? = null
    /** The bug's whole draw, in the top part's coordinates. */
    var draw: ((android.graphics.Canvas) -> Unit)? = null
}
