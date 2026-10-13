package app.thebox.core.match

import app.thebox.core.text.NameNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimilarityTest {
    private fun sim(a: String, b: String) = Similarity.sim(NameNormalizer.forMatch(a), NameNormalizer.forMatch(b))

    @Test
    fun digitGuard() {
        assertEquals(0f, sim("Sky Sports 1", "Sky Sports 2"))
        assertEquals(0f, sim("Channel 4", "Channel 4 +1"))
        assertEquals(0f, sim("Channel 4 +1", "Channel 4"))
        assertEquals(0f, sim("ESPN", "ESPN 2"))
        assertEquals(1f, sim("Sky Sports 1 HD", "Sky Sports 1"))
        assertEquals(1f, sim("Channel 4 +1", "channel 4 +1"))
    }

    @Test
    fun digitSignatures() {
        assertEquals("", Similarity.digitSignature("bbc one"))
        assertEquals("1", Similarity.digitSignature("sky sports 1"))
        assertEquals("+1,1,4", Similarity.digitSignature("channel 4 +1"))
        assertEquals("1", Similarity.digitSignature("sky sports 01"))
        assertEquals("2,24", Similarity.digitSignature("24 kitchen 2"))
    }

    @Test
    fun jaroWinklerReferenceValues() {
        // Classic reference pairs (Winkler 1990).
        assertEquals(0.961f, Similarity.jaroWinkler("martha", "marhta"), 0.001f)
        assertEquals(0.840f, Similarity.jaroWinkler("dwayne", "duane"), 0.001f)
        assertEquals(0.813f, Similarity.jaroWinkler("dixon", "dicksonx"), 0.001f)
        assertEquals(1f, Similarity.jaroWinkler("abc", "abc"))
        assertEquals(0f, Similarity.jaroWinkler("abc", ""))
        assertEquals(0f, Similarity.jaroWinkler("abc", "xyz"))
    }

    @Test
    fun levenshteinAndRatio() {
        assertEquals(3, Similarity.levenshtein("kitten", "sitting"))
        assertEquals(0, Similarity.levenshtein("a", "a"))
        assertEquals(4, Similarity.levenshtein("", "abcd"))
        assertEquals(1f, Similarity.ratio("", ""))
        assertEquals(0f, Similarity.ratio("ab", "cd"))
        assertEquals(0.5f, Similarity.ratio("ab", "ac"))
    }

    @Test
    fun tokenSetRatioIgnoresOrderAndExtras() {
        val a = arrayOf("discovery", "science").sortedArray()
        val b = arrayOf("science", "discovery").sortedArray()
        assertEquals(1f, Similarity.tokenSetRatio(a, b))
        // Subset: "bbc one" vs "bbc london one" → the intersection equals RA → 1.0.
        assertEquals(1f, Similarity.tokenSetRatio(arrayOf("bbc", "one"), arrayOf("bbc", "london", "one")))
        assertTrue(Similarity.tokenSetRatio(arrayOf("abc"), arrayOf("xyz")) < 0.1f)
    }

    @Test
    fun countryPenalty() {
        val a = Similarity.prepare("bbc one", "uk")
        val b = Similarity.prepare("bbc one", "de")
        val c = Similarity.prepare("bbc one", null)
        assertEquals(Similarity.COUNTRY_PENALTY, Similarity.sim(a, b), 0.0001f)
        assertEquals(1f, Similarity.sim(a, c))
    }

    @Test
    fun typosScoreHigh() {
        assertTrue(sim("Discovery Channel", "Discovery Chanel") >= 0.92f)
        assertTrue(sim("National Geographic", "Nat Geo") < 0.92f)
        assertTrue(sim("Eurosport", "Eurosport News") >= 0.75f)
    }
}
