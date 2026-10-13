package app.thebox.core.match

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * §3.6.4 budget: 20k subjects × 50k EPG channels in ≤ 3 s on the JVM with ≤ 8 MB retained. Runs in
 * `:core:perfTest` (`-Xmx64m`). Time is enforced with the §5.1 local tolerance (1.5×, so 4.5 s) on the best
 * of three runs after a warm-up; set `-Dperf.reportOnly=true` on shared CI runners.
 */
@Tag("perf")
class EpgMatcherPerfTest {
    @Test
    fun twentyThousandByFiftyThousand() {
        val corpus = MatchFixtures.corpus(subjects = 20_000, epgChannels = 50_000, seed = 3)
        val settings = MatchSettings()
        fun run(): Pair<Long, Int> {
            val t0 = System.nanoTime()
            val m = EpgMatcher(settings)
            val results = m.matchSource(m.prepare(corpus.subjects), 1, corpus.channels.asSequence())
            return (System.nanoTime() - t0) / 1_000_000 to results.count { it.best != null }
        }
        run() // warm-up (JIT)

        val retainedKiB = measureRetainedKiB { EpgMatcher(settings).prepare(corpus.subjects) }
        val runs = List(3) { run() }
        val ms = runs.minOf { it.first }
        println("EpgMatcherPerfTest: 20k × 50k in $ms ms (runs ${runs.map { it.first }}), matched ${runs[0].second}, prepared index ≈ $retainedKiB KiB")
        if (System.getProperty("perf.reportOnly") != "true") {
            assertTrue(ms <= 4_500, "matching took $ms ms (budget 3,000 ms × 1.5)")
            assertTrue(retainedKiB <= 8 * 1024, "prepared subjects retain $retainedKiB KiB (budget 8 MiB)")
        }
    }

    private fun measureRetainedKiB(make: () -> Any): Long {
        val rt = Runtime.getRuntime()
        repeat(3) { System.gc() }
        val before = rt.totalMemory() - rt.freeMemory()
        val keep = make()
        repeat(3) { System.gc() }
        val after = rt.totalMemory() - rt.freeMemory()
        check(keep.hashCode() != 42 || true)
        return (after - before) / 1024
    }
}
