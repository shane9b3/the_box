// WP-A (docs/design/m6-timeshift.md §3.4, §3.5, A-S4, A-S5).
package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.TimeshiftConstants.INDEX_MAX_GAP_MS
import app.thebox.core.playback.timeshift.TimeshiftConstants.INDEX_MIN_SPACING_MS
import app.thebox.core.playback.timeshift.TimeshiftConstants.PCR_INLINE_JUMP_MS
import app.thebox.core.playback.timeshift.TimeshiftConstants.PSI_MAX_PACKETS
import app.thebox.core.playback.timeshift.TimeshiftConstants.RAI_FALLBACK_MS
import app.thebox.core.playback.timeshift.TimeshiftConstants.TS_PACKET

/**
 * Extra callbacks a sink may take from [TsIndexerImpl] (the ring's sink does). Not part of the contract: a plain
 * [IndexSink] simply misses them.
 */
internal interface IndexClockSink {
    /** Every PCR of the current segment (unwrapped, 90 kHz) with the arrival time of its read (§3.3 `monoOffset`). */
    fun onClock(pcr90k: Long, arrivalMonoMs: Long)
    /** The newest video PES PTS (audio for radio), unwrapped (the live edge, A-S4). */
    fun onPts(pts90k: Long)
}

/**
 * RAP, PSI and jump detection on the writer thread (§3.4). Allocation-free per packet; all state is preallocated.
 *
 * - **Alignment.** Packets are taken at 188-byte steps from `off`; a packet without the 0x47 sync byte is skipped.
 * - **PSI.** The latest PAT and the PMTs it lists (≤ [PSI_MAX_PACKETS] packets, single-packet sections) are kept and
 *   reported with [IndexSink.onPsi] whenever the segment's set is complete and changed. A PAT/PMT whose section (version
 *   or content) changes ends the segment with `PSI_CHANGE` at that packet (A-S5).
 * - **RAP.** `random_access_indicator` on the video PID (on the PCR PID without video) with the PTS of the PES starting
 *   in that packet. NAL/start-code fallback (H.264 5/7, H.265 16–21/32–34, MPEG-2 0xB3) while no RAI has been seen in the
 *   last [RAI_FALLBACK_MS] of stream time. At most one RAP per [INDEX_MIN_SPACING_MS] (anchors never count); an anchor at a PCR packet when
 *   no entry came for [INDEX_MAX_GAP_MS]. Radio (no video): the anchors are placed on audio PES starts and carry
 *   [RingFlags.RAP], because an audio frame is a random-access point.
 * - **Clocks.** PCR and PTS are unwrapped per segment in one 90 kHz domain (33-bit wrap via signed deltas), so `pts − pcr`
 *   is the small lead and the mono timeline is continuous across 2³³.
 * - **Jumps (stream domain, A-S5).** A PCR step > [PCR_INLINE_JUMP_MS], backwards, or with `discontinuity_indicator`; a
 *   video PTS step > [PCR_INLINE_JUMP_MS] either way. The first of the pair places the boundary (normally the PTS, which
 *   comes first in byte order) and the other one is absorbed as the new segment's base. Arrival time is never used for
 *   jumps, so a connect burst makes no segment. A jump right after [onSplice] is `PCR_JUMP`, otherwise `INLINE_PCR_JUMP`.
 * - **No PCR.** A segment that sees no PCR for [INDEX_MAX_GAP_MS] of arrival time switches to the arrival clock
 *   ([RingFlags.ARRIVAL_CLOCK] entries, `pcr` = arrival × 90).
 */
