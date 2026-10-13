package app.thebox.core.refresh

import app.thebox.core.identity.StableKeyDeriverImpl
import app.thebox.core.m3u.M3uParseOptions
import app.thebox.core.m3u.M3uParserImpl
import app.thebox.core.m3u.M3uSink
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.PlaylistType
import okio.buffer
import org.junit.jupiter.api.Tag
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test

/**
 * Diagnostic only (no budget): samples the importer thread's stack during a 50k first import and an
 * unchanged refresh, and prints the hottest frames. Kept so M6 can re-run it on hardware.
 */
@Tag("perf")
class M3uBreakdownPerfTest {
    private val n = 50_000

    private fun producer() = EntryProducer { consumer ->
        M3uParserImpl().parse(M3uGenerator(n, 0.6, 7).source().buffer(), M3uParseOptions(), object : M3uSink {
            override fun onEntry(entry: ParsedChannel) = consumer(entry)
        })
    }

    private fun profile(label: String, block: () -> Unit) {
        val target = Thread.currentThread()
        val inclusive = HashMap<String, Int>()
        val self = HashMap<String, Int>()
        val running = AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                val st = target.stackTrace
                if (st.isNotEmpty()) {
                    self.merge(st[0].className.substringAfterLast('.') + "." + st[0].methodName, 1, Int::plus)
                    val seen = HashSet<String>()
                    for (f in st) {
                        val k = f.className.substringAfterLast('.') + "." + f.methodName
                        if (f.className.startsWith("app.thebox") && seen.add(k)) inclusive.merge(k, 1, Int::plus)
                    }
                }
                Thread.sleep(1)
            }
        }
        sampler.start()
        val t0 = System.nanoTime()
        block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        running.set(false)
        sampler.join()
        println("BREAKDOWN $label: $ms ms")
        inclusive.entries.sortedByDescending { it.value }.take(18).forEach { println("BREAKDOWN   incl ${it.value}  ${it.key}") }
        self.entries.sortedByDescending { it.value }.take(12).forEach { println("BREAKDOWN   self ${it.value}  ${it.key}") }
    }

    @Test fun breakdown() {
        repeat(2) {
            val s = M3uPlaylistPerfTest.CompactStore(n)
            val d = PlaylistDifferImpl(StableKeyDeriverImpl(), clock = { 0 })
            val r = d.merge(producer(), s, s, MergeContext(1, PlaylistType.M3U_URL, 1, 0, 0))
            d.merge(producer(), s, s, MergeContext(1, PlaylistType.M3U_URL, 2, 0, r.live + r.radio))
        }
        val s = M3uPlaylistPerfTest.CompactStore(n)
        val d = PlaylistDifferImpl(StableKeyDeriverImpl(), clock = { 0 })
        var live = 0
        profile("first import ${n / 1000}k") { live = d.merge(producer(), s, s, MergeContext(1, PlaylistType.M3U_URL, 1, 0, 0)).let { it.live + it.radio } }
        profile("unchanged refresh ${n / 1000}k") { d.merge(producer(), s, s, MergeContext(1, PlaylistType.M3U_URL, 2, 0, live)) }
    }
}
