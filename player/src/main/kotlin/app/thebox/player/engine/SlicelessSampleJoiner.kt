package app.thebox.player.engine

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import java.io.EOFException
import java.util.concurrent.atomic.AtomicLong

/**
 * (m6 H.264 access units, lead ruling 2026-11-26; docs/design/m6-h264-access-units.md) With
 * `FLAG_DETECT_ACCESS_UNITS`, Media3 1.11.1's `H264Reader` cuts the non-VCL NAL units in front of a picture (AUD, SEI,
 * SPS/PPS) and trailing filler into samples of their own. A decoder that counts every input as a frame (MTK on the Sony,
 * tunneling off) then runs at a fraction of the frame rate. This wrapper sits between the reader and the next
 * [TrackOutput]: on an H.264-in-TS video track a sample holding no slice NAL unit (types 1, 2, 5) is held and joined to
 * the front of the next sample. The joined sample takes the later sample's time, the OR of both flags and the sum of
 * both sizes (the bytes are contiguous). It never splits a sample and never joins two slice samples, so it cannot fuse
 * pictures. Every other track and every other format passes through untouched.
 *
 * Slice positions come from a start-code scan of the bytes passing through [sampleData] (start codes can straddle
 * calls), recorded against the running byte count in a fixed ring. The track's mode is decided by the first [format]:
 * `video/avc` with container `video/mp2t` (HLS fMP4 carries length-prefixed NAL units and must not be scanned). Before
 * the format arrives, bytes are scanned (H264Reader writes the first PES before its format) but samples pass through.
 *
 * Loader thread only. Allocation-free after construction, except the [DataReader] path's fixed scratch buffer.
 */