class TsIndexerImpl : TsIndexer {
    // ---------------------------------------------------------------- PSI
    private val patPkt = ByteArray(TS_PACKET)
    private var hasPat = false
    private val pmtPids = IntArray(PSI_MAX_PACKETS - 1)
    private var pmtCount = 0
    private val pmtPkts = ByteArray((PSI_MAX_PACKETS - 1) * TS_PACKET)
    private val pmtHave = BooleanArray(PSI_MAX_PACKETS - 1)
    private val psiOut = ByteArray(PSI_MAX_PACKETS * TS_PACKET)
    private var psiDirty = false
    private var pcrPid = -1
    private var videoPid = -1
    private var videoType = VT_NONE
    private var audioPid = -1

    // ---------------------------------------------------------------- per-segment clock
    private var hasPcr = false
    private var lastPcrRaw = 0L
    private var pcrU = 0L
    private var hasPts = false
    private var lastPtsRaw = 0L
    private var ptsU = 0L
    private var absorbPcr = false
    private var absorbPts = false
    private var staleProgress = 0L
    private var arrivalClock = false
    private var segStartArrival = Long.MIN_VALUE
    private var lastPcrArrival = Long.MIN_VALUE

    // ---------------------------------------------------------------- entries
    private var hasEntry = false
    /**
     * (Integration 2026-10-19) The spacing rule counts RAPs only. With a GOP of exactly INDEX_MAX_GAP_MS the time-only
     * anchor fires on the PCR packet just before the keyframe (same lead), and spacing against the anchor's estimated PTS
     * dropped that RAP and, the cadence locking, every later one: a whole connection without a RAP (device I-2).
     */
    private var hasRapEntry = false
    private var lastRapPts = 0L
    private var lastEntryPts = 0L
    private var lastEntryPcr = 0L
    private var lastEntryArrival = 0L
    private var raiSeen = false
    private var lastRaiPts = 0L
    private var pendingRap = false
    private var pendingOffset = 0L
    private var pendingPts = 0L
    private var spliceFlag = false
    private var afterSplice = false

    private var curArrival = 0L
    private var sink: IndexSink? = null
    private var clockSink: IndexClockSink? = null

    override fun scan(buf: ByteArray, off: Int, len: Int, logicalOffset: Long, arrivalMonoMs: Long, sink: IndexSink) {
        this.sink = sink
        this.clockSink = sink as? IndexClockSink
        curArrival = arrivalMonoMs
        if (segStartArrival == Long.MIN_VALUE) segStartArrival = arrivalMonoMs
        var p = off
        val end = off + len
        while (p + TS_PACKET <= end) {
            if (buf[p] == SYNC) packet(buf, p, logicalOffset + (p - off), arrivalMonoMs)
            p += TS_PACKET
        }
        // No PCR on this segment for a while: the arrival clock takes over (§3.4 item 4).
        if (!hasPcr && !arrivalClock && !absorbPcr && arrivalMonoMs - segStartArrival >= INDEX_MAX_GAP_MS) {
            arrivalClock = true
            if (pendingRap) { pendingRap = false; emitRap(pendingOffset, pendingPts, true) }
        }
        if (arrivalClock && (!hasEntry || arrivalMonoMs - lastEntryArrival >= INDEX_MAX_GAP_MS) && videoPid < 0 && audioPid < 0) {
            emitAnchor(logicalOffset, arrivalMonoMs * 90, RingFlags.ARRIVAL_CLOCK, arrivalMonoMs)
        }
    }

    override fun onSplice() { spliceFlag = true; afterSplice = true }

    override fun reset() {
        hasPat = false; pmtCount = 0; java.util.Arrays.fill(pmtHave, false); psiDirty = false
        pcrPid = -1; videoPid = -1; videoType = VT_NONE; audioPid = -1
        raiSeen = false; spliceFlag = false; afterSplice = false
        resetSegment()
        absorbPcr = false; absorbPts = false
        hasPcr = false; hasPts = false
    }

    // ================================================================ packets

