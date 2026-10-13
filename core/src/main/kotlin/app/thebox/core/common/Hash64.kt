package app.thebox.core.common

/**
 * FNV-1a 64-bit over UTF-16 code units (low byte, then high byte), with a separator byte after
 * every field so ("ab","c") != ("a","bc"). Stable across app versions: it is persisted
 * (channel.content_hash, epg_text.hash, reminder.title_hash). Never change the algorithm without a
 * migration that recomputes stored hashes.
 */
object Hash64 {
    const val OFFSET: Long = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    const val PRIME: Long = 0x100000001b3L

    fun of(s: String?): Long = str(OFFSET, s)

    fun str(h: Long, s: String?): Long {
        var x = h
        if (s == null) return (x xor 0xFEL) * PRIME
        for (i in s.indices) {
            val c = s[i].code
            x = (x xor (c and 0xFF).toLong()) * PRIME
            x = (x xor (c ushr 8).toLong()) * PRIME
        }
        return (x xor 0xFFL) * PRIME
    }

    fun long(h: Long, v: Long): Long {
        var x = h
        for (i in 0 until 8) x = (x xor ((v ushr (i * 8)) and 0xFFL)) * PRIME
        return x
    }

    fun int(h: Long, v: Int?): Long = if (v == null) str(h, null) else long(h, v.toLong())
    fun bool(h: Long, v: Boolean): Long = long(h, if (v) 1L else 0L)
    fun double(h: Long, v: Double?): Long = if (v == null) str(h, null) else long(h, v.toRawBits())

    /** Order-independent map hash (keys sorted). */
    fun map(h: Long, m: Map<String, String>): Long {
        var x = long(h, m.size.toLong())
        for (k in m.keys.sorted()) x = str(str(x, k), m[k])
        return x
    }
}
