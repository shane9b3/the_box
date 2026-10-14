package app.thebox.player.engine

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.common.BoxLog
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.IOException

/** [DeviceCapabilities], [Media3LogBridge] and [WindowDisplayModeBinder]. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceAndLogTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val originalSink = BoxLog.sink
    private val originalLevel = BoxLog.minLevel

    @After fun tearDown() {
        BoxLog.sink = originalSink
        BoxLog.minLevel = originalLevel
        Log.setLogger(Log.Logger.DEFAULT)
    }

    // ------------------------------------------------------------------ DeviceCapabilities

    @Test
    fun lowRamClassRule() {
        assertTrue(DeviceCapabilities.isLowRamClass(isLowRamDevice = true, memoryClassMb = 512, totalMemBytes = 8L shl 30))
        assertTrue("Fire TV Stick 4K: memoryClass 192", DeviceCapabilities.isLowRamClass(false, 192, 1_536L shl 20))
        assertTrue("≤ 2 GiB total", DeviceCapabilities.isLowRamClass(false, 256, 2L shl 30))
        assertFalse(DeviceCapabilities.isLowRamClass(false, 256, 3L shl 30))
        assertFalse("unknown values do not count", DeviceCapabilities.isLowRamClass(false, 0, 0))
    }

    private fun setMemory(lowRamDevice: Boolean, memoryClass: Int, totalMem: Long) {
        val shadow = shadowOf(context.getSystemService(ActivityManager::class.java))
        shadow.setIsLowRamDevice(lowRamDevice)
        shadow.setMemoryClass(memoryClass)
        shadow.setMemoryInfo(ActivityManager.MemoryInfo().apply { this.totalMem = totalMem })
    }

    @Test
    fun lowRamFromTheActivityManager() {
        setMemory(lowRamDevice = false, memoryClass = 192, totalMem = 1_536L shl 20)
        val low = DeviceCapabilities(context)
        assertTrue(low.isLowRam)
        assertEquals(24 shl 20, low.targetBufferBytes)

        setMemory(lowRamDevice = false, memoryClass = 512, totalMem = 4L shl 30)
        val high = DeviceCapabilities(context)
        assertFalse(high.isLowRam)
        assertEquals(48 shl 20, high.targetBufferBytes)
    }

    @Test
    fun dumpListsTheDiagnosticsKeysAndIsCached() {
        setMemory(lowRamDevice = true, memoryClass = 128, totalMem = 1L shl 30)
        val caps = DeviceCapabilities(context)
        val dump = caps.dump()
        for (key in listOf(
            "device", "ram.lowRamClass", "ram.memoryClassMb", "ram.totalMemMb", "buffer.targetBytes", "decoder.video/avc",
            "decoder.audio/mpeg-L2", "mp2.platformDecoder", "ffmpeg.available", "ffmpeg.version", "audio.passthrough", "display.mode",
            "display.modes", "display.hdr", "afr.matchContentPref",
        )) assertTrue("missing $key in $dump", key in dump)
        assertEquals("true", dump["ram.lowRamClass"])
        assertEquals("false", dump["ffmpeg.available"])
        assertTrue(dump === caps.dump())
        caps.invalidate()
        assertFalse(dump === caps.dump())
    }

    // ------------------------------------------------------------------ Media3LogBridge

    @Test
    fun media3LogsGoThroughBoxLogRedacted() {
        val lines = mutableListOf<Triple<String, String, Throwable?>>()
        BoxLog.sink = BoxLog.Sink { _, tag, message, error -> lines += Triple(tag, message, error) }
        BoxLog.minLevel = BoxLog.DEBUG
        Media3LogBridge.install(debuggable = true)

        Log.w("ExoPlayerImpl", "Playback error http://panel.example/live/alice/s3cret/1.ts?token=abc", IOException("GET http://h/x?token=zzz", IllegalStateException("inner")))
        Log.d("MediaCodec", "debug line")
        val (tag, message, error) = lines.first()
        assertEquals("Media3", tag)
        assertTrue(message, message.startsWith("ExoPlayerImpl: Playback error"))
        assertFalse(message, message.contains("alice") || message.contains("s3cret") || message.contains("abc") || message.contains("zzz"))
        assertTrue("cause chain flattened: $message", message.contains("IOException") && message.contains("IllegalStateException: inner"))
        assertNull("throwables are never passed on raw", error)
        assertEquals("MediaCodec: debug line", lines[1].second)
    }

    @Test
    fun releaseBuildsLogWarningsOnly() {
        val lines = mutableListOf<String>()
        BoxLog.sink = BoxLog.Sink { _, _, message, _ -> lines += message }
        BoxLog.minLevel = BoxLog.DEBUG
        Media3LogBridge.install(debuggable = false)
        Log.i("Tag", "info")
        Log.w("Tag", "warn")
        assertEquals(listOf("Tag: warn"), lines)
        Media3LogBridge.install(debuggable = true)
    }

    // ------------------------------------------------------------------ WindowDisplayModeBinder

    @Test
    fun binderAppliesTheRequestToTheWindowUntilDestroyed() {
        val requests = MutableStateFlow(0)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        WindowDisplayModeBinder(requests).bind(activity)
        ShadowLooper.idleMainLooper()
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)

        requests.value = 7
        ShadowLooper.idleMainLooper()
        assertEquals(7, activity.window.attributes.preferredDisplayModeId)
        requests.value = 0
        ShadowLooper.idleMainLooper()
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)

        controller.pause().stop().destroy()
        requests.value = 9
        ShadowLooper.idleMainLooper()
        assertEquals("unbound after destroy", 0, activity.window.attributes.preferredDisplayModeId)
    }
}