    private fun packet(b: ByteArray, p: Int, offset: Long, arrival: Long) {
        val b1 = b[p + 1].toInt() and 0xFF
        val pusi = b1 and 0x40 != 0
        val pid = ((b1 and 0x1F) shl 8) or (b[p + 2].toInt() and 0xFF)
        val afc = (b[p + 3].toInt() ushr 4) and 3
        var payload = p + 4
        var rai = false
        var disc = false
        var pcr = -1L
        if (afc and 2 != 0) {
            val afLen = b[p + 4].toInt() and 0xFF
            if (afLen > 0 && afLen <= 183) {
                val flags = b[p + 5].toInt() and 0xFF
                disc = flags and 0x80 != 0
                rai = flags and 0x40 != 0
                if (flags and 0x10 != 0 && afLen >= 7) {
                    pcr = ((b[p + 6].toLong() and 0xFF) shl 25) or ((b[p + 7].toLong() and 0xFF) shl 17) or
                        ((b[p + 8].toLong() and 0xFF) shl 9) or ((b[p + 9].toLong() and 0xFF) shl 1) or
                        ((b[p + 10].toLong() and 0xFF) ushr 7)
                }
            }
            payload = p + 5 + afLen
        }
        val end = p + TS_PACKET
        val hasPayload = afc and 1 != 0 && payload < end

        if (pid == 0) { if (pusi && hasPayload) pat(b, p, payload, end, offset); return }
        for (i in 0 until pmtCount) if (pmtPids[i] == pid) { if (pusi && hasPayload) pmt(b, p, payload, end, offset, i); return }

        if (pid == pcrPid && pcr >= 0) onPcr(pcr, disc, offset, arrival)

        if (pid == videoPid) {
            if (pusi && hasPayload) videoPes(b, payload, end, offset, rai)
        } else if (videoPid < 0 && pid == audioPid && pusi && hasPayload) {
            audioPes(b, payload, end, offset, rai)
        }

        // A time-only anchor when no entry came for a while (video streams; radio anchors sit on PES starts).
        if (videoPid >= 0 && pid == pcrPid && pcr >= 0 && hasPcr && !absorbPcr && !arrivalClock) {
            if (!hasEntry || pcrU - lastEntryPcr >= INDEX_MAX_GAP_MS * 90) emitAnchor(offset, pcrU, 0, arrival)
        }
    }

    private fun onPcr(raw: Long, disc: Boolean, offset: Long, arrival: Long) {
        lastPcrArrival = arrival
        if (!hasPcr) {
            if (absorbPcr) {
                // After a PTS-placed boundary: PCRs still on the old timeline are stale until the PCR step (§3.4 item 5).
                val d = signed33(raw - lastPcrRaw)
                val jumped = disc || d > PCR_INLINE_JUMP_MS * 90 || d < 0
                lastPcrRaw = raw
                if (!jumped) {
                    staleProgress += d
                    if (staleProgress < STALE_MAX_90K) return
                }
                absorbPcr = false
            }
            hasPcr = true
            arrivalClock = false
            lastPcrRaw = raw
            pcrU = if (hasPts) ptsU + signed33(raw - lastPtsRaw) else raw
            clockSink?.onClock(pcrU, arrival)
            if (pendingRap) { pendingRap = false; emitRap(pendingOffset, pendingPts, true) }
            return
        }
        val d = signed33(raw - lastPcrRaw)
        if (disc || d > PCR_INLINE_JUMP_MS * 90 || d < 0) {
            lastPcrRaw = raw
            boundary(offset, jumpReason(), absorbNext = ABSORB_PTS)
            hasPcr = true
            pcrU = raw
            clockSink?.onClock(pcrU, arrival)
            return
        }
        lastPcrRaw = raw
        pcrU += d
        afterSplice = false
        clockSink?.onClock(pcrU, arrival)
    }

    private fun videoPes(b: ByteArray, payload: Int, end: Int, offset: Long, rai: Boolean) {
        val raw = pesPts(b, payload, end)
        if (raw < 0) return
        if (!ptsStep(raw, offset)) return
        clockSink?.onPts(ptsU)
        val streamMs = ptsU / 90
        if (rai) { raiSeen = true; lastRaiPts = ptsU }
        var key = rai
        if (!key && (!raiSeen || streamMs - lastRaiPts / 90 > RAI_FALLBACK_MS)) key = nalKey(b, payload, end)
        if (key) emitRap(offset, ptsU, false)
    }

