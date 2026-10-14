package app.thebox.player.resolve

import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.catchup.CatchupTarget
import app.thebox.core.common.BoxLog
import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.model.StableKey
import app.thebox.core.playback.ChannelStreamInfo
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.PlaylistPlaybackInfo
import app.thebox.core.playback.ResolveInput
import app.thebox.core.playback.ResolveOutcome
import app.thebox.core.playback.ResolvedStream
import app.thebox.core.playback.StreamCredentials
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.StreamUrlBuilder
import app.thebox.core.playback.XtreamOutput
import app.thebox.core.playback.resolve.ResolveConstants
import app.thebox.core.playback.resolve.ResolveSecrets
import app.thebox.core.playback.resolve.StreamUrlBuilderImpl
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.player.api.PlaybackSettingsSource
import app.thebox.player.api.PlaybackSourceProvider
import app.thebox.player.internal.StreamUrlResolver
import app.thebox.player.internal.TuneTargetKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Provider reads + [StreamUrlBuilder]; builds `CatchupTarget`s (§3.2, §3.14). No cache of resolved URLs: every call
 * re-reads the template and re-expands. Never throws (cancellation aside); every failure is [ResolveOutcome.Failed].
 * Logs `ResolvedStream.toString()` only. Every expanded credential (raw, path- and query-encoded) and every resolved
 * URL is registered with `Redact` (§3.2.8, S9).
 */
