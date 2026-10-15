package app.thebox.ui.full

/**
 * Zero-allocation time labels for draw lambdas (docs/design/m4-player-ux.md §4.2): digits are written into a reused
 * [CharArray]. Every function returns the index after the last char written. Main thread (or any single thread).
 */
object TimeChars {
    /** U+2212 MINUS SIGN, as the design writes `−00:17:50`. */
    const val MINUS = '−'

    /**
     * Wall-clock time of [epochMs] shifted by [offsetMs] (the zone offset at that instant): `HH:mm` (24 h, two-digit
     * hours) or `h:mm` (12 h, no leading zero, no AM/PM like the guide's labels), plus `:ss` when [seconds].
     */
    fun clock(buf: CharArray, at: Int, epochMs: Long, offsetMs: Int, clock24: Boolean, seconds: Boolean): Int {
        val local = epochMs + offsetMs
        val daySec = Math.floorMod(Math.floorDiv(local, 1000L), 86_400L).toInt()
        var h = daySec / 3600
        val m = daySec / 60 % 60
        val s = daySec % 60
        var i = at
        if (clock24) {
            i = two(buf, i, h)
        } else {
            h %= 12
            if (h == 0) h = 12
            if (h >= 10) buf[i++] = '1'
            buf[i++] = ('0'.code + h % 10).toChar()
        }
        buf[i++] = ':'
        i = two(buf, i, m)
        if (seconds) {
            buf[i++] = ':'
            i = two(buf, i, s)
        }
        return i
    }

    /** `−MM:SS` below an hour, `−H:MM:SS` from an hour on (§3.2.3); negative input is written as zero. */
    fun behind(buf: CharArray, at: Int, ms: Long): Int {
        val total = (ms.coerceAtLeast(0) / 1000L)
        val h = total / 3600
        val m = (total / 60 % 60).toInt()
        val s = (total % 60).toInt()
        var i = at
        buf[i++] = MINUS
        if (h > 0) {
            i = number(buf, i, h)
            buf[i++] = ':'
        }
        i = two(buf, i, m)
        buf[i++] = ':'
        return two(buf, i, s)
    }

    /** Copies [src] into [buf] at [at]. */
    fun copy(buf: CharArray, at: Int, src: CharArray): Int {
        System.arraycopy(src, 0, buf, at, src.size)
        return at + src.size
    }

    private fun two(buf: CharArray, at: Int, v: Int): Int {
        buf[at] = ('0'.code + v / 10 % 10).toChar()
        buf[at + 1] = ('0'.code + v % 10).toChar()
        return at + 2
    }

    private fun number(buf: CharArray, at: Int, v: Long): Int {
        var digits = 1
        var p = 10L
        while (v >= p && digits < 9) { digits++; p *= 10 }
        var x = v
        for (k in digits - 1 downTo 0) {
            buf[at + k] = ('0'.code + (x % 10).toInt()).toChar()
            x /= 10
        }
        return at + digits
    }
}
