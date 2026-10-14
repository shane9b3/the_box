package app.thebox.player.resolve

import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.ArchiveTargets
import app.thebox.player.api.CatchupTargetResult
import app.thebox.player.api.PlaybackSourceProvider
import app.thebox.player.internal.StreamUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The M3 catch-up seam (docs/design/m3-guide.md §2.8, S13) over the internal [StreamUrlResolver.catchupTarget]. The
 * resolver returns null for every failure, so a missing channel is told apart from missing credentials by asking the
 * provider first; any other resolver failure (playlist gone, re-auth needed, credentials unavailable) is reported as
 * [CatchupTargetResult.CredentialsUnavailable], whose next step is "check the playlist login".
 */
@Singleton
internal class ArchiveTargetsImpl @Inject constructor(
    private val provider: PlaybackSourceProvider,
    private val resolver: StreamUrlResolver,
) : ArchiveTargets {
    override suspend fun catchupTarget(identity: ChannelIdentity): CatchupTargetResult = withContext(Dispatchers.IO) {
        if (provider.channel(identity) == null) return@withContext CatchupTargetResult.ChannelGone
        val target = resolver.catchupTarget(identity) ?: return@withContext CatchupTargetResult.CredentialsUnavailable
        CatchupTargetResult.Found(target)
    }
}
