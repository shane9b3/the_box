package app.thebox.core.identity

import app.thebox.core.text.NameNormalizer
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** [FastNames] must equal [NameNormalizer] exactly: its output is persisted in stable keys and group keys. */
class FastNamesParityTest {
    private fun check(s: String) {
        assertEquals(NameNormalizer.forKey(s), FastNames.forKey(s), "forKey(\"$s\")")
        assertEquals(NameNormalizer.forGroup(s), FastNames.forGroup(s), "forGroup(\"$s\")")
        // Code review M1 S-3: norm_name / matcher fast path.
        assertEquals(NameNormalizer.forMatchRegex(s), FastNames.forMatch(s), "forMatch(\"$s\")")
        assertEquals(NameNormalizer.forMatchRegex(s), NameNormalizer.forMatch(s), "forMatch(\"$s\") public")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "BBC One HD", "BBC One FHD ᴴᴰ", "UK: BBC One (Backup)", "HBO (East)", "Channel 4 +1", "Télé-Québec", "|DE| Das Erste 4K", "HD",
            "", " ", "  hd  ", "---", "+", "++1", "HD HD", "hdhd", "hd.hd", "h.264 x265 HEVC", "Movie 1080p 1080i 720p 576i 2160p",
            "Sports 50 fps 50fps 60 Fps", "HDR10+ HDR10+x hdr10 hdr hlg", "VIP raw Backup", "\tTab\tName\t", "SD-HD", "4k8k 4K 8K",
            "x264x265", "Fox News (US) [backup] {raw}", "A&E", "100% Music", "N.C.I.S.", "UHD Sport", "uhd_sport", "Sky_Sports_1",
            "h264", "h.26", "h.2645", "1080 p", "fhd+", "hd+1", "+hd", "1hd", "hd1", "MTV\u0001Live", "\u001f", "Channel 5 (+1) HD",
        ],
    )
    fun `hand-picked names`(s: String) = check(s)

    @Test fun `50k random ASCII names built from tricky parts`() {
        val parts = listOf(
            "HD", "hd", "FHD", "uhd", "sd", "4K", "8k", "HEVC", "h.264", "H265", "x264", "avc", "2160p", "1080i", "720P", "576p", "50 fps",
            "60fps", "HDR10+", "hdr", "hlg", "backup", "VIP", "raw", "BBC", "One", "+1", "(", ")", "[", "]", "|", ":", "-", "_", ".", ",",
            " ", "  ", "\t", "1", "22", "a", "Z", "&", "'", "\"", "/", "+", "fps", "50", "h", ".", "26", "4",
            // forMatch: country prefixes (ISO, uk, non-country) and every bracket kind.
            "UK", "uk:", "|DE|", "de", "XX", "xx:", "US - ", "fr |", "{", "}", "(East)", "[VIP]", "\n", "\u000B",
        )
        val rnd = Random(2026)
        repeat(50_000) {
            val sb = StringBuilder()
            repeat(rnd.nextInt(1, 8)) { sb.append(parts[rnd.nextInt(parts.size)]) }
            check(sb.toString())
        }
    }

    @Test fun `random printable ASCII`() {
        val rnd = Random(7)
        repeat(20_000) {
            val s = String(CharArray(rnd.nextInt(0, 20)) { (32 + rnd.nextInt(95)).toChar() })
            check(s)
        }
    }
}
