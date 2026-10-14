package app.thebox.core.guide.feed

import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.EpgWhitelist
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.EpgWindow
import app.thebox.core.epg.testutil.EpgTestDb
import app.thebox.core.epg.testutil.XmltvGenerator
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideRows
import app.thebox.core.guide.fakes.FakeGuideMetrics
import app.thebox.core.guide.fakes.FakeRowCellsBuilder
import app.thebox.core.guide.fakes.FakeTextMeasurer
import app.thebox.core.guide.fakes.FakeTimeLabels
import app.thebox.core.guide.fakes.GuideFixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * docs/design/m3-guide.md §5.2 `WindowQueryPerfTest` / §5.9 "Window query": 20k channels × 7 days (24 programmes a day,
 * ≈ 3.4M rows, `XmltvGenerator`), 18 rows × 9 h. Warm ≤ 5 ms, cold ≤ 30 ms on the JVM (1.5× local tolerance as in M1's
 * `EpgPerfTest`), plus the pager's full snapshot build (query, cells, measurement) for the hand-off.
 * `:core:perfTest` (-Xmx64m, SerialGC). Results go to `build/perf/guide-window.json`.
 */
@Tag("perf")
class WindowQueryPerfTest {
    @TempDir
    lateinit var tmp: File

    private val tolerance = 1.5

    private fun pct(ns: DoubleArray, p: Int): Double = ns.sorted()[((ns.size * p) / 100).coerceAtMost(ns.size - 1)]

    @Test
    fun window18RowsBy9Hours() {
        val gen = XmltvGenerator(channels = 20_000, whitelisted = 20_000, days = 7, perDay = 24, gzip = true, seed = 3)
        val cache = File("build/perf-cache/xmltv-20000-20000-7-24-3.xml.gz")
        if (!cache.isFile || cache.length() == 0L) gen.writeTo(File(cache.path + ".tmp")).renameTo(cache)
        val nowMs = gen.startMs + 36 * 3_600_000L
        val store = EpgTestDb.openStore(File(tmp, "epg"), clock = { nowMs })
        val t0 = System.nanoTime()
        val report = store.build(EpgBuildRequest(listOf(EpgSourceFile(1, cache)), nowMs, pastDays = 1, futureDays = 7), EpgWhitelistResolver { _, _ -> EpgWhitelist.ALL })
        val importSec = (System.nanoTime() - t0) / 1e9
        val now = (nowMs / 60_000L).toInt()
        val ws = GuideFixtures.floor30(now)
        val rnd = Random(11)

        // 1. Reader window: 18 channels × 9 h, cold (first touch of each block of channels) and warm.
        val cold = DoubleArray(20)
        val warm = DoubleArray(1_000)
        val out = EpgWindow()
        store.openReader()!!.use { reader ->
            for (i in cold.indices) {
                val top = rnd.nextInt(0, 20_000 - 18)
                val ids = reader.resolve(List(18) { EpgChannelRef(1, gen.channelId(top + it)) })
                val s = System.nanoTime()
                reader.window(ids, IntArray(18), ws - 180, ws + 360, out)
                cold[i] = (System.nanoTime() - s) / 1e6
                check(out.size >= 18 * 9) { "rows ${out.size}" }
            }
            val ids = reader.resolve(List(18) { EpgChannelRef(1, gen.channelId(5_000 + it)) })
            repeat(200) { reader.window(ids, IntArray(18), ws - 180, ws + 360, out) }
            for (i in warm.indices) {
                val s = System.nanoTime()
                reader.window(ids, IntArray(18), ws - 180 + (i % 4) * 30, ws + 360 + (i % 4) * 30, out)
                warm[i] = (System.nanoTime() - s) / 1e6
            }
        }

        // 2. The pager's snapshot build (query + RowCellsBuilder + title/time measurement), off-main.
        val channels = arrayOfNulls<GuideChannel>(20_000)
        val rows = object : GuideRows {
            override val size = 20_000
            override val version = 1L
            override fun channel(index: Int): GuideChannel =
                channels[index] ?: GuideFixtures.channel(index, epg = EpgChannelRef(1, gen.channelId(index))).also { channels[index] = it }
            override fun indexOf(rowId: Long) = -1
        }
        val pager = GuideWindowPagerImpl(store, rows, { FakeGuideMetrics() }, { FakeTextMeasurer() }, FakeRowCellsBuilder(), FakeTimeLabels())
        val builds = DoubleArray(200)
        val warmBuilds = DoubleArray(100)
        try {
            runBlocking {
                var top = 0
                for (i in -20 until builds.size) {
                    val prev = pager.snapshots.value?.seq ?: 0L
                    top = (top + 40) % (20_000 - 30)
                    pager.ensure(top, ws)
                    withTimeout(10_000) { pager.snapshots.first { it != null && it.seq > prev } }
                    if (i >= 0) builds[i] = pager.lastBuildMs
                }
                // Warm: the same target re-run (invalidate), data and titles in cache — the steady-scroll cost.
                for (i in -20 until warmBuilds.size) {
                    val prev = pager.snapshots.value!!.seq
                    pager.invalidate()
                    withTimeout(10_000) { pager.snapshots.first { it != null && it.seq > prev } }
                    if (i >= 0) warmBuilds[i] = pager.lastBuildMs
                }
            }
        } finally {
            pager.close()
        }

        val result = linkedMapOf(
            "programmes" to report.programmes.toDouble(),
            "importSec" to importSec,
            "windowColdP50Ms" to pct(cold, 50), "windowColdP90Ms" to pct(cold, 90), "windowColdMaxMs" to cold.max(),
            "windowWarmP50Ms" to pct(warm, 50), "windowWarmP90Ms" to pct(warm, 90),
            "snapshotBuildColdP50Ms" to pct(builds, 50), "snapshotBuildColdP90Ms" to pct(builds, 90),
            "snapshotBuildWarmP50Ms" to pct(warmBuilds, 50), "snapshotBuildWarmP90Ms" to pct(warmBuilds, 90),
        )
        val json = result.entries.joinToString(",\n  ", "{\n  ", "\n}\n") { (k, v) -> "\"$k\": ${"%.3f".format(v)}" }
        File("build/perf").mkdirs()
        File("build/perf/guide-window.json").writeText(json)
        println("Guide window perf: $json")

        assertTrue(report.programmes > 3_000_000, "programmes ${report.programmes}")
        assertTrue(pct(warm, 90) <= 5 * tolerance, "warm P90 ${pct(warm, 90)} ms > 5 ms × $tolerance")
        // Cold = first touch of 18 random channels' pages; the max of 20 samples is noisy on a loaded machine, so the
        // guard is the P90 (the max is reported).
        assertTrue(pct(cold, 90) <= 30 * tolerance, "cold P90 ${pct(cold, 90)} ms > 30 ms × $tolerance")
    }
}
