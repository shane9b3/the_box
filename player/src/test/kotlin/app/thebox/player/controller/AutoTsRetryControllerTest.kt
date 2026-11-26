package app.thebox.player.controller

import androidx.media3.common.PlaybackException
import app.thebox.core.playback.LadderDecision
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.XtreamOutput
import app.thebox.core.playback.resolve.AutoTsRetry
import app.thebox.core.playback.resolve.AutoTsRetry.Result
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.TuneTarget
import app.thebox.player.api.ZapList
import app.thebox.player.controller.fakes.CH1
import app.thebox.player.controller.fakes.CH2
import app.thebox.player.controller.fakes.ControllerFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (docs/decisions/2026-11-25-auto-format-retries-ts.md) The controller's side of the Auto TS retry: it starts only at
 * the first session of a user tune whose playlist says it is due; a refusal (the ladder's flip) goes back to HLS in the
 * same tune without the format notice; 30 s of TS confirms; any other failure is inconclusive and, before first media,
 * falls back to HLS at once; Retry, re-resolves and restarts never start one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoTsRetryControllerTest {
    private val f = ControllerFixture()

    @After fun tearDown() = f.close()

    private fun error() = PlaybackException("e", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

    private fun due(on: Boolean = true) {
        f.provider.playlistInfo = f.provider.playlistInfo.copy(xtreamOutput = XtreamOutput.AUTO, xtreamOutputLearned = XtreamOutput.HLS, autoTsRetryDue = on)
    }

    private fun tune() {
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
    }

    private fun retryEvents() = f.events.events.filter { it.type == PlaybackEventType.DECISION && it.code == "ts_retry" }.map { it.detail }

    private fun failWith(decision: LadderDecision) {
        var used = false
        f.ladder.script = { _, _ -> if (!used) { used = true; decision } else LadderDecision.Fail(PlaybackError(PlaybackErrorCode.INTERNAL)) }
        f.exo.emitError(error())
        f.idle()
    }

    @Test
    fun notDueTunesAsLearned() {
        due(on = false)
        tune()
        assertEquals(listOf<XtreamOutput?>(null), f.resolver.calls.map { it.output })
        assertTrue(retryEvents().isEmpty())
    }

    @Test
    fun dueAtATuneAsksForTsWithOneExtraLocalResolve() {
        due()
        tune()
        // The early resolve (learned HLS) is dropped; the session opens on TS.
        assertEquals(listOf(null, XtreamOutput.TS), f.resolver.calls.map { it.output })
        assertEquals(1, f.sources.builds.size)
        assertEquals(XtreamOutput.TS, f.sources.last.ctx.resolved.output)
        assertEquals(listOf("attempt"), retryEvents())
    }

    @Test
    fun dueAtAZap() {
        due(on = false)
        val list = object : ZapList {
            override val id = "all"
            override suspend fun neighbour(from: ChannelIdentity, delta: Int) = if (from == CH1) CH2 else CH1
        }
        f.controller.tune(TuneTarget.Channel(CH1), list)
        f.idle()
        f.play()
        due()
        f.controller.zap(+1)
        f.idle()
        assertEquals(XtreamOutput.TS, f.resolver.calls.last().output)
        assertEquals(listOf("attempt"), retryEvents())
    }

    @Test
    fun aRefusalGoesBackToHlsInTheSameTuneQuietly() {
        due()
        tune()
        failWith(LadderDecision.FlipXtreamOutput(XtreamOutput.HLS)) // L9: 405/406 on the TS request
        assertEquals(XtreamOutput.HLS, f.resolver.calls.last().output)
        assertEquals(listOf(Result.REFUSED), f.provider.tsRetries)
        f.play()
        assertTrue(f.state is PlaybackState.Playing)
        assertEquals("HLS re-learned through the existing flip", listOf<XtreamOutput?>(XtreamOutput.HLS), f.provider.savedOutput)
        assertFalse("no format notice for the retry", f.notices.any { it is PlaybackNotice.SwitchedFormat })
        assertFalse(f.states.any { it is PlaybackState.Error })
        f.advance(AutoTsRetry.CONFIRM_MS + 1_000)
        assertEquals("the HLS session never confirms TS", listOf(Result.REFUSED), f.provider.tsRetries)
    }

    @Test
    fun thirtySecondsOfTsConfirm() {
        due()
        tune()
        f.play()
        f.advance(AutoTsRetry.CONFIRM_MS - 1_000)
        assertTrue(f.provider.tsRetries.isEmpty())
        f.advance(2_000)
        assertEquals(listOf(Result.CONFIRMED), f.provider.tsRetries)
        assertEquals(listOf("attempt", "confirmed"), retryEvents())
        assertTrue(f.provider.savedOutput.isEmpty())
    }

    @Test
    fun anUnrelatedFailureBeforeFirstMediaIsInconclusiveAndFallsBackToHls() {
        due()
        tune()
        failWith(LadderDecision.RetrySame(1_500)) // e.g. a 403 or a reset on the TS request
        assertEquals(listOf(Result.INCONCLUSIVE), f.provider.tsRetries)
        f.idle()
        assertEquals("one extra request: straight to HLS", XtreamOutput.HLS, f.resolver.calls.last().output)
        f.play()
        assertTrue(f.state is PlaybackState.Playing)
        assertFalse(f.notices.any { it is PlaybackNotice.SwitchedFormat })
        assertEquals(listOf<XtreamOutput?>(XtreamOutput.HLS), f.provider.savedOutput)
    }

    @Test
    fun anUnrelatedFailureAfterFirstMediaIsInconclusiveAndTheLadderStands() {
        due()
        tune()
        f.play()
        f.advance(5_000)
        val resolves = f.resolver.calls.size
        failWith(LadderDecision.RetrySame(1_500))
        f.advance(1_600)
        assertEquals(listOf(Result.INCONCLUSIVE), f.provider.tsRetries)
        assertEquals("RetrySame reuses the TS stream", resolves, f.resolver.calls.size)
        f.play()
        f.advance(AutoTsRetry.CONFIRM_MS + 1_000)
        assertEquals("settled once", listOf(Result.INCONCLUSIVE), f.provider.tsRetries)
    }

    @Test
    fun noPictureWithinTheBudgetFallsBackToHls() {
        due()
        tune()
        f.advance(AutoTsRetry.FIRST_MEDIA_BUDGET_MS - 500)
        assertTrue(f.provider.tsRetries.isEmpty())
        f.advance(1_000)
        assertEquals(listOf(Result.INCONCLUSIVE), f.provider.tsRetries)
        assertEquals(XtreamOutput.HLS, f.resolver.calls.last().output)
        f.play()
        assertTrue(f.state is PlaybackState.Playing)
    }

    @Test
    fun neverOnARetryFromTheCardOrALadderReResolve() {
        due(on = false)
        tune()
        due()
        failWith(LadderDecision.ReResolve(0))
        f.idle()
        assertEquals("a re-resolve keeps the learned HLS", listOf<XtreamOutput?>(null, null), f.resolver.calls.map { it.output })
        failWith(LadderDecision.Fail(PlaybackError(PlaybackErrorCode.CHANNEL_OFFLINE)))
        assertTrue(f.state is PlaybackState.Error)
        f.controller.retry()
        f.idle()
        assertTrue("Retry is not a user tune", f.resolver.calls.all { it.output == null })
        assertTrue(retryEvents().isEmpty())
    }

    @Test
    fun aTuneAwayBeforeTheVerdictRecordsNothing() {
        due()
        tune()
        f.play()
        f.advance(10_000)
        due(on = false)
        f.controller.tune(TuneTarget.Channel(CH2))
        f.idle()
        f.advance(AutoTsRetry.CONFIRM_MS + AutoTsRetry.FIRST_MEDIA_BUDGET_MS)
        assertTrue(f.provider.tsRetries.isEmpty())
        assertEquals(StreamFormat.TS, f.sources.last.format)
    }
}
