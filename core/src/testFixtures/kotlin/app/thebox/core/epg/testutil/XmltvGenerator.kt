package app.thebox.core.epg.testutil

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.Random
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/**
 * Deterministic XMLTV generator for tests and perf runs (docs/design/m1-data-layer.md §5.5).
 *
 * `XmltvGenerator(50_000, 20_000, 7, 7)` writes 50k `<channel>` elements, then 49 programmes per channel
 * per week, grouped by channel: about 1M programmes on the [whitelisted] channels and about 1.5M on the
 * others. 15 % of titles are unique, the rest repeat (dedup). 5 % of programmes have no stop and 1 %
 * overlap the next one by 10 minutes. Streams to the file: memory use is constant.
 *
 * The first programme day starts at [startMs] (UTC midnight); the store's `nowMs` for a full window is
 * typically `startMs + 1 day` with `pastDays = 1`, `futureDays = days`.
 */
class XmltvGenerator(
    val channels: Int = 50_000,
    val whitelisted: Int = 20_000,
    val days: Int = 7,
    val perDay: Int = 7,
    val gzip: Boolean = true,
    val seed: Long = 1L,
    val startMs: Long = DEFAULT_START_MS,
) {
    init {
        require(whitelisted in 0..channels) { "whitelisted must be within 0..channels" }
        require(perDay in 1..48) { "perDay must be within 1..48" }
    }

    /** Statistics of the last [writeTo]. */
    var programmesWritten = 0L
        private set
    var whitelistedProgrammes = 0L
        private set

    fun channelId(index: Int): String = "ch$index.gen"

    /** Exactly [whitelisted] channels, spread evenly over the file. */
    fun isWhitelisted(index: Int): Boolean =
        (index.toLong() + 1) * whitelisted / channels != index.toLong() * whitelisted / channels

    fun whitelistedIds(): Set<String> = (0 until channels).filter(::isWhitelisted).mapTo(HashSet()) { channelId(it) }

    fun writeTo(file: File): File {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            val out: OutputStream = if (gzip) FastGzip(fos) else fos
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 1 shl 16).use { w -> write(w) }
        }
        return file
    }

    private fun write(w: Writer) {
        val rnd = Random(seed)
        programmesWritten = 0
        whitelistedProgrammes = 0
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"XmltvGenerator\">\n")
        for (c in 0 until channels) {
            w.write("  <channel id=\"")
            w.write(channelId(c))
            w.write("\">\n    <display-name lang=\"en\">Channel ")
            w.write(c.toString())
            w.write("</display-name>\n    <icon src=\"http://img.example/")
            w.write(c.toString())
            w.write(".png\"/>\n  </channel>\n")
        }
        val startMin = (startMs / 60_000L).toInt()
        val slot = 1440 / perDay
        val starts = IntArray(perDay + 1)
        val sb = StringBuilder(512)
        for (c in 0 until channels) {
            val id = channelId(c)
            val wl = isWhitelisted(c)
            for (d in 0 until days) {
                val day = startMin + d * 1440
                for (k in 0 until perDay) starts[k] = day + k * slot + (if (k == 0) 0 else (rnd.nextInt(7) - 3) * 5)
                starts[perDay] = day + 1440
                for (k in 0 until perDay) {
                    val s = starts[k]
                    var e = starts[k + 1]
                    val r = rnd.nextInt(100)
                    val noStop = r < 5
                    if (r == 5) e += 10 // 1 % overlap
                    sb.setLength(0)
                    sb.append("  <programme start=\"").append(time(s)).append('"')
                    if (!noStop) sb.append(" stop=\"").append(time(e)).append('"')
                    sb.append(" channel=\"").append(id).append("\">\n")
                    val unique = rnd.nextInt(100) < 15
                    val t = rnd.nextInt(TITLE_POOL)
                    if (unique) {
                        sb.append("    <title lang=\"en\">Special ").append(c).append('-').append(d).append('-').append(k).append("</title>\n")
                        sb.append("    <desc lang=\"en\">One-off programme ").append(c).append('/').append(d).append('/').append(k)
                            .append(" with a description of typical length for a guide entry.</desc>\n")
                    } else {
                        sb.append("    <title lang=\"en\">").append(WORDS[t % WORDS.size]).append(' ').append(WORDS[(t / WORDS.size) % WORDS.size])
                            .append(' ').append(t).append("</title>\n")
                        sb.append("    <desc lang=\"en\">Episode guide text number ").append(t)
                            .append(" describing the programme in a sentence or two, as most providers do.</desc>\n")
                        if (t % 3 == 0) sb.append("    <episode-num system=\"xmltv_ns\">").append(t % 9).append('.').append(t % 23).append(".</episode-num>\n")
                    }
                    sb.append("    <category lang=\"en\">").append(CATEGORIES[t % CATEGORIES.size]).append("</category>\n")
                    sb.append("  </programme>\n")
                    w.append(sb)
                    programmesWritten++
                    if (wl) whitelistedProgrammes++
                }
            }
        }
        w.write("</tv>\n")
    }

    private fun time(min: Int): CharSequence {
        val days = Math.floorDiv(min, 1440)
        val mod = min - days * 1440
        val ymd = civil(days.toLong())
        val sb = StringBuilder(20)
        sb.append(ymd)
        two(sb, mod / 60)
        two(sb, mod % 60)
        sb.append("00 +0000")
        return sb
    }

    private var cachedDay = Long.MIN_VALUE
    private var cachedYmd = ""

    /** "YYYYMMDD" for an epoch day (Hinnant's civil_from_days), cached for the last day. */
    private fun civil(epochDay: Long): String {
        if (epochDay == cachedDay) return cachedYmd
        val z = epochDay + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
        val sb = StringBuilder(8)
        sb.append(y)
        two(sb, m.toInt())
        two(sb, d.toInt())
        cachedDay = epochDay
        cachedYmd = sb.toString()
        return cachedYmd
    }

    private fun two(sb: StringBuilder, v: Int) {
        if (v < 10) sb.append('0')
        sb.append(v)
    }

    private class FastGzip(out: OutputStream) : GZIPOutputStream(out, 1 shl 16) {
        init {
            def.setLevel(Deflater.BEST_SPEED)
        }
    }

    companion object {
        /** 2026-10-13T00:00:00Z. */
        const val DEFAULT_START_MS = 1_791_849_600_000L
        private const val TITLE_POOL = 4_000
        private val WORDS = arrayOf(
            "News", "Today", "World", "Sport", "Kitchen", "Garden", "Mystery", "Comedy", "Drama", "Live",
            "Weekend", "Morning", "Evening", "Report", "Quiz", "Show", "Journey", "History", "Science", "Music",
        )
        private val CATEGORIES = arrayOf("News", "Sport", "Movie", "Series", "Documentary", "Kids", "Music", "Entertainment")
    }
}
