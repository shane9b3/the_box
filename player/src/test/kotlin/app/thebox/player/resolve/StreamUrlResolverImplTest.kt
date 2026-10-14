package app.thebox.player.resolve

import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.catchup.TemplateExpander
import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.Outcome
import app.thebox.core.common.Redact
import app.thebox.core.common.SourceError
import app.thebox.core.model.Catchup
import app.thebox.core.model.CatchupMode
import app.thebox.core.model.StableKey
import app.thebox.core.playback.ChannelStreamInfo
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.PlaylistPlaybackInfo
import app.thebox.core.playback.ResolveOutcome
import app.thebox.core.playback.StreamCredentials
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.XtreamOutput
import app.thebox.core.playback.resolve.StreamUrlBuilderImpl
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.AccountStatus
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.core.xtream.XtreamStreamRef
import app.thebox.player.api.InMemoryPlaybackSettings
import app.thebox.player.api.PlaybackSourceProvider
import app.thebox.player.internal.TuneTargetKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** JUnit 4, no Robolectric: the resolver touches no `android.*`. */
class StreamUrlResolverImplTest {
    private val id = ChannelIdentity(3, StableKey("xc:42"))
    private val pass = "p/w#1"

    private class FakeProvider : PlaybackSourceProvider {
        var channel: ChannelStreamInfo? = null
        var playlist: PlaylistPlaybackInfo? = null
        var credentials: Outcome<StreamCredentials?> = Outcome.Ok(null)
        var account: XtreamAccountInfo? = null
        var throwOnChannel: Exception? = null
        val calls = mutableListOf<String>()

        override suspend fun channel(identity: ChannelIdentity): ChannelStreamInfo? {
            calls += "channel"
            throwOnChannel?.let { throw it }
            return channel
        }
        override suspend fun playlist(playlistId: Long): PlaylistPlaybackInfo? = playlist.also { calls += "playlist" }
        override suspend fun credentials(playlistId: Long): Outcome<StreamCredentials?> = credentials.also { calls += "credentials" }
        override suspend fun account(playlistId: Long): XtreamAccountInfo? = account.also { calls += "account" }
        override suspend fun refreshAccount(playlistId: Long): Outcome<XtreamAccountInfo> = error("not used")
        override suspend fun saveActiveBaseIndex(playlistId: Long, index: Int) = error("not used")
        override suspend fun saveLearnedOutput(playlistId: Long, output: XtreamOutput?) = error("not used")
        override suspend fun backupSources(identity: ChannelIdentity): List<ChannelIdentity> = emptyList()
    }

    private val provider = FakeProvider().apply {
        channel = ChannelStreamInfo(id, "Forty-two", "{{base}}/live/{{u}}/{{p}}/42.{{ext}}", headers = mapOf("Cookie" to "p={{p}}"), xtreamStreamId = 42, catchup = Catchup(CatchupMode.XC, 3, null))
        playlist = PlaylistPlaybackInfo(3, PlaylistType.XTREAM, userAgent = "PL/1", activeBaseIndex = 1)
        credentials = Outcome.Ok(StreamCredentials(listOf("http://a.example", "http://b.example:8000"), "joe", pass))
        account = XtreamAccountInfo(auth = true, status = AccountStatus.ACTIVE, allowedOutputFormats = listOf("m3u8"), measuredAtDeviceMs = 0)
    }
    private val settings = InMemoryPlaybackSettings()
    private val resolver = StreamUrlResolverImpl(provider, settings, StreamUrlBuilderImpl())

    private fun ok(r: ResolveOutcome) = (r as ResolveOutcome.Ok).stream
    private fun failed(r: ResolveOutcome) = (r as ResolveOutcome.Failed).code

    @Test
    fun resolvesAChannelWithThePersistedBaseAndTheAccountOutput() = runTest {
        val s = ok(resolver.resolve(TuneTargetKey.Channel(id)))
        assertEquals("http://b.example:8000/live/joe/p%2Fw%231/42.m3u8", s.url)
        assertEquals(1, s.baseIndex)
        assertEquals(XtreamOutput.HLS, s.output)
        assertEquals("PL/1", s.headers["User-Agent"])
        assertEquals("p=$pass", s.headers["Cookie"])
        assertEquals(listOf("channel", "playlist", "credentials", "account"), provider.calls)
    }

