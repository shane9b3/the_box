package app.thebox.playback

import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.catchup.archive.ArchiveStart
import app.thebox.core.full.fakes.FakeArchivePlayback
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.ui.playback.CatchupFailure
import app.thebox.ui.playback.CatchupLaunch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/design/m4-player-ux.md §1.3, §3.8.9: the M3 launcher as an adapter over ArchivePlayback. */
class CatchupLauncherImplTest {
    private val archive = FakeArchivePlayback()
    private val launcher = CatchupLauncherImpl(archive)
    private val channel = GuideFixtures.channel(3, archiveDays = 3)
    private val nowMin = GuideFixtures.NOW_MIN
    private val info = GuideFixtures.info(nowMin - 60, nowMin - 30)

    @Test
    fun startedPassesThroughWithTheSameArguments() = runTest {
        assertEquals(CatchupLaunch.Started, launcher.play(channel, info, rawEndMin = nowMin - 30))
        val call = archive.calls.single()
        assertEquals("playProgramme", call.name)
        assertEquals(listOf(channel, info, nowMin - 30, false), call.args)
    }

    @Test
    fun failureMapping() = runTest {
        val expected = mapOf(
            ArchiveFailure.CHANNEL_GONE to CatchupFailure.CHANNEL_GONE,
            ArchiveFailure.NO_ARCHIVE to CatchupFailure.NO_ARCHIVE,
            ArchiveFailure.OUTSIDE_ARCHIVE to CatchupFailure.OUTSIDE_ARCHIVE,
            ArchiveFailure.UNSUPPORTED_MODE to CatchupFailure.UNSUPPORTED_MODE,
            ArchiveFailure.CREDENTIALS to CatchupFailure.CREDENTIALS,
            // The panel's clock has not reached the programme (negative correction): "not in the archive".
            ArchiveFailure.NO_PROGRAMME to CatchupFailure.OUTSIDE_ARCHIVE,
            // Never synchronous; mapped defensively.
            ArchiveFailure.NOT_IN_ARCHIVE to CatchupFailure.OUTSIDE_ARCHIVE,
            ArchiveFailure.PLAYBACK to CatchupFailure.UNSUPPORTED_MODE,
        )
        assertEquals(ArchiveFailure.entries.toSet(), expected.keys)
        for ((reason, failure) in expected) {
            archive.startResult = ArchiveStart.Failed(reason)
            assertEquals(reason.name, CatchupLaunch.Failed(failure), launcher.play(channel, info, nowMin - 30))
        }
    }
}
