package app.thebox.core.catchup.archive

import app.thebox.core.catchup.ArchiveWindow
import app.thebox.core.catchup.CatchupBuild
import app.thebox.core.catchup.CatchupTarget
import app.thebox.core.catchup.CatchupUrlBuilderImpl
import app.thebox.core.catchup.CatchupVariants
import app.thebox.core.catchup.archive.ArchivePlannerImpl.Companion.ARCHIVE_LAG_STEP_MS
import app.thebox.core.catchup.archive.ArchivePlannerImpl.Companion.EDGE_MARGIN_MS
import app.thebox.core.model.Catchup
import app.thebox.core.model.CatchupMode
import app.thebox.core.model.TimeBase
import app.thebox.core.xtream.XtreamStreamRef
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m4-player-ux.md §5.2: every pure decision of §3.8 as tables. */
class ArchivePlannerTest {
    private val p = ArchivePlannerImpl()
    private val builder = CatchupUrlBuilderImpl()

    /** 2026-10-15T12:00:17.250Z: deliberately not on a minute. */
    private val deviceNow = Instant.parse("2026-10-15T12:00:17.250Z").toEpochMilli()
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val ref = XtreamStreamRef("http://panel.example:8080", "user", "pass", 101, "ts")

    private fun tb(corrH: Double = 0.0, skewSec: Long = 0, padBefore: Long = 60, padAfter: Long = 300) = TimeBase(
        serverZone = tokyo, clockSkew = Duration.ofSeconds(skewSec), correctionHours = corrH,
        paddingBefore = Duration.ofSeconds(padBefore), paddingAfter = Duration.ofSeconds(padAfter),
    )

    private fun xcTarget(days: Int = 3, corr: Double? = null) =
        CatchupTarget("http://panel.example:8080/live/{{u}}/{{p}}/101.ts", emptyMap(), Catchup(CatchupMode.XC, days, correctionHours = corr), ref)

    private fun req(origin: Long, end: Long? = null, from: Long? = null, to: Long? = null, variant: String = CatchupVariants.XC_PATH) =
        ActiveRequest(variant, origin, end, from, to)

    private fun live(edge: Long = deviceNow, days: Int = 3) = LiveWindow(edge, edge - days * DAY + EDGE_MARGIN_MS)

    // ------------------------------------------------------------------ frames (B2)

    @Test
    fun framesRow() {
        for (corrH in listOf(-1.5, 0.0, 2.0)) for (skewSec in listOf(-40L, 0L, 95L)) for (shift in listOf(-60, 0, 120)) {
            val f = p.frames(tb(corrH, skewSec), null, shift)
            val corrMs = (corrH * 3_600_000).toLong()
            assertEquals(corrMs, f.correctionMs, "corr $corrH")
            assertEquals(skewSec * 1000, f.skewMs)
            assertEquals(shift * MIN, f.displayShiftMs)
            // B2: liveEdge − correction == device now + skew (the panel's now in the archive frame), every sign.
            assertEquals(deviceNow + skewSec * 1000, f.liveEdge(deviceNow) - f.correctionMs, "corr $corrH skew $skewSec")
            assertEquals(f.archiveOf(f.liveEdge(deviceNow)), deviceNow + f.skewMs)
        }
    }

    @Test
    fun channelCorrectionOverridesThePlaylistsAndUsesTimeBaseRounding() {
        assertEquals(-5_400_000L, p.frames(tb(corrH = 2.0), -1.5, 0).correctionMs)
        // TimeBase rounds to whole seconds: 0.0001 h = 0.36 s → 0 s.
        assertEquals(0L, p.frames(tb(), 0.0001, 0).correctionMs)
        assertEquals(36_000L, p.frames(tb(), 0.01, 0).correctionMs)
        // Same as the builder's own correction.
        val t = Instant.ofEpochSecond(1_800_000_000)
        assertEquals(t.toEpochMilli() - p.frames(tb(), 0.0137, 0).correctionMs, tb(padBefore = 0).archiveStart(t, 0.0137).toEpochMilli())
    }

