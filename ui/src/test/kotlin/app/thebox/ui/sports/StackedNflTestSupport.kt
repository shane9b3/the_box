package app.thebox.ui.sports

import android.graphics.Bitmap
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.ClockSlot
import app.thebox.core.sports.DownText
import app.thebox.core.sports.FieldZone
import app.thebox.core.sports.League
import app.thebox.core.sports.NflBarKey
import app.thebox.core.sports.NflBarKind
import app.thebox.core.sports.NflScore
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.NflScoreSpec
import app.thebox.core.sports.NflTab
import app.thebox.core.sports.NflTimeoutMark
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.PlacementInput
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.StackedNflFrame
import app.thebox.core.sports.StackedNflTargets
import app.thebox.core.sports.StackedNflTimeline
import app.thebox.core.sports.StackedNflTrack
import app.thebox.core.sports.StackedNflTransition
import app.thebox.core.sports.TabLead
import app.thebox.core.sports.TabTail
import app.thebox.core.sports.BottomBar
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * (NFL Stacked WP-4; re-pointed at the real code in the integration pass, 2026-11-15) Views, inputs and hooks for the
 * NFL Stacked runner tests. The layout table is the real `StackedNflTargets.of`, the transitions the real
 * `StackedNflTimeline` (sampled and timed), the takeover `NflScoreTimeline` under the real `NflScoreSpec.STACKED`. Fakes
 * are kept only where a test must observe a call: the painters' once-per-change calls (`frame`, `fillBar`, `begin`:
 * recorded, with plain words), the glow bake (a 1-px mask, counted) and recording wrappers around the real samplers.
 * `StackedNflEndToEndTest` runs everything real, painters included.
 */
internal object StackedNflRunFixtures {
    val GAME = SportsFixtures.nflGame()
    val Q4 = PeriodLabel(PeriodKind.REG, 4)
    val kcTab = NflTab(Side.AWAY, TabLead.BALL, TabTail.Field(FieldZone.OWN, 35))
    val bufTab = NflTab(Side.HOME, TabLead.BALL, TabTail.Field(FieldZone.OWN, 25))
    val firstAndTen = DownText.Down(1, 10, goal = false)

    /** The shipped takeover spec (end 4 800, pair second 4 800, pair end 9 600, still B 1 200). */
    val SPEC: NflScoreSpec = NflScoreSpec.STACKED

    /** KC @ BUF in play (4th, 3:42, 24–17), KC ball 1st & 10, timeouts 3 / 2 of 3, or as told. */
    fun view(
        away: Int = 24,
        home: Int = 17,
        clock: Int = 222,
        down: DownText? = firstAndTen,
        tab: NflTab? = kcTab,
        awayTimeouts: Int? = 3,
        homeTimeouts: Int? = 2,
        timeout: NflTimeoutMark? = null,
        flagId: String? = null,
        seq: Int = 0,
        side: Side? = null,
        scores: List<NflScore> = emptyList(),
        top: CentreText? = null,
        bottom: CentreText? = CentreText.Clock,
        dim: Boolean = false,
        gameId: app.thebox.core.sports.GameId? = null,
    ): ScoreBugView {
        val g = GAME.copy(away = GAME.away.copy(score = away), home = GAME.home.copy(score = home))
        val v = SportsFixtures.nflStackedView(
            game = g, down = down, tab = tab, awayTimeouts = awayTimeouts, homeTimeouts = homeTimeouts,
            timeout = timeout, flagId = flagId, goalSeq = seq, goalSide = side,
        )
        return v.copy(
            top = top ?: v.top, bottom = bottom, dim = dim, clockSecs = if (bottom == CentreText.Clock) clock else null,
            nfl = v.nfl?.copy(scores = scores), gameId = gameId ?: v.gameId,
        )
    }

    fun td(side: Side = Side.AWAY, away: Int = 30, home: Int = 17) = NflScore(NflScoreKind.TOUCHDOWN, 6, away, home)
    fun xp(side: Side = Side.AWAY, away: Int = 31, home: Int = 17) = NflScore(NflScoreKind.EXTRA_POINT, 1, away, home)

    /** KC's touchdown (30–17) under the mark, seq [seq]. */
    fun touchdown(seq: Int = 1) = view(away = 30, seq = seq, side = Side.AWAY, scores = listOf(td()))

    /** KC's +7 pair (touchdown 30–17 then the extra point 31–17), seq [seq]. */
    fun pair(seq: Int = 1) = view(away = 31, seq = seq, side = Side.AWAY, scores = listOf(td(), xp()))

    /** Halftime; [dim]: stale in the break (the NFL machine writes "No update" on the top line, no clock line). */
    fun halftime(dim: Boolean = false) = view(
        top = if (dim) CentreText.NoUpdate else CentreText.Halftime, bottom = null, tab = null, down = null, dim = dim,
    )

    /** Stale in play, as the NFL machine writes it: "No update" on the top line over the clock line, no down or tab. */
    fun stale() = view(top = CentreText.NoUpdate, dim = true, down = null, tab = null)

    fun input(
        corner: ScoreCorner = ScoreCorner.TOP_RIGHT,
        size: ScoreBugSize = ScoreBugSize.STANDARD,
        placing: Boolean = false,
        bar: BottomBar = BottomBar.NONE,
        textTrackOn: Boolean = false,
    ): PlacementInput = ScoreBugFixtures.input(corner, size, placing, bar)
        .copy(league = League.NFL, style = NhlBugStyle.STACKED, textTrackOn = textTrackOn)

    /** The real §3.4 table. */
    fun targets(v: ScoreBugView): StackedNflTargets = StackedNflTargets.of(v)