    @Test
    fun ladderOverridesReachTheBuilder() = runTest {
        val s = ok(resolver.resolve(TuneTargetKey.Channel(id), baseIndex = 0, output = XtreamOutput.TS, userAgentOverride = "VLC/3.0.20"))
        assertEquals("http://a.example/live/joe/p%2Fw%231/42.ts", s.url)
        assertEquals("VLC/3.0.20", s.headers["User-Agent"]) // §3.2.5 level 4 beats the playlist UA (level 5)
        provider.channel = provider.channel!!.copy(headers = mapOf("User-Agent" to "Chan/1"))
        assertEquals("Chan/1", ok(resolver.resolve(TuneTargetKey.Channel(id), userAgentOverride = "VLC/3.0.20")).headers["User-Agent"])
    }

    @Test
    fun everyResolveReadsTheTemplateAgain() = runTest {
        resolver.resolve(TuneTargetKey.Channel(id))
        provider.calls.clear()
        resolver.resolve(TuneTargetKey.Channel(id))
        assertEquals(listOf("channel", "playlist", "credentials", "account"), provider.calls)
    }

    @Test
    fun defaultUserAgentComesFromSettings() = runTest {
        provider.playlist = provider.playlist!!.copy(userAgent = null)
        settings.update { it.copy(defaultUserAgent = "TheBox/9") }
        assertEquals("TheBox/9", ok(resolver.resolve(TuneTargetKey.Channel(id))).headers["User-Agent"])
    }

    // ------------------------------------------------------------------ provider failures (§3.2.1)

    @Test
    fun missingChannelOrPlaylist() = runTest {
        provider.channel = null
        assertEquals(PlaybackErrorCode.CHANNEL_NOT_FOUND, failed(resolver.resolve(TuneTargetKey.Channel(id))))
        provider.channel = ChannelStreamInfo(id, "x", "http://h/x.ts")
        provider.playlist = null
        assertEquals(PlaybackErrorCode.CHANNEL_NOT_FOUND, failed(resolver.resolve(TuneTargetKey.Channel(id))))
    }

    @Test
    fun needsReauthStopsBeforeCredentials() = runTest {
        provider.playlist = provider.playlist!!.copy(needsReauth = true)
        assertEquals(PlaybackErrorCode.NEEDS_REAUTH, failed(resolver.resolve(TuneTargetKey.Channel(id))))
        assertFalse("credentials" in provider.calls)
    }

    @Test
    fun credentialFailuresMapToOutcomes() = runTest {
        val expected = mapOf(
            CredentialFailure.UNAVAILABLE to PlaybackErrorCode.CREDENTIALS_UNAVAILABLE,
            CredentialFailure.KEY_INVALIDATED to PlaybackErrorCode.NEEDS_REAUTH,
            CredentialFailure.CORRUPT to PlaybackErrorCode.NEEDS_REAUTH,
            CredentialFailure.MISSING to PlaybackErrorCode.NEEDS_REAUTH,
        )
        for ((reason, code) in expected) {
            provider.credentials = Outcome.Err(SourceError.Credentials(reason))
            assertEquals(reason.name, code, failed(resolver.resolve(TuneTargetKey.Channel(id))))
        }
        provider.credentials = Outcome.Err(SourceError.Internal("x"))
        assertEquals(PlaybackErrorCode.CREDENTIALS_UNAVAILABLE, failed(resolver.resolve(TuneTargetKey.Channel(id))))
    }

    @Test
    fun templateWithoutCredentialsNeedsReauth() = runTest {
        provider.credentials = Outcome.Ok(null)
        assertEquals(PlaybackErrorCode.NEEDS_REAUTH, failed(resolver.resolve(TuneTargetKey.Channel(id))))
    }

    @Test
    fun providerExceptionsBecomeInternalFailures() = runTest {
        provider.throwOnChannel = IllegalStateException("db closed")
        val r = resolver.resolve(TuneTargetKey.Channel(id)) as ResolveOutcome.Failed
        assertEquals(PlaybackErrorCode.INTERNAL, r.code)
        assertFalse(r.reason.contains("db closed"))
        assertNull(resolver.catchupTarget(id))
    }

    // ------------------------------------------------------------------ direct URLs