    /** B2 (§5.2): a request planned from liveEdge − 30 s builds with requestStart == panel now − 30 s (aligned), every sign. */
    @Test
    fun liveAnchoredRequestStartsAtThePanelClockForEverySign() {
        for (corrH in listOf(-1.5, 0.0, 2.0)) for (skewSec in listOf(-40L, 0L, 95L)) {
            val base = tb(corrH, skewSec)
            val f = p.frames(base, null, 0)
            val lw = p.liveWindow(f, 3, deviceNow)
            val t = lw.liveEdgeMs - 30_000
            val panelNow = deviceNow + skewSec * 1000
            for (variant in listOf(CatchupVariants.XC_PATH, CatchupVariants.XC_PHP, CatchupVariants.XC_PATH_SECONDS)) {
                val start = p.alignStart(variant, t, f)
                val built = builder.build(
                    xcTarget(), base.copy(paddingBefore = Duration.ZERO),
                    ArchiveWindow(Instant.ofEpochMilli(start), null, deviceNow = Instant.ofEpochMilli(deviceNow)), variant,
                )
                val r = assertIs<CatchupBuild.Ok>(built, "corr $corrH skew $skewSec $variant: $built").request
                val expected = if (variant == CatchupVariants.XC_PATH_SECONDS) (panelNow - 30_000) / 1000 * 1000
                else Math.floorDiv(panelNow - 30_000, MIN) * MIN
                assertEquals(expected, r.requestStart.toEpochMilli(), "corr $corrH skew $skewSec $variant")
                assertNull(r.requestEnd)
            }
        }
    }

    @Test
    fun liveWindowMatchesTheBuildersArchiveEdge() {
        val f = p.frames(tb(corrH = 2.0, skewSec = 30), null, 0)
        val lw = p.liveWindow(f, 2, deviceNow)
        assertEquals(f.liveEdge(deviceNow), lw.liveEdgeMs)
        assertEquals(lw.liveEdgeMs - 2 * DAY + EDGE_MARGIN_MS, lw.archiveFromMs)
        // days < 1 behaves like the builder (coerceAtLeast(1)).
        assertEquals(lw.liveEdgeMs - DAY + EDGE_MARGIN_MS, p.liveWindow(f, 0, deviceNow).archiveFromMs)
        // A request at archiveFrom builds; one before (archiveFrom − margin − 1 s) is refused.
        val base = tb(corrH = 2.0, skewSec = 30, padBefore = 0)
        fun build(startMs: Long) = builder.build(
            xcTarget(days = 2), base, ArchiveWindow(Instant.ofEpochMilli(startMs), null, deviceNow = Instant.ofEpochMilli(deviceNow)),
            CatchupVariants.XC_PATH_SECONDS,
        )
        assertIs<CatchupBuild.Ok>(build(lw.archiveFromMs))
        assertIs<CatchupBuild.Unsupported>(build(lw.archiveFromMs - EDGE_MARGIN_MS - 1_000))
    }

    // ------------------------------------------------------------------ alignment (N2)

    @Test
    fun alignStartFloorsInTheArchiveFrameForMinuteVariantsOnly() {
        // Fractional-minute correction: 0.01 h = 36 s. A programme-frame floor would be 36 s off in the archive frame.
        val f = p.frames(tb(), 0.01, 0)
        val t = Instant.parse("2026-10-15T10:20:50.400Z").toEpochMilli()
        val aligned = Instant.parse("2026-10-15T10:20:00Z").toEpochMilli() + 36_000 // archive 10:20:14.4 → 10:20:00
        assertEquals(aligned, p.alignStart(CatchupVariants.XC_PATH, t, f))
        assertEquals(aligned, p.alignStart(CatchupVariants.XC_PHP, t, f))
        assertEquals(0L, Math.floorMod(f.archiveOf(aligned), MIN))
        for (v in listOf(CatchupVariants.XC_PATH_SECONDS, CatchupVariants.FLUSSONIC_RANGE, CatchupVariants.FLUSSONIC_ABS,
            CatchupVariants.SHIFT, CatchupVariants.DEFAULT, CatchupVariants.APPEND, CatchupVariants.VOD)) {
            assertEquals(t, p.alignStart(v, t, f), v)
        }
        // Already aligned: unchanged. Exactly one ms before a minute floors to the previous one.
        assertEquals(aligned, p.alignStart(CatchupVariants.XC_PATH, aligned, f))
        assertEquals(aligned - MIN, p.alignStart(CatchupVariants.XC_PATH, aligned - 1, f))
    }

    /** §3.8.3: requestStart equals the aligned instant minus correction (the builder never re-rounds it). */
    @Test
    fun builtRequestStartIsTheAlignedInstantMinusCorrection() {
        for (corr in listOf(-1.5, 0.01, 2.0)) {
            val base = tb(padBefore = 0)
            val f = p.frames(base, corr, 0)
            // Three hours back: inside the archive on the panel's clock for every sign (−1.5 h moves it 1.5 h later).
            val t = deviceNow - 3 * 3_600_000 + 12_345
            val aligned = p.alignStart(CatchupVariants.XC_PATH, t, f)
            val r = (builder.build(
                xcTarget(corr = corr), base, ArchiveWindow(Instant.ofEpochMilli(aligned), null, deviceNow = Instant.ofEpochMilli(deviceNow)),
                CatchupVariants.XC_PATH,
            ) as CatchupBuild.Ok).request
            assertEquals(aligned - f.correctionMs, r.requestStart.toEpochMilli(), "corr $corr")
            assertEquals(0L, Math.floorMod(r.requestStart.toEpochMilli(), MIN))
        }
    }

