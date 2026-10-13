package app.thebox.core.epg.store

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgProgress
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.EpgWhitelist
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.EpgWindow
import app.thebox.core.epg.testutil.EpgTestDb
import app.thebox.core.epg.testutil.XmltvGenerator
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.kxml2.io.KXmlParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * §5.5 budgets on the JVM (`:core:perfTest`, -Xmx64m, SerialGC): 1M kept programmes on 20k of 50k
 * channels over 7 days. Time budgets carry the 1.5× local tolerance (review S21); heap is strict.
 * Results go to `build/perf/epg.json`.
 */
@Tag("perf")
class EpgPerfTest {
    @TempDir
    lateinit var tmp: File

    private val tolerance = 1.5

    private fun usedAfterGc(): Long {
        val rt = Runtime.getRuntime()
        repeat(2) { System.gc() }
        return rt.totalMemory() - rt.freeMemory()
    }

    private fun p95(ns: LongArray): Double = ns.sorted()[(ns.size * 95) / 100 - 1] / 1e6

    @Test
    fun millionProgrammeRebuildWindowAndSearch() {
        val gen = XmltvGenerator(channels = 50_000, whitelisted = 20_000, days = 7, perDay = 7, gzip = true, seed = 1)
        val cache = File("build/perf-cache/xmltv-50000-20000-7-7-1.xml.gz")
        val tGen = System.nanoTime()
        if (!cache.isFile || cache.length() == 0L) gen.writeTo(File(cache.path + ".tmp")).renameTo(cache)
        val genSec = (System.nanoTime() - tGen) / 1e9
        val whitelistIds = gen.whitelistedIds()
        val resolver = EpgWhitelistResolver { _, channels ->
            var n = 0
            for (c in channels) n++ // the real matcher walks every channel once
            check(n == 50_000) { "channels seen: $n" }
            EpgWhitelist { it in whitelistIds }
        }
        val store = EpgStoreImpl(File(tmp, "epg"), BundledSQLiteDriver(), EpgTestDb.PLENTY_OF_SPACE, ::KXmlParser, System::currentTimeMillis)
        val nowMs = gen.startMs + 12 * 3_600_000L
        val request = EpgBuildRequest(listOf(EpgSourceFile(1, cache)), nowMs = nowMs, pastDays = 1, futureDays = 7)

        val baseline = usedAfterGc()
        var maxRetained = 0L
        var nextMark = 0.25
        val t0 = System.nanoTime()
        val report = store.build(request, resolver) { p ->
            val total = p.bytesTotal ?: return@build
            val frac = p.bytesRead.toDouble() / total
            if (p.stage == EpgProgress.Stage.PARSING && frac >= nextMark || p.stage == EpgProgress.Stage.INDEXING) {
                maxRetained = maxOf(maxRetained, usedAfterGc() - baseline)
                while (nextMark <= frac) nextMark += 0.25
            }
        }
        val importSec = (System.nanoTime() - t0) / 1e9
        maxRetained = maxOf(maxRetained, usedAfterGc() - baseline)
        val dbMb = report.dbBytes / 1e6

        // Window: 36 channels × 12 h.
        val refs = whitelistIds.sorted().take(36).map { EpgChannelRef(1, it) }
        val from = (nowMs / 60_000L).toInt()
        val to = from + 12 * 60
        val shift = IntArray(36)
        val out = EpgWindow()
        val coldNs: Long
        val warm = LongArray(1000)
        store.openReader()!!.use { reader ->
            val channels = reader.resolve(refs)
            check(channels.none { it < 0 })
            val c0 = System.nanoTime()
            reader.window(channels, shift, from, to, out)
            coldNs = System.nanoTime() - c0
            check(out.size >= 36 * 3) { "rows ${out.size}" }
            repeat(200) { reader.window(channels, shift, from, to, out) }
            for (i in warm.indices) {
                val s = System.nanoTime()
                reader.window(channels, shift, from, to, out)
                warm[i] = System.nanoTime() - s
            }
        }
        val search = LongArray(50)
        var hits = 0
        store.openReader()!!.use { reader ->
            repeat(5) { reader.search("news", from) }
            for (i in search.indices) {
                val s = System.nanoTime()
                hits = reader.search("news", from).size
                search[i] = System.nanoTime() - s
            }
        }
        val result = mapOf(
            "generateSec" to genSec,
            "importSec" to importSec,
            "programmes" to report.programmes.toDouble(),
            "texts" to report.texts.toDouble(),
            "retainedHeapMb" to maxRetained / 1e6,
            "dbMb" to dbMb,
            "windowColdMs" to coldNs / 1e6,
            "windowWarmP95Ms" to p95(warm),
            "searchP95Ms" to p95(search),
            "searchHits" to hits.toDouble(),
        )
        val json = result.entries.joinToString(",\n  ", "{\n  ", "\n}\n") { (k, v) -> "\"$k\": ${"%.3f".format(v)}" }
        File("build/perf").mkdirs()
        File("build/perf/epg.json").writeText(json)
        println("EPG perf: $json")
        println("source stat: ${report.sources.single()}")

        assertTrue(report.programmes in 900_000..1_100_000, "programmes ${report.programmes}")
        assertTrue(importSec <= 25 * tolerance, "import $importSec s > 25 s × $tolerance")
        assertTrue(maxRetained <= 20L * 1024 * 1024, "retained heap ${maxRetained / 1e6} MB > 20 MB")
        assertTrue(coldNs / 1e6 <= 15 * tolerance, "cold window ${coldNs / 1e6} ms > 15 ms × $tolerance")
        assertTrue(p95(warm) <= 2 * tolerance, "warm window p95 ${p95(warm)} ms > 2 ms × $tolerance")
        assertTrue(p95(search) <= 30 * tolerance, "search p95 ${p95(search)} ms > 30 ms × $tolerance")
    }
}
