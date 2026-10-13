package app.thebox.core.refresh

import app.thebox.core.identity.StableKeyDeriverImpl
import app.thebox.core.m3u.M3uParseOptions
import app.thebox.core.m3u.M3uParserImpl
import app.thebox.core.m3u.M3uSink
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.PlaylistType
import okio.buffer
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §5.5 M3U budgets on the JVM (CI runner), run by `:core:perfTest` (64 MB heap, SerialGC):
 * - 200k entries, parse + key + diff into an in-memory sink, first import: ≤ 3 s;
 * - second refresh with no changes: ≤ 2.5 s and 0 rows written;
 * - retained heap (max of `used` after `System.gc()` at 25/50/75/100 %, both passes): ≤ 20 MB above the
 *   baseline taken before the merge (the baseline holds the test's own copy of the "database").
 */
@Tag("perf")
class M3uPlaylistPerfTest {
    private val n = 200_000

    /** library.db stand-in with primitive, pre-sized storage, so it does not grow while the differ is measured. */
    internal class CompactStore(capacity: Int) : MergeSink, ExistingChannels {
        val keyBytes = ByteArray(capacity * 64)
        val keyOffsets = IntArray(capacity + 1)
        val content = LongArray(capacity)
        val urls = LongArray(capacity)
        val names = LongArray(capacity)
        val sorts = IntArray(capacity)
        var size = 0
        var written = 0
        var batches = 0

        override fun forEach(block: (ExistingChannel) -> Unit) {
            for (i in 0 until size) {
                val key = String(keyBytes, keyOffsets[i], keyOffsets[i + 1] - keyOffsets[i], Charsets.UTF_8)
                block(ExistingChannel(i + 1L, key, content[i], urls[i], names[i], sorts[i], null, 0))
            }
        }

        override fun begin(groups: List<GroupRow>, detected: DetectedCredentials?) {}

        override fun apply(batch: MergeBatch) {
            batches++
            written += batch.inserts.size + batch.updates.size + batch.touches.size
            for (c in batch.inserts) {
                val kb = c.stableKey.value.toByteArray(Charsets.UTF_8)
                val off = keyOffsets[size]
                System.arraycopy(kb, 0, keyBytes, off, kb.size)
                keyOffsets[size + 1] = off + kb.size
                content[size] = c.contentHash
                urls[size] = c.urlHash
                names[size] = c.nameHash
                sorts[size] = c.sortIndex
                size++
            }
        }

        override fun finish(finish: MergeFinish) = FinishStats(0, 0, 0, emptyList())
        override fun abort(error: Throwable) {}
    }

    private class HeapProbe(private val total: Int) {
        var baseline = 0L
        var peak = 0L
        fun used(): Long {
            System.gc()
            val rt = Runtime.getRuntime()
            return rt.totalMemory() - rt.freeMemory()
        }
        fun start() { baseline = used() }
        fun maybeSample(count: Int) {
            if (count % (total / 4) == 0) peak = maxOf(peak, used())
        }
        val retainedMb get() = (peak - baseline) / (1024.0 * 1024.0)
    }

    private fun producer(entries: Int, probe: HeapProbe? = null) = EntryProducer { consumer ->
        var count = 0
        M3uParserImpl().parse(M3uGenerator(entries, 0.6, seed = 7).source().buffer(), M3uParseOptions(), object : M3uSink {
            override fun onEntry(entry: ParsedChannel) {
                consumer(entry)
                count++
                probe?.maybeSample(count)
            }
        })
    }

    private fun ctx(prevLive: Int, batch: Int = 2_000) = MergeContext(1, PlaylistType.M3U_URL, 1, 1_000, prevLive, batchSize = batch)

    private fun gcMs(): Long = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0L, it.collectionTime) }

    /** GC milliseconds spent inside the last [merge] call (diagnostics for the JSON). */
    private var lastGcMs = 0L

    private fun merge(store: CompactStore, entries: Int, prevLive: Int, probe: HeapProbe? = null): Pair<RefreshReport, Long> {
        val differ = PlaylistDifferImpl(StableKeyDeriverImpl(), clock = { 0 })
        val gc0 = gcMs()
        val t0 = System.nanoTime()
        val r = differ.merge(producer(entries, probe), store, store, ctx(prevLive))
        val ms = (System.nanoTime() - t0) / 1_000_000
        lastGcMs = gcMs() - gc0
        return r to ms
    }

    private class Timings(
        val live: Int, val movies: Int, val series: Int, val firstMs: Long, val secondMs: Long, val written: Int, val rules: Map<String, Int>, val dups: Int,
        val firstGcMs: Long = 0, val secondGcMs: Long = 0,
    )

    private fun timed(): Timings {
        val store = CompactStore(n)
        val (first, firstMs) = merge(store, n, 0)
        val firstGc = lastGcMs
        assertEquals(n, first.entries)
        assertEquals(n, first.added)
        val live = first.live + first.radio
        val (second, secondMs) = merge(store, n, live)
        assertEquals(n, second.unchanged)
        return Timings(live, first.movies, first.series, firstMs, secondMs, store.written - n, first.keyRules, first.duplicateKeys, firstGc, lastGcMs)
    }

    private fun heap(live: Int): Pair<Double, Double> {
        val store = CompactStore(n)
        val p1 = HeapProbe(n).apply { start() }
        merge(store, n, 0, p1)
        val p2 = HeapProbe(n).apply { start() }
        merge(store, n, live, p2)
        return p1.retainedMb to p2.retainedMb
    }

    @Test fun `200k entries - first import, unchanged refresh, retained heap`() {
        // Warm-up (JIT) on a smaller list.
        run {
            val s = CompactStore(20_000)
            val (r, _) = merge(s, 20_000, 0)
            merge(s, 20_000, r.live + r.radio)
        }
        // Timed runs (best of 3: the minimum is the least noisy estimate on a shared runner), then separate
        // heap runs so the GC calls do not distort the timings.
        val samples = List(TIMED_RUNS) { timed() }
        val t = samples.first().let { s0 -> Timings(s0.live, s0.movies, s0.series, samples.minOf { it.firstMs }, samples.minOf { it.secondMs }, samples.maxOf { it.written }, s0.rules, s0.dups) }
        val (heapFirst, heapRefresh) = heap(t.live)

        val json = """
            {"test":"m3u-200k","entries":$n,"live":${t.live},"movies":${t.movies},"series":${t.series},
             "firstImportMs":${t.firstMs},"unchangedRefreshMs":${t.secondMs},"unchangedRowsWritten":${t.written},
             "firstImportSamplesMs":${samples.map { it.firstMs }},"unchangedRefreshSamplesMs":${samples.map { it.secondMs }},
             "firstImportGcMs":${samples.map { it.firstGcMs }},"unchangedRefreshGcMs":${samples.map { it.secondGcMs }},
             "retainedHeapFirstMb":${"%.2f".format(heapFirst)},"retainedHeapRefreshMb":${"%.2f".format(heapRefresh)},
             "keyRules":"${t.rules}","duplicateKeys":${t.dups},"maxHeapMb":${Runtime.getRuntime().maxMemory() / (1024 * 1024)}}
        """.trimIndent()
        File("build/perf").mkdirs()
        File("build/perf/m3u-200k.json").writeText(json + "\n")
        println(json)

        assertTrue(t.firstMs <= FIRST_IMPORT_BUDGET_MS, "first import took ${t.firstMs} ms (budget $FIRST_IMPORT_BUDGET_MS)")
        assertTrue(t.secondMs <= REFRESH_BUDGET_MS, "unchanged refresh took ${t.secondMs} ms (budget $REFRESH_BUDGET_MS)")
        assertEquals(0, t.written, "an unchanged refresh writes no rows")
        assertTrue(heapFirst <= HEAP_BUDGET_MB, "first import retained $heapFirst MB")
        assertTrue(heapRefresh <= HEAP_BUDGET_MB, "refresh retained $heapRefresh MB")
    }

    private companion object {
        const val FIRST_IMPORT_BUDGET_MS = 3_000L
        const val REFRESH_BUDGET_MS = 2_500L
        const val HEAP_BUDGET_MB = 20.0
        const val TIMED_RUNS = 3
    }
}
