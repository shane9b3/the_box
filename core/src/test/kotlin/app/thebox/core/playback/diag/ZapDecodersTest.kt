package app.thebox.core.playback.diag

import app.thebox.core.diag.ExportBundleBuilder
import app.thebox.core.diag.ExportInputs
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.ZapRecord
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Keep-decoders WP-0: the per-zap decoder figures, their aggregation and the line the owner reads. */
class ZapDecodersTest {

    private val full = ZapDecoders(
        videoDecoder = "OMX.MTK.VIDEO.DECODER.AVC", videoInitMs = 38, videoInits = 1,
        audioDecoder = "c2.android.aac.decoder", audioInitMs = 4, audioInits = 1,
        prepareToFirstFrameMs = 612, stopToPrepareMs = 210, videoReleaseMs = 22,
        videoMime = "video/avc", videoHeight = 576, videoFps = 25f, audioMime = "audio/mp4a-latm", audioChannels = 2,
    )

    @Test
    fun theLogLineCarriesEveryFigureAndTheChannelKind() {
        assertEquals(
            "zap decoders: tune=12 video=avc 576 25fps OMX.MTK.VIDEO.DECODER.AVC 38 ms, audio=mp4a-latm 2ch c2.android.aac.decoder 4 ms, " +
                "init total 42 ms, first frame 612 ms after prepare, stop to prepare 210 ms, old video release <=22 ms",
            full.logLine(12),
        )
        assertEquals(42L, full.initTotalMs)
        assertEquals("avc 576", full.videoKind(withFps = false))
        assertEquals("mp4a-latm", full.audioKind(withChannels = false))
    }

    @Test
    fun missingFiguresAreDashesNeverZeroAndRepeatedInitsAreCounted() {
        // Passthrough: an audio format but no audio decoder. The total is "-", not the video time alone.
        val passthrough = full.copy(audioDecoder = null, audioInitMs = null, audioInits = 0, audioMime = "audio/ac3", audioChannels = 6, videoInitMs = 70, videoInits = 2)
        assertNull(passthrough.initTotalMs)
        assertEquals(
            "video=avc 576 25fps OMX.MTK.VIDEO.DECODER.AVC 70 ms (2 inits), audio=ac3 6ch - -, init total -, first frame 612 ms after prepare, " +
                "stop to prepare 210 ms, old video release <=22 ms",
            passthrough.summary(),
        )
        assertEquals(
            "video=- -, audio=- -, init total -, first frame - after prepare, stop to prepare -, old video release <=-",
            ZapDecoders().summary(),
        )
        assertNull(ZapDecoders(videoInitMs = 30).initTotalMs, "radio or no audio seen: excluded")
    }

    @Test
    fun theMeterSumsTheZapsInitsAndMeasuresFromTheOldChannelsStop() {
        val m = ZapDecoderMeter()
        m.begin(1)
        m.onStop(1_000)
        m.onVideoDecoderReleased(1_030)
        m.onPrepare(1_200)
        m.onVideoFormat("video/hevc", 1080, 50f)
        m.onAudioFormat("audio/mpeg-L2", 2)
        m.onVideoDecoderInitialized("hw.hevc", 40)
        m.onAudioDecoderInitialized("mp2", 5)
        // A ladder repair before first media: a second stop (ignored), its release (a decoder of this zap: ignored), a new
        // prepare and a second video init.
        m.onStop(1_500)
        m.onVideoDecoderReleased(1_520)
        m.onPrepare(1_600)
        m.onVideoDecoderInitialized("sw.hevc", 25)
        val d = assertNotNull(m.finish(1, firstMediaAtMs = 2_100))
        assertEquals("sw.hevc", d.videoDecoder)
        assertEquals(65L, d.videoInitMs)
        assertEquals(2, d.videoInits)
        assertEquals(5L, d.audioInitMs)
        assertEquals(1, d.audioInits)
        assertEquals(500L, d.prepareToFirstFrameMs, "from the last prepare")
        assertEquals(200L, d.stopToPrepareMs, "old stop to the first prepare")
        assertEquals(30L, d.videoReleaseMs)
        assertEquals("hevc 1080 50fps", d.videoKind())
        assertEquals("mpeg-L2 2ch", d.audioKind())
    }

    @Test
    fun aLateCallbackOfTheSupersededZapAndAReleaseAfterRadioAreNotCounted() {
        val m = ZapDecoderMeter()
        m.begin(1)
        m.onPrepare(100)
        assertNotNull(m.finish(1, firstMediaAtMs = null)) // superseded
        m.begin(2)
        m.onStop(200)
        // Posted by the playback thread for zap 1, delivered after zap 2 began: before zap 2's first prepare.
        m.onVideoDecoderInitialized("old.avc", 99)
        m.onVideoFormat("video/avc", 720, 25f)
        m.onPrepare(400)
        // Zap 1 was radio: no old video decoder. The first release after zap 2's prepare is one of zap 2's.
        m.onVideoDecoderReleased(450)
        m.onVideoDecoderInitialized("new.avc", 30)
        val d = assertNotNull(m.finish(2, firstMediaAtMs = 900))
        assertEquals(30L, d.videoInitMs)
        assertEquals(1, d.videoInits)
        assertNull(d.videoMime, "the late format is zap 1's")
        assertNull(d.videoReleaseMs)
    }

    @Test
    fun eventsOutsideAZapAreIgnoredAndAnotherCyclesFinishIsNull() {
        val m = ZapDecoderMeter()
        m.onVideoDecoderInitialized("before", 99)
        m.onPrepare(10)
        m.begin(5)
        m.onPrepare(100)
        m.onVideoDecoderInitialized("hw.avc", 30)
        assertNull(m.finish(4, firstMediaAtMs = 200), "another cycle")
        val superseded = assertNotNull(m.finish(5, firstMediaAtMs = null))
        assertNull(superseded.prepareToFirstFrameMs)
        assertNull(superseded.stopToPrepareMs, "no stop: the first tune")
        assertEquals(30L, superseded.videoInitMs)
        assertNull(m.finish(5, firstMediaAtMs = null), "already finished")
        // After finish (playback): a ladder repair's init does not leak into the next zap.
        m.onVideoDecoderInitialized("repair", 50)
        m.begin(6)
        m.onPrepare(300)
        val next = assertNotNull(m.finish(6, firstMediaAtMs = 500))
        assertNull(next.videoDecoder)
        assertNull(next.videoInitMs)
        assertEquals(200L, next.prepareToFirstFrameMs)
    }

    @Test
    fun theExportZapLineCarriesTheSummary() {
        val z = ZapRecord(7, "ch1", StreamFormat.TS, 120, 10, 300, 500, 700, "ok", full)
        val plain = z.copy(tuneId = 8, decoders = null)
        val out = ByteArrayOutputStream()
        ExportBundleBuilder().write(
            ExportInputs(emptyList(), null, emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(), listOf(z, plain), emptyList(), emptyList(), emptyList()),
            out,
        )
        val text = out.toString(Charsets.UTF_8)
        assertTrue(
            text.contains("tune=7 TS resolve=120 lease=10 firstByte=300 ready=500 firstFrame=700 ok | video=avc 576 25fps OMX.MTK.VIDEO.DECODER.AVC 38 ms, audio=mp4a-latm 2ch"),
            text,
        )
        assertTrue(text.lines().any { it == "tune=8 TS resolve=120 lease=10 firstByte=300 ready=500 firstFrame=700 ok" }, text)
    }
}
