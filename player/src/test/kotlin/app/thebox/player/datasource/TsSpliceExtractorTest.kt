package app.thebox.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.test.utils.FakeExtractorInput
import androidx.media3.test.utils.FakeExtractorOutput
import app.thebox.core.playback.ts.TsPacketAlignerImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * (reviewed 2026-10-13, B1) A spliced stream through the aligner and a real `TsExtractor` (`MODE_SINGLE_PMT`, the
 * design's TS flags): for every restart CC a server may pick, every access unit the extractor emits is byte-identical
 * to the access unit with the same timestamp in the original stream, i.e. no access unit spans the splice. A naive
 * splice whose CC happens to continue is the control and must fail that check.
 *
 * Access unit = the concatenated samples of one track with one timestamp. With `FLAG_DETECT_ACCESS_UNITS` on a stream
 * that carries AUDs (ffmpeg's muxer adds them), Media3's `H264Reader` emits an IDR access unit as several samples with
 * the same timestamp (AUD 6 B, SPS 29 B, PPS 9 B, slice), and after a `seek()` it groups the header NAL units
 * differently, so samples are not comparable one by one.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TsSpliceExtractorTest {
    private val videoPid = 0x100

    /** Video packets of the fixture as (packet index, payload_unit_start). */
    private val videoPackets: List<Pair<Int, Boolean>> by lazy {
        (0 until FIXTURE.size / PKT).mapNotNull { i ->
            val off = i * PKT
            val pid = ((FIXTURE[off + 1].toInt() and 0x1F) shl 8) or (FIXTURE[off + 2].toInt() and 0xFF)
            if (pid == videoPid) i to ((FIXTURE[off + 1].toInt() and 0x40) != 0) else null
        }
    }

    /** End of connection A: right after a video packet whose next video packet continues the same PES (mid-PES cut). */
    private val cutA: Int by lazy {
        val k = videoPackets.indices.first { it > videoPackets.size / 4 && !videoPackets[it].second && !videoPackets[it + 1].second }
        (videoPackets[k].first + 1) * PKT
    }

    /** Start of connection B: a video packet that continues a PES (the reconnected server resumes mid-PES). */
    private val resumeB: Int by lazy {
        val k = videoPackets.indices.first { it > videoPackets.size / 2 && !videoPackets[it].second && !videoPackets[it - 1].second }
        videoPackets[k].first * PKT
    }

    /** (track type, timeUs) → concatenated sample bytes. */
    private fun accessUnits(data: ByteArray): Map<Pair<Int, Long>, List<Byte>> {
        val extractor = TsExtractor(
            TsExtractor.MODE_SINGLE_PMT,
            TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
            SubtitleParser.Factory.UNSUPPORTED,
            TimestampAdjuster(0),
            DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS),
            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
        )
        val output = FakeExtractorOutput()
        extractor.init(output)
        val input = FakeExtractorInput.Builder().setData(data).setSimulateUnknownLength(true).build()
        val position = PositionHolder()
        while (true) {
            val r = extractor.read(input, position)
            if (r == Extractor.RESULT_END_OF_INPUT) break
            check(r == Extractor.RESULT_CONTINUE) { "unexpected result $r" }
        }
        val units = LinkedHashMap<Pair<Int, Long>, MutableList<Byte>>()
        for (i in 0 until output.trackOutputs.size()) {
            val track = output.trackOutputs.valueAt(i)
            if (track.type != C.TRACK_TYPE_VIDEO && track.type != C.TRACK_TYPE_AUDIO) continue
            for (s in 0 until track.sampleCount) {
                units.getOrPut(track.type to track.getSampleTimeUs(s)) { ArrayList() } += track.getSampleData(s).toList()
            }
        }
        return units
    }

    private fun spliceThroughAligner(a: ByteArray, b: ByteArray): ByteArray {
        val aligner = TsPacketAlignerImpl()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16_384)
        fun drain() {
            while (true) {
                val n = aligner.read(buf, 0, buf.size)
                if (n == 0) break
                out.write(buf, 0, n)
            }
        }
        aligner.write(a, 0, a.size)
        drain()
        aligner.resync()
        aligner.write(b, 0, b.size)
        aligner.releaseHeld()
        drain()
        return out.toByteArray()
    }

    /** Access units of [spliced] that are absent from, or differ from, [original]. */
    private fun foreign(spliced: Map<Pair<Int, Long>, List<Byte>>, original: Map<Pair<Int, Long>, List<Byte>>) =
        spliced.filter { (key, bytes) -> original[key] != bytes }.keys

    @Test
    fun noAccessUnitSpansTheSpliceForAnyRestartCc() {
        val original = accessUnits(FIXTURE)
        val videoUnits = original.keys.count { it.first == C.TRACK_TYPE_VIDEO }
        assertTrue("fixture has video and audio: ${original.size}", videoUnits > 40 && original.keys.any { it.first == C.TRACK_TYPE_AUDIO })
        for (restart in 0 until 16) {
            val b = restartCcs(FIXTURE.copyOfRange(resumeB, FIXTURE.size), restart)
            val spliced = accessUnits(spliceThroughAligner(FIXTURE.copyOf(cutA), b))
            assertEquals("restart CC $restart: access units spanning the splice", emptySet<Pair<Int, Long>>(), foreign(spliced, original))
            val after = spliced.keys.count { it.first == C.TRACK_TYPE_VIDEO }
            assertTrue("restart CC $restart: video continues after the splice ($after of $videoUnits)", after > videoUnits / 2)
        }
    }

    /** Control: without the forced gap, a server whose CC happens to continue (1 in 16) glues two PES together. */
    @Test
    fun aNaiveSpliceWithAContinuingCcCorruptsAnAccessUnitAndTheAlignerDoesNot() {
        val original = accessUnits(FIXTURE)
        val restart = (lastPayloadCc(FIXTURE, cutA, videoPid) + 1) and 0xF
        val b = restartCcs(FIXTURE.copyOfRange(resumeB, FIXTURE.size), restart)
        val naive = foreign(accessUnits(FIXTURE.copyOf(cutA) + b), original)
        assertTrue("the naive splice must corrupt an access unit", naive.isNotEmpty())
        assertEquals(emptySet<Pair<Int, Long>>(), foreign(accessUnits(spliceThroughAligner(FIXTURE.copyOf(cutA), b)), original))
    }

    private fun lastPayloadCc(ts: ByteArray, end: Int, pid: Int): Int {
        var cc = -1
        var off = 0
        while (off + PKT <= end) {
            val p = ((ts[off + 1].toInt() and 0x1F) shl 8) or (ts[off + 2].toInt() and 0xFF)
            if (p == pid && (ts[off + 3].toInt() and 0x10) != 0) cc = ts[off + 3].toInt() and 0xF
            off += PKT
        }
        return cc
    }

    /** Every PID restarts at [restart] (payload packets advance the CC), as a reconnecting server does. */
    private fun restartCcs(ts: ByteArray, restart: Int): ByteArray {
        val out = ts.copyOf()
        val next = HashMap<Int, Int>()
        var off = 0
        while (off + PKT <= out.size) {
            val pid = ((out[off + 1].toInt() and 0x1F) shl 8) or (out[off + 2].toInt() and 0xFF)
            val payload = (out[off + 3].toInt() and 0x10) != 0
            val c = next.getOrPut(pid) { restart and 0xF }
            val use = if (payload) c else (c - 1) and 0xF
            out[off + 3] = ((out[off + 3].toInt() and 0xF0) or use).toByte()
            if (payload) next[pid] = (c + 1) and 0xF
            off += PKT
        }
        return out
    }
}
