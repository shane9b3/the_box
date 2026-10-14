package app.thebox.player.resolve

import app.thebox.core.playback.ChannelStreamInfo
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.CatchupTargetResult
import app.thebox.player.api.PlaybackSourceProvider
import app.thebox.player.controller.fakes.CH1
import app.thebox.player.controller.fakes.FakeProvider
import app.thebox.player.controller.fakes.FakeResolver
import app.thebox.player.controller.fakes.Journal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** M3 catch-up seam (docs/design/m3-guide.md §2.8, review S13): "channel gone" is told apart from a resolver failure. */
class ArchiveTargetsImplTest {
    private val base = FakeProvider()

    @Test
    fun missingChannelIsChannelGone() = runTest {
        val provider = object : PlaybackSourceProvider by base {
            override suspend fun channel(identity: ChannelIdentity): ChannelStreamInfo? = null
        }
        val targets = ArchiveTargetsImpl(provider, FakeResolver(Journal()))
        assertEquals(CatchupTargetResult.ChannelGone, targets.catchupTarget(CH1))
    }

    @Test
    fun resolverFailureWithAChannelIsCredentialsUnavailable() = runTest {
        // FakeResolver.catchupTarget returns null (what StreamUrlResolverImpl returns for re-auth or missing credentials).
        val targets = ArchiveTargetsImpl(base, FakeResolver(Journal()))
        assertEquals(CatchupTargetResult.CredentialsUnavailable, targets.catchupTarget(CH1))
    }
}
