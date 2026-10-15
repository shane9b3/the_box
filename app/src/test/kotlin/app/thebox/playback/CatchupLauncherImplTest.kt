package app.thebox.playback

import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.catchup.ArchiveWindow
import app.thebox.core.catchup.CatchupBuild
import app.thebox.core.catchup.CatchupTarget
import app.thebox.core.catchup.CatchupUrlBuilder
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.model.Catchup
import app.thebox.core.model.CatchupMode
import app.thebox.core.model.TimeBase
import app.thebox.core.playback.Viewport
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.ArchiveTargets
import app.thebox.player.api.ArchiveTune
import app.thebox.player.api.CatchupTargetResult
import app.thebox.player.api.PlaybackController
import app.thebox.ui.playback.CatchupFailure
import app.thebox.ui.playback.CatchupLaunch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private fun unused(): PlaybackController = Proxy.newProxyInstance(
    PlaybackController::class.java.classLoader, arrayOf(PlaybackController::class.java),
) { _, m, _ -> throw AssertionError("unexpected PlaybackController.${m.name}") } as PlaybackController

/** docs/design/m3-guide.md §3.13.6. */
class CatchupLauncherImplTest {

    /** Only tuneArchive and setViewport are expected; anything else fails the test. */
    private class FakeController : PlaybackController by unused() {
        val tunes = ArrayList<ArchiveTune>()
        val viewports = ArrayList<Viewport>()
        override fun tuneArchive(tune: ArchiveTune) { tunes += tune }
        override fun setViewport(viewport: Viewport) { viewports += viewport }

    }

    private class FakeTargets(var result: CatchupTargetResult) : ArchiveTargets {
        val asked = ArrayList<ChannelIdentity>()
        override suspend fun catchupTarget(identity: ChannelIdentity): CatchupTargetResult { asked += identity; return result }
    }

    private class FakeBuilder(var result: (CatchupTarget) -> CatchupBuild) : CatchupUrlBuilder {
        val calls = ArrayList<Triple<CatchupTarget, TimeBase, ArchiveWindow>>()
        val variants = ArrayList<String?>()
        override fun build(target: CatchupTarget, timeBase: TimeBase, window: ArchiveWindow, variant: String?): CatchupBuild {
            calls += Triple(target, timeBase, window); variants += variant
            return result(target)
        }
        override fun variants(target: CatchupTarget): List<String> = emptyList()
    }

    private val nowMin = GuideFixtures.NOW_MIN
    private val nowMs = nowMin * 60_000L + 25_000L
    private val channel = GuideFixtures.channel(3, archiveDays = 3)
    private val timeBase = TimeBase(serverZone = ZoneId.of("Europe/London"), clockSkew = Duration.ofSeconds(7))
    private fun target(catchup: Catchup = Catchup(CatchupMode.XC, days = 3)) =
        CatchupTarget(liveUrl = "http://p/live/{{u}}/{{p}}/1.ts", headers = emptyMap(), catchup = catchup, xtream = null)
    private val request = ArchiveRequest(
        url = "http://p/timeshift/{{u}}/{{p}}/60/x/1.ts", headers = emptyMap(), mode = CatchupMode.XC, variant = "xc-path",
        requestStart = Instant.EPOCH, requestEnd = null, nativeSeekRange = null,
    )

    private val controller = FakeController()
    private val targets = FakeTargets(CatchupTargetResult.Found(target()))
    private val builder = FakeBuilder { CatchupBuild.Ok(request) }
    private var lookup: TimeBase? = timeBase
    private val lookedUp = ArrayList<Long>()
    private val launcher = CatchupLauncherImpl(
        targets, controller, builder, TimeBaseLookup { id -> lookedUp += id; lookup }, { nowMs },
    )

    private suspend fun play(startMin: Int, endMin: Int) =
        launcher.play(channel, GuideFixtures.info(startMin, endMin), rawEndMin = endMin)

    private fun assertFailed(reason: CatchupFailure, actual: CatchupLaunch) {
        assertEquals(CatchupLaunch.Failed(reason), actual)
        assertTrue(controller.tunes.isEmpty())
    }

    @Test
    fun channelGone() = runTest {
        targets.result = CatchupTargetResult.ChannelGone
        assertFailed(CatchupFailure.CHANNEL_GONE, play(nowMin - 60, nowMin - 30))
        assertTrue(builder.calls.isEmpty())
    }

    @Test
    fun credentialsUnavailable() = runTest {
        targets.result = CatchupTargetResult.CredentialsUnavailable
        assertFailed(CatchupFailure.CREDENTIALS, play(nowMin - 60, nowMin - 30))
    }

    @Test
    fun noArchive() = runTest {
        targets.result = CatchupTargetResult.Found(target(Catchup.NONE))
        assertFailed(CatchupFailure.NO_ARCHIVE, play(nowMin - 60, nowMin - 30))
        assertTrue(builder.calls.isEmpty())
    }

    @Test
    fun outsideArchive() = runTest {
        val edge = nowMin - 3 * 1440
        assertFailed(CatchupFailure.OUTSIDE_ARCHIVE, play(edge - 1, edge + 29))
        // Exactly at the edge is still inside.
        assertEquals(CatchupLaunch.Started, play(edge, edge + 30))
    }

    @Test
    fun unsupportedMode() = runTest {
        builder.result = { CatchupBuild.Unsupported("no template") }
        assertFailed(CatchupFailure.UNSUPPORTED_MODE, play(nowMin - 60, nowMin - 30))
    }

    @Test
    fun playlistGoneAfterLookupIsChannelGone() = runTest {
        lookup = null
        assertFailed(CatchupFailure.CHANNEL_GONE, play(nowMin - 60, nowMin - 30))
    }

    @Test
    fun endedProgrammeHasAStop() = runTest {
        assertEquals(CatchupLaunch.Started, play(nowMin - 60, nowMin - 30))
        val (t, tb, w) = builder.calls.single()
        assertEquals(target(), t)
        assertSame(timeBase, tb)
        assertEquals(listOf(1L), lookedUp)
        assertNull("default variant", builder.variants.single())
        assertEquals(Instant.ofEpochSecond((nowMin - 60) * 60L), w.programmeStart)
        assertEquals(Instant.ofEpochSecond((nowMin - 30) * 60L), w.programmeStop)
        assertEquals(Instant.ofEpochMilli(nowMs), w.deviceNow)
    }

    @Test
    fun runningProgrammeIsOpenEnded() = runTest {
        assertEquals(CatchupLaunch.Started, play(nowMin - 20, nowMin + 10))
        val w = builder.calls.single().third
        assertEquals(Instant.ofEpochSecond((nowMin - 20) * 60L), w.programmeStart)
        assertNull(w.programmeStop)
    }

    @Test
    fun okTunesArchiveOnceAndLeavesTheViewportToTheRoot() = runTest {
        assertEquals(CatchupLaunch.Started, play(nowMin - 60, nowMin - 30))
        assertEquals(listOf(ArchiveTune(channel.identity, request)), controller.tunes)
        assertEquals("FULL follows the first archive Tuned (§2.13 item 11)", emptyList<Viewport>(), controller.viewports)
        assertEquals(listOf(channel.identity), targets.asked)
    }
}
