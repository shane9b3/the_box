package app.thebox.core.playback.diag

import app.thebox.core.diag.ExportBundleBuilder
import app.thebox.core.diag.ExportInputs
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.ZapRecord
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Keep-decoders WP-0: the per-zap decoder figures, their aggregation and the line the owner reads. */
class ZapDecodersTest {

    @Test
    fun theLogLineCarriesEveryFigure() {
        val d = ZapDecoders(
            videoDecoder = "OMX.MTK.VIDEO.DECODER.AVC", videoInitMs = 38, videoInits = 1,
            audioDecoder = "c2.android.aac.decoder", audioInitMs = 4, audioInits = 1,
            prepareToFirstFrameMs = 612, stopToPrepareMs = 210, videoReleaseMs = 22,
        )
        assertEquals(
            "zap decoders: tune=12 video=OMX.MTK.VIDEO.DECODER.AVC 38 ms, audio=c2.android.aac.decoder 4 ms, " +
                "first frame 612 ms after prepare, stop to prepare 210 ms, old video release <=22 ms",
            d.logLine(12),
        )
        assertEquals(42L, d.initTotalMs)
    }

    @Test
    fun missingFiguresAreDashesAndRepeatedInitsAreCounted() {
        val d = ZapDecoders(audioDecoder = "ffmpeg", audioInitMs = 9, audioInits = 2)
        assertEquals(
            "video=- -, audio=ffmpeg 9 ms (2 inits), first frame - after prepare, stop to prepare -, old video release <=-",
            d.summary(),
        )
        assertEquals(9L, d.initTotalMs)
        assertNull(ZapDecoders().initTotalMs)
    }

    @Test
    fun theMeterSumsTheZapsInitsAndMeasuresFromTheOldChannelsStop() {
        val m = ZapDecoderMeter()
        m.begin()
        m.onStop(1_000)
        m.onVideoDecoderReleased(1_030)
        m.onPrepare(1_200)
        m.onVideoDecoderInitialized("hw.avc", 40)
        m.onAudioDecoderInitialized("aac", 5)
        // A ladder repair before first media: a second stop (ignored), a new prepare and a second video init.
        m.onStop(1_500)
        m.onVideoDecoderReleased(1_520)
        m.onPrepare(1_600)
        m.onVideoDecoderInitialized("sw.avc", 25)
        val d = m.finish(firstMediaAtMs = 2_100)
        assertEquals("sw.avc", d.videoDecoder)
        assertEquals(65L, d.videoInitMs)
        assertEquals(2, d.videoInits)
        assertEquals("aac", d.audioDecoder)
        assertEquals(5L, d.audioInitMs)
        assertEquals(1, d.audioInits)
        assertEquals(500L, d.prepareToFirstFrameMs, "from the last prepare")
        assertEquals(200L, d.stopToPrepareMs, "old stop to the first prepare")
        assertEquals(30L, d.videoReleaseMs)
    }

    @Test
    fun eventsOutsideAZapAreIgnoredAndAZapWithoutMediaHasNoFirstFrame() {
        val m = ZapDecoderMeter()
        m.onVideoDecoderInitialized("before", 99)
        m.onPrepare(10)
        m.begin()
        m.onPrepare(100)
        m.onVideoDecoderInitialized("hw.avc", 30)
        val superseded = m.finish(firstMediaAtMs = null)
        assertNull(superseded.prepareToFirstFrameMs)
        assertNull(superseded.stopToPrepareMs, "no stop: the first tune")
        assertNull(superseded.videoReleaseMs)
        assertEquals(30L, superseded.videoInitMs)
        // After finish (playback): a ladder repair's init does not leak into the next zap.
        m.onVideoDecoderInitialized("repair", 50)
        m.begin()
        val next = m.finish(firstMediaAtMs = 500)
        assertNull(next.videoDecoder)
        assertNull(next.videoInitMs)
        assertNull(next.prepareToFirstFrameMs)
    }

    @Test
    fun theExportZapLineCarriesTheSummary() {
        val z = ZapRecord(7, "ch1", StreamFormat.TS, 120, 10, 300, 500, 700, "ok", ZapDecoders("hw.avc", 38, 1, "aac", 4, 1, 400, 150, 20))
        val plain = z.copy(tuneId = 8, decoders = null)
        val out = ByteArrayOutputStream()
        ExportBundleBuilder().write(
            ExportInputs(emptyList(), null, emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(), listOf(z, plain), emptyList(), emptyList(), emptyList()),
            out,
        )
        val text = out.toString(Charsets.UTF_8)
        assertTrue(
            text.contains("tune=7 TS resolve=120 lease=10 firstByte=300 ready=500 firstFrame=700 ok | video=hw.avc 38 ms, audio=aac 4 ms, first frame 400 ms after prepare"),
            text,
        )
        assertTrue(text.lines().any { it == "tune=8 TS resolve=120 lease=10 firstByte=300 ready=500 firstFrame=700 ok" }, text)
    }
}