    private fun audioPes(b: ByteArray, payload: Int, end: Int, offset: Long, rai: Boolean) {
        val raw = pesPts(b, payload, end)
        if (raw < 0) return
        if (!ptsStep(raw, offset)) return
        clockSink?.onPts(ptsU)
        if (rai || !hasEntry || ptsU - lastEntryPts >= INDEX_MAX_GAP_MS * 90) emitRap(offset, ptsU, false)
    }

    /** Unwraps [raw] into the segment's PTS domain; places a boundary on a PTS step. False = ignore this PES. */
    private fun ptsStep(raw: Long, offset: Long): Boolean {
        if (!hasPts) {
            if (absorbPts) {
                val d = signed33(raw - lastPtsRaw)
                val jumped = d > PCR_INLINE_JUMP_MS * 90 || d < -PCR_INLINE_JUMP_MS * 90
                lastPtsRaw = raw
                if (!jumped) {
                    staleProgress += if (d > 0) d else 0
                    if (staleProgress < STALE_MAX_90K) return false
                }
                absorbPts = false
            }
            hasPts = true
            lastPtsRaw = raw
            ptsU = if (hasPcr) pcrU + signed33(raw - lastPcrRaw) else raw
            return true
        }
        val d = signed33(raw - lastPtsRaw)
        if (d > PCR_INLINE_JUMP_MS * 90 || d < -PCR_INLINE_JUMP_MS * 90) {
            lastPtsRaw = raw
            boundary(offset, jumpReason(), absorbNext = ABSORB_PCR)
            hasPts = true
            ptsU = raw
            return true
        }
        lastPtsRaw = raw
        ptsU += d
        return true
    }

    // ================================================================ entries

    private fun emitRap(offset: Long, pts: Long, fromPending: Boolean) {
        if (!hasPcr && !arrivalClock) {
            // No PCR yet in this segment: keep the newest RAP until the first PCR gives the clock.
            pendingRap = true; pendingOffset = offset; pendingPts = pts
            return
        }
        if (hasRapEntry && pts - lastRapPts < INDEX_MIN_SPACING_MS * 90 && pts >= lastRapPts) return
        var flags = RingFlags.RAP
        if (spliceFlag) { flags = flags or RingFlags.SPLICE; spliceFlag = false }
        val pcr: Long
        if (arrivalClock) {
            flags = flags or RingFlags.ARRIVAL_CLOCK
            pcr = curArrival * 90
        } else pcr = pcrU
        sink?.onRap(offset, pts, pcr, flags)
        hasEntry = true; lastEntryPts = pts; lastEntryArrival = curArrival
        hasRapEntry = true; lastRapPts = pts
        if (!arrivalClock) lastEntryPcr = pcrU
    }

    private fun emitAnchor(offset: Long, pcr: Long, extraFlags: Int, arrival: Long) {
        var flags = extraFlags
        if (spliceFlag) { flags = flags or RingFlags.SPLICE; spliceFlag = false }
        sink?.onAnchor(offset, pcr, flags)
        hasEntry = true; lastEntryArrival = arrival
        if (extraFlags and RingFlags.ARRIVAL_CLOCK == 0) { lastEntryPcr = pcr; lastEntryPts = pcr + leadGuess() }
    }

    private fun leadGuess(): Long = if (hasPts && hasPcr) ptsU - pcrU else 0L

    private fun jumpReason(): SegmentReason = if (afterSplice) SegmentReason.PCR_JUMP else SegmentReason.INLINE_PCR_JUMP

