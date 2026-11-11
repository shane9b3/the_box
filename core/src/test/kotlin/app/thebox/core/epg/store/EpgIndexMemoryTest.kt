package app.thebox.core.epg.store

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgProgress
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.EpgWhitelist
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.testutil.EpgTestDb
import app.thebox.core.epg.testutil.XmltvGenerator
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.kxml2.io.KXmlParser
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Performance review H5 (2026-11-10): resident memory of the process around the post-load index build at about
 * 2M kept programmes (20k of 50k channels × 7 days × 14 a day), through the real [EpgStoreImpl] and the same
 * `BundledSQLiteDriver` the app uses (compiled with `TEMP_STORE=3`, so sorter spill stays in RAM).
 *
 * Measurement only, no budget asserted. Opt-in on top of the `perf` tag, because the input is ~5M programmes:
 * `THEBOX_H5=1 ./gradlew :core:perfTest --tests '*EpgIndexMemoryTest*'`. RSS is sampled with `ps -o rss=` every
 * 50 ms on a daemon thread (macOS/Linux). The JVM heap is capped by the task (-Xmx64m), so RSS growth after the
 * load is mostly native (SQLite page cache, sorter, FTS). Compare `EpgSchema.INDEX_BEFORE_LOAD` by flipping it and
 * running again. Results go to `build/perf/epg-index-memory-<before|after>.json`.
 *
 * What it does not prove: the device is a 32-bit ARM process with a different allocator (scudo/jemalloc) and no JIT
 * heap of this size. SQLite's own allocations are the same sizes (pointers aside), so the post-load delta is the
 * figure that carries over; the absolute RSS does not.
 */
@Tag("perf")
@EnabledIfEnvironmentVariable(named = "THEBOX_H5", matches = "1")
class EpgIndexMemoryTest {
    @TempDir
    lateinit var tmp: File

    private val pid = ProcessHandle.current().pid()

    private fun rssKb(): Long = try {
        val p = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out.toLongOrNull() ?: -1
    } catch (e: Exception) {
        -1
    }

