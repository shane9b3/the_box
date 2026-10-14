package app.thebox.core.playback.ts

import app.thebox.core.playback.TsPacketAligner

/**
 * Sync search and whole-packet delivery, CC gap after resync, post-splice hold (§3.3.4; reviewed 2026-10-13, B1/S1).
 *
 * One linear buffer, compacted in place: `[readPos, readLimit)` readable packets, `[readLimit, scanPos)` validated
 * packets held back after a [resync], `[scanPos, writePos)` bytes not yet validated (a partial packet, or the bytes
 * of a sync search). The buffer starts at [initialCapacity] and only grows when a caller writes more than fits (a
 * 16 KiB read chunk, or a post-splice hold of up to 256 KiB); growth is amortised, never per call.
 *
 * Continuity counters are rewritten when a packet becomes **readable** (published), not when it is validated, so
 * packets dropped by [dropHeld]/[resync] never advance the per-PID "last emitted CC" the next gap is computed from.
 * [onPacket] sees every validated packet once, held or not, before its CC is rewritten (the PCR tracker needs only
 * the adaptation field). Not thread-safe: one loader thread.
 */
class TsPacketAlignerImpl(
    initialCapacity: Int = ALIGN_RING_PACKETS * TS_PACKET_SIZE,
    private val onPacket: PacketListener? = null,
) : TsPacketAligner {

    /** Called for each validated whole packet at [off] in [buf]. Must not keep [buf]. */
    fun interface PacketListener { fun onPacket(buf: ByteArray, off: Int) }

    private var buf = ByteArray(maxOf(initialCapacity, TS_PACKET_SIZE * TS_SYNC_RUN))
    private var readPos = 0
    private var readLimit = 0
    private var scanPos = 0
    private var writePos = 0
    private var locked = false
    private var holding = false

    /** Last CC published per PID with a payload, or -1 when the PID was never published. */
    private val lastCc = ByteArray(PID_COUNT) { -1 }
    /** CC offset applied to every published packet of a PID since its last forced gap (mod 16). */
    private val ccOffset = ByteArray(PID_COUNT)
    private val gapPending = BooleanArray(PID_COUNT)

    /** Bytes discarded by sync searches since construction (diagnostics). */
    var bytesSkipped: Long = 0
        private set

    override fun write(src: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return available()
        ensureCapacity(len)
        System.arraycopy(src, off, buf, writePos, len)
        writePos += len
        scan()
        return available()
    }

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        val avail = readLimit - readPos
        if (avail <= 0 || len <= 0) return 0
        val n = if (len >= TS_PACKET_SIZE) minOf(avail, len / TS_PACKET_SIZE * TS_PACKET_SIZE) else minOf(avail, len)
        System.arraycopy(buf, readPos, dst, off, n)
        readPos += n
        if (readPos == writePos) { readPos = 0; readLimit = 0; scanPos = 0; writePos = 0 }
        return n
    }

    override fun available(): Int = readLimit - readPos

    override fun resync(): Int {
        val dropped = writePos - readLimit
        writePos = readLimit
        scanPos = readLimit
        locked = false
        holding = true
        for (pid in 0 until PID_COUNT) if (lastCc[pid] >= 0) gapPending[pid] = true
        return dropped
    }

    override fun heldBytes(): Int = if (holding) writePos - readLimit else 0

    override fun releaseHeld() {
        var p = readLimit
        while (p < scanPos) { publish(p); p += TS_PACKET_SIZE }
        readLimit = scanPos
        holding = false
    }

    override fun dropHeld() {
        val n = scanPos - readLimit
        if (n > 0) {
            System.arraycopy(buf, scanPos, buf, readLimit, writePos - scanPos)
            writePos -= n
            scanPos = readLimit
        }
        holding = false
    }

    // ------------------------------------------------------------------ internals

    private fun scan() {
        while (true) {
            if (!locked && !findSync()) return
            if (writePos - scanPos < TS_PACKET_SIZE) return
            if (buf[scanPos] != TS_SYNC_BYTE) { locked = false; continue }
            onPacket?.onPacket(buf, scanPos)
            if (!holding) publish(scanPos)
            scanPos += TS_PACKET_SIZE
            if (!holding) readLimit = scanPos
        }
    }

    /** Looks for a [TS_SYNC_RUN] run in `[scanPos, writePos)`; drops the bytes before it. */
    private fun findSync(): Boolean {
        val span = (TS_SYNC_RUN - 1) * TS_PACKET_SIZE
        var o = scanPos
        while (o + span < writePos) {
            var k = 0
            while (k < TS_SYNC_RUN && buf[o + k * TS_PACKET_SIZE] == TS_SYNC_BYTE) k++
            if (k == TS_SYNC_RUN) {
                removeUnscanned(o - scanPos)
                locked = true
                return true
            }
            o++
        }
        // No run yet: keep only the tail that could still start one.
        removeUnscanned(maxOf(0, writePos - scanPos - span))
        return false
    }

    /** Removes [n] bytes at [scanPos]. */
    private fun removeUnscanned(n: Int) {
        if (n <= 0) return
        System.arraycopy(buf, scanPos + n, buf, scanPos, writePos - scanPos - n)
        writePos -= n
        bytesSkipped += n
    }

    /** Applies the per-PID CC offset (forcing a +2 gap on the first payload packet after a resync). */
    private fun publish(pos: Int) {
        val pid = ((buf[pos + 1].toInt() and 0x1F) shl 8) or (buf[pos + 2].toInt() and 0xFF)
        if (pid == NULL_PID) return
        val b3 = buf[pos + 3].toInt()
        val hasPayload = (b3 and 0x10) != 0
        val cc = b3 and 0x0F
        val newCc: Int
        if (hasPayload && gapPending[pid]) {
            newCc = (lastCc[pid] + 2) and 0x0F
            ccOffset[pid] = ((newCc - cc) and 0x0F).toByte()
            gapPending[pid] = false
        } else {
            newCc = (cc + ccOffset[pid]) and 0x0F
        }
        if (newCc != cc) buf[pos + 3] = ((b3 and 0xF0) or newCc).toByte()
        if (hasPayload) lastCc[pid] = newCc.toByte()
    }

    private fun ensureCapacity(len: Int) {
        if (writePos + len <= buf.size) return
        val live = writePos - readPos
        val target = if (live + len <= buf.size) buf else ByteArray(maxOf(buf.size * 2, live + len))
        System.arraycopy(buf, readPos, target, 0, live)
        buf = target
        readLimit -= readPos
        scanPos -= readPos
        writePos -= readPos
        readPos = 0
    }

    companion object {
        const val TS_PACKET_SIZE = StreamSnifferImpl.TS_PACKET_SIZE
        const val TS_SYNC_BYTE = StreamSnifferImpl.TS_SYNC_BYTE
        const val TS_SYNC_RUN = StreamSnifferImpl.TS_SYNC_RUN

        /** Initial buffer in packets (Appendix B; 12,032 bytes). Grows for larger writes and the post-splice hold. */
        const val ALIGN_RING_PACKETS = 64

        private const val PID_COUNT = 8192
        private const val NULL_PID = 0x1FFF
    }
}
