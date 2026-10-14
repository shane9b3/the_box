package app.thebox.player.resolve

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-A. Replace the TODO bodies; keep the names.
import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.catchup.CatchupTarget
import app.thebox.core.playback.ResolveOutcome
import app.thebox.core.playback.StreamUrlBuilder
import app.thebox.core.playback.XtreamOutput
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.PlaybackSettingsSource
import app.thebox.player.api.PlaybackSourceProvider
import app.thebox.player.internal.StreamUrlResolver
import app.thebox.player.internal.TuneTargetKey
import javax.inject.Inject
import javax.inject.Singleton

/** Provider reads + [StreamUrlBuilder]; builds `CatchupTarget`s (§3.2). No cache of resolved URLs. */
@Singleton
internal class StreamUrlResolverImpl @Inject constructor(
    private val provider: PlaybackSourceProvider,
    private val settings: PlaybackSettingsSource,
    private val builder: StreamUrlBuilder,
) : StreamUrlResolver {
    override suspend fun resolve(target: TuneTargetKey, baseIndex: Int?, output: XtreamOutput?, userAgentOverride: String?): ResolveOutcome =
        TODO("WP-A")

    override suspend fun catchupTarget(identity: ChannelIdentity): CatchupTarget? = TODO("WP-A")

    override suspend fun expandArchive(identity: ChannelIdentity, request: ArchiveRequest): ResolveOutcome = TODO("WP-A")
}