    private fun boundary(offset: Long, reason: SegmentReason, absorbNext: Int) {
        sink?.onBoundary(offset, reason)
        resetSegment()
        afterSplice = false
        absorbPcr = absorbNext == ABSORB_PCR
        absorbPts = absorbNext == ABSORB_PTS
        staleProgress = 0L
        segStartArrival = curArrival
        if (hasPat) { psiDirty = true; reportPsi() }
    }

    private fun resetSegment() {
        hasPcr = false; hasPts = false
        hasEntry = false; pendingRap = false
        hasRapEntry = false
        arrivalClock = false
        segStartArrival = Long.MIN_VALUE
    }

    // ================================================================ PSI

    private fun pat(b: ByteArray, p: Int, payload: Int, end: Int, offset: Long) {
        val sec = payload + 1 + (b[payload].toInt() and 0xFF)
        if (sec + 8 > end || b[sec].toInt() != 0x00) return
        val secLen = ((b[sec + 1].toInt() and 0x0F) shl 8) or (b[sec + 2].toInt() and 0xFF)
        val secEnd = sec + 3 + secLen
        if (secEnd > end || secLen < 9) return
        if (hasPat) {
            if (sectionEquals(b, sec, secEnd, patPkt, sec - p)) return
            // PAT changed: the PSI is different from here on.
            boundary(offset, SegmentReason.PSI_CHANGE, absorbNext = NONE)
        }
        System.arraycopy(b, p, patPkt, 0, TS_PACKET)
        hasPat = true
        pmtCount = 0
        var q = sec + 8
        while (q + 4 <= secEnd - 4 && pmtCount < pmtPids.size) {
            val prog = ((b[q].toInt() and 0xFF) shl 8) or (b[q + 1].toInt() and 0xFF)
            val pid = ((b[q + 2].toInt() and 0x1F) shl 8) or (b[q + 3].toInt() and 0xFF)
            if (prog != 0) { pmtPids[pmtCount] = pid; pmtHave[pmtCount] = false; pmtCount++ }
            q += 4
        }
        psiDirty = true
    }

    private fun pmt(b: ByteArray, p: Int, payload: Int, end: Int, offset: Long, i: Int) {
        val sec = payload + 1 + (b[payload].toInt() and 0xFF)
        if (sec + 12 > end || b[sec].toInt() != 0x02) return
        val secLen = ((b[sec + 1].toInt() and 0x0F) shl 8) or (b[sec + 2].toInt() and 0xFF)
        val secEnd = sec + 3 + secLen
        if (secEnd > end || secLen < 13) return
        if (pmtHave[i]) {
            if (sectionEquals(b, sec, secEnd, pmtPkts, i * TS_PACKET + (sec - p))) return
            boundary(offset, SegmentReason.PSI_CHANGE, absorbNext = NONE)
        }
        System.arraycopy(b, p, pmtPkts, i * TS_PACKET, TS_PACKET)
        pmtHave[i] = true
        if (i == 0) {
            pcrPid = ((b[sec + 8].toInt() and 0x1F) shl 8) or (b[sec + 9].toInt() and 0xFF)
            val pil = ((b[sec + 10].toInt() and 0x0F) shl 8) or (b[sec + 11].toInt() and 0xFF)
            var q = sec + 12 + pil
            videoPid = -1; videoType = VT_NONE; audioPid = -1
            while (q + 5 <= secEnd - 4) {
                val st = b[q].toInt() and 0xFF
                val pid = ((b[q + 1].toInt() and 0x1F) shl 8) or (b[q + 2].toInt() and 0xFF)
                val esl = ((b[q + 3].toInt() and 0x0F) shl 8) or (b[q + 4].toInt() and 0xFF)
                val vt = videoTypeOf(st)
                if (vt != VT_NONE && videoPid < 0) { videoPid = pid; videoType = vt }
                else if (vt == VT_NONE && audioPid < 0 && isAudio(st)) audioPid = pid
                q += 5 + esl
            }
        }
        psiDirty = true
        reportPsi()
    }

