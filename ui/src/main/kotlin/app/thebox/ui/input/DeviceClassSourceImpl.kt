package app.thebox.ui.input

import android.app.ActivityManager
import android.content.Context
import app.thebox.core.guide.DeviceClass
import app.thebox.core.guide.DeviceClassSource

/**
 * docs/design/m3-guide.md §1.5, §2.4a (review B7/S3): from [ActivityManager], computed once on the first [get] and
 * cached for the process (one binder call for `getMemoryInfo`); never on a hot path.
 */
class DeviceClassSourceImpl(private val context: Context) : DeviceClassSource {
    private val value: DeviceClass by lazy {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        DeviceClass(lowRam = am.isLowRamDevice, totalMemBytes = mem.totalMem, memoryClassMb = am.memoryClass)
    }

    override fun get(): DeviceClass = value
}