    /** N2: the builder's edge-margin start (padding crossed the archive edge) is aligned in the archive frame too. */
    @Test
    fun edgeMarginStartAlignsInTheArchiveFrame() {
        val base = tb(corrH = 0.01, skewSec = 7)
        val f = p.frames(base, null, 0)
        val lw = p.liveWindow(f, 1, deviceNow)
        // Programme starting 30 s after the window edge: the 60 s padding crosses it → S = windowStart + 60 s.
        val programmeStart = lw.archiveFromMs - EDGE_MARGIN_MS + 30_000
        val r = (builder.build(
            xcTarget(days = 1), base, ArchiveWindow(Instant.ofEpochMilli(programmeStart), null, deviceNow = Instant.ofEpochMilli(deviceNow)),
            CatchupVariants.XC_PATH_SECONDS,
        ) as CatchupBuild.Ok).request
        val edgeStartP = f.programmeOf(r.requestStart.toEpochMilli())
        val aligned = p.alignStart(CatchupVariants.XC_PATH, edgeStartP, f)
        assertEquals(0L, Math.floorMod(f.archiveOf(aligned), MIN))
        assertTrue(aligned <= edgeStartP && edgeStartP - aligned < MIN)
    }

    // ------------------------------------------------------------------ programmeRequest

    @Test
    fun programmeRequestPadsAndIsOpenWhileAiring() {
        val lw = live()
        val start = deviceNow - 3_600_000
        assertEquals(RequestPlan(start, start + 1_800_000, padBefore = true, targetMs = start), p.programmeRequest(start, start + 1_800_000, lw))
        // Still airing on the panel's clock (stop after the live edge): open-ended.
        assertEquals(RequestPlan(start, null, padBefore = true, targetMs = start), p.programmeRequest(start, lw.liveEdgeMs + 1, lw))
        assertEquals(RequestPlan(start, null, padBefore = true, targetMs = start), p.programmeRequest(start, null, lw))
        assertEquals(lw.liveEdgeMs, p.programmeRequest(start, lw.liveEdgeMs, lw).stopMs)
    }

    // ------------------------------------------------------------------ seek (§3.8.4)

    private val origin = deviceNow - 3_600_000
    private val seekable = PositionView(positionMs = 600_000, durationMs = 1_800_000, isSeekable = true, ended = false)
    private val unseekable = PositionView(positionMs = 600_000, durationMs = null, isSeekable = false, ended = false)

    @Test
    fun seekNoOpWithinOneSecond() {
        val wall = origin + 600_000
        assertEquals(SeekPlan.None, p.seek(wall + 999, wall, req(origin), seekable, live()))
        assertEquals(SeekPlan.None, p.seek(wall - 999, wall, req(origin), seekable, live()))
        assertIs<SeekPlan.Native>(p.seek(wall + 1_000, wall, req(origin), seekable, live()))
    }

    @Test
    fun userSeekSnapsToLiveWithin30s() {
        val lw = live()
        val wall = origin + 600_000
        assertEquals(SeekPlan.Live, p.seek(lw.liveEdgeMs - 30_000, wall, req(origin), unseekable, lw))
        assertEquals(SeekPlan.Live, p.seek(lw.liveEdgeMs - 10_000, wall, req(origin), unseekable, lw))
        assertEquals(SeekPlan.Live, p.seek(lw.liveEdgeMs + 3_600_000, wall, req(origin), unseekable, lw), "clamped to the edge")
        assertIs<SeekPlan.Rerequest>(p.seek(lw.liveEdgeMs - 30_001, wall, req(origin), unseekable, lw))
    }

