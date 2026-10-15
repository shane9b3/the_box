package app.thebox.ui.full.vm

import app.thebox.core.full.TrackKey
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.playback.AfrMode
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.BrokerSnapshot
import app.thebox.core.playback.HdrKind
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.player.api.AudioTrackOption
import app.thebox.player.api.StreamStats
import app.thebox.player.api.TextTrackOption
import app.thebox.player.api.TrackInfo
import app.thebox.player.api.TrackRef
import app.thebox.player.api.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `:player` → `:core` mirrors (docs/design/m4-player-ux.md §3.6.3, §3.10.1, §5.4), and the list's now cache (§3.3.2). */
class PlayerMappersTest {
    private fun text(i: Int, kind: String, language: String? = null, label: String? = null, selected: Boolean = false) =
        TextTrackOption(TrackRef(3, "t$i", 0), language, label, kind, selected)

    @Test
    fun trackMenuMapsKeysAndSortsSpeculativeCaptionsLast() {
        val info = TrackInfo(
            audio = listOf(
                AudioTrackOption(TrackRef(1, "a0", 0), "en", null, "AAC", 2, describesVideo = false, selected = true, supported = true),
                AudioTrackOption(TrackRef(1, "a1", 2), "de", "Deutsch", "E-AC-3", 6, describesVideo = true, selected = false, supported = false),
            ),
            text = listOf(
                text(0, "CEA-608"),                      // speculative: no descriptor, no language, no label
                text(1, "DVB", language = "en"),
                text(2, "CEA-708", language = "en"),     // declared by a caption descriptor: real
                text(3, "CEA-608", label = "CC1"),       // labelled: real
                text(4, "WebVTT"),                       // not CEA: never speculative
            ),
            textEnabled = true,
        )
        val m = PlayerMappers.trackMenu(info)
        assertEquals(TrackKey(1, "a1", 2), m.audio[1].key)
        assertEquals("E-AC-3", m.audio[1].codec)
        assertEquals(6, m.audio[1].channels)
        assertTrue(m.audio[1].describesVideo)
        assertFalse(m.audio[1].supported)
        assertTrue(m.audio[0].selected)
        assertTrue(m.textEnabled)
        assertEquals("real tracks keep their order, speculative last", listOf("t1", "t2", "t3", "t4", "t0"), m.text.map { it.key.groupId })
        assertEquals(listOf(false, false, false, false, true), m.text.map { it.speculative })
        assertEquals("t0", PlayerMappers.ref(m.text.last().key).groupId)
    }

    @Test
    fun videoFactsCarryAspectHdrAndTheDecoderOverride() {
        val f = PlayerMappers.videoFacts(
            VideoInfo(width = 1920, height = 1080, fps = 25f, hdr = HdrKind.HLG, aspectMode = AspectMode.RATIO_4_3, interlacedHint = true, decoderOverride = VideoDecoderMode.SOFTWARE_PREFERRED),
        )
        assertEquals(1920, f.width)
        assertEquals(1080, f.height)
        assertEquals(25f, f.fps)
        assertTrue(f.interlaced)
        assertEquals(HdrKind.HLG, f.hdr)
        assertEquals(AspectMode.RATIO_4_3, f.aspect)
        assertEquals(VideoDecoderMode.SOFTWARE_PREFERRED, f.decoderOverride)
        assertNull(PlayerMappers.videoFacts(VideoInfo()).decoderOverride)
    }

    @Test
    fun statsFactsCopyEveryFieldIncludingTheServer() {
        val broker = BrokerSnapshot("p:1", active = 1, closing = 0, max = 2, waiting = 0, byKind = emptyMap())
        val s = StreamStats(
            videoMime = "video/avc", videoCodecs = "avc1.640028", videoDecoder = "c2.dec", width = 1280, height = 720, fps = 50f,
            hdr = HdrKind.HDR10, audioMime = "audio/eac3", audioChannels = 6, audioDecoder = "ffmpeg", audioPassthrough = true,
            streamBitrateBps = 7_800_000, bufferMs = 2_400, droppedFrames = 3, httpStatus = 206, host = "panel:80", redirects = 1,
            format = StreamFormat.HLS, reconnects = 2, broker = broker, afr = AfrMode.ALWAYS, displayRefreshHz = 50f, tunneling = true,
            serverIndex = 1, serverCount = 3,
        )
        val f = PlayerMappers.statsFacts(s, VideoInfo(interlacedHint = true))
        assertEquals("video/avc", f.videoMime)
        assertEquals("avc1.640028", f.videoCodecs)
        assertEquals("c2.dec", f.videoDecoder)
        assertEquals(1280, f.width)
        assertEquals(720, f.height)
        assertEquals(50f, f.fps)
        assertTrue("interlace from VideoInfo", f.interlaced)
        assertEquals(HdrKind.HDR10, f.hdr)
        assertEquals("audio/eac3", f.audioMime)
        assertEquals(6, f.audioChannels)
        assertEquals("ffmpeg", f.audioDecoder)
        assertTrue(f.audioPassthrough)
        assertEquals(7_800_000, f.streamBitrateBps)
        assertEquals(2_400, f.bufferMs)
        assertEquals(3, f.droppedFrames)
        assertEquals(206, f.httpStatus)
        assertEquals("panel:80", f.host)
        assertEquals(1, f.redirects)
        assertEquals(StreamFormat.HLS, f.format)
        assertEquals(2, f.reconnects)
        assertEquals(broker, f.broker)
        assertEquals(AfrMode.ALWAYS, f.afr)
        assertEquals(50f, f.displayRefreshHz)
        assertTrue(f.tunneling)
        assertEquals(1, f.serverIndex)
        assertEquals(3, f.serverCount)
    }

    private fun prog(start: Int, end: Int) = ProgrammeInfo(start, start, end, "P$start", null, null, null, null, 0, start.toLong())

    @Test
    fun nowCacheExpiresAtTheProgrammeEndAndIsAnLru() {
        val c = ChannelNowCache(capacity = 2)
        c.put(1, prog(100, 130), nowMin = 110)
        assertEquals(130, c.get(1, 129)?.endMin)
        assertNull("expired at the end minute", c.get(1, 130))
        c.put(2, null, nowMin = 110)
        assertTrue("nothing on is fresh for its minute", c.isFresh(2, 110))
        assertFalse("and asked again the next minute", c.isFresh(2, 111))
        c.get(1, 110)
        c.put(3, prog(100, 200), nowMin = 110)
        assertEquals(2, c.size)
        assertNull("least recently used evicted", c.get(2, 110).also { assertFalse(c.isFresh(2, 110)) })
        assertEquals(3L, c.version)
    }
}
