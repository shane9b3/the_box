package app.thebox.data.work

import org.junit.Assert.assertEquals
import org.junit.Test

/** Performance review L8: progress reaches WorkManager's database at most every 2 s, and never twice with the same text. */
class ProgressWritesTest {
    @Test
    fun firstLinePassesThenAtMostOneChangedLinePerGap() {
        var now = 0L
        val w = ProgressWrites(2_000) { now }
        val passed = ArrayList<String>()
        fun offer(line: String) { if (w.shouldWrite(line)) passed += line }
        offer("Downloading guide 1 of 2")
        now = 500; offer("Parsing 10%")
        now = 1_999; offer("Parsing 20%")
        now = 2_000; offer("Parsing 30%")
        now = 9_000; offer("Parsing 30%")
        now = 9_500; offer("Parsing 40%")
        assertEquals(listOf("Downloading guide 1 of 2", "Parsing 30%", "Parsing 40%"), passed)
    }
}
