// Contract (NFL commit 0, docs/design/m6-nfl.md §4.6, §11): `NflBug`'s and `NflHooks`' signatures are fixed for WP-4 (the
// shell copy, `NflRun`, `NflTabRun`); changes go through the lead.
package app.thebox.ui.sports

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.thebox.core.sports.Box as DesignBox
import app.thebox.core.sports.NflScoreFrame
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.NflScoreTimeline
import app.thebox.core.sports.NflTeamColours
import app.thebox.core.sports.OverlayGeometry
import app.thebox.core.sports.PlacementInput
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreBugSource
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.TeamColours
import app.thebox.ui.R
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** (NFL §5.1, §5.6, §5.7) Samples the NFL takeover into a preallocated frame (draw path; no allocation). */
internal fun interface NflSampler {
    /** [second] non-null = a §5.7 pair; [still] = the Reduce-animations version (§5.6). */
    fun sample(first: NflScoreKind, second: NflScoreKind?, tMs: Float, still: Boolean, out: NflScoreFrame): NflScoreFrame

    companion object {
        val Real = NflSampler { first, second, t, still, out ->
            when {
                still -> NflScoreTimeline.sampleStill(first, second, t, out)
                second != null -> NflScoreTimeline.samplePair(first, second, t, out)
                else -> NflScoreTimeline.sample(first, t, out)
            }
        }
    }
}

/**
 * (NFL §2 S7, §4.6) The NFL bug's seams, as [GoalHooks] for the NHL: the colour table ([NflTeamColours.of], never the
 * NHL's), the timeline, the placement checks, the bakes and the test contexts. Defaults are the real objects.
 */
internal class NflHooks(
    val colours: (String?) -> TeamColours = NflTeamColours::of,
    val sampler: NflSampler = NflSampler.Real,
    val glowAllowed: (PlacementInput, OverlayGeometry, DesignBox, Float) -> Boolean = ScoreBugPlacement::glowAllowed,
    val tabAllowed: (PlacementInput, OverlayGeometry, DesignBox) -> Boolean = ScoreBugPlacement::tabAllowed,
    /** The score painter's glow bake (never called in low-end mode), off the main thread. */
    val bake: (NflScorePainter) -> GlowMasks? = { it.bakeGlow() },
    /** The tab's per-size bake; `withShadow` is false in low-end mode. */
    val bakeSize: (NflTabPainter, Boolean) -> Unit = { p, withShadow -> p.bakeSize(withShadow) },
    /** The followed game's logo loader; null = the shell's image loader. */
    val logoFetch: LogoFetch? = null,
    /** Added to the takeover effect's coroutine context (tests put a `MotionDurationScale` here); empty in production. */
    val context: CoroutineContext = EmptyCoroutineContext,
    /** Where the painters are built and the masks baked: off the main thread in production. */
    val background: CoroutineContext = Dispatchers.Default,
) {
    companion object { val Default = NflHooks() }
}

/**
 * The NFL score bug (docs/design/m6-nfl.md §4; WP-4): its own copy of the shell (the fade, the corner slide,
 * `FollowLogos`, the place hint, the live region), `NflRun` (the takeover and the §5.7 pair) and `NflTabRun` (the
 * possession tab), drawing through [NflBugPainter.drawFrame]. Reached only through `ScoreBug(...)`'s league branch.
 *
 * Commit 0 stub: draws the glass alone at the placed box (or nothing), with no takeover, tab, words or semantics. No NFL
 * view can reach it before WP-1/WP-2 (the ESPN source answers `Failed`).
 */
@Composable
internal fun NflBug(
    source: ScoreBugSource,
    placement: PlacementInput,
    geometry: OverlayGeometry,
    modifier: Modifier,
    place: (PlacementInput, OverlayGeometry) -> DesignBox?,
    @Suppress("UNUSED_PARAMETER") hooks: NflHooks,
) {
    val context = LocalContext.current
    val res = context.resources
    val d = LocalDensity.current.density // device px per design px (BoxDesignScale)

    val bug by source.bug.collectAsState()
    val sample = remember(res) { nflSampleView(res) }
    val view: ScoreBugView? = bug ?: if (placement.placing) sample else null
    val box = if (view != null) place(placement, geometry) else null
    if (box != null) {
        val w = box.r - box.l
        val h = box.b - box.t
        val s = if (placement.size == ScoreBugSize.LARGE) d * K.SCORE_LARGE_SCALE else d
        val painter = remember(context, s, w, h, d) {
            NflBugPainter(
                s, sansTypeface(context, R.font.instrument_sans_semibold), sansTypeface(context, R.font.instrument_sans_bold),
                pillW = w * d, pillH = h * d,
            )
        }
        val glass: Bitmap = remember(painter) { painter.bakeGlass(withShadow = true) }
        Box(
            modifier
                .offset(box.l.dp, box.t.dp)
                .size(w.dp, h.dp)
                .testTag(SCORE_BUG_TAG)
                .drawBehind { drawIntoCanvas { painter.drawGlass(it.nativeCanvas, glass) } },
        )
    }
}
