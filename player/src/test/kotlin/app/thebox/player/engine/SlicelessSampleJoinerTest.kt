package app.thebox.player.engine

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.test.utils.FakeExtractorOutput
import androidx.media3.test.utils.FakeTrackOutput
import app.thebox.player.internal.TsCompat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * m6 H.264 access units (lead ruling 2026-11-26): the slice-less sample joiner, through the real Media3 1.11.1
 * `TsExtractor`/`H264Reader` on the fixtures of review S2 (`core/src/test/resources/ts/README.md`), plus unit tests of
 * the wrapper on byte-level NAL streams.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SlicelessSampleJoinerTest {

    // ---- Fixtures through the real reader ----

    /** Media3 with our flags and no joiner ("before"), and the production guarded chain ("after"). */
    private class Pair2(val raw: FakeTrackOutput, val joined: FakeTrackOutput, val es: ByteArray, val counter: SlicelessJoinCounter)

    private fun run(name: String): Pair2 {
        val bytes = EngineTestSupport.fixture(name)
        val raw = DefaultExtractorsFactory().setTsExtractorFlags(TsExtractorsFactory.TS_FLAGS)
            .setTsSubtitleFormats(TsExtractorsFactory.SPECULATIVE_CC_FORMATS)
            .createExtractors().filterIsInstance<TsExtractor>().single()
        val rawOut = extract(raw, bytes)
        val counter = SlicelessJoinCounter()
        val guarded = TsExtractorsFactory(TsCompat.DEFAULT, joinCounter = counter).createExtractors().single()
        val joinedOut = extract(guarded, bytes)
        val rawVideo = rawOut.getTrackOutputsForType(C.TRACK_TYPE_VIDEO).single()
        val joinedVideo = joinedOut.getTrackOutputsForType(C.TRACK_TYPE_VIDEO).single()
        val pid = rawOut.trackOutputs.keyAt((0 until rawOut.trackOutputs.size()).first { rawOut.trackOutputs.valueAt(it) === rawVideo })
        return Pair2(rawVideo, joinedVideo, H264Bytes.esOf(bytes, pid), counter)
    }

    private fun extract(extractor: Extractor, bytes: ByteArray): FakeExtractorOutput {
        val output = FakeExtractorOutput()
        extractor.init(output)
        val source = ByteArrayDataSource(bytes)
        source.open(DataSpec(Uri.parse("thebox-session://1")))
        val input = DefaultExtractorInput(source, 0, C.LENGTH_UNSET.toLong())
        val holder = PositionHolder()
        while (true) {
            val r = extractor.read(input, holder)
            if (r == Extractor.RESULT_END_OF_INPUT) break
            check(r == Extractor.RESULT_CONTINUE) { "unexpected result $r" }
        }
        return output
    }

    /**
     * The gate for one fixture: samples out == pictures in; no slice-less sample; one picture per sample; the bytes are
     * the reader's bytes in order (only a trailing slice-less run may be missing) and a contiguous run of the ES;
     * times and flags are those of the reader's slice samples.
     */
    private fun assertJoined(name: String, pictures: Int, rawSamples: Int? = null): Pair2 {
        val r = run(name)
        val rawSlice = (0 until r.raw.sampleCount).filter { H264Bytes.sliceCount(r.raw.getSampleData(it)) > 0 }
        println("$name: pictures=$pictures before=${r.raw.sampleCount} after=${r.joined.sampleCount} joined=${r.counter.joined}")
        if (rawSamples != null) assertEquals("$name: Media3 samples without the joiner", rawSamples, r.raw.sampleCount)
        assertEquals("$name: video samples out == pictures in", pictures, r.joined.sampleCount)
        assertEquals("$name: ES pictures", pictures, H264Bytes.pictureCount(r.es))
        for (i in 0 until r.joined.sampleCount) {
            val d = r.joined.getSampleData(i)
            assertTrue("$name: sample $i holds a slice", H264Bytes.sliceCount(d) > 0)
            assertEquals("$name: sample $i holds one picture", 1, H264Bytes.pictureCount(d))
        }
        val rawConcat = concat(r.raw)
        val joinedConcat = concat(r.joined)
        val lastSliceEnd = (0..rawSlice.last()).sumOf { r.raw.getSampleData(it).size }
        assertArrayEquals("$name: bytes unchanged up to the last picture", rawConcat.copyOf(lastSliceEnd), joinedConcat)
        for (i in rawSlice.last() + 1 until r.raw.sampleCount) assertEquals("$name: only slice-less samples trail", 0, H264Bytes.sliceCount(r.raw.getSampleData(i)))
        assertTrue("$name: a contiguous run of the elementary stream", indexOf(r.es, joinedConcat) >= 0)
        assertEquals("$name: times", rawSlice.map { r.raw.getSampleTimeUs(it) }, (0 until r.joined.sampleCount).map { r.joined.getSampleTimeUs(it) })
        assertEquals("$name: flags", rawSlice.map { r.raw.getSampleFlags(it) }, (0 until r.joined.sampleCount).map { r.joined.getSampleFlags(it) })
        assertTrue("$name: first sample is the keyframe", r.joined.getSampleFlags(0) and C.BUFFER_FLAG_KEY_FRAME != 0)
        assertEquals("$name: format", r.raw.lastFormat, r.joined.lastFormat)
        val trailing = r.raw.sampleCount - rawSlice.last() - 1
        assertEquals("$name: joined count", (r.raw.sampleCount - rawSlice.size - trailing).toLong(), r.counter.joined)
        return r
    }

    @Test fun audSeiSingleSlice() { assertJoined("h264_aud_sei_s1.ts", pictures = 30, rawSamples = 89) }

    @Test fun aud4Slices() { assertJoined("h264_aud_s4.ts", pictures = 30, rawSamples = 60) }

    @Test fun audLessSingleSlice() { assertJoined("h264_noaud_s1.ts", pictures = 30, rawSamples = 30) }

    @Test fun audLessSeiSingleSlice() { assertJoined("h264_noaud_sei_s1.ts", pictures = 30, rawSamples = 59) }

    @Test fun fillerAfterTheSlice() {
        val r = assertJoined("h264_aud_sei_filler_s1.ts", pictures = 30, rawSamples = 92)
        val filler = (0 until r.joined.sampleCount).count { H264Bytes.types(r.joined.getSampleData(it)).first() == 12 }
        assertTrue("trailing filler moved to the front of the next picture ($filler)", filler > 0)
    }

    @Test fun awkwardPesLayout() { assertJoined("h264_aud_sei_awkward.ts", pictures = 30, rawSamples = 89) }

    /**
     * Media3's own assets (review S2 oracles). `sample_h264.ts`: its flag-off dump has 30 samples; flag on gives 64, joined 30.
     * `sample_h264_no_access_unit_delimiters.ts` does carry AUDs (types `[9,7,8,6] [5] [9] [1] [9] [1]`): Media3's flag-on
     * dump (6 samples) is the split itself, 3 pictures.
     */
    @Test fun media3SampleH264MatchesItsFlagOffDump() { assertJoined("media3_sample_h264.ts", pictures = 30, rawSamples = 64) }

    @Test fun media3SampleNamedWithoutAuds() { assertJoined("media3_sample_h264_no_aud.ts", pictures = 3, rawSamples = 6) }

    @Test fun repoClipH264Aac() { assertJoined("h264_aac_2s.ts", pictures = 50, rawSamples = 102) }

    /** HLS: the same through `DefaultHlsExtractorFactory` (TsExtractor in MODE_HLS, synthesized end-of-segment PUSI). */
    @Test
    fun hlsChunkExtractorJoinsTsSegments() {
        for (name in listOf("h264_aud_sei_s1.ts", "h264_aud_sei_filler_s1.ts", "h264_noaud_sei_s1.ts")) {
            val counter = SlicelessJoinCounter()
            val raw = hlsVideo(DefaultHlsExtractorFactory(MediaSourceBuilder.HLS_TS_FLAGS, false), name)
            val joined = hlsVideo(SlicelessJoiningHlsExtractorFactory(DefaultHlsExtractorFactory(MediaSourceBuilder.HLS_TS_FLAGS, false), counter), name)
            val rawSlice = (0 until raw.sampleCount).filter { H264Bytes.sliceCount(raw.getSampleData(it)) > 0 }
            println("hls $name: before=${raw.sampleCount} after=${joined.sampleCount} joined=${counter.joined}")
            assertEquals("$name: one sample per picture the reader delivered", rawSlice.size, joined.sampleCount)
            assertTrue("$name: at most the segment's last picture is missing in HLS mode", joined.sampleCount >= 29)
            for (i in 0 until joined.sampleCount) assertEquals("$name sample $i", 1, H264Bytes.pictureCount(joined.getSampleData(i)))
            assertEquals(rawSlice.map { raw.getSampleTimeUs(it) }, (0 until joined.sampleCount).map { joined.getSampleTimeUs(it) })
            assertEquals(rawSlice.map { raw.getSampleFlags(it) }, (0 until joined.sampleCount).map { joined.getSampleFlags(it) })
        }
    }

    private fun hlsVideo(factory: androidx.media3.exoplayer.hls.HlsExtractorFactory, name: String): FakeTrackOutput {
        val bytes = EngineTestSupport.fixture(name)
        val source = ByteArrayDataSource(bytes)
        source.open(DataSpec(Uri.parse("thebox-session://1")))
        val input = DefaultExtractorInput(source, 0, bytes.size.toLong())
        val chunk = factory.createExtractor(
            Uri.parse("thebox-session://1/seg.ts"), Format.Builder().build(), null, TimestampAdjuster(TimestampAdjuster.MODE_NO_OFFSET),
            emptyMap(), input, PlayerId.UNSET,
        )
        assertTrue("TS chunk extractors are reusable", chunk.isReusable)
        assertFalse(chunk.isPackedAudioExtractor)
        val output = FakeExtractorOutput()
        chunk.init(output)
        while (chunk.read(input)) Unit
        return output.getTrackOutputsForType(C.TRACK_TYPE_VIDEO).single()
    }

    // ---- The wrapper on byte-level NAL streams ----

    private val avcTs = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setContainerMimeType(MimeTypes.VIDEO_MP2T).build()

    private fun joiner(format: Format = avcTs): kotlin.Pair<SlicelessSampleJoiner, FakeTrackOutput> {
        val out = FakeTrackOutput(C.TRACK_TYPE_VIDEO, false)
        return SlicelessSampleJoiner(out).also { it.format(format) } to out
    }

    private fun write(t: TrackOutput, bytes: ByteArray) = t.sampleData(ParsableByteArray(bytes), bytes.size, TrackOutput.SAMPLE_DATA_PART_MAIN)

    private val aud = H264Bytes.nal(9, 1)
    private val sei = H264Bytes.nal(6, 9)
    private val idr = H264Bytes.nal(5, 40)
    private val p = H264Bytes.nal(1, 30)
    private val filler = H264Bytes.nal(12, 12)

    @Test
    fun startCodeStraddlingCallsIsFound() {
        val (j, out) = joiner()
        val prefix = aud + sei
        write(j, prefix)
        j.sampleMetadata(1_000, 0, prefix.size, 0, null) // slice-less: held
        // The slice's 00 00 00 01 65 split over three calls: "00 00" | "00 01" | "65 …".
        write(j, idr.copyOfRange(0, 2)); write(j, idr.copyOfRange(2, 4)); write(j, idr.copyOfRange(4, idr.size))
        j.sampleMetadata(2_000, C.BUFFER_FLAG_KEY_FRAME, idr.size, 0, null)
        assertEquals(1, out.sampleCount)
        assertArrayEquals(prefix + idr, out.getSampleData(0))
        assertEquals(2_000, out.getSampleTimeUs(0))
        assertEquals(C.BUFFER_FLAG_KEY_FRAME, out.getSampleFlags(0))
        assertEquals(1, j.joinedSamples)
    }

    /** H264Reader writes the PES chunk first and outputs the format at the end of the PPS: slices before it count. */
    @Test
    fun slicesWrittenBeforeTheFormatAreKept() {
        val out = FakeTrackOutput(C.TRACK_TYPE_VIDEO, false)
        val j = SlicelessSampleJoiner(out)
        val sps = H264Bytes.nal(7, 8)
        val pps = H264Bytes.nal(8, 4)
        write(j, aud + sps + pps + idr + aud + p) // one chunk, before the format
        j.format(avcTs)
        j.sampleMetadata(1_000, 0, aud.size + sps.size + pps.size, idr.size + aud.size + p.size, null)
        j.sampleMetadata(1_000, C.BUFFER_FLAG_KEY_FRAME, idr.size, aud.size + p.size, null)
        j.sampleMetadata(2_000, 0, aud.size, p.size, null)
        j.sampleMetadata(2_000, 0, p.size, 0, null)
        assertEquals("two pictures, never fused", 2, out.sampleCount)
        assertArrayEquals(aud + sps + pps + idr, out.getSampleData(0))
        assertArrayEquals(aud + p, out.getSampleData(1))
    }

    @Test
    fun headerByteAloneInTheNextCall() {
        val (j, out) = joiner()
        write(j, aud)
        j.sampleMetadata(1_000, 0, aud.size, 0, null)
        write(j, p.copyOfRange(0, 4)); write(j, p.copyOfRange(4, p.size)) // "00 00 00 01" | "41 …"
        j.sampleMetadata(2_000, 0, p.size, 0, null)
        assertEquals(1, out.sampleCount)
        assertArrayEquals(aud + p, out.getSampleData(0))
    }

    @Test
    fun offsetCountsBytesAfterTheSample() {
        val (j, out) = joiner()
        // AUD+SEI, then the IDR, then the next AUD and a P slice already written past both samples.
        val tail = aud + p
        write(j, aud + sei + idr + tail)
        j.sampleMetadata(1_000, 0, aud.size + sei.size, idr.size + tail.size, null) // slice-less (the P slice is after it)
        assertEquals("held, not fooled by the slice in the offset bytes", 0, out.sampleCount)
        j.sampleMetadata(2_000, C.BUFFER_FLAG_KEY_FRAME, idr.size, tail.size, null)
        assertEquals(1, out.sampleCount)
        assertArrayEquals(aud + sei + idr, out.getSampleData(0))
        // A slice-less sample whose following bytes hold a slice is still held.
        j.sampleMetadata(3_000, 0, aud.size, p.size, null)
        assertEquals(1, out.sampleCount)
        j.sampleMetadata(4_000, 0, p.size, 0, null)
        assertEquals(2, out.sampleCount)
        assertArrayEquals(aud + p, out.getSampleData(1))
        assertEquals(4_000, out.getSampleTimeUs(1))
    }

    @Test
    fun heldSampleIsDroppedOnSeek() {
        val (j, out) = joiner()
        write(j, aud + sei)
        j.sampleMetadata(1_000, 0, aud.size + sei.size, 0, null)
        j.reset()
        write(j, idr)
        j.sampleMetadata(5_000, C.BUFFER_FLAG_KEY_FRAME, idr.size, 0, null)
        assertEquals(1, out.sampleCount)
        assertArrayEquals(idr, out.getSampleData(0))
        assertEquals(1, j.droppedSamples)
        assertEquals(0, j.joinedSamples)
    }

    @Test
    fun scannerRestartsAfterSeek() {
        val (j, out) = joiner()
        write(j, byteArrayOf(0, 0)) // half a start code, then a seek
        j.reset()
        val bogus = byteArrayOf(1, 0x65, 0x11) // would read as a slice if the scanner kept its state
        write(j, bogus + aud)
        j.sampleMetadata(1_000, 0, aud.size, 0, null)
        assertEquals("slice-less after the seek: held", 0, out.sampleCount)
    }

    @Test
    fun nonH264TracksPassThrough() {
        for (format in listOf(
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setContainerMimeType(MimeTypes.VIDEO_MP2T).build(),
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setContainerMimeType(MimeTypes.VIDEO_MP4).build(),
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_MPEG2).setContainerMimeType(MimeTypes.VIDEO_MP2T).build(),
        )) {
            val (j, out) = joiner(format)
            write(j, aud); j.sampleMetadata(1_000, 0, aud.size, 0, null)
            write(j, sei); j.sampleMetadata(1_000, 0, sei.size, 0, null)
            write(j, p); j.sampleMetadata(1_000, 0, p.size, 0, null)
            assertEquals("${format.sampleMimeType} in ${format.containerMimeType}", 3, out.sampleCount)
        }
        // Non-video tracks never get a joiner at all.
        val output = FakeExtractorOutput()
        val joining = SlicelessJoiningOutput(output)
        assertTrue(joining.track(0x101, C.TRACK_TYPE_AUDIO) === output.track(0x101, C.TRACK_TYPE_AUDIO))
        assertTrue(joining.track(0x100, C.TRACK_TYPE_VIDEO) is SlicelessSampleJoiner)
    }

    @Test
    fun twoSlicelessSamplesInARowJoinTheNextSlice() {
        val (j, out) = joiner()
        write(j, aud); j.sampleMetadata(1_000, C.BUFFER_FLAG_KEY_FRAME, aud.size, 0, null)
        write(j, sei); j.sampleMetadata(1_000, 0, sei.size, 0, null)
        write(j, p); j.sampleMetadata(1_500, 0, p.size, 0, null)
        assertEquals(1, out.sampleCount)
        assertArrayEquals(aud + sei + p, out.getSampleData(0))
        assertEquals(1_500, out.getSampleTimeUs(0))
        assertEquals("flags OR-ed", C.BUFFER_FLAG_KEY_FRAME, out.getSampleFlags(0))
        assertEquals(2, j.joinedSamples)
    }

    @Test
    fun streamEndingOnAHeldSample() {
        val (j, out) = joiner()
        write(j, aud + idr); j.sampleMetadata(1_000, C.BUFFER_FLAG_KEY_FRAME, aud.size + idr.size, 0, null)
        write(j, filler); j.sampleMetadata(1_000, 0, filler.size, 0, null)
        j.endOfInput()
        assertEquals(1, out.sampleCount)
        assertArrayEquals(aud + idr, out.getSampleData(0))
        assertEquals(1, j.droppedSamples)
    }

    @Test
    fun gapBetweenHeldAndNextSampleDropsTheHeldOne() {
        val (j, out) = joiner()
        write(j, aud); j.sampleMetadata(1_000, 0, aud.size, 0, null)
        write(j, sei) // orphan bytes no sample claims
        write(j, p); j.sampleMetadata(2_000, 0, p.size, 0, null)
        assertEquals(1, out.sampleCount)
        assertArrayEquals(p, out.getSampleData(0))
        assertEquals(1, j.droppedSamples)
    }

    @Test
    fun dataReaderPathIsScannedToo() {
        val (j, out) = joiner()
        val bytes = aud + sei + idr
        val src = ByteArrayDataSource(bytes).also { it.open(DataSpec(Uri.parse("thebox-session://1"))) }
        var left = bytes.size
        while (left > 0) left -= j.sampleData(src, minOf(left, 5), false, TrackOutput.SAMPLE_DATA_PART_MAIN)
        j.sampleMetadata(1_000, 0, aud.size + sei.size, idr.size, null)
        j.sampleMetadata(2_000, C.BUFFER_FLAG_KEY_FRAME, idr.size, 0, null)
        assertEquals(1, out.sampleCount)
        assertArrayEquals(bytes, out.getSampleData(0))
    }

    @Test
    fun counterReportsMilestones() {
        val seen = mutableListOf<Long>()
        val c = SlicelessJoinCounter { seen += it }
        c.add(1); c.add(2); repeat(48) { c.add(2) } // 99
        c.add(2) // 101
        repeat(450) { c.add(2) } // 1001
        assertEquals(listOf(1L, 101L, 1001L), seen)
        assertEquals(1001L, c.joined)
    }
}