@OptIn(UnstableApi::class)
internal class SlicelessSampleJoiner(
    private val downstream: TrackOutput,
    private val counter: SlicelessJoinCounter? = null,
) : TrackOutput {
    private var mode = MODE_UNKNOWN

    /** Bytes written to [downstream] through this wrapper (never reset). */
    private var written = 0L

    // Start-code scanner carried across calls.
    private var zeros = 0
    private var expectHeader = false

    // Slice NAL header positions, oldest first; a fixed ring.
    private val slices = LongArray(SLICE_RING)
    private var sliceHead = 0
    private var sliceCount = 0
    /** Highest slice position dropped because the ring was full; a range starting at or below it is "has a slice". */
    private var lostUpTo = -1L

    // The held slice-less run: bytes [heldStart, heldEnd), OR-ed flags, number of samples in it.
    private var held = false
    private var heldStart = 0L
    private var heldEnd = 0L
    private var heldFlags = 0
    private var heldRun = 0

    private var scratch: ByteArray? = null
    private val scratchView = ParsableByteArray()

    /** Slice-less samples absorbed into a following sample on this track (tests, diagnostics). */
    var joinedSamples = 0L
        private set

    /** Held slice-less samples dropped (seek, end of input, a gap in the byte stream). */
    var droppedSamples = 0L
        private set

    override fun format(format: Format) {
        val active = format.sampleMimeType == MimeTypes.VIDEO_H264 && format.containerMimeType == MimeTypes.VIDEO_MP2T
        val newMode = if (active) MODE_ACTIVE else MODE_OFF
        // UNKNOWN → ACTIVE keeps the slice positions: H264Reader writes the PES chunk holding SPS, PPS and often the
        // first slice before it outputs the format.
        if (newMode != mode) {
            if (newMode == MODE_OFF) {
                dropHeld()
                clearSlices()
            }
            mode = newMode
        }
        downstream.format(format)
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (mode == MODE_OFF || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            val n = downstream.sampleData(input, length, allowEndOfInput, sampleDataPart)
            if (n > 0) written += n
            return n
        }
        val buf = scratch ?: ByteArray(SCRATCH_BYTES).also { scratch = it }
        val n = input.read(buf, 0, minOf(length, buf.size))
        if (n == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        scan(buf, 0, n)
        scratchView.reset(buf, n)
        downstream.sampleData(scratchView, n, sampleDataPart)
        written += n
        return n
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (mode != MODE_OFF && sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) scan(data.data, data.position, length)
        downstream.sampleData(data, length, sampleDataPart)
        written += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (mode != MODE_ACTIVE || cryptoData != null) {
            dropHeld()
            downstream.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        val end = written - offset
        val start = end - size
        val hasSlice = containsSlice(start, end)
        pruneBelow(end)
        if (held && heldEnd != start) dropHeld() // not contiguous: never join across a gap
        if (held) {
            if (!hasSlice && heldRun < MAX_HELD_RUN) {
                heldEnd = end
                heldFlags = heldFlags or flags
                heldRun++
                return
            }
            val run = heldRun
            val joinedFlags = heldFlags or flags
            val joinedSize = (end - heldStart).toInt()
            held = false
            downstream.sampleMetadata(timeUs, joinedFlags, joinedSize, offset, null)
            joinedSamples += run
            counter?.add(run)
            return
        }
        if (hasSlice) {
            downstream.sampleMetadata(timeUs, flags, size, offset, null)
            return
        }
        held = true
        heldStart = start
        heldEnd = end
        heldFlags = flags
        heldRun = 1
    }

    /** A seek or flush of the extractor: the held sample is dropped and the scanner restarts. */
    fun reset() {
        dropHeld()
        clearSlices()
        zeros = 0
        expectHeader = false
    }

    /** End of the input (or of an HLS segment): a held sample has no following sample to join; drop it. */
    fun endOfInput() = dropHeld()

    private fun dropHeld() {
        if (!held) return
        held = false
        droppedSamples += heldRun
    }

    private fun clearSlices() {
        sliceHead = 0
        sliceCount = 0
        lostUpTo = -1L
    }

    /** True when a slice NAL header lies in [start, end), or when the ring lost positions that may lie there. */
    private fun containsSlice(start: Long, end: Long): Boolean {
        if (lostUpTo >= start) return true
        var i = 0
        while (i < sliceCount) {
            val p = slices[(sliceHead + i) and SLICE_MASK]
            if (p >= end) return false
            if (p >= start) return true
            i++
        }
        return false
    }

    private fun pruneBelow(end: Long) {
        while (sliceCount > 0 && slices[sliceHead] < end) {
            sliceHead = (sliceHead + 1) and SLICE_MASK
            sliceCount--
        }
    }

    private fun addSlice(position: Long) {
        if (sliceCount == SLICE_RING) {
            lostUpTo = slices[sliceHead]
            sliceHead = (sliceHead + 1) and SLICE_MASK
            sliceCount--
        }
        slices[(sliceHead + sliceCount) and SLICE_MASK] = position
        sliceCount++
    }

    /** Annex-B start-code scan of [length] bytes at [from]; positions are absolute (bytes written before + index). */
    private fun scan(a: ByteArray, from: Int, length: Int) {
        var z = zeros
        var hdr = expectHeader
        val base = written - from
        var i = from
        val limit = from + length
        while (i < limit) {
            val b = a[i].toInt() and 0xFF
            if (hdr) {
                hdr = false
                if (b and 0x80 == 0) {
                    val type = b and 0x1F
                    if (type == 1 || type == 2 || type == 5) addSlice(base + i)
                }
                z = if (b == 0) 1 else 0
            } else if (b == 0) {
                if (z < 2) z++
            } else {
                if (b == 1 && z >= 2) hdr = true
                z = 0
            }
            i++
        }
        zeros = z
        expectHeader = hdr
    }

    private companion object {
        const val MODE_UNKNOWN = 0
        const val MODE_ACTIVE = 1
        const val MODE_OFF = 2
        /** Slice positions kept (8 KiB of longs; far more than one PES plus one picture holds). */
        const val SLICE_RING = 1024
        const val SLICE_MASK = SLICE_RING - 1
        /**
         * Safety valve: after this many slice-less samples in a row the run is committed as one sample (today's
         * behaviour, fewer samples) instead of being held forever. Real prefixes are AUD, a few SEI, SPS, PPS.
         */
        const val MAX_HELD_RUN = 32
        const val SCRATCH_BYTES = 4096
    }
}

/**
 * Per-source count of joined slice-less samples, shared by every extractor of one source (loader threads). [onMilestone]
 * is called at 1, 100, 1 000, 10 000, … joined samples, so the event log shows it without a line per sample.
 */
internal class SlicelessJoinCounter(private val onMilestone: ((joined: Long) -> Unit)? = null) {
    private val total = AtomicLong()
    val joined: Long get() = total.get()

    fun add(n: Int) {
        val before = total.getAndAdd(n.toLong())
        val after = before + n
        if (onMilestone == null) return
        var m = 1L
        while (m <= after) {
            if (before < m) {
                onMilestone.invoke(after)
                return
            }
            m = if (m == 1L) 100L else m * 10
        }
    }
}

/**
 * `ExtractorOutput` that puts a [SlicelessSampleJoiner] in front of every video track of [output]. Other tracks are
 * [output]'s own. [reset] on an extractor seek, [endOfInput] at the end of the input or of an HLS segment.
 */
@OptIn(UnstableApi::class)
internal class SlicelessJoiningOutput(
    private val output: ExtractorOutput,
    private val counter: SlicelessJoinCounter? = null,
) : ExtractorOutput {
    private val joiners = HashMap<Int, SlicelessSampleJoiner>()

    val videoJoiners: Collection<SlicelessSampleJoiner> get() = joiners.values

    override fun track(id: Int, type: Int): TrackOutput {
        if (type != C.TRACK_TYPE_VIDEO) return output.track(id, type)
        return joiners.getOrPut(id) { SlicelessSampleJoiner(output.track(id, type), counter) }
    }

    override fun endTracks() = output.endTracks()

    override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)

    fun reset() {
        for (j in joiners.values) j.reset()
    }

    fun endOfInput() {
        for (j in joiners.values) j.endOfInput()
    }
}

/**
 * HLS: [delegate] (a `DefaultHlsExtractorFactory`) with every chunk extractor's output wrapped in a
 * [SlicelessJoiningOutput]. The joiner activates only on H.264 in TS, so fMP4, packed audio and WebVTT chunks pass
 * through. The subtitle and codec configuration calls `HlsMediaSource.Factory` makes are forwarded.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
internal class SlicelessJoiningHlsExtractorFactory(
    val delegate: HlsExtractorFactory,
    private val counter: SlicelessJoinCounter? = null,
) : HlsExtractorFactory {

    override fun createExtractor(
        uri: Uri,
        format: Format,
        muxedCaptionFormats: List<Format>?,
        timestampAdjuster: TimestampAdjuster,
        responseHeaders: Map<String, List<String>>,
        sniffingExtractorInput: ExtractorInput,
        playerId: PlayerId,
    ): HlsMediaChunkExtractor = SlicelessJoiningChunkExtractor(
        delegate.createExtractor(uri, format, muxedCaptionFormats, timestampAdjuster, responseHeaders, sniffingExtractorInput, playerId),
        counter,
    )

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): HlsExtractorFactory {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction: Boolean): HlsExtractorFactory {
        delegate.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): HlsExtractorFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }

    override fun getOutputTextFormat(sourceFormat: Format): Format = delegate.getOutputTextFormat(sourceFormat)
}

/**
 * One HLS chunk extractor with a joining output. `isPackedAudioExtractor`/`isReusable` are the delegate's (Media3's
 * bundled implementation asks its own extractor, not this wrapper); `recreate` wraps the recreated delegate again;
 * the end of a segment (`read` false) and `onTruncatedSegmentParsed` (the delegate seeks its extractor to 0) drop a
 * held sample.
 */
@OptIn(UnstableApi::class)
internal class SlicelessJoiningChunkExtractor(
    val delegate: HlsMediaChunkExtractor,
    private val counter: SlicelessJoinCounter?,
) : HlsMediaChunkExtractor {
    private var joining: SlicelessJoiningOutput? = null

    override fun init(extractorOutput: ExtractorOutput) {
        val j = SlicelessJoiningOutput(extractorOutput, counter)
        joining = j
        delegate.init(j)
    }

    override fun read(extractorInput: ExtractorInput): Boolean {
        val more = delegate.read(extractorInput)
        if (!more) joining?.endOfInput()
        return more
    }

    override fun isPackedAudioExtractor(): Boolean = delegate.isPackedAudioExtractor

    override fun isReusable(): Boolean = delegate.isReusable

    override fun recreate(): HlsMediaChunkExtractor = SlicelessJoiningChunkExtractor(delegate.recreate(), counter)

    override fun onTruncatedSegmentParsed() {
        joining?.reset()
        delegate.onTruncatedSegmentParsed()
    }
}
