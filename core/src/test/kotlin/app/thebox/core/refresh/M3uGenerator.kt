package app.thebox.core.refresh

import okio.Buffer
import okio.Source
import okio.Timeout
import kotlin.random.Random

/**
 * Streaming M3U generator for the §5.5 perf tests: `M3uGenerator(entries = 200_000, vodShare = 0.6, seed)`.
 * Realistic names and groups, `tvg-id` on 70 %, Xtream-shaped URLs on 50 %. The text is produced on
 * demand, so a 200k-entry list never sits in memory; the same seed always yields the same bytes.
 */
class M3uGenerator(
    private val entries: Int = 200_000,
    private val vodShare: Double = 0.6,
    private val seed: Long = 1,
) {
    fun source(): Source = Gen()

    private inner class Gen : Source {
        private val rnd = Random(seed)
        private var i = -1
        private val sb = StringBuilder(512)

        private val pending = Buffer()

        override fun read(sink: Buffer, byteCount: Long): Long {
            while (pending.size < byteCount && i < entries) {
                sb.setLength(0)
                if (i < 0) sb.append("#EXTM3U x-tvg-url=\"http://epg.example.com/guide.xml.gz\"\n") else entry(i, sb)
                i++
                pending.writeUtf8(sb.toString())
            }
            if (pending.size == 0L) return -1
            return pending.read(sink, byteCount)
        }

        private fun entry(n: Int, sb: StringBuilder) {
            val vod = rnd.nextDouble() < vodShare
            val series = vod && rnd.nextInt(3) == 0
            val xc = rnd.nextBoolean()
            val country = COUNTRIES[rnd.nextInt(COUNTRIES.size)]
            val name = if (vod) {
                val title = WORDS[rnd.nextInt(WORDS.size)] + " " + WORDS[rnd.nextInt(WORDS.size)] + " " + (1950 + rnd.nextInt(75))
                if (series) "$title S0" + (1 + rnd.nextInt(9)) + "E" + (10 + rnd.nextInt(14)) else "$title ($n)"
            } else {
                "$country: " + BRANDS[rnd.nextInt(BRANDS.size)] + " " + (1 + rnd.nextInt(40)) + QUALITY[rnd.nextInt(QUALITY.size)] + " #" + n
            }
            val group = if (vod) (if (series) "Series | " else "VOD | ") + GENRES[rnd.nextInt(GENRES.size)] else "$country | " + GENRES[rnd.nextInt(GENRES.size)]
            sb.append("#EXTINF:-1")
            if (rnd.nextInt(10) < 7) sb.append(" tvg-id=\"").append(BRANDS[rnd.nextInt(BRANDS.size)].replace(" ", "")).append(n).append('.').append(country.lowercase()).append("@SD\"")
            sb.append(" tvg-name=\"").append(name).append('"')
            sb.append(" tvg-logo=\"http://logo.example.com/img/").append(n % 5000).append(".png\"")
            if (vod) sb.append(" tvg-type=\"").append(if (series) "series" else "movie").append('"')
            sb.append(" group-title=\"").append(group).append('"')
            if (!vod && rnd.nextInt(5) == 0) sb.append(" catchup=\"default\" catchup-days=\"7\"")
            sb.append(',').append(name).append('\n')
            if (!vod && rnd.nextInt(20) == 0) sb.append("#EXTVLCOPT:http-user-agent=Mozilla/5.0 (Linux; Android 9)\n")
            when {
                xc && series -> sb.append("http://line.example.tv:8080/series/user77/pw88/").append(300_000 + n).append(".mp4")
                xc && vod -> sb.append("http://line.example.tv:8080/movie/user77/pw88/").append(200_000 + n).append(".mkv")
                xc -> sb.append("http://line.example.tv:8080/live/user77/pw88/").append(n).append(".ts")
                vod -> sb.append("http://vod").append(n % 7).append(".cdn.example.com/films/").append(n).append(if (series) ".mp4" else ".mkv")
                else -> sb.append("http://lb").append(n % 9).append(".cdn.example.com/hls/ch").append(n).append("/index.m3u8?token=").append(rnd.nextLong(1L shl 40))
            }
            sb.append('\n')
        }

        override fun timeout(): Timeout = Timeout.NONE
        override fun close() {}
    }

    private companion object {
        val COUNTRIES = arrayOf("UK", "US", "DE", "FR", "IT", "ES", "NL", "PL", "TR", "AR", "IN", "PT", "SE", "CA")
        val BRANDS = arrayOf("BBC", "Sky Sports", "ITV", "Canal Plus", "Rai", "ZDF", "ESPN", "Fox", "beIN Sports", "Discovery", "Nat Geo", "MTV", "CNN", "TF1")
        val QUALITY = arrayOf("", " HD", " FHD", " 4K", " SD", " HEVC")
        val GENRES = arrayOf("News", "Sports", "Movies", "Kids", "Music", "Documentary", "Entertainment", "Drama", "Comedy", "Action", "Horror", "Sci-Fi")
        val WORDS = arrayOf("Last", "Night", "Dark", "Star", "River", "Iron", "Silent", "Golden", "Lost", "City", "Storm", "Empire", "Ghost", "Blue", "Wild", "Secret")
    }
}