    @Test
    fun nativeThroughSeekableAndDuration() {
        val wall = origin + 600_000
        // Inside [origin, origin + duration − END_GUARD].
        assertEquals(SeekPlan.Native(0), p.seek(origin, wall, req(origin), seekable, live()))
        assertEquals(SeekPlan.Native(1_795_000), p.seek(origin + 1_795_000, wall, req(origin), seekable, live()))
        // Past the end guard: re-request open-ended from the target.
        val t = origin + 1_795_001
        assertEquals(SeekPlan.Rerequest(RequestPlan(t, null, false, t)), p.seek(t, wall, req(origin), seekable, live()))
        // Before the origin.
        val b = origin - 60_000
        assertEquals(SeekPlan.Rerequest(RequestPlan(b, null, false, b)), p.seek(b, wall, req(origin), seekable, live()))
        // Seekable but no duration (HLS EVENT window, open TS): re-request only.
        assertIs<SeekPlan.Rerequest>(p.seek(origin + 30_000, wall, req(origin), seekable.copy(durationMs = null), live()))
        // Duration but not seekable.
        assertIs<SeekPlan.Rerequest>(p.seek(origin + 30_000, wall, req(origin), seekable.copy(isSeekable = false), live()))
    }

    @Test
    fun nativeThroughNativeSeekRange() {
        val wall = origin + 600_000
        val r = req(origin, end = origin + 1_800_000, from = origin, to = origin + 1_800_000, variant = CatchupVariants.FLUSSONIC_RANGE)
        // The range wins even when the player reports nothing yet; no end guard on an explicit range.
        assertEquals(SeekPlan.Native(1_800_000), p.seek(origin + 1_800_000, wall, r, unseekable, live()))
        assertEquals(SeekPlan.Native(5_000), p.seek(origin + 5_000, wall, r, unseekable, live()))
        assertIs<SeekPlan.Rerequest>(p.seek(origin + 1_800_001, wall, r, unseekable, live()))
    }

    @Test
    fun hlsWithoutRangeIsRerequestOnly() {
        // The orchestrator reports an HLS request without nativeSeekRange as unseekable without duration (§3.8.4).
        val wall = origin + 600_000
        val hls = PositionView(600_000, null, isSeekable = false, ended = false)
        val t = origin + 300_000
        assertEquals(SeekPlan.Rerequest(RequestPlan(t, null, false, t)), p.seek(t, wall, req(origin, variant = CatchupVariants.FLUSSONIC_ABS), hls, live()))
    }

    @Test
    fun seekClampsToTheArchiveStart() {
        val lw = live()
        val wall = origin
        val plan = assertIs<SeekPlan.Rerequest>(p.seek(lw.archiveFromMs - DAY, wall, req(origin), unseekable, lw)).plan
        assertEquals(lw.archiveFromMs, plan.startMs)
        assertEquals(lw.archiveFromMs, plan.targetMs)
        assertEquals(false, plan.padBefore)
        assertNull(plan.stopMs)
    }

    // ------------------------------------------------------------------ continuation (§3.8.5, S1)

    @Test
    fun continuationLeadAndEnded() {
        val lw = live()
        val r = req(origin)
        val wall = origin + 1_797_000
        // remaining = duration − position.
        assertEquals(ContinuePlan.Keep, p.onProgress(wall - 1, r, PositionView(1_796_999, 1_800_000, true, false), lw))
        assertEquals(
            ContinuePlan.Rerequest(RequestPlan(wall, null, false, wall)),
            p.onProgress(wall, r, PositionView(1_797_000, 1_800_000, true, false), lw),
        )
        // ended without a duration (an open stream the panel ended).
        assertEquals(
            ContinuePlan.Rerequest(RequestPlan(wall, null, false, wall)),
            p.onProgress(wall, r, PositionView(1_797_000, null, false, ended = true), lw),
        )
        // A fixed range without a reported duration uses its end.
        val fixed = req(origin, end = origin + 1_800_000)
        assertEquals(ContinuePlan.Keep, p.onProgress(origin + 1_796_999, fixed, PositionView(1_796_999, null, false, false), lw))
        assertIs<ContinuePlan.Rerequest>(p.onProgress(origin + 1_797_000, fixed, PositionView(1_797_000, null, false, false), lw))
    }

    @Test
    fun openRequestWithoutDurationKeeps() {
        assertEquals(ContinuePlan.Keep, p.onProgress(origin + 10_000_000, req(origin), PositionView(10_000_000, null, false, false), live()))
    }

    /** S1: a 20 s time-shift is never dropped by a continuation; within 5 s it goes live. */
    @Test
    fun continuationSnapsOnlyWithin5s() {
        val lw = live()
        val r = req(lw.liveEdgeMs - 120_000)
        val end = PositionView(119_000, 120_000, true, false)
        for ((behind, expectLive) in listOf(20_000L to false, 6_000L to false, 5_001L to false, 5_000L to true, 1_000L to true)) {
            val wall = lw.liveEdgeMs - behind
            val plan = p.onProgress(wall, r, end, lw)
            if (expectLive) assertEquals(ContinuePlan.Live, plan, "behind $behind") else assertEquals(
                ContinuePlan.Rerequest(RequestPlan(wall, null, false, wall)), plan, "behind $behind",
            )
        }
        // A user seek to 20 s behind snaps (30 s rule) where the continuation does not.
        assertEquals(SeekPlan.Live, p.seek(lw.liveEdgeMs - 20_000, lw.liveEdgeMs - 120_000, r, end, lw))
    }