/** Annex-B helpers for the tests. */
internal object H264Bytes {
    /** One NAL unit with a 4-byte start code; slices start with first_mb_in_slice = 0 (ue "1"). */
    fun nal(type: Int, payload: Int): ByteArray {
        val ref = if (type == 1 || type == 5 || type == 7 || type == 8) 0x60 else 0
        return byteArrayOf(0, 0, 0, 1, (ref or type).toByte()) + ByteArray(payload) { if (it == 0) 0x88.toByte() else 0x11 }
    }

    /** NAL types in [d], in order. */
    fun types(d: ByteArray): List<Int> = headers(d).map { d[it].toInt() and 0x1F }

    fun sliceCount(d: ByteArray): Int = types(d).count { it == 1 || it == 2 || it == 5 }

    /** Slices with first_mb_in_slice == 0: one per picture (frame coding). */
    fun pictureCount(d: ByteArray): Int = headers(d).count {
        val t = d[it].toInt() and 0x1F
        (t == 1 || t == 5) && it + 1 < d.size && (d[it + 1].toInt() and 0x80) != 0
    }

    private fun headers(d: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i + 3 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) { out += i + 3; i += 3 } else i++
        }
        return out
    }

    /** The PES payload bytes of [pid] in a TS, concatenated (the elementary stream as the reader receives it). */
    fun esOf(ts: ByteArray, pid: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var start = 0
        while (start < ts.size && ts[start].toInt() != 0x47) start++
        var i = start
        while (i + 188 <= ts.size) {
            val b1 = ts[i + 1].toInt() and 0xFF
            val packetPid = ((b1 and 0x1F) shl 8) or (ts[i + 2].toInt() and 0xFF)
            val afc = (ts[i + 3].toInt() shr 4) and 3
            if (packetPid == pid && afc and 1 != 0) {
                var off = 4
                if (afc and 2 != 0) off += 1 + (ts[i + 4].toInt() and 0xFF)
                if (b1 and 0x40 != 0) off += 9 + (ts[i + off + 8].toInt() and 0xFF)
                if (off < 188) out.write(ts, i + off, 188 - off)
            }
            i += 188
        }
        return out.toByteArray()
    }
}

private fun concat(t: FakeTrackOutput): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for (i in 0 until t.sampleCount) out.write(t.getSampleData(i))
    return out.toByteArray()
}

private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty()) return 0
    outer@ for (i in 0..hay.size - needle.size) {
        for (k in needle.indices) if (hay[i + k] != needle[k]) continue@outer
        return i
    }
    return -1
}
