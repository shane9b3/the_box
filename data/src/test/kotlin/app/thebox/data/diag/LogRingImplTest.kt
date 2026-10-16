package app.thebox.data.diag

import androidx.test.core.app.ApplicationProvider
import app.thebox.core.common.BoxLog
import app.thebox.core.diag.LogLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** The installed composite sink, the file batch and its rotation (docs/design/m5-setup-settings.md §3.11.3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LogRingImplTest {
    private val savedSink = BoxLog.sink
    private val savedLevel = BoxLog.minLevel
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var ring: LogRingImpl
    private val platform = ArrayList<String>()

    @Before
    fun setUp() {
        BoxLog.sink = BoxLog.Sink { _, tag, m, e -> platform += "$tag|$m|${e != null}" }
        BoxLog.minLevel = BoxLog.DEBUG
        ring = LogRingImpl(ApplicationProvider.getApplicationContext(), scope, Dispatchers.Unconfined)
        ring.dir.deleteRecursively()
    }

    @After
    fun tearDown() {
        scope.cancel()
        BoxLog.sink = savedSink
        BoxLog.minLevel = savedLevel
    }

    @Test
    fun installWrapsThePlatformSinkOnceAndRedactsTheThrowable() {
        ring.install()
        ring.install()
        BoxLog.e("Net", "failed", IOException("unexpected end of stream on http://h/live/user9/pass9/3.ts"))
        assertEquals(1, platform.size)
        assertFalse(platform.single().contains("user9"))
        assertTrue(platform.single().endsWith("|false"))
        assertTrue(ring.snapshot().single().contains("/live/***/***/"))
        ring.flushToFile()
        val file = File(ring.dir, LogLimits.LOG_FILE_CURRENT).readText()
        assertTrue(file.contains("E/Net: failed"))
        assertFalse(file.contains("pass9"))
    }

    @Test
    fun fileRotatesAtTheLimitKeepingTwoFiles() {
        ring.install()
        val big = "y".repeat(400)
        repeat(3_000) { BoxLog.w("T", "$it $big") } // ~1.3 MB of entries
        ring.flushToFile()
        repeat(10) { BoxLog.w("T", "after $it") }
        ring.flushToFile()
        val current = File(ring.dir, LogLimits.LOG_FILE_CURRENT)
        val previous = File(ring.dir, LogLimits.LOG_FILE_PREVIOUS)
        assertTrue(previous.isFile)
        assertTrue(current.readText().contains("after 9"))
        assertFalse(current.readText().contains("2999 "))
        val lines = ring.fileLines()
        assertEquals(2, lines.size)
        assertTrue(lines.last().last().endsWith("after 9"))
        assertEquals(2, ring.dir.listFiles()!!.size)
    }
}