    // ------------------------------------------------------------------ pause on live (§3.8.7)

    @Test
    fun resumeFromPause() {
        val lw = live()
        val at = lw.liveEdgeMs - 22_500
        assertEquals(ContinuePlan.Live, p.resumeFromPause(at, 2_999, lw))
        assertEquals(ContinuePlan.Live, p.resumeFromPause(at, 0, lw))
        assertEquals(ContinuePlan.Rerequest(RequestPlan(at, null, false, at)), p.resumeFromPause(at, 3_000, lw))
        assertEquals(ContinuePlan.Rerequest(RequestPlan(at, null, false, at)), p.resumeFromPause(at, 20_000, lw))
        // A pause older than the archive resumes at its oldest instant.
        val old = lw.archiveFromMs - 1
        assertEquals(lw.archiveFromMs, (p.resumeFromPause(old, DAY * 9, lw) as ContinuePlan.Rerequest).plan.startMs)
    }

    // ------------------------------------------------------------------ probing and lag (§3.8.8, S3)

    @Test
    fun lagStepsBackOnceThenLiveNotReady() {
        val lw = live()
        val near = req(lw.liveEdgeMs - 20_000)
        assertEquals(ProbePlan.StepBack(ARCHIVE_LAG_STEP_MS), p.onNotInArchive(near, lw, false, 0, 3, lagRetriesUsed = 0))
        // The stepped-back request (60 s earlier, still part of the lag sequence) fails again → live.
        val stepped = req(near.originMs - ARCHIVE_LAG_STEP_MS)
        assertEquals(ProbePlan.LiveNotReady, p.onNotInArchive(stepped, lw, false, 0, 3, lagRetriesUsed = 1))
        // Guard boundary: exactly 120 s from the edge is lag, 120 s + 1 ms is a variant failure.
        assertEquals(ProbePlan.StepBack(ARCHIVE_LAG_STEP_MS), p.onNotInArchive(req(lw.liveEdgeMs - 120_000), lw, false, 0, 3, 0))
        assertEquals(ProbePlan.NextVariant(1), p.onNotInArchive(req(lw.liveEdgeMs - 120_001), lw, false, 0, 3, 0))
        // The lag rule comes before "variant worked earlier" and before exhaustion.
        assertEquals(ProbePlan.StepBack(ARCHIVE_LAG_STEP_MS), p.onNotInArchive(near, lw, true, 2, 3, 0))
    }

    @Test
    fun probeOrder() {
        val lw = live()
        val far = req(lw.liveEdgeMs - 3_600_000)
        assertEquals(ProbePlan.NextVariant(1), p.onNotInArchive(far, lw, false, 0, 3, 0))
        assertEquals(ProbePlan.NextVariant(2), p.onNotInArchive(far, lw, false, 1, 3, 0))
        assertEquals(ProbePlan.Fail, p.onNotInArchive(far, lw, false, 2, 3, 0), "exhausted")
        assertEquals(ProbePlan.Fail, p.onNotInArchive(far, lw, false, 0, 1, 0), "single variant")
        // Mid-session (the variant worked earlier): never another variant.
        assertEquals(ProbePlan.Fail, p.onNotInArchive(far, lw, true, 0, 3, 0))
        // The orchestrator disables the lag rule for non-404 failures with an unreachable live edge.
        assertEquals(ProbePlan.NextVariant(1), p.onNotInArchive(req(lw.liveEdgeMs - 20_000), lw.copy(liveEdgeMs = Long.MAX_VALUE), false, 0, 3, 0))
    }

    // ------------------------------------------------------------------ startOffset

    @Test
    fun startOffset() {
        val f = p.frames(tb(corrH = 2.0), null, 0)
        val reqStartA = deviceNow - 3_600_000
        val originP = f.programmeOf(reqStartA)
        assertEquals(0L, p.startOffset(originP, reqStartA, f))
        assertEquals(42_000L, p.startOffset(originP + 42_000, reqStartA, f))
        assertEquals(0L, p.startOffset(originP - 1, reqStartA, f), "never negative")
    }

    private companion object {
        const val MIN = 60_000L
        const val DAY = 86_400_000L
    }
}
