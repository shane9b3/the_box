// WP-S2 (docs/design/m6-storage-locations.md §3.2 "Removal", §3.5, §6, §16.2 VolumeWatcherTest).
package app.thebox.player.timeshift

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.storage.VolumeCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VolumeWatcherTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val root: File = Files.createTempDirectory("ts-volumes").toFile()
    private val volDir = File(root, "storage/ABCD-1234/Android/data/app.thebox/files")
    private val volume = VolumeCatalog.Volume("ABCD-1234", "SanDisk", volDir, 64L shl 30, 32L shl 30)
    private var listed = listOf(volume)
    private val gone = mutableListOf<Triple<String?, String?, Boolean>>()

    private fun watcher(pid: Int = 3) = VolumeWatcher(
        context,
        onGone = { uuid, path -> gone += Triple(uuid, path, Looper.myLooper() == Looper.getMainLooper()); true },
        lister = { listed },
        executor = { it.run() },
        pid = pid,
    )

    @After fun tearDown() { root.deleteRecursively() }

    @Test
    fun anEjectBroadcastStopsTheRingOnMainAndRefreshes() {
        val w = watcher()
        w.start()
        assertEquals(listOf(volume), w.volumes.value)
        listed = emptyList()
        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_EJECT, Uri.parse("file://${root.path}/storage/ABCD-1234")))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, gone.size)
        assertEquals("ABCD-1234", gone[0].first)
        assertEquals("${root.path}/storage/ABCD-1234", gone[0].second)
        assertTrue("the receiver runs on main", gone[0].third)
        assertTrue("re-listed after the removal", w.volumes.value.isEmpty())
    }

    @Test
    fun everyRemovalActionIsHandledAndAnUnknownPathStillReachesTheFactory() {
        val w = watcher()
        w.start()
        for (a in listOf(Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_BAD_REMOVAL, Intent.ACTION_MEDIA_REMOVED)) {
            w.onBroadcast(Intent(a, Uri.parse("file:///storage/FFFF-0000")))
        }
        assertEquals(3, gone.size)
        assertTrue(gone.all { it.first == null && it.second == "/storage/FFFF-0000" })
    }

    @Test
    fun mountedSweepsOtherProcessesFilesOnly() {
        val dir = File(volDir, "timeshift").apply { mkdirs() }
        val stale = File(dir, "ring-99-1.bin").apply { writeText("x") }
        val strayOld = File(dir, "ring-99.bin").apply { writeText("x") }
        val mine = File(dir, "ring-3-2.bin").apply { writeText("x") }
        val w = watcher(pid = 3)
        w.start()
        assertFalse(stale.exists())
        assertFalse(strayOld.exists())
        assertTrue(mine.exists())
        File(dir, "ring-42-1.bin").writeText("x")
        w.onBroadcast(Intent(Intent.ACTION_MEDIA_MOUNTED, Uri.parse("file://${root.path}/storage/ABCD-1234")))
        assertFalse(File(dir, "ring-42-1.bin").exists())
        assertTrue(mine.exists())
    }

    @Test
    fun uuidFromThePath() {
        assertEquals("ABCD-1234", VolumeWatcher.uuidFromPath(File("/storage/ABCD-1234/Android/data/x/files")))
        assertNull(VolumeWatcher.uuidFromPath(File("/storage/emulated/0/Android/data/x/files")))
        assertNull(VolumeWatcher.uuidFromPath(File("/data/user/0/x/files")))
    }

    @Test
    fun ejectClosesTheFdOffTheCallersThread() {
        val discard = Executors.newSingleThreadExecutor { r -> Thread(r, "box-ts-discard") }
        val closedOn = arrayOfNulls<String>(1)
        val latch = CountDownLatch(1)
        val file = File(root, "v/timeshift/ring-3-1.bin")
        val raw = AndroidRingStorage(
            file, 1L shl 22, posix = FakePosix(), preallocate = false, blocking = true,
            closer = { r -> discard.execute { r.run(); closedOn[0] = Thread.currentThread().name; latch.countDown() } },
        )
        val vs = VolumeStorage("ABCD-1234", raw, space = { 1L shl 40 }, nlinkReliable = raw.nlinkNow() == 1L)
        vs.pwrite(ByteBuffer.wrap(ByteArray(100)), 4096L)
        val caller = Thread.currentThread().name
        vs.eject()
        assertTrue("gone at once", vs.isUnlinked())
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("box-ts-discard", closedOn[0])
        assertNotEquals(caller, closedOn[0])
        discard.shutdown()
    }
}
