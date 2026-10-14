package app.thebox.core.playback.errors

import app.thebox.core.playback.AccountGate
import app.thebox.core.playback.FailureSignal
import app.thebox.core.playback.LadderContext
import app.thebox.core.playback.LadderCounters
import app.thebox.core.playback.LadderDecision
import app.thebox.core.playback.LoadOrigin
import app.thebox.core.playback.NextStep
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.StallKind
import app.thebox.core.playback.errors.ErrorLadderTableTest.Companion.ctx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sequences and global rules of §4.3 that a single table line cannot show. */
class ErrorLadderRulesTest {
    private val ladder = ErrorLadderImpl(random = { 0.5 })
    private val http403 = FailureSignal.Http(403, LoadOrigin.PROGRESSIVE)

    private fun LadderContext.with(c: LadderCounters, now: Long = nowMs, ms: Long = msSinceTuneStart) = copy(counters = c, nowMs = now, msSinceTuneStart = ms)

    @Test
    fun limitLadderFeedsItsOwnCounters() {
        var c = LadderCounters()
        val base = ctx()
        val steps = mutableListOf<String>()
        repeat(3) {
            val t = ladder.trace(http403, base.with(c))
            steps += t.row
            c = t.counters
        }
        assertEquals(listOf("L2", "L3", "L4"), steps)
        assertEquals(2, c.limitSteps)
    }

    @Test
    fun expiredAccountAnsweredWith403EndsAsAccountExpiredAfterTheRefresh() {
        // I-17: the panel answers 403 on the stream; the L3 refresh brings a card that says Expired.
        var c = LadderCounters()
        c = ladder.trace(http403, ctx().with(c)).counters // L2
        val l3 = ladder.trace(http403, ctx().with(c))
        assertEquals("L3", l3.row)
        assertTrue((l3.decision as LadderDecision.ReResolve).refreshAccount)
        val fresh = ctx().copy(accountStatus = AccountGate.EXPIRED, counters = l3.counters)
        val t = ladder.trace(FailureSignal.TinyBody(500), fresh) // any upstream failure after the refresh
        assertEquals("G1", t.row)
        assertEquals(PlaybackErrorCode.ACCOUNT_EXPIRED, assertIs<LadderDecision.Fail>(t.decision).error.code)
    }

    @Test
    fun streamSwitchTimeoutReplacesTheDecisionAndKeepsCounters() {
        val t = ladder.trace(FailureSignal.Http(500, LoadOrigin.PROGRESSIVE), ctx(mapOf("ms" to "15000")))
        assertEquals("GT/L15", t.row)
        assertEquals(PlaybackErrorCode.SERVER_ERROR, (t.decision as LadderDecision.Fail).error.code)
        assertEquals(1, t.counters.transientRetries)
    }

    @Test
    fun streamSwitchTimeoutComesFromTheSettingsSupplier() {
        val short = ErrorLadderImpl(streamSwitchTimeoutMs = { 2_000 }, random = { 0.5 })
        assertEquals("GT/L2", short.trace(http403, ctx(mapOf("ms" to "2000"))).row)
        assertEquals("L2", short.trace(http403, ctx(mapOf("ms" to "1999"))).row)
    }

    @Test
    fun unlimitedReconnectsContinuePastTheTimeout() {
        var c = LadderCounters()
        val signal = FailureSignal.ReconnectExhausted(null, noBytes = true)
        repeat(100) { n ->
            val t = ladder.trace(signal, ctx(mapOf("attempts" to "-1", "ms" to (60_000L + n * 5_000L).toString())).with(c))
            assertEquals(LadderDecision.ReResolve(ErrorConstants.UNLIMITED_RETRY_INTERVAL_MS), t.decision)
            c = t.counters
        }
        assertEquals(100, c.transientRetries)
    }

    @Test
    fun controllerRetryDelaysJitterTwentyPercent() {
        val low = ErrorLadderImpl(random = { 0.0 })
        val high = ErrorLadderImpl(random = { 1.0 })
        val signal = FailureSignal.Http(500, LoadOrigin.PROGRESSIVE)
        assertEquals(800L, (low.decide(signal, ctx()).first as LadderDecision.ReResolve).delayMs)
        assertEquals(1_200L, (high.decide(signal, ctx()).first as LadderDecision.ReResolve).delayMs)
        assertEquals(4_800L, (high.decide(signal, ctx(mapOf("transientRetries" to "7", "attempts" to "10"))).first as LadderDecision.ReResolve).delayMs)
    }

    @Test
    fun healthyResetClearsCountersAfterThirtySeconds() {
        val used = LadderCounters(limitSteps = 2, transientRetries = 3, uaFallbackTried = true)
        assertEquals(used, ErrorLadderImpl.countersAfterHealthy(used, ErrorConstants.LADDER_HEALTHY_RESET_MS - 1))
        assertEquals(LadderCounters(), ErrorLadderImpl.countersAfterHealthy(used, ErrorConstants.LADDER_HEALTHY_RESET_MS))
    }

    @Test
    fun compatWindowSurvivesTheHealthyResetButNotAChannelChange() {
        val stall = FailureSignal.Stall(StallKind.DECODER_STUCK)
        val playing = mapOf("firstFrame" to "true", "ms" to "60000")
        ladder.beginChannel("p:1/xc:1")
        assertEquals("L27a", ladder.trace(stall, ctx(playing + ("now" to "1000000"))).row)
        // 30 s healthy → counters reset by the controller; the second stall within 5 min still escalates.
        assertEquals("L27b", ladder.trace(stall, ctx(playing + ("now" to "1120000"))).row)
        assertEquals("L27c", ladder.trace(stall, ctx(playing + mapOf("now" to "1200000", "compat" to "1"))).row)
        // Outside the 5-minute window it starts over.
        assertEquals("L27a", ladder.trace(stall, ctx(playing + ("now" to "1600000"))).row)
        ladder.beginChannel("p:1/xc:1")
        assertEquals("L27b", ladder.trace(stall, ctx(playing + ("now" to "1610000"))).row)
        ladder.beginChannel("p:1/xc:2")
        assertEquals("L27a", ladder.trace(stall, ctx(playing + ("now" to "1620000"))).row)
    }

