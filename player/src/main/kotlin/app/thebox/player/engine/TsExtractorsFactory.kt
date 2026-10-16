package app.thebox.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import app.thebox.player.internal.TsCompat
import java.io.EOFException

/**
 * TS-only extractor list for a sniffed TS session (docs/design/m2-playback-core.md §3.1.3, §3.8.5): the `TsExtractor`
 * that `DefaultExtractorsFactory().setTsExtractorFlags(TS_FLAGS).setTsExtractorMode(mode)` creates (text transcoding
 * stays on), with mode `MODE_SINGLE_PMT` at [TsCompat.DEFAULT] and `MODE_MULTI_PMT` at [TsCompat.MULTI_PMT], wrapped
 * in the dead-track guard (review S5).
 *
 * **Dead-track guard.** `ProgressiveMediaPeriod` finishes preparing only when every sample queue has a format, so a PMT
 * entry whose PID never carries a decodable stream (an unfed second audio PID, LATM `audioMuxVersion == 1`) would keep
 * the tune in BUFFERING forever. The guard's `ExtractorOutput` hands the payload readers lazy track outputs: the real
 * track is created on the first `format()`, and `endTracks()` is forwarded once every declared track has a format or
 * [graceBytes] of TS were read since the PMT (or the input ended), whichever comes first. Tracks still without a format
 * then discard everything, and [onDeadTrack] is told (PID, track type).
 *
 * Deviation from the design's "no copy of sample data": readers write sample bytes **before** their format
 * (`H264Reader.consume` writes the whole PES chunk first and outputs the format at the end of the PPS), so dropping
 * pre-format bytes would drop the first keyframe and cost a GOP of zap time. A lazy track therefore holds its
 * pre-format bytes and sample metadata (at most [graceBytes]) and replays them into the real track when the format
 * arrives. In practice that is the first PES of each track; nothing is copied after the format.
 */
@OptIn(UnstableApi::class)
internal class TsExtractorsFactory(
    private val compat: TsCompat,
    private val onDeadTrack: ((pid: Int, trackType: Int) -> Unit)? = null,
    private val graceBytes: Long = DEAD_TRACK_GRACE_BYTES,
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> {
        val mode = if (compat == TsCompat.MULTI_PMT) TsExtractor.MODE_MULTI_PMT else TsExtractor.MODE_SINGLE_PMT
        val defaults = DefaultExtractorsFactory().setTsExtractorFlags(flagsFor(compat)).setTsExtractorMode(mode)
            .setTsSubtitleFormats(SPECULATIVE_CC_FORMATS) // V8
        return defaults.createExtractors()
            .filterIsInstance<TsExtractor>()
            .map { GuardedTsExtractor(it, graceBytes, onDeadTrack) }
            .toTypedArray()
    }

    companion object {
        /** `FLAG_ALLOW_NON_IDR_KEYFRAMES | FLAG_DETECT_ACCESS_UNITS`; HDMV DTS deliberately off (review S5, Q10). */
        const val TS_FLAGS: Int = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS

        /**
         * (integration 2026-10-13) The compat level also drops `FLAG_DETECT_ACCESS_UNITS`: Media3's Javadoc says the flag
         * "should be disabled if the stream contains access unit delimiters", and with AUD-carrying H.264 (ffmpeg's
         * muxer adds AUDs) it can split one access unit into several samples. Whether a stream has AUDs is not known
         * before the first PES, so the design default (flag on, for AUD-less broadcast encoders) stays; a channel that
         * escalated to compat level 1 (L27) gets slice-header detection off as part of that level.
         */
        const val COMPAT_TS_FLAGS: Int = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES

        fun flagsFor(compat: TsCompat): Int {
            val flags = if (compat == TsCompat.MULTI_PMT) COMPAT_TS_FLAGS else TS_FLAGS
            return if (TsDebugOverrides.detectAccessUnitsOff) flags and DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS.inv() else flags
        }

        /**
         * (code review 2026-10-16, V8) One speculative CEA-608 track per H.264/H.265 stream without a caption descriptor.
         * Media3 1.11.1's `DefaultExtractorsFactory` passes an empty list to `DefaultTsPayloadReaderFactory`, and with no
         * descriptor `SeiReader` then creates no caption track at all, so CC1 in SEI was never offered (API notes §1.3
         * corrected). `PlayerMappers.isSpeculative` labels the track and sorts it last; a stream without captions just
         * leaves it empty.
         */
        val SPECULATIVE_CC_FORMATS: List<Format> =
            listOf(Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.APPLICATION_CEA608).build())

        /** `DEAD_TRACK_GRACE_BYTES` (App. B). */
        const val DEAD_TRACK_GRACE_BYTES: Long = 1_048_576L
    }
}