    @Test
    fun directUrlNeedsNoProvider() = runTest {
        val s = ok(resolver.resolve(TuneTargetKey.Direct("http://Direct.Example/feed?k=v", mapOf("Referer" to "r"), 2, StreamFormat.HLS)))
        assertNull(s.identity)
        assertEquals("d:direct.example", s.brokerKey)
        assertEquals(StreamFormat.HLS, s.formatHint)
        assertEquals("r", s.headers["Referer"])
        assertEquals(settings.current.defaultUserAgent, s.headers["User-Agent"])
        assertTrue(provider.calls.isEmpty())
        assertEquals(StreamFormat.TS, ok(resolver.resolve(TuneTargetKey.Direct("http://d/feed", emptyMap(), 1, StreamFormat.UNKNOWN))).formatHint)
    }

    // ------------------------------------------------------------------ catch-up seam (§3.14)

    @Test
    fun catchupTargetKeepsCredentialsTemplated() = runTest {
        val t = resolver.catchupTarget(id)
        assertNotNull(t)
        t!!
        assertEquals("http://b.example:8000/live/{{u}}/{{p}}/42.m3u8", t.liveUrl)
        assertEquals("p={{p}}", t.headers["Cookie"])
        assertEquals(CatchupMode.XC, t.catchup.mode)
        assertEquals(XtreamStreamRef("http://b.example:8000", "joe", pass, 42, "m3u8"), t.xtream)
        provider.channel = null
        assertNull(resolver.catchupTarget(id))
    }

    @Test
    fun archiveExpansionRunsAfterTheCatchupPass() = runTest {
        // A catch-up source holding {{u}}/{{p}} and catch-up tokens; the password itself looks like a token.
        provider.credentials = Outcome.Ok(StreamCredentials(listOf("http://a.example"), "joe", "{utc}{{u}}"))
        val target = resolver.catchupTarget(id)!!
        val source = target.liveUrl.substringBefore("/live/") + "/timeshift/{{u}}/{{p}}/60/{Y}-{m}-{d}:{H}-{M}/42.ts?start={utc}"
        val start = 1_791_919_800L
        val catchupPass = TemplateExpander.expand(source, TemplateExpander.Values(start, start + 3600, start + 7200, ZoneOffset.UTC)).url
        assertEquals("http://a.example/timeshift/{{u}}/{{p}}/60/2026-10-13:19-30/42.ts?start=$start", catchupPass)
        val req = ArchiveRequest(catchupPass, target.headers, CatchupMode.XC, "xc-path", Instant.ofEpochSecond(start), null, null)
        val s = ok(resolver.expandArchive(id, req))
        assertEquals("http://a.example/timeshift/joe/%7Butc%7D%7B%7Bu%7D%7D/60/2026-10-13:19-30/42.ts?start=$start", s.url)
        assertEquals("p={utc}{{u}}", s.headers["Cookie"])
        assertEquals(id, s.identity)
        assertEquals("p:3", s.brokerKey)
    }

    @Test
    fun archiveFailuresFollowTheProvider() = runTest {
        provider.credentials = Outcome.Err(SourceError.Credentials(CredentialFailure.UNAVAILABLE))
        val req = ArchiveRequest("http://a/timeshift/{{u}}/{{p}}/1.ts", emptyMap(), CatchupMode.XC, "xc-path", Instant.EPOCH, null, null)
        assertEquals(PlaybackErrorCode.CREDENTIALS_UNAVAILABLE, failed(resolver.expandArchive(id, req)))
    }

    // ------------------------------------------------------------------ redaction (§3.2.8, S9)

    @Test
    fun expandedFormsAndTheUrlAreRegisteredWithRedact() = runTest {
        provider.credentials = Outcome.Ok(StreamCredentials(listOf("http://a.example"), "zed-user", "x+y@z#9"))
        val s = ok(resolver.resolve(TuneTargetKey.Channel(id), baseIndex = 0))
        val text = Redact.text("open ${s.url} pass x%2By%40z%239 path x+y@z%239 raw x+y@z#9")
        assertFalse(text, text.contains("x+y@z"))
        assertFalse(text, text.contains("x%2By%40z%239"))
        assertFalse(text, text.contains("zed-user"))
        assertFalse(text, text.contains(s.url))
        assertFalse(s.toString().contains("x+y"))
    }
}