    /** The real transition lengths. */
    fun totalMs(track: StackedNflTrack, kind: StackedNflTransition): Float = StackedNflTimeline.totalMs(track, kind)

    /** CLOCK and BAR along a straight line over [totalMs] from the interrupted value; the others at their end. */
    val LinearSampler = StackedNflSampler { track, kind, t, from, to, out ->
        val total = totalMs(track, kind)
        val f = if (total <= 0f || t.isNaN()) 1f else (t / total).coerceIn(0f, 1f)
        val end = when (kind) {
            StackedNflTransition.OPEN, StackedNflTransition.REKEY -> true
            StackedNflTransition.CLOSE -> false
            else -> to
        }
        StackedNflSampler.Real.sample(track, kind, Float.POSITIVE_INFINITY, from, to, out)
        when (track) {
            StackedNflTrack.CLOCK -> out.clockWidth = from.clockWidth + ((if (end) 1f else 0f) - from.clockWidth) * f
            StackedNflTrack.BAR -> if (kind != StackedNflTransition.REKEY) {
                out.barHeight = from.barHeight + ((if (end) 1f else 0f) - from.barHeight) * f
                out.barClosed = out.barHeight <= 0f
            } else {
                out.barBgMix = f
            }
            else -> Unit
        }
        out
    }

    /** The pin's sampler stand-in: the layout channels at the end of the transition. */
    val PinnedSampler = StackedNflSampler { track, kind, _, from, to, out -> LinearSampler.sample(track, kind, Float.POSITIVE_INFINITY, from, to, out) }

    /** The takeover: the production sampler (`NflScoreSpec.STACKED`). */
    val ScoreSampler = NflScoreSampler.Real

    fun fakeGlow(): GlowMasks {
        val b = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
        return GlowMasks(b, b, 0f, 0f)
    }

    /** The bar's label as the fake `fillBar` writes it: "KIND SIDE …" (allocates; tests only). */
    fun words(c: NflBarContent): String = String(c.label, 0, c.labelLen)

    /** A box body with the view's own scores and codes (the WP-3 `frame` stand-in). */
    fun frameOf(v: ScoreBugView, t: StackedNflTargets) = StackedNflFrameState(
        awayAbbrev = v.away.abbrev, homeAbbrev = v.home.abbrev,
        awayScore = v.away.score?.toString().orEmpty(), homeScore = v.home.score?.toString().orEmpty(),
        periodText = if (t.clockOpen) (v.period?.let { "Q${it.number}" } ?: "") else "",
        periodPx = K.STACKED_NFL_CLOCK_PX,
        slot = t.clockSlot, slotText = t.clockSlot.toString(), slotPx = K.STACKED_NFL_CLOCK_PX,
        clockSecs = v.clockSecs ?: 0, columnDim = t.columnDim, targets = t,
    )

    /** What the fakes saw: every `fillBar` key, every `begin`, every sampled (track, kind) pair. */
    class Calls {
        val fills = mutableListOf<NflBarKey>()
        val begins = mutableListOf<Pair<Side, List<NflScore>>>()
        val badges = mutableListOf<Bitmap?>()
        val sampled = mutableSetOf<Pair<StackedNflTrack, StackedNflTransition>>()
        val pipSamples = mutableSetOf<Triple<Side, StackedNflTransition, Pair<Int, Int>>>()
        var flagSamples = 0
        var frames = 0
    }

    /**
     * The production runner with the fakes above: the background work and the effects on the test thread, the recording
     * painter calls, the [SPEC] takeover, the test [context] (a `MotionDurationScale` for the still path) and logos.
     */
    fun hooks(
        probe: StackedNflProbe?,
        calls: Calls = Calls(),
        context: CoroutineContext = EmptyCoroutineContext,
        logoFetch: LogoFetch? = null,
        glowAllowed: Boolean = true,
        linear: Boolean = false,
    ) = StackedNflHooks(
        sampler = StackedNflSampler { track, kind, t, from, to, out ->
            calls.sampled += track to kind
            (if (linear) LinearSampler else StackedNflSampler.Real).sample(track, kind, t, from, to, out)
        },
        scoreSampler = ScoreSampler,
        bakeGlow = { _, _, _ -> fakeGlow() },
        logoFetch = logoFetch,
        context = context,
        background = EmptyCoroutineContext,
        probe = probe,
        spec = SPEC,
        pinnedSampler = PinnedSampler,
        pipSampler = StackedNflPipSampler { side, kind, a, b, t, from, out ->
            calls.pipSamples += Triple(side, kind, a to b)
            StackedNflPipSampler.Real.sample(side, kind, a, b, t, from, out)
        },
        flagSampler = StackedNflFlagSampler { t, still, out ->
            calls.flagSamples++
            StackedNflFlagSampler.Real.sample(t, still, out)
        },
        totalMs = ::totalMs,
        targets = ::targets,
        glowAllowed = { _, _, _, _ -> glowAllowed },
        frame = { _, v, t, _ -> calls.frames++; frameOf(v, t) },
        fillBar = { _, c, key, _, _, _, _ ->
            calls.fills += key
            val w = listOfNotNull(key.kind.name, key.side?.name, key.score?.name, key.left?.toString()).joinToString(" ")
            w.toCharArray().copyInto(c.label)
            c.labelLen = w.length
            c.subLen = 0
            c.flag = key.kind == NflBarKind.FLAG
        },
        begin = { _, side, scores, _, badge, _, _, _ -> calls.begins += side to scores; calls.badges += badge },
    )

    /** A fresh [StackedNflFrame] (for reading sampled values). */
    fun frame() = StackedNflFrame()
}