/** `TsExtractor` whose output goes through a [DeadTrackGuard]. */
@OptIn(UnstableApi::class)
internal class GuardedTsExtractor(
    private val delegate: TsExtractor,
    private val graceBytes: Long,
    private val onDeadTrack: ((Int, Int) -> Unit)?,
) : Extractor {
    private var guard: DeadTrackGuard? = null

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        val g = DeadTrackGuard(output, graceBytes, onDeadTrack)
        guard = g
        delegate.init(g)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        // (M4 integration 2026-10-15) Bytes consumed by this call, not the input position: on a finite TS (archive with a
        // Content-Length) TsExtractor ends its tracks after the PMT and then TsDurationReader seeks to the end of the file
        // for the last PCR; a position difference counted that jump as read bytes, gave up every track before its first
        // PES and prepared the period with no tracks (READY, no decoder, NO_DATA_TIMEOUT after 15 s).
        val before = input.position
        val result = delegate.read(input, seekPosition)
        guard?.onRead((input.position - before).coerceAtLeast(0), result == Extractor.RESULT_END_OF_INPUT)
        return result
    }

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate
}

/** The guard's `ExtractorOutput` (see [TsExtractorsFactory]). Loader thread only. */
@OptIn(UnstableApi::class)
internal class DeadTrackGuard(
    private val output: ExtractorOutput,
    private val graceBytes: Long,
    private val onDeadTrack: ((Int, Int) -> Unit)?,
) : ExtractorOutput {
    private val tracks = LinkedHashMap<Int, LazyTrackOutput>()
    private val discard = DiscardingTrackOutput()
    private var endRequested = false
    /** TS bytes consumed since `endTracks()` was requested (seeks do not count). */
    private var readSinceEnd = 0L

    /** True once `endTracks()` was forwarded. */
    var tracksEnded = false
        private set

    /** PIDs given up on (no format within the grace). */
    val deadTracks: List<Int> get() = tracks.values.filter { it.dead }.map { it.id }

    override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { LazyTrackOutput(id, type) }

    override fun endTracks() {
        if (endRequested) return
        endRequested = true
        readSinceEnd = 0L
        maybeEnd()
    }

    override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)

    /** After each extractor read: [consumedBytes] read by that call (a seek in between is not a read). */
    fun onRead(consumedBytes: Long, endOfInput: Boolean) {
        if (!endRequested || tracksEnded) return
        readSinceEnd += consumedBytes
        if (endOfInput || readSinceEnd >= graceBytes) giveUp()
    }

    private fun maybeEnd() {
        if (!endRequested || tracksEnded) return
        if (tracks.values.all { it.real != null }) {
            tracksEnded = true
            output.endTracks()
        }
    }

    private fun giveUp() {
        for (t in tracks.values) {
            if (t.real == null) {
                t.markDead()
                onDeadTrack?.invoke(t.id, t.type)
            }
        }
        tracksEnded = true
        output.endTracks()
    }

    internal inner class LazyTrackOutput(val id: Int, val type: Int) : TrackOutput {
        var real: TrackOutput? = null
            private set
        var dead = false
            private set

        /** Pre-format bytes; [pendingMeta] rows are (bytes written before the call, timeUs, flags, size, offset). */
        private var pending: ByteArray? = null
        private var pendingLength = 0
        private var pendingMeta: ArrayList<LongArray>? = null
        private var overflowed = false

        /** Bytes written to [real] so far; metadata spanning more than this refers to discarded bytes and is dropped. */
        private var realBytes = 0L

        fun markDead() {
            dead = true
            pending = null
            pendingMeta = null
        }

        override fun format(format: Format) {
            if (dead) return
            val r = real
            if (r != null) {
                r.format(format)
                return
            }
            val created = output.track(id, type)
            real = created
            created.format(format)
            replay(created)
            maybeEnd()
        }

        private fun replay(target: TrackOutput) {
            val bytes = pending
            val meta = pendingMeta
            val length = pendingLength
            pending = null
            pendingMeta = null
            pendingLength = 0
            val buf = if (bytes != null) ParsableByteArray(bytes, length) else null
            meta?.forEach { m ->
                val upTo = m[0].toInt()
                if (buf != null && upTo > buf.position) writeReal(target, buf, upTo - buf.position)
                commit(target, m[1], m[2].toInt(), m[3].toInt(), m[4].toInt())
            }
            if (buf != null && buf.bytesLeft() > 0) writeReal(target, buf, buf.bytesLeft())
        }

        private fun writeReal(target: TrackOutput, data: ParsableByteArray, length: Int) {
            target.sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)
            realBytes += length
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            real?.let { r ->
                val n = r.sampleData(input, length, allowEndOfInput, sampleDataPart)
                if (n > 0) realBytes += n
                return n
            }
            if (dead || overflowed || !reserve(length)) return discard.sampleData(input, length, allowEndOfInput, sampleDataPart)
            val n = input.read(checkNotNull(pending), pendingLength, length)
            if (n == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            pendingLength += n
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val r = real
            if (r != null) {
                r.sampleData(data, length, sampleDataPart)
                realBytes += length
                return
            }
            if (dead || overflowed || !reserve(length)) {
                data.skipBytes(length)
                return
            }
            data.readBytes(checkNotNull(pending), pendingLength, length)
            pendingLength += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val r = real
            if (r != null) {
                commit(r, timeUs, flags, size, offset)
                return
            }
            if (dead || overflowed) return
            val meta = pendingMeta ?: ArrayList<LongArray>().also { pendingMeta = it }
            meta += longArrayOf(pendingLength.toLong(), timeUs, flags.toLong(), size.toLong(), offset.toLong())
        }

        private fun commit(target: TrackOutput, timeUs: Long, flags: Int, size: Int, offset: Int) {
            if (size.toLong() + offset > realBytes) return // its bytes were discarded before the format arrived
            target.sampleMetadata(timeUs, flags, size, offset, null)
        }

        private fun reserve(length: Int): Boolean {
            val needed = pendingLength.toLong() + length
            if (needed > graceBytes) {
                overflowed = true
                pending = null
                pendingMeta = null
                pendingLength = 0
                return false
            }
            val buf = pending
            if (buf == null || buf.size < needed) {
                val doubled = minOf(graceBytes, 2L * (buf?.size ?: INITIAL_PENDING))
                val grown = ByteArray(maxOf(needed, doubled).toInt())
                if (buf != null) System.arraycopy(buf, 0, grown, 0, pendingLength)
                pending = grown
            }
            return true
        }
    }

    private companion object {
        const val INITIAL_PENDING = 16 * 1024
    }
}

/**
 * (follow-up 2026-10-14, O-1 diagnosis) Debug-only override, set only through `app.thebox.player.debug.PlayerDebugHooks`
 * (the `debug` source set); always false in release. Turns off `FLAG_DETECT_ACCESS_UNITS` alone.
 */
internal object TsDebugOverrides {
    @Volatile var detectAccessUnitsOff: Boolean = false
}