    @Test
    fun pcrResetsAreLimitedPerMinuteAcrossCycles() {
        val pcr = FailureSignal.PcrDiscontinuity(10_000)
        val playing = mapOf("firstFrame" to "true", "ms" to "60000")
        ladder.beginChannel("k")
        for (now in listOf(1_000_000L, 1_010_000L, 1_020_000L)) assertEquals("L25", ladder.trace(pcr, ctx(playing + ("now" to now.toString()))).row)
        assertEquals("L27a", ladder.trace(pcr, ctx(playing + ("now" to "1030000"))).row)
        assertEquals("L25", ladder.trace(pcr, ctx(playing + ("now" to "1080000"))).row)
    }

    @Test
    fun behindLiveWindowIsLimitedPerMinuteAcrossCycles() {
        val playing = mapOf("firstFrame" to "true", "ms" to "60000")
        ladder.beginChannel("k")
        repeat(3) { assertEquals("L26", ladder.trace(FailureSignal.BehindLiveWindow, ctx(playing + ("now" to (1_000_000L + it).toString()))).row) }
        assertEquals("L26b", ladder.trace(FailureSignal.BehindLiveWindow, ctx(playing + ("now" to "1000010"))).row)
    }

    @Test
    fun reclaimedTwiceInAMinuteFailsButNotSpreadOut() {
        val playing = mapOf("firstFrame" to "true", "ms" to "60000")
        assertEquals("L38", ladder.trace(FailureSignal.ResourcesReclaimed, ctx(playing + ("now" to "1000000"))).row)
        assertEquals("L38", ladder.trace(FailureSignal.ResourcesReclaimed, ctx(playing + ("now" to "1060000"))).row)
        assertEquals("L38b", ladder.trace(FailureSignal.ResourcesReclaimed, ctx(playing + ("now" to "1061000"))).row)
    }

    @Test
    fun failuresCarryDetailsAndFilteredActions() {
        val limit = ladder.decide(http403, ctx(mapOf("limitSteps" to "2", "account" to "ACTIVE", "active" to "2", "max" to "2"))).first
        val le = assertIs<LadderDecision.Fail>(limit).error
        assertEquals(PlaybackErrorCode.CONNECTION_LIMIT, le.code)
        assertEquals(2, le.detail.activeConnections)
        assertEquals(2, le.detail.maxConnections)
        assertEquals(403, le.detail.httpStatus)
        assertEquals("h.example:8080", le.detail.host)
        assertEquals("Http", le.detail.signal)
        assertEquals(listOf(NextStep.RETRY, NextStep.STOP_AND_WAIT), le.actions)

        val offline = assertIs<LadderDecision.Fail>(ladder.decide(FailureSignal.Http(404, LoadOrigin.PROGRESSIVE), ctx()).first).error
        assertEquals(listOf(NextStep.PREVIOUS_CHANNEL, NextStep.RETRY), offline.actions)
        assertNull(offline.detail.activeConnections)

        val codec = assertIs<LadderDecision.Fail>(
            ladder.decide(FailureSignal.DecoderInit(true, "video/hevc", "c2.hevc"), ctx(mapOf("swVideo" to "false"))).first,
        ).error
        assertEquals("video/hevc", codec.detail.codec)
        assertEquals("c2.hevc", codec.detail.decoderName)

        val local = assertIs<LadderDecision.Fail>(ladder.decide(FailureSignal.LocalConnectionLimit(1, 1), ctx()).first).error
        assertEquals(1, local.detail.activeConnections)
        assertEquals(1, local.detail.maxConnections)

        val unknown = assertIs<LadderDecision.Fail>(ladder.decide(FailureSignal.Unknown(9999), ctx(mapOf("unknownRetries" to "1"))).first).error
        assertEquals(9999, unknown.detail.media3ErrorCode)

        val reconnect = assertIs<LadderDecision.Fail>(
            ladder.decide(FailureSignal.ReconnectExhausted(500, false), ctx(mapOf("firstFrame" to "true", "ms" to "60000", "transientRetries" to "5"))).first,
        ).error
        assertEquals(500, reconnect.detail.httpStatus)
    }

    @Test
    fun formatFlipRunsOncePerCycle() {
        val t1 = ladder.trace(FailureSignal.Http(405, LoadOrigin.PROGRESSIVE), ctx())
        assertEquals(LadderDecision.FlipXtreamOutput(app.thebox.core.playback.XtreamOutput.HLS), t1.decision)
        val t2 = ladder.trace(FailureSignal.Http(406, LoadOrigin.HLS_MANIFEST), ctx(mapOf("output" to "HLS")).copy(counters = t1.counters))
        assertEquals("L10", t2.row)
    }

    @Test
    fun audioFallbacksWalkPassthroughSoftwareDrop() {
        val stall = FailureSignal.Stall(StallKind.AUDIO_NOT_STARTED)
        var c = LadderCounters()
        val base = ctx(mapOf("firstFrame" to "true", "ms" to "60000", "passthrough" to "true", "passthroughMime" to "audio/eac3"))
        val rows = mutableListOf<String>()
        repeat(4) {
            val t = ladder.trace(stall, base.copy(counters = c))
            rows += t.row
            c = t.counters
        }
        assertEquals(listOf("L31", "L32", "L33", "L33b"), rows)
    }
}
