package app.thebox.core.xmltv

import app.thebox.core.xmltv.XmltvProgramme.Companion.NONE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EpisodeNumTest {
    private data class Ep(val season: Int, val episode: Int, val part: Int, val total: Int, val label: String?, val series: String?)

    private fun of(vararg nums: Pair<String?, String>): Ep {
        val acc = EpisodeNum.Accumulator()
        acc.reset()
        for ((system, text) in nums) acc.add(system, text)
        val p = XmltvProgramme()
        acc.applyTo(p)
        return Ep(p.season, p.episode, p.part, p.partTotal, p.episodeLabel, p.seriesRef)
    }

    @Test
    fun xmltvNs() {
        assertEquals(Ep(3, 10, 1, 2, "S3 E10 (Part 1/2)", null), of("xmltv_ns" to "2.9.0/2"))
        assertEquals(Ep(NONE, 10, NONE, NONE, "E10", null), of("xmltv_ns" to ".9."))
        assertEquals(Ep(1, 1, NONE, NONE, "S1 E1", null), of("xmltv_ns" to "0.0."))
        assertEquals(Ep(2, 5, NONE, NONE, "S2 E5", null), of("xmltv_ns" to "1/3.4/10."))
        assertEquals(Ep(3, 10, NONE, NONE, "S3 E10", null), of("xmltv_ns" to " 2 . 9 . "))
        assertEquals(Ep(NONE, NONE, NONE, NONE, null, null), of("xmltv_ns" to ".."))
        assertEquals(Ep(4, NONE, NONE, NONE, "S4", null), of("xmltv_ns" to "3.."))
    }

    @Test
    fun onscreenAndSxxExx() {
        assertEquals(Ep(3, 10, NONE, NONE, "S03E10", null), of("onscreen" to "S03E10"))
        assertEquals(Ep(3, 10, NONE, NONE, "s3 x 10", null), of("onscreen" to "s3 x 10"))
        assertEquals(Ep(NONE, 5, NONE, NONE, "Ep. 5", null), of("onscreen" to "Ep. 5"))
        assertEquals(Ep(NONE, 12, NONE, NONE, "Episode 12", null), of("onscreen" to "Episode 12"))
        assertEquals(Ep(1, 4, NONE, NONE, "S01E04", null), of("SxxExx" to "S01E04"))
        assertEquals(Ep(NONE, NONE, NONE, NONE, "Pilot", null), of("onscreen" to "Pilot"))
    }

    @Test
    fun ddProgid() {
        assertEquals(Ep(NONE, NONE, NONE, NONE, null, "EP01234567"), of("dd_progid" to "EP01234567.0012"))
        assertEquals("SH0001", EpisodeNum.seriesRef("SH0001"))
        assertNull(EpisodeNum.seriesRef("  "))
    }

    @Test
    fun precedenceNsBeatsOnscreenButLabelIsOnscreen() {
        assertEquals(Ep(5, 2, NONE, NONE, "S6E3", "SH0001"), of("dd_progid" to "SH0001.0002", "onscreen" to "S6E3", "xmltv_ns" to "4.1."))
        assertEquals(Ep(5, 2, NONE, NONE, "S6E3", null), of("xmltv_ns" to "4.1.", "onscreen" to "S6E3"))
    }

    @Test
    fun unknownSystemsIgnored() {
        assertEquals(Ep(NONE, NONE, NONE, NONE, null, null), of("thetvdb.com" to "12345", "imdb.com" to "tt0000"))
        assertFalse(EpisodeNum.parseOnscreen("Season finale", IntArray(2)))
    }

    @Test
    fun noSystemAttribute() {
        assertEquals(Ep(3, 10, NONE, NONE, "S3 E10", null), of(null to "2.9."))
        assertEquals(Ep(1, 2, NONE, NONE, "S01E02", null), of(null to "S01E02"))
    }
}
