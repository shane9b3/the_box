package app.thebox.core.m3u

import app.thebox.core.source.EntryKind
import app.thebox.core.source.EntryKind.LIVE
import app.thebox.core.source.EntryKind.MOVIE
import app.thebox.core.source.EntryKind.RADIO
import app.thebox.core.source.EntryKind.SERIES
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntryClassifierTest {
    private fun k(url: String, tvgType: String? = null, radio: String? = null, group: String? = null, name: String = "x"): EntryKind =
        EntryClassifier.classify(tvgType, radio, url, group, name)

    @Test fun `M19 order - tvg-type first`() {
        assertEquals(MOVIE, k("http://h/live/u/p/1.ts", tvgType = "movie"))
        assertEquals(SERIES, k("http://h/x.ts", tvgType = "SERIES"))
        assertEquals(RADIO, k("http://h/x.mp4", tvgType = "radio"))
        assertEquals(LIVE, k("http://h/x.mp4", tvgType = "live"))
    }

    @Test fun `M19 radio flag`() {
        assertEquals(RADIO, k("http://h/a.mp3", radio = "true"))
        assertEquals(RADIO, k("http://h/a.mp3", radio = "1"))
        assertEquals(LIVE, k("http://h/a.mp3", radio = "false"))
    }

    @Test fun `M19 Xtream URL kind, then path, then extension`() {
        assertEquals(LIVE, k("http://h:8080/live/u/p/101.ts"))
        assertEquals(MOVIE, k("http://h:8080/movie/u/p/2001.mkv"))
        assertEquals(SERIES, k("http://h:8080/series/u/p/3001.mp4"))
        assertEquals(MOVIE, k("http://cdn/vod/movie/abc/file.m3u8"))
        assertEquals(SERIES, k("http://cdn/media/series/x/ep1.m3u8"))
        assertEquals(MOVIE, k("http://cdn/films/Inception.MKV?token=1"))
    }

    @Test fun `M19 series heuristic applies only with a VOD extension`() {
        assertEquals(SERIES, k("http://cdn/f/ep.mp4", group = "TV Shows"))
        assertEquals(SERIES, k("http://cdn/f/ep.mp4", name = "Show S01E02"))
        assertEquals(SERIES, k("http://cdn/f/ep.mp4", name = "Show s1 e2"))
        assertEquals(MOVIE, k("http://cdn/f/ep.mp4", name = "Seasoned Chef"))
        assertEquals(LIVE, k("http://cdn/f/ep.m3u8", group = "Series"))
    }

    @ParameterizedTest
    @CsvSource(
        "Breaking Bad S01E01, true", "breaking bad s1e10, true", "S01 E01, true", "Season 2, true", "My TV Show, true",
        "TV Shows, true", "Series, true", "Miniseries, false", "S100E1, false", "Classic Movies, false", "S01E1234, false",
    )
    fun `series regex equivalent`(text: String, expected: Boolean) {
        val regex = Regex("""(?i)\b(series|tv shows?|season)\b|\bs\d{1,2}\s?e\d{1,3}\b""")
        assertEquals(regex.containsMatchIn(text), expected, "fixture expectation disagrees with the spec regex")
        assertEquals(expected, EntryClassifier.looksLikeSeries(text))
    }

    @Test fun `extension extraction ignores query and directories`() {
        assertEquals("mkv", EntryClassifier.extension("http://h/a.b/c.MKV?x=1.mp4"))
        assertEquals(null, EntryClassifier.extension("http://h/a.b/c"))
        assertTrue(EntryClassifier.looksLikeSeries("x S1E1"))
        assertFalse(EntryClassifier.looksLikeSeries(null))
    }
}
