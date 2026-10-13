package app.thebox.core.match

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenIndexTest {
    @Test
    fun unionOfRareTokensSkipsCommonOnes() {
        val subjects = ArrayList<Array<String>>()
        repeat(100) { subjects += arrayOf("tv", "brand$it") }
        val index = TokenIndex(subjects)
        assertEquals(50, index.commonThreshold)
        assertTrue(index.isCommon("tv"))
        val out = IntArray(200)
        val n = index.candidates(arrayOf("brand7", "tv"), out)
        assertEquals(1, n)
        assertEquals(7, out[0])
    }

    @Test
    fun allCommonFallsBackToRarest() {
        val subjects = ArrayList<Array<String>>()
        repeat(200) { subjects += if (it < 60) arrayOf("news", "tv") else arrayOf("tv") }
        val index = TokenIndex(subjects)
        val out = IntArray(200)
        val n = index.candidates(arrayOf("news", "tv"), out)
        assertEquals(60, n)
        assertTrue((0 until n).all { out[it] < 60 })
    }

    @Test
    fun unknownTokenFallsBackToAffixes() {
        val subjects = ArrayList<Array<String>>()
        repeat(100) { subjects += arrayOf("tv", "brand$it") }
        subjects += arrayOf("discovery", "tv")
        val index = TokenIndex(subjects)
        val out = IntArray(200)
        // "discvery" (typo) is unknown: its prefix "dis" finds subject 100.
        assertEquals(1, index.candidates(arrayOf("discvery", "tv"), out))
        assertEquals(100, out[0])
    }

    @Test
    fun capAndDeduplication() {
        val subjects = ArrayList<Array<String>>()
        repeat(10_000) { subjects += arrayOf("a${it % 150}", "b${it % 150}") }
        val index = TokenIndex(subjects)
        assertEquals(200, index.commonThreshold)
        val out = IntArray(200)
        // a7 and b7 share the same 67 subjects: deduplicated.
        assertEquals(67, index.candidates(arrayOf("a7", "b7"), out))
        assertEquals(0, index.candidates(arrayOf("zzz"), out))
        val big = TokenIndex(List(1000) { arrayOf("x$it", "shared${it % 3}") }, commonMin = 400)
        assertEquals(200, big.candidates(arrayOf("shared0", "shared1"), out, cap = 200))
    }
}
