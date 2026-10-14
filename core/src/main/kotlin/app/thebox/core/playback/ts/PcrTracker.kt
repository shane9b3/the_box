package app.thebox.core.playback.ts

import app.thebox.core.playback.PcrTracker
import kotlin.math.abs

/**
 * PCR PID pick, 33-bit PCR base, jump test (§3.3.5).
 *
 * The first packet whose adaptation field carries a PCR fixes the PCR PID; only that PID is inspected afterwards
 * (two bit tests per packet). After [markReconnect], the first PCR on that PID is compared with
 * `last + (now − lastWall) × 90` (mod 2³³, `now` = when that PCR is seen); a signed difference beyond
 * [jumpThresholdMs] is reported by [pendingJumpMs]. Before any PCR was seen, [markReconnect] leaves the check
 * disabled (radio streams without PCR rely on the stall detectors). Not thread-safe: one loader thread.
 */
class PcrTrackerImpl(private val jumpThresholdMs: Long = PCR_JUMP_REPREPARE_MS) : PcrTracker {
    private var pcrPid = -1
    private var hasLast = false
    private var lastPcr90k = 0L
    private var lastWallMs = 0L
    private var awaiting = false
    private var pendingJump = NO_JUMP

    /** The PCR PID, or -1 before the first PCR. */
    val pid: Int get() = pcrPid

    override fun onPacket(pkt: ByteArray, off: Int, nowMs: Long) {
        if ((pkt[off + 3].toInt() and 0x20) == 0) return                  // no adaptation field
        if ((pkt[off + 4].toInt() and 0xFF) < PCR_AF_MIN_LENGTH) return    // too short to carry a PCR
        if ((pkt[off + 5].toInt() and 0x10) == 0) return                  // PCR_flag
        val pid = ((pkt[off + 1].toInt() and 0x1F) shl 8) or (pkt[off + 2].toInt() and 0xFF)
        if (pcrPid < 0) pcrPid = pid else if (pid != pcrPid) return
        val base = ((pkt[off + 6].toLong() and 0xFF) shl 25) or
            ((pkt[off + 7].toLong() and 0xFF) shl 17) or
            ((pkt[off + 8].toLong() and 0xFF) shl 9) or
            ((pkt[off + 9].toLong() and 0xFF) shl 1) or
            ((pkt[off + 10].toLong() and 0xFF) ushr 7)
        if (awaiting) {
            awaiting = false
            val expected = (lastPcr90k + (nowMs - lastWallMs) * 90) and MASK_33
            val deltaMs = signed33(base - expected) / 90
            if (abs(deltaMs) > jumpThresholdMs) pendingJump = deltaMs
        }
        lastPcr90k = base
        lastWallMs = nowMs
        hasLast = true
    }

    override fun markReconnect(nowMs: Long) {
        pendingJump = NO_JUMP
        awaiting = hasLast
    }

    override fun pendingJumpMs(): Long? = if (pendingJump == NO_JUMP) null else pendingJump

    override fun awaitingPcr(): Boolean = awaiting

    companion object {
        /** A post-reconnect PCR further than this from the extrapolated value is a jump (Appendix B). */
        const val PCR_JUMP_REPREPARE_MS = 2_000L

        /** adaptation_field_length must cover the flags byte and the 6-byte PCR. */
        private const val PCR_AF_MIN_LENGTH = 7
        private const val MASK_33 = (1L shl 33) - 1
        private const val NO_JUMP = Long.MIN_VALUE

        /** Signed value of a 33-bit difference (two's complement on 33 bits). */
        fun signed33(diff: Long): Long {
            val m = diff and MASK_33
            return if (m >= (1L shl 32)) m - (1L shl 33) else m
        }
    }
}
