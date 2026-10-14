package app.thebox.ui.input

import android.content.Context
import app.thebox.core.guide.DeviceClass
import app.thebox.core.guide.DeviceClassSource

/** WP-D stub (docs/design/m3-guide.md §1.5, §2.4a, review B7/S3): from ActivityManager, computed once. */
class DeviceClassSourceImpl(private val context: Context) : DeviceClassSource {
    override fun get(): DeviceClass = TODO("WP-D")
}
