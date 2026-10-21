package app.thebox.core.playback.timeshift

/**
 * A deterministic synthetic MPEG-TS stream for the time-shift tests (docs/design/m6-timeshift.md §17.1): PAT/PMT,
 * a video PES per frame with PCR in its adaptation field, RAI and an IDR/keyframe NAL on GOP starts, an optional audio
 * PES, and filler packets up to [bytesPerSec]. Timestamps can start near 2³³ ([startPcr90k]) and step ([jump]).
 *
 * Streaming and allocation-free after construction: [fill] writes whole packets into the caller's array, one frame at a
 * time from an internal buffer. Two instances with the same parameters (and the same [jump]/[changePmt] calls at the
 * same frames) produce the same bytes, so a test can regenerate what it wrote.
 */
class SyntheticTs(
    val fps: Int = 25,
    val gopFrames: Int = 25,
    val bytesPerSec: Int = 1_000_000,
    val leadMs: Long = 700,
    startPcr90k: Long = 900_000L,
    var rai: Boolean = true,
    val video: Boolean = true,
    val audio: Boolean = true,
    val streamType: Int = H264,
    val psiEveryFrames: Int = 10,
    val programs: Int = 1,
    val pcr: Boolean = true,
    /** Keyframes carry this NAL (H.264): 5 (IDR slice) or 7 (SPS). */
    val h264KeyNal: Int = 7,
    /**
     * (Integration 2026-10-19) An adaptation-only PCR packet on the PCR PID before each frame's PES, carrying the frame's
     * PCR (ffmpeg's `pcr_period` packets): the PCR reaches a value before the keyframe packet that carries the same one.
     */
    val pcrPacketFirst: Boolean = false,
) {
    val videoPid = 0x100
    var audioPid = 0x101; private set
    val pmtPid = 0x1000
    private val pcrPid get() = if (video) videoPid else audioPid
    private var pmtVersion = 0
    private val cc = IntArray(0x2000)

    /** Frames emitted so far. */
    var frame = 0L; private set
    /** Bytes emitted so far (the logical offset of the next byte). */
    var position = 0L; private set
    private var pcrBase = startPcr90k
    private var ptsBase = startPcr90k
    private var pendingPtsStep = 0L
    private var pendingPcrStep = 0L
    private var pcrStepPending = false
    private var delayedPcrStep = 0L
    private var delayedPending = false
    private var discWithPcr = false

    val packetsPerFrame = maxOf(4, bytesPerSec / fps / PACKET)
    /** Bytes per frame and the real rate (whole packets per frame). */
    val frameBytes: Int get() = packetsPerFrame * PACKET
    val actualBytesPerSec: Double get() = frameBytes.toDouble() * fps
    private val frameBuf = ByteArray((packetsPerFrame + 8) * PACKET)
    private var frameLen = 0
    private var framePos = 0
    /** Stream offset of the first byte of the frame being built. */
    private var frameStart = 0L

    /** Stream offset of the last keyframe PES packet emitted, and its PTS (raw 33-bit). */
    var lastKeyOffset = -1L; private set
    var lastKeyPts = -1L; private set
    /** Called for each keyframe with (stream offset, raw PTS). Tests only; null in allocation runs. */
    var onKeyframe: ((Long, Long) -> Unit)? = null
    /** Called for each frame's first packet with (stream offset, raw PTS, frame index). */
    var onFrame: ((Long, Long, Long) -> Unit)? = null

    /** Frame duration in 90 kHz ticks. */
    val frameTicks: Long get() = 90_000L / fps

    /**
     * A timestamp step of [delta90k] at the next frame. [ptsFirst] (the usual byte order, A-S5): the PTS steps on the
     * next frame and the PCR one frame later; otherwise both together. [discontinuity] sets the indicator on the PCR.
     */
    fun jump(delta90k: Long, ptsFirst: Boolean = true, discontinuity: Boolean = false) {
        pendingPtsStep += delta90k
        if (ptsFirst) { delayedPcrStep = delta90k; delayedPending = true } else { pendingPcrStep = delta90k; pcrStepPending = true }
        discWithPcr = discontinuity
    }

    /** Bumps the PMT version and moves the audio PID (fake panel `pmt_change_after`). */
    fun changePmt() { pmtVersion = (pmtVersion + 1) and 0x1F; audioPid += 0x10 }

    /** Writes whole packets into [dst]; returns the bytes written (a multiple of 188, at least one packet if len ≥ 188). */
    fun fill(dst: ByteArray, off: Int, len: Int): Int {
        var done = 0
        while (len - done >= PACKET) {
            if (framePos >= frameLen) buildFrame()
            val n = minOf((len - done) / PACKET * PACKET, frameLen - framePos)
            System.arraycopy(frameBuf, framePos, dst, off + done, n)
            framePos += n; done += n
        }
        position += done
        return done
    }

    /** Current PCR / PTS (raw 33-bit) of the next frame. */
    fun nextPcr(): Long = (pcrBase + frame * frameTicks) and MASK33
    fun nextPts(): Long = (ptsBase + frame * frameTicks + leadMs * 90) and MASK33

    private fun buildFrame() {
        frameStart += frameLen
        frameLen = 0; framePos = 0
        var disc = false
        if (pendingPtsStep != 0L) { ptsBase += pendingPtsStep; pendingPtsStep = 0L }
        if (pcrStepPending) { pcrBase += pendingPcrStep; pendingPcrStep = 0L; pcrStepPending = false; disc = discWithPcr; discWithPcr = false }
        val key = frame % gopFrames == 0L
        val pcrNow = (pcrBase + frame * frameTicks) and MASK33
        val ptsNow = (ptsBase + frame * frameTicks + leadMs * 90) and MASK33
        if (frame % psiEveryFrames == 0L) { pat(); for (p in 0 until programs) pmt(p) }
        if (pcrPacketFirst && pcr) pcrOnly(pcrNow)
        val pesOffset = frameStart + frameLen
        if (video) videoPes(pcrNow, ptsNow, key, disc && pcr)
        if (audio) audioPes(if (video) -1 else pcrNow, ptsNow, if (!video) disc else false, !video && key)
        if (key) { lastKeyOffset = pesOffset; lastKeyPts = ptsNow; onKeyframe?.invoke(pesOffset, ptsNow) }
        onFrame?.invoke(pesOffset, ptsNow, frame)
        while (frameLen / PACKET < packetsPerFrame) filler(if (video) videoPid else audioPid)
        if (delayedPending) { pendingPcrStep = delayedPcrStep; pcrStepPending = true; delayedPending = false }
        frame++
    }

    // ---------------------------------------------------------------- packets

    private fun header(pid: Int, pusi: Boolean, afc: Int): Int {
        val p = frameLen
        frameBuf[p] = 0x47
        frameBuf[p + 1] = (((if (pusi) 0x40 else 0) or (pid ushr 8)) and 0xFF).toByte()
        frameBuf[p + 2] = (pid and 0xFF).toByte()
        val c = cc[pid]
        if (afc and 1 != 0) cc[pid] = (c + 1) and 0x0F
        frameBuf[p + 3] = ((afc shl 4) or c).toByte()
        return p
    }

    private fun end(p: Int, from: Int) {
        java.util.Arrays.fill(frameBuf, from, p + PACKET, 0xFF.toByte())
        frameLen = p + PACKET
    }

    private fun pat() {
        val p = header(0, true, 1)
        var q = p + 4
        frameBuf[q++] = 0 // pointer
        val secStart = q
        frameBuf[q++] = 0x00
        val secLen = 5 + 4 * programs + 4
        frameBuf[q++] = (0xB0 or (secLen ushr 8)).toByte(); frameBuf[q++] = secLen.toByte()
        frameBuf[q++] = 0; frameBuf[q++] = 1
        frameBuf[q++] = 0xC1.toByte(); frameBuf[q++] = 0; frameBuf[q++] = 0
        for (i in 0 until programs) {
            val pid = pmtPid + i
            frameBuf[q++] = 0; frameBuf[q++] = (i + 1).toByte()
            frameBuf[q++] = (0xE0 or (pid ushr 8)).toByte(); frameBuf[q++] = pid.toByte()
        }
        q = crc(secStart, q)
        end(p, q)
    }

    private fun pmt(program: Int) {
        val p = header(pmtPid + program, true, 1)
        var q = p + 4
        frameBuf[q++] = 0
        val secStart = q
        val streams = (if (video) 1 else 0) + (if (audio) 1 else 0)
        val secLen = 9 + 5 * streams + 4
        frameBuf[q++] = 0x02
        frameBuf[q++] = (0xB0 or (secLen ushr 8)).toByte(); frameBuf[q++] = secLen.toByte()
        frameBuf[q++] = 0; frameBuf[q++] = (program + 1).toByte()
        frameBuf[q++] = (0xC1 or (pmtVersion shl 1)).toByte(); frameBuf[q++] = 0; frameBuf[q++] = 0
        val pp = if (pcr) pcrPid else 0x1FFF
        frameBuf[q++] = (0xE0 or (pp ushr 8)).toByte(); frameBuf[q++] = pp.toByte()
        frameBuf[q++] = 0xF0.toByte(); frameBuf[q++] = 0
        if (video) { frameBuf[q++] = streamType.toByte(); frameBuf[q++] = (0xE0 or (videoPid ushr 8)).toByte(); frameBuf[q++] = videoPid.toByte(); frameBuf[q++] = 0xF0.toByte(); frameBuf[q++] = 0 }
        if (audio) { frameBuf[q++] = 0x0F; frameBuf[q++] = (0xE0 or (audioPid ushr 8)).toByte(); frameBuf[q++] = audioPid.toByte(); frameBuf[q++] = 0xF0.toByte(); frameBuf[q++] = 0 }
        q = crc(secStart, q)
        end(p, q)
    }

    private fun crc(@Suppress("UNUSED_PARAMETER") from: Int, q: Int): Int {
        // The indexer does not check the CRC; a fixed value keeps repeats byte-identical.
        frameBuf[q] = 0x12; frameBuf[q + 1] = 0x34; frameBuf[q + 2] = 0x56; frameBuf[q + 3] = 0x78
        return q + 4
    }

    private fun videoPes(pcrNow: Long, ptsNow: Long, key: Boolean, disc: Boolean) {
        val withPcr = pcr
        val p = header(videoPid, true, if (withPcr || (key && rai)) 3 else 1)
        var q = p + 4
        if (withPcr || (key && rai)) {
            val afLen = if (withPcr) 7 else 1
            frameBuf[q++] = afLen.toByte()
            frameBuf[q++] = ((if (disc) 0x80 else 0) or (if (key && rai) 0x40 else 0) or (if (withPcr) 0x10 else 0)).toByte()
            if (withPcr) q = putPcr(q, pcrNow)
        }
        q = putPesHeader(q, 0xE0, ptsNow)
        // ES: AUD, then the keyframe NAL or a slice.
        when (streamType) {
            H264 -> {
                q = startCode(q); frameBuf[q++] = 0x09; frameBuf[q++] = 0xF0.toByte()
                q = startCode(q); frameBuf[q++] = (if (key) 0x60 or h264KeyNal else 0x41).toByte()
            }
            H265 -> {
                q = startCode(q); frameBuf[q++] = (35 shl 1).toByte(); frameBuf[q++] = 1
                q = startCode(q); frameBuf[q++] = ((if (key) 19 else 1) shl 1).toByte(); frameBuf[q++] = 1
            }
            else -> {
                q = startCode(q); frameBuf[q++] = (if (key) 0xB3 else 0x00).toByte()
            }
        }
        end(p, q)
    }

    private fun audioPes(pcrNow: Long, ptsNow: Long, disc: Boolean, raiFlag: Boolean) {
        val withPcr = pcrNow >= 0 && pcr
        val withAf = withPcr || (raiFlag && rai)
        val p = header(audioPid, true, if (withAf) 3 else 1)
        var q = p + 4
        if (withAf) {
            val afLen = if (withPcr) 7 else 1
            frameBuf[q++] = afLen.toByte()
            frameBuf[q++] = ((if (disc) 0x80 else 0) or (if (raiFlag && rai) 0x40 else 0) or (if (withPcr) 0x10 else 0)).toByte()
            if (withPcr) q = putPcr(q, pcrNow)
        }
        q = putPesHeader(q, 0xC0, ptsNow)
        frameBuf[q++] = 0xFF.toByte(); frameBuf[q++] = 0xF1.toByte()
        end(p, q)
    }

    private fun pcrOnly(pcrNow: Long) {
        val p = header(pcrPid, false, 2)
        var q = p + 4
        frameBuf[q++] = (PACKET - 5).toByte()
        frameBuf[q++] = 0x10
        q = putPcr(q, pcrNow)
        end(p, q)
    }

    private fun filler(pid: Int) {
        val p = header(pid, false, 1)
        // Payload: a byte pattern of the stream position (no start codes needed: only PUSI packets are scanned).
        val base = frameStart + p
        for (i in 4 until PACKET) frameBuf[p + i] = (((base + i) * 7 + 3) and 0x7F).toByte()
        frameLen = p + PACKET
    }

    private fun startCode(q0: Int): Int { var q = q0; frameBuf[q++] = 0; frameBuf[q++] = 0; frameBuf[q++] = 0; frameBuf[q++] = 1; return q }

    private fun putPcr(q0: Int, base: Long): Int {
        var q = q0
        frameBuf[q++] = (base ushr 25).toByte(); frameBuf[q++] = (base ushr 17).toByte()
        frameBuf[q++] = (base ushr 9).toByte(); frameBuf[q++] = (base ushr 1).toByte()
        frameBuf[q++] = (((base and 1) shl 7) or 0x7E).toByte(); frameBuf[q++] = 0
        return q
    }

    private fun putPesHeader(q0: Int, streamId: Int, pts: Long): Int {
        var q = q0
        frameBuf[q++] = 0; frameBuf[q++] = 0; frameBuf[q++] = 1; frameBuf[q++] = streamId.toByte()
        frameBuf[q++] = 0; frameBuf[q++] = 0
        frameBuf[q++] = 0x80.toByte(); frameBuf[q++] = 0x80.toByte(); frameBuf[q++] = 5
        frameBuf[q++] = (0x21 or ((pts ushr 29) and 0x0E).toInt()).toByte()
        frameBuf[q++] = (pts ushr 22).toByte()
        frameBuf[q++] = (((pts ushr 14) and 0xFE) or 1).toByte()
        frameBuf[q++] = (pts ushr 7).toByte()
        frameBuf[q++] = (((pts shl 1) and 0xFE) or 1).toByte()
        return q
    }

    companion object {
        const val PACKET = 188
        const val H264 = 0x1B
        const val H265 = 0x24
        const val MPEG2 = 0x02
        const val MASK33 = (1L shl 33) - 1
    }
}
