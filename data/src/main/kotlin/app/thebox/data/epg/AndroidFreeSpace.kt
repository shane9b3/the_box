package app.thebox.data.epg

import android.os.StatFs
import app.thebox.core.epg.FreeSpace
import java.io.File

/** `StatFs`-backed free-space probe for the EPG disk precheck (§2.5.5). Uses the nearest existing ancestor. */
object AndroidFreeSpace : FreeSpace {
    override fun bytesFree(dir: File): Long {
        var d: File? = dir
        while (d != null && !d.exists()) d = d.parentFile
        if (d == null) return 0L
        return try {
            StatFs(d.path).availableBytes
        } catch (e: IllegalArgumentException) {
            d.usableSpace
        }
    }
}