    private fun reportPsi() {
        if (!psiDirty || !hasPat || pmtCount == 0) return
        for (i in 0 until pmtCount) if (!pmtHave[i]) return
        System.arraycopy(patPkt, 0, psiOut, 0, TS_PACKET)
        System.arraycopy(pmtPkts, 0, psiOut, TS_PACKET, pmtCount * TS_PACKET)
        psiDirty = false
        sink?.onPsi(psiOut, pmtCount + 1)
    }

    private fun sectionEquals(b: ByteArray, from: Int, to: Int, stored: ByteArray, storedFrom: Int): Boolean {
        if (storedFrom < 0 || storedFrom + (to - from) > stored.size) return false
        for (k in 0 until to - from) if (b[from + k] != stored[storedFrom + k]) return false
        return true
    }

    // ================================================================ PES and NAL

    /** PTS of the PES starting at [payload] (33 bits), or −1. */
    private fun pesPts(b: ByteArray, payload: Int, end: Int): Long {
        if (payload + 14 > end) return -1L
        if (b[payload].toInt() != 0 || b[payload + 1].toInt() != 0 || b[payload + 2].toInt() != 1) return -1L
        if ((b[payload + 7].toInt() and 0x80) == 0) return -1L
        val q = payload + 9
        return (((b[q].toLong() and 0x0E) shl 29) or ((b[q + 1].toLong() and 0xFF) shl 22) or
            ((b[q + 2].toLong() and 0xFE) shl 14) or ((b[q + 3].toLong() and 0xFF) shl 7) or
            ((b[q + 4].toLong() and 0xFE) ushr 1))
    }

    /** True when the ES data of this packet starts a keyframe (start-code scan inside this packet only). */
    private fun nalKey(b: ByteArray, payload: Int, end: Int): Boolean {
        if (payload + 9 > end) return false
        var q = payload + 9 + (b[payload + 8].toInt() and 0xFF)
        while (q + 3 < end) {
            if (b[q].toInt() == 0 && b[q + 1].toInt() == 0 && b[q + 2].toInt() == 1) {
                val h = b[q + 3].toInt() and 0xFF
                when (videoType) {
                    VT_H264 -> {
                        val t = h and 0x1F
                        if (t == 5 || t == 7) return true
                        if (t == 1) return false
                    }
                    VT_H265 -> {
                        val t = (h ushr 1) and 0x3F
                        if (t in 16..21 || t in 32..34) return true
                        if (t in 0..9) return false
                    }
                    VT_MPEG2 -> {
                        if (h == 0xB3) return true
                        if (h == 0x00) return false
                    }
                    else -> return false
                }
                q += 3
            } else q++
        }
        return false
    }

    internal companion object {
        private const val SYNC: Byte = 0x47
        private const val VT_NONE = 0
        private const val VT_H264 = 1
        private const val VT_H265 = 2
        private const val VT_MPEG2 = 3
        private const val NONE = 0
        private const val ABSORB_PCR = 1
        private const val ABSORB_PTS = 2
        /** Old-timeline progress after which an expected second jump is given up (stream time, 90 kHz). */
        private const val STALE_MAX_90K = 2_000L * 90
        private const val MASK_33 = (1L shl 33) - 1

        fun videoTypeOf(streamType: Int): Int = when (streamType) {
            0x1B -> VT_H264
            0x24 -> VT_H265
            0x01, 0x02 -> VT_MPEG2
            else -> VT_NONE
        }

        private fun isAudio(st: Int): Boolean = st == 0x03 || st == 0x04 || st == 0x0F || st == 0x11 || st == 0x81 || st == 0x87

        /** Signed value of a 33-bit difference (as `PcrTrackerImpl.signed33`, §3.3). */
        fun signed33(diff: Long): Long {
            val m = diff and MASK_33
            return if (m >= (1L shl 32)) m - (1L shl 33) else m
        }
    }
}