    @Test
    fun rssAroundThePostLoadIndexBuild() {
        val gen = XmltvGenerator(channels = 50_000, whitelisted = 20_000, days = 7, perDay = 14, gzip = true, seed = 1)
        val cache = File("build/perf-cache/xmltv-50000-20000-7-14-1.xml.gz")
        if (!cache.isFile || cache.length() == 0L) gen.writeTo(File(cache.path + ".tmp")).renameTo(cache)
        val whitelistIds = gen.whitelistedIds()
        val resolver = EpgWhitelistResolver { _, _ -> EpgWhitelist { it in whitelistIds } }
        val driver = BundledSQLiteDriver()

        // The pragmas in effect on the build connection (same driver, same BUILD_PRAGMAS).
        val pragmas = LinkedHashMap<String, String>()
        driver.open(File(tmp, "probe.db").path).also { c ->
            for (p in EpgSchema.BUILD_PRAGMAS) c.exec(p)
            for (name in listOf("temp_store", "cache_size", "threads", "page_size", "soft_heap_limit", "hard_heap_limit", "mmap_size", "cache_spill")) {
                pragmas[name] = c.textOrNull("PRAGMA $name") ?: "?"
            }
            val opts = ArrayList<String>()
            c.query("PRAGMA compile_options") { st -> while (st.step()) opts += st.getText(0) }
            pragmas["compile_options"] = opts.filter {
                it.startsWith("TEMP_STORE") || it.contains("THREAD") || it.contains("SORTER") || it.startsWith("DEFAULT_CACHE") ||
                    it.contains("PMASZ") || it.contains("MEMORY") || it.startsWith("DEFAULT_PAGE") || it.contains("MAX_MMAP")
            }.joinToString(" ")
            c.close()
        }

        val store = EpgStoreImpl(File(tmp, "epg"), driver, EpgTestDb.PLENTY_OF_SPACE, ::KXmlParser, System::currentTimeMillis)
        val request = EpgBuildRequest(listOf(EpgSourceFile(1, cache)), nowMs = gen.startMs + 12 * 3_600_000L, pastDays = 1, futureDays = 7)

        repeat(2) { System.gc() }
        val baseline = rssKb()
        val current = AtomicLong(baseline)
        val peakLoad = AtomicLong(baseline)
        val peakIndex = AtomicLong(0)
        val phase = AtomicInteger(0) // 0 load, 1 index/FTS/ANALYZE, 2 publishing/done
        val atIndexStart = AtomicLong(-1)
        val atIndexEnd = AtomicLong(-1)
        var tIndexStart = 0L
        var tIndexEnd = 0L
        val running = AtomicBoolean(true) // a flag, not interrupt(): Process.waitFor in rssKb() can swallow it
        val sampler = Thread {
            while (running.get()) {
                val r = rssKb()
                current.set(r)
                when (phase.get()) {
                    0 -> peakLoad.accumulateAndGet(r, ::maxOf)
                    1 -> peakIndex.accumulateAndGet(r, ::maxOf)
                }
                Thread.sleep(50)
            }
        }.apply { isDaemon = true; start() }

        val t0 = System.nanoTime()
        val report = store.build(request, resolver) { p ->
            if (p.stage == EpgProgress.Stage.INDEXING && phase.get() == 0) {
                tIndexStart = System.nanoTime()
                atIndexStart.set(rssKb())
                peakIndex.set(atIndexStart.get())
                phase.set(1)
            } else if (p.stage == EpgProgress.Stage.PUBLISHING && phase.get() == 1) {
                tIndexEnd = System.nanoTime()
                atIndexEnd.set(rssKb())
                phase.set(2)
            }
        }
        val totalSec = (System.nanoTime() - t0) / 1e9
        running.set(false)
        sampler.join()
        val after = rssKb()

        val mb = { kb: Long -> kb / 1024.0 }
        val label = if (EpgSchema.INDEX_BEFORE_LOAD) "before" else "after"
        val result = linkedMapOf(
            "indexBeforeLoad" to EpgSchema.INDEX_BEFORE_LOAD.toString(),
            "programmes" to report.programmes.toString(),
            "texts" to report.texts.toString(),
            "dbMb" to "%.1f".format(report.dbBytes / 1e6),
            "totalSec" to "%.1f".format(totalSec),
            "loadSec" to "%.1f".format((tIndexStart - t0) / 1e9),
            "indexPhaseSec" to "%.1f".format((tIndexEnd - tIndexStart) / 1e9),
            "rssBaselineMb" to "%.1f".format(mb(baseline)),
            "rssPeakDuringLoadMb" to "%.1f".format(mb(peakLoad.get())),
            "rssAtIndexStartMb" to "%.1f".format(mb(atIndexStart.get())),
            "rssPeakDuringIndexMb" to "%.1f".format(mb(peakIndex.get())),
            "rssAtIndexEndMb" to "%.1f".format(mb(atIndexEnd.get())),
            "rssAfterBuildMb" to "%.1f".format(mb(after)),
            "indexPeakOverIndexStartMb" to "%.1f".format(mb(peakIndex.get() - atIndexStart.get())),
            "indexPeakOverBaselineMb" to "%.1f".format(mb(peakIndex.get() - baseline)),
            "loadPeakOverBaselineMb" to "%.1f".format(mb(peakLoad.get() - baseline)),
        ) + pragmas
        val json = result.entries.joinToString(",\n  ", "{\n  ", "\n}\n") { (k, v) -> "\"$k\": \"$v\"" }
        File("build/perf").mkdirs()
        File("build/perf/epg-index-memory-$label.json").writeText(json)
        println("EPG index memory ($label): $json")
        assertTrue(report.programmes > 1_500_000, "programmes ${report.programmes}")
        assertTrue(baseline > 0, "ps did not answer")
    }
}