@Singleton
internal class StreamUrlResolverImpl @Inject constructor(
    private val provider: PlaybackSourceProvider,
    private val settings: PlaybackSettingsSource,
    private val builder: StreamUrlBuilder,
) : StreamUrlResolver {

    override suspend fun resolve(target: TuneTargetKey, baseIndex: Int?, output: XtreamOutput?, userAgentOverride: String?): ResolveOutcome =
        guard {
            when (target) {
                is TuneTargetKey.Direct -> resolveDirect(target, userAgentOverride)
                is TuneTargetKey.Channel -> when (val inputs = load(target.identity)) {
                    is Loaded.Failed -> inputs.outcome
                    is Loaded.Ok -> build(
                        ResolveInput(
                            channel = inputs.channel,
                            playlist = inputs.playlist,
                            credentials = inputs.credentials,
                            account = inputs.account,
                            defaultUserAgent = settings.current.defaultUserAgent,
                            baseIndex = baseIndex ?: inputs.playlist.activeBaseIndex,
                            output = output,
                            userAgentOverride = userAgentOverride,
                        ),
                        inputs.credentials,
                    )
                }
            }
        }

    override suspend fun catchupTarget(identity: ChannelIdentity): CatchupTarget? {
        val inputs = try {
            load(identity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "catch-up target failed: ${e.javaClass.simpleName}")
            return null
        }
        if (inputs !is Loaded.Ok) return null
        val outcome = build(inputs.input(settings.current.defaultUserAgent), inputs.credentials)
        val s = (outcome as? ResolveOutcome.Ok)?.stream ?: return null
        // playlist.catchup_probe_winner is not part of PlaylistPlaybackInfo (contract gap, see the WP-A report).
        return CatchupTarget(liveUrl = s.liveUrlTemplate, headers = s.headerTemplates, catchup = inputs.channel.catchup, xtream = s.xtream, winner = null)
    }

    override suspend fun expandArchive(identity: ChannelIdentity, request: ArchiveRequest): ResolveOutcome = guard {
        when (val inputs = load(identity)) {
            is Loaded.Failed -> inputs.outcome
            is Loaded.Ok -> {
                val input = inputs.input(settings.current.defaultUserAgent)
                val b = builder as? StreamUrlBuilderImpl ?: StreamUrlBuilderImpl()
                registered(b.buildArchive(input, request), inputs.credentials)
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private sealed interface Loaded {
        class Ok(val channel: ChannelStreamInfo, val playlist: PlaylistPlaybackInfo, val credentials: StreamCredentials?, val account: XtreamAccountInfo?) : Loaded {
            fun input(defaultUa: String) = ResolveInput(channel, playlist, credentials, account, defaultUa)
        }
        class Failed(val outcome: ResolveOutcome.Failed) : Loaded
    }

    /** §3.2.1: channel → playlist (needsReauth) → credentials → account. */
    private suspend fun load(identity: ChannelIdentity): Loaded {
        val channel = provider.channel(identity) ?: return failed(PlaybackErrorCode.CHANNEL_NOT_FOUND, "channel not found")
        val playlist = provider.playlist(identity.playlistId) ?: return failed(PlaybackErrorCode.CHANNEL_NOT_FOUND, "playlist not found")
        if (playlist.needsReauth) return failed(PlaybackErrorCode.NEEDS_REAUTH, "playlist needs a new login")
        val credentials = when (val c = provider.credentials(identity.playlistId)) {
            is Outcome.Ok -> c.value
            is Outcome.Err -> return failed(credentialCode(c.error), "credentials: ${c.error.code}")
        }
        val account = provider.account(identity.playlistId)
        return Loaded.Ok(channel, playlist, credentials, account)
    }

    private fun credentialCode(e: SourceError): PlaybackErrorCode = when (e) {
        is SourceError.Credentials -> when (e.reason) {
            CredentialFailure.UNAVAILABLE -> PlaybackErrorCode.CREDENTIALS_UNAVAILABLE
            CredentialFailure.MISSING, CredentialFailure.KEY_INVALIDATED, CredentialFailure.CORRUPT -> PlaybackErrorCode.NEEDS_REAUTH
        }
        else -> PlaybackErrorCode.CREDENTIALS_UNAVAILABLE
    }

    private fun failed(code: PlaybackErrorCode, reason: String) = Loaded.Failed(ResolveOutcome.Failed(code, reason))

    private fun build(input: ResolveInput, credentials: StreamCredentials?): ResolveOutcome = registered(builder.build(input), credentials)

    /** §3.2.1 Direct: a literal M3U URL, no provider calls, broker key `d:<host>`. */
    private fun resolveDirect(target: TuneTargetKey.Direct, userAgentOverride: String?): ResolveOutcome {
        val channel = ChannelStreamInfo(
            identity = ChannelIdentity(ResolveConstants.DIRECT_PLAYLIST_ID, DIRECT_KEY),
            name = DIRECT_NAME,
            urlTemplate = target.url,
            headers = target.headers,
        )
        val playlist = PlaylistPlaybackInfo(playlistId = ResolveConstants.DIRECT_PLAYLIST_ID, type = PlaylistType.M3U_URL, userAgent = null)
        val input = ResolveInput(channel, playlist, credentials = null, account = null, defaultUserAgent = settings.current.defaultUserAgent, userAgentOverride = userAgentOverride)
        val outcome = builder.build(input)
        if (outcome !is ResolveOutcome.Ok || target.formatHint == StreamFormat.UNKNOWN) return registered(outcome, null)
        val s = outcome.stream
        return registered(
            ResolveOutcome.Ok(
                ResolvedStream(
                    s.identity, s.brokerKey, s.url, s.headers, target.formatHint, s.xtream, s.output, s.baseIndex, s.baseCount, s.host,
                    s.ignoreTlsErrors, s.liveUrlTemplate, s.headerTemplates,
                ),
            ),
            null,
        )
    }

    private fun registered(outcome: ResolveOutcome, credentials: StreamCredentials?): ResolveOutcome {
        when (outcome) {
            is ResolveOutcome.Ok -> {
                ResolveSecrets.registerCredentials(credentials)
                ResolveSecrets.registerUrl(outcome.stream.url)
                BoxLog.i(TAG) { "resolved ${outcome.stream}" }
            }
            is ResolveOutcome.Failed -> BoxLog.i(TAG) { "resolve failed ${outcome.code.key}: ${outcome.reason}" }
        }
        return outcome
    }

    private inline fun guard(block: () -> ResolveOutcome): ResolveOutcome = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        BoxLog.w(TAG, "resolve error: ${e.javaClass.simpleName}")
        ResolveOutcome.Failed(PlaybackErrorCode.INTERNAL, "resolver error: ${e.javaClass.simpleName}")
    }

    private companion object {
        const val TAG = "StreamResolver"
        const val DIRECT_NAME = "direct"
        val DIRECT_KEY = StableKey("direct")
    }
}
