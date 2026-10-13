package app.thebox.data

import android.os.Debug
import android.util.Xml
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgImportReport
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.EpgWhitelist
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.EpgWindow
import app.thebox.core.epg.store.EpgStoreImpl
import app.thebox.data.epg.AndroidFreeSpace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.GZIPOutputStream

/** XMLTV written on the device (androidTest cannot read core's test resources). */
internal object DeviceXmltv {
    fun write(file: File, channels: Int, programmesPerChannel: Int, startMin: Long, gzip: Boolean = false) {
        val out = if (gzip) GZIPOutputStream(file.outputStream().buffered()) else file.outputStream().buffered()
        out.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
            for (c in 0 until channels) w.write("<channel id=\"ch$c.uk\"><display-name>Channel $c</display-name></channel>\n")
            for (c in 0 until channels) {
                var t = startMin
                for (p in 0 until programmesPerChannel) {
                    w.write("<programme channel=\"ch$c.uk\" start=\"${ts(t)}\" stop=\"${ts(t + 30)}\"><title>Show $c-$p</title><desc>Description of show $p on channel $c.</desc></programme>\n")
                    t += 30
                }
            }
            w.write("</tv>\n")
        }
    }

    private fun ts(min: Long): String = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
        .format(java.time.Instant.ofEpochSecond(min * 60).atZone(java.time.ZoneOffset.UTC))
}

/**
 * I-3 (generation swap with an open reader), I-5 (1M-programme build: time, heap, native heap ≤ 64 MB peak
 * above baseline, §5.7) and I-7 (low disk → in-place, readers keep working) against WP-B's EpgStoreImpl on the
 * bundled driver and the platform XML parser. I-3's full worker path is in [I3EpgImportWorkerTest].
 */
@RunWith(AndroidJUnit4::class)
class EpgDeviceTests {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private val all = EpgWhitelistResolver { _, _ -> EpgWhitelist.ALL }
    private val now = System.currentTimeMillis()

    @Before fun setUp() { dir = File(context.noBackupFilesDir, "epg-it-${System.nanoTime()}") }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun store(freeBytes: Long? = null) = EpgStoreImpl(
        dir, BundledSQLiteDriver(), if (freeBytes == null) AndroidFreeSpace else app.thebox.core.epg.FreeSpace { freeBytes }, Xml::newPullParser, { now },
    )

    private fun xml(name: String, channels: Int, perChannel: Int): File =
        File(context.cacheDir, name).also { DeviceXmltv.write(it, channels, perChannel, now / 60_000 - 60) }

    @Test
    fun i3GenerationSwapKeepsAnOpenReaderValid() {
        val s = store().apply { recover() }
        val file = xml("i3.xml", 5, 20)
        s.build(EpgBuildRequest(listOf(EpgSourceFile(1, file)), now, 1, 2), all)
        val old = s.openReader()!!
        val oldFile = File(dir, "epg-${old.info.generation}.db")
        s.build(EpgBuildRequest(listOf(EpgSourceFile(1, file)), now, 1, 2), all)
        assertTrue("old reader still answers", old.findChannels("Channel", 10).isNotEmpty())
        assertTrue(oldFile.exists())
        old.close()
        assertFalse("the retired generation goes with its last reader", oldFile.exists())
    }

    @Ignore("I-5: long-running device benchmark; run manually and record the numbers (§5.7)")
    @Test
    fun i5MillionProgrammeBuild() {
        val file = File(context.cacheDir, "i5.xml.gz").also { DeviceXmltv.write(it, 2_000, 500, now / 60_000 - 60, gzip = true) }
        val s = store().apply { recover() }
        val baseline = Debug.getNativeHeapAllocatedSize()
        var peak = baseline
        val sampler = Thread {
            while (!Thread.currentThread().isInterrupted) {
                peak = maxOf(peak, Debug.getNativeHeapAllocatedSize())
                try { Thread.sleep(250) } catch (e: InterruptedException) { return@Thread }
            }
        }.apply { start() }
        val t0 = System.nanoTime()
        val report: EpgImportReport = s.build(EpgBuildRequest(listOf(EpgSourceFile(1, file)), now, 1, 14), all)
        val ms = (System.nanoTime() - t0) / 1_000_000
        sampler.interrupt(); sampler.join()
        val nativeMb = (peak - baseline) / (1024 * 1024)
        android.util.Log.i("I5", "programmes=${report.programmes} ms=$ms db=${report.dbBytes} nativePeakMb=$nativeMb javaHeapMb=${Runtime.getRuntime().totalMemory() / (1024 * 1024)}")
        assertTrue("native heap $nativeMb MB above baseline (budget 64)", nativeMb <= 64)
    }

    @Test
    fun i7LowDiskUsesInPlaceAndReadersKeepWorking() {
        val file = xml("i7.xml", 5, 20)
        store().apply { recover() }.build(EpgBuildRequest(listOf(EpgSourceFile(1, file)), now, 1, 2), all)
        val low = store(freeBytes = 10L * 1024 * 1024)
        low.recover()
        val reader = low.openReader()!!
        val report = low.build(EpgBuildRequest(listOf(EpgSourceFile(1, file)), now, 1, 2), all)
        assertEquals(EpgImportReport.Mode.IN_PLACE, report.mode)
        val ids = reader.resolve(listOf(app.thebox.core.epg.EpgChannelRef(1, "ch0.uk")))
        val out = EpgWindow()
        reader.window(ids, IntArray(1), (now / 60_000).toInt() - 60, (now / 60_000).toInt() + 120, out)
        assertTrue(out.size > 0)
        reader.close()
    }
}
