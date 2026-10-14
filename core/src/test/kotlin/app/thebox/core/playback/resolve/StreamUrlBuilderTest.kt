package app.thebox.core.playback.resolve

import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.model.CatchupMode
import app.thebox.core.model.StableKey
import app.thebox.core.playback.ChannelStreamInfo
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.PlaylistPlaybackInfo
import app.thebox.core.playback.ResolveInput
import app.thebox.core.playback.ResolveOutcome
import app.thebox.core.playback.ResolvedStream
import app.thebox.core.playback.StreamCredentials
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.XtreamOutput
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.AccountStatus
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.core.xtream.XtreamStreamRef
import app.thebox.core.xtream.XtreamUrls
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamUrlBuilderTest {
    private val builder = StreamUrlBuilderImpl()
    private val id = ChannelIdentity(7, StableKey("xc:123"))
    private val pass = "p@ss/w#rd"
    private val creds = StreamCredentials(listOf("a.example:8080/player_api.php", "http://b.example"), "user", pass)
    private val xtreamChannel = ChannelStreamInfo(id, "One", "{{base}}/live/{{u}}/{{p}}/123.{{ext}}", xtreamStreamId = 123)
    private val xtreamPlaylist = PlaylistPlaybackInfo(7, PlaylistType.XTREAM, userAgent = null)

    private fun account(vararg formats: String) =
        XtreamAccountInfo(auth = true, status = AccountStatus.ACTIVE, allowedOutputFormats = formats.toList(), measuredAtDeviceMs = 0)

    private fun input(
        channel: ChannelStreamInfo = xtreamChannel,
        playlist: PlaylistPlaybackInfo = xtreamPlaylist,
        credentials: StreamCredentials? = creds,
        account: XtreamAccountInfo? = null,
        baseIndex: Int = playlist.activeBaseIndex,
        output: XtreamOutput? = null,
        ua: String? = null,
    ) = ResolveInput(channel, playlist, credentials, account, "TheBox/1.0", baseIndex, output, ua)

    private fun ok(i: ResolveInput): ResolvedStream = assertIs<ResolveOutcome.Ok>(builder.build(i)).stream

    // ------------------------------------------------------------------ Xtream API

    @Test
    fun xtreamApiExpandsAllFourPlaceholders() {
        val s = ok(input())
        assertEquals("http://a.example:8080/live/user/p@ss%2Fw%23rd/123.ts", s.url)
        assertEquals("http://a.example:8080/live/{{u}}/{{p}}/123.ts", s.liveUrlTemplate)
        assertEquals("a.example:8080", s.host)
        assertEquals("p:7", s.brokerKey)
        assertEquals(id, s.identity)
        assertEquals(StreamFormat.TS, s.formatHint)
        assertEquals(XtreamOutput.TS, s.output)
        assertEquals(0, s.baseIndex)
        assertEquals(2, s.baseCount)
        assertEquals(XtreamStreamRef("http://a.example:8080", "user", pass, 123, "ts"), s.xtream)
        assertEquals(mapOf("User-Agent" to "TheBox/1.0"), s.headers)
    }

    @Test
    fun baseIndexSelectsAndWrapsTheBackupHost() {
        assertEquals("b.example", ok(input(baseIndex = 1)).host)
        assertEquals(1, ok(input(baseIndex = 3)).baseIndex)
        assertEquals("http://b.example/live/{{u}}/{{p}}/123.ts", ok(input(baseIndex = 1)).liveUrlTemplate)
        assertEquals("a.example:8080", ok(input(playlist = xtreamPlaylist.copy(activeBaseIndex = 2))).host)
    }

    @ParameterizedTest(name = "setting={0} learned={1} allowed={2} flip={3} -> {4}")
    @CsvSource(
        "AUTO, , , , TS",
        "AUTO, , ts, , TS",
        "AUTO, , m3u8, , HLS",
        "AUTO, , 'ts,m3u8', , TS",
        "AUTO, , 'M3U8,rtmp', , HLS",
        "AUTO, , rtmp, , TS",
        "AUTO, HLS, , , HLS",
        "AUTO, HLS, ts, , HLS",
        "AUTO, TS, m3u8, , TS",
        "AUTO, AUTO, m3u8, , HLS",
        "TS, , m3u8, , TS",
        "TS, HLS, , , TS",
        "HLS, , ts, , HLS",
        "HLS, TS, , , HLS",
        "HLS, , , TS, TS",
        "TS, , , HLS, HLS",
        "AUTO, HLS, , TS, TS",
        "AUTO, , m3u8, AUTO, HLS",
    )
    fun outputTable(setting: XtreamOutput, learned: XtreamOutput?, allowed: String?, flip: XtreamOutput?, expected: XtreamOutput) {
        val pl = xtreamPlaylist.copy(xtreamOutput = setting, xtreamOutputLearned = learned)
        val acc = allowed?.let { account(*it.split(',').toTypedArray()) }
        val s = ok(input(playlist = pl, account = acc, output = flip))
        assertEquals(expected, s.output)
        assertTrue(s.url.endsWith("/123." + expected.extension), s.url)
        assertEquals(if (expected == XtreamOutput.HLS) StreamFormat.HLS else StreamFormat.TS, s.formatHint)
        assertEquals(expected.extension, s.xtream!!.extension)
    }

    @Test
    fun xtreamWithoutCredentialsFails() {
        assertFailed(PlaybackErrorCode.NEEDS_REAUTH, builder.build(input(credentials = null)))
        assertFailed(PlaybackErrorCode.NEEDS_REAUTH, builder.build(input(credentials = StreamCredentials(emptyList(), "u", "p"))))
        assertFailed(PlaybackErrorCode.NEEDS_REAUTH, builder.build(input(credentials = StreamCredentials(listOf("h"), null, "p"))))
    }

    // ------------------------------------------------------------------ Xtream-shaped M3U

    private val m3u = PlaylistPlaybackInfo(9, PlaylistType.M3U_URL, userAgent = "PlaylistUA/2")
    private val m3uCreds = StreamCredentials(emptyList(), "joe", "s3cret+1")
    private fun shaped(url: String) = ChannelStreamInfo(ChannelIdentity(9, StableKey("tvg:a|A")), "A", url)

    @Test
    fun shapedM3uKeepsLiteralBaseAndExtension() {
        val s = ok(input(channel = shaped("http://cdn.example:80/live/{{u}}/{{p}}/55.m3u8"), playlist = m3u, credentials = m3uCreds, baseIndex = 1))
        assertEquals("http://cdn.example:80/live/joe/s3cret+1/55.m3u8", s.url)
        assertEquals("cdn.example:80", s.host)
        assertEquals(XtreamOutput.HLS, s.output)
        assertEquals(0, s.baseIndex)
        assertEquals(1, s.baseCount)
        assertEquals("p:9", s.brokerKey)
        assertEquals(XtreamStreamRef("http://cdn.example:80", "joe", "s3cret+1", 55, "m3u8"), s.xtream)
        assertEquals("PlaylistUA/2", s.headers["User-Agent"])
    }

    @Test
    fun shapedM3uExtensionFlips() {
        val ch = shaped("http://cdn/live/{{u}}/{{p}}/55.ts?x=1")
        assertEquals("http://cdn/live/joe/s3cret+1/55.m3u8?x=1", ok(input(channel = ch, playlist = m3u, credentials = m3uCreds, output = XtreamOutput.HLS)).url)
        assertEquals("http://cdn/live/joe/s3cret+1/55.ts?x=1", ok(input(channel = ch, playlist = m3u, credentials = m3uCreds, output = XtreamOutput.TS)).url)
        val learned = m3u.copy(xtreamOutputLearned = XtreamOutput.HLS)
        assertEquals("http://cdn/live/joe/s3cret+1/55.m3u8?x=1", ok(input(channel = ch, playlist = learned, credentials = m3uCreds)).url)
        val forced = m3u.copy(xtreamOutput = XtreamOutput.HLS)
        assertEquals(XtreamOutput.HLS, ok(input(channel = ch, playlist = forced, credentials = m3uCreds)).output)
    }

    @Test
    fun shapedShortFormAppendsOnlyForHls() {
        val ch = shaped("http://cdn/{{u}}/{{p}}/77")
        val ts = ok(input(channel = ch, playlist = m3u, credentials = m3uCreds, output = XtreamOutput.TS))
        assertEquals("http://cdn/joe/s3cret+1/77", ts.url)
        assertEquals(XtreamOutput.TS, ts.output)
        assertEquals(XtreamStreamRef("http://cdn", "joe", "s3cret+1", 77, "ts"), ts.xtream)
        assertEquals("http://cdn/joe/s3cret+1/77.m3u8", ok(input(channel = ch, playlist = m3u, credentials = m3uCreds, output = XtreamOutput.HLS)).url)
    }

    @Test
    fun shapedNonLiveExtensionIsNeverFlipped() {
        val s = ok(input(channel = shaped("http://cdn/movie/{{u}}/{{p}}/9.mp4"), playlist = m3u, credentials = m3uCreds, output = XtreamOutput.HLS))
        assertEquals("http://cdn/movie/joe/s3cret+1/9.mp4", s.url)
        assertNull(s.output)
        assertEquals(StreamFormat.PROGRESSIVE_OTHER, s.formatHint)
        assertEquals(9L, s.xtream!!.streamId)
    }

    @Test
    fun shapedTemplateWithoutUsernameFails() {
        val r = builder.build(input(channel = shaped("http://cdn/live/{{u}}/{{p}}/1.ts"), playlist = m3u, credentials = null))
        assertFailed(PlaybackErrorCode.NEEDS_REAUTH, r)
        assertEquals("template needs credentials", (r as ResolveOutcome.Failed).reason)
    }

    // ------------------------------------------------------------------ plain, direct, headers

    @Test
    fun plainUrlIsUntouched() {
        val url = "https://cdn.example/x/stream.m3u8?token=abc&u={{nope}}"
        val s = ok(input(channel = shaped(url), playlist = m3u, credentials = null))
        assertEquals(url, s.url)
        assertEquals(url, s.liveUrlTemplate)
        assertNull(s.xtream)
        assertNull(s.output)
        assertEquals(StreamFormat.HLS, s.formatHint)
    }

    @Test
    fun directUrlHasNoIdentityAndAHostBrokerKey() {
        val pl = PlaylistPlaybackInfo(ResolveConstants.DIRECT_PLAYLIST_ID, PlaylistType.M3U_URL, userAgent = null, ignoreTlsErrors = true)
        val s = ok(input(channel = shaped("http://Direct.Example:81/a.ts"), playlist = pl, credentials = null))
        assertNull(s.identity)
        assertEquals("d:direct.example:81", s.brokerKey)
        assertFalse(s.ignoreTlsErrors)
    }

    @Test
    fun headersMergeAndExpandCredentialsRaw() {
        val ch = shaped("http://cdn/live/{{u}}/{{p}}/1.ts").copy(headers = mapOf("Cookie" to "u={{u}}; p={{p}}", "user-agent" to "Chan/1"))
        val c = m3uCreds.let { StreamCredentials(it.baseUrls, it.username, it.password, secretHeaders = mapOf("Authorization" to "Bearer {{p}}")) }
        val s = ok(input(channel = ch, playlist = m3u.copy(extraHeaders = mapOf("X-Extra" to "1")), credentials = c, ua = "VLC/3"))
        assertEquals("u=joe; p=s3cret+1", s.headers["Cookie"])
        assertEquals("Bearer s3cret+1", s.headers["Authorization"])
        assertEquals("Chan/1", s.headers["User-Agent"])
        assertEquals("1", s.headers["X-Extra"])
        assertEquals("u={{u}}; p={{p}}", s.headerTemplates["Cookie"])
    }

    @Test
    fun headerTemplateWithoutCredentialsFails() {
        val ch = shaped("http://cdn/a.ts").copy(headers = mapOf("Cookie" to "p={{p}}"))
        assertFailed(PlaybackErrorCode.NEEDS_REAUTH, builder.build(input(channel = ch, playlist = m3u, credentials = null)))
    }

    @Test
    fun relativeUrlFails() {
        assertFailed(PlaybackErrorCode.UNSUPPORTED_SOURCE, builder.build(input(channel = shaped("/local/file.ts"), playlist = m3u, credentials = null)))
    }

    // ------------------------------------------------------------------ archive

    @Test
    fun archiveRequestsGetCredentialsLast() {
        val req = ArchiveRequest(
            url = "http://a.example:8080/timeshift/{{u}}/{{p}}/60/2026-10-13:20-30/123.ts?x={{p}}",
            headers = mapOf("User-Agent" to "TheBox/1.0", "Cookie" to "p={{p}}"),
            mode = CatchupMode.XC, variant = "xc-path",
            requestStart = Instant.EPOCH, requestEnd = null, nativeSeekRange = null,
        )
        val s = assertIs<ResolveOutcome.Ok>(builder.buildArchive(input(), req)).stream
        assertEquals("http://a.example:8080/timeshift/user/p@ss%2Fw%23rd/60/2026-10-13:20-30/123.ts?x=p%40ss%2Fw%23rd", s.url)
        assertEquals("p=$pass", s.headers["Cookie"])
        assertEquals(req.url, s.liveUrlTemplate)
        assertNull(s.xtream)
        assertFailed(PlaybackErrorCode.NEEDS_REAUTH, builder.buildArchive(input(credentials = null), req))
    }

    // ------------------------------------------------------------------ secrets

    @Test
    fun toStringNeverContainsThePassword() {
        val rnd = Random(20261013)
        val alphabet = "abcXYZ0189/?#%@+&=:;!$'()*,~ é€漢{}[]|\\\"<>^`"
        repeat(300) {
            val p = buildString { repeat(8 + rnd.nextInt(8)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            val u = "u" + rnd.nextInt(1_000_000)
            val c = StreamCredentials(listOf("http://h.example"), u, p)
            val s = ok(input(credentials = c))
            val text = s.toString() + c.toString()
            for (form in listOf(p, XtreamUrls.pathSegment(p), XtreamUrls.query(p), u)) assertFalse(text.contains(form), "leaked '$form' in $text")
        }
    }

    private fun assertFailed(code: PlaybackErrorCode, r: ResolveOutcome) {
        assertEquals(code, assertIs<ResolveOutcome.Failed>(r).code)
    }
}
