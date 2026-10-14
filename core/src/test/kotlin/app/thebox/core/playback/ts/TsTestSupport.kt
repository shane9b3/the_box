package app.thebox.core.playback.ts

/** Byte fixtures (`core/src/test/resources/{ts,http}`, see the README there) and synthetic TS packets. */
internal object TsTestSupport {
    const val PKT = 188

    fun res(path: String): ByteArray =
        TsTestSupport::class.java.getResourceAsStream("/$path")?.use { it.readBytes() } ?: error("missing fixture $path")

    fun pid(b: ByteArray, off: Int): Int = ((b[off + 1].toInt() and 0x1F) shl 8) or (b[off + 2].toInt() and 0xFF)
    fun cc(b: ByteArray, off: Int): Int = b[off + 3].toInt() and 0x0F
    fun hasPayload(b: ByteArray, off: Int): Boolean = (b[off + 3].toInt() and 0x10) != 0

    /** PCR base (90 kHz) of the packet at [off], or null. */
    fun pcr(b: ByteArray, off: Int): Long? {
        if ((b[off + 3].toInt() and 0x20) == 0 || (b[off + 4].toInt() and 0xFF) < 7 || (b[off + 5].toInt() and 0x10) == 0) return null
        return ((b[off + 6].toLong() and 0xFF) shl 25) or ((b[off + 7].toLong() and 0xFF) shl 17) or
            ((b[off + 8].toLong() and 0xFF) shl 9) or ((b[off + 9].toLong() and 0xFF) shl 1) or ((b[off + 10].toLong() and 0xFF) ushr 7)
    }

    /** One synthetic packet. With [pcr90k], an adaptation field carrying a PCR (base only, extension 0). */
    fun packet(pid: Int, cc: Int, payload: Boolean = true, pcr90k: Long? = null, fill: Int = 0xA5): ByteArray {
        val p = ByteArray(PKT) { fill.toByte() }
        p[0] = 0x47
        p[1] = ((pid shr 8) and 0x1F).toByte()
        p[2] = (pid and 0xFF).toByte()
        val afc = when {
            pcr90k != null && payload -> 0x30
            pcr90k != null -> 0x20
            payload -> 0x10
            else -> 0x20
        }
        p[3] = (afc or (cc and 0x0F)).toByte()
        if (pcr90k != null) {
            p[4] = (if (payload) 7 else 183).toByte()
            p[5] = 0x10
            val base = pcr90k and ((1L shl 33) - 1)
            p[6] = (base ushr 25).toByte()
            p[7] = (base ushr 17).toByte()
            p[8] = (base ushr 9).toByte()
            p[9] = (base ushr 1).toByte()
            p[10] = (((base and 1) shl 7) or 0x7E).toByte()
            p[11] = 0
        } else if (!payload) {
            p[4] = 183.toByte()
            p[5] = 0
        }
        return p
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }

    /** Drains everything readable from [a] with reads of [len]. */
    fun drain(a: TsPacketAlignerImpl, len: Int = 4096): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(len)
        while (true) {
            val n = a.read(buf, 0, len)
            if (n <= 0) return out.toByteArray()
            out.write(buf, 0, n)
        }
    }

    /** Copy of [ts] (whole packets) with every PID's CCs restarted at [restart] (payload packets advance the CC). */
    fun restartCcs(ts: ByteArray, restart: Int): ByteArray {
        val out = ts.copyOf()
        val next = HashMap<Int, Int>()
        var off = 0
        while (off + PKT <= out.size) {
            val pid = pid(out, off)
            if (pid != 0x1FFF) {
                val payload = hasPayload(out, off)
                val c = next.getOrPut(pid) { restart and 0x0F }
                // An adaptation-only packet repeats the CC of the last payload packet.
                val use = if (payload) c else (c - 1) and 0x0F
                out[off + 3] = ((out[off + 3].toInt() and 0xF0) or use).toByte()
                if (payload) next[pid] = (c + 1) and 0x0F
            }
            off += PKT
        }
        return out
    }
}
