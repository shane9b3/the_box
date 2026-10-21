package app.thebox.data.art

import app.thebox.data.di.WallClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TitleNormaliserAndCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun broadcastMarkersAreStripped() {
        val table = listOf(
            "Doctor Who" to "Doctor Who",
            "New: Doctor Who" to "Doctor Who",
            "NEW - Doctor Who (2023)" to "Doctor Who",
            "Heat (R)" to "Heat",
            "Heat (1995)" to "Heat",
            "Casualty - S38 E12" to "Casualty",
            "Casualty: Episode 4" to "Casualty",
            "Taskmaster - Series 16" to "Taskmaster",
            "Doctor Who: The Giggle" to "Doctor Who: The Giggle",
            "  The   Bear  " to "The Bear",
            "Film: Heat" to "Heat",
            "Live: Match of the Day" to "Match of the Day",
        )
        for ((raw, clean) in table) assertEquals(raw, clean, TitleNormaliser.clean(raw))
        assertNull(TitleNormaliser.clean(" "))
        assertNull(TitleNormaliser.clean("(R)"))
        assertEquals("doctor who", TitleNormaliser.key("Doctor Who"))
    }

    @Test fun headIsThePartBeforeASubtitleSeparator() {
        assertEquals("Doctor Who", TitleNormaliser.head("Doctor Who: The Giggle"))
        assertEquals("Doctor Who", TitleNormaliser.head("Doctor Who - The Giggle"))
        assertEquals("Doctor Who", TitleNormaliser.head("Doctor Who – The Giggle"))
        assertNull(TitleNormaliser.head("Doctor Who"))
        assertNull(TitleNormaliser.head("Spider-Man"))
        assertNull(TitleNormaliser.head("A: B"))
    }

    @Test fun cacheKeepsHitsLongerThanMissesAndSurvivesAReload() {
        var now = 0L
        val file = File(tmp.root, "c.json")
        val c = ArtworkCache(file, { now }, max = 3)
        c.put("a", "/a.jpg")
        c.put("b", "")
        assertEquals("/a.jpg", c.get("a"))
        assertEquals("", c.get("b"))
        now += ArtworkCache.NEGATIVE_TTL_MS + 1
        assertNull(c.get("b"))
        assertEquals("/a.jpg", c.get("a"))
        now += ArtworkCache.POSITIVE_TTL_MS
        assertNull(c.get("a"))

        now = 0
        val d = ArtworkCache(file, { now }, max = 3)
        d.put("x", "/x")
        d.put("y", "/y")
        d.put("z", "/z")
        d.get("x")
        d.put("w", "/w")
        val e = ArtworkCache(file, { now }, max = 3)
        assertNull(e.get("y"))
        assertEquals("/x", e.get("x"))
        assertEquals("/w", e.get("w"))
        assertEquals(3, e.size)

        file.writeText("not json")
        val f = ArtworkCache(file, { now }, max = 3)
        assertEquals(0, f.size)
        f.put("q", "/q")
        assertEquals("/q", ArtworkCache(file, { now }, max = 3).get("q"))
    }

    @Test fun v4TokensAreRecognised() {
        assertEquals(true, TmdbProgrammeArtwork.isV4Token("eyJhbGciOiJIUzI1NiJ9.x.y"))
        assertEquals(false, TmdbProgrammeArtwork.isV4Token("0123456789abcdef0123456789abcdef"))
    }
}
