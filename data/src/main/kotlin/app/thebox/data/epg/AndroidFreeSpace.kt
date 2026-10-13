package app.thebox.data.epg

import app.thebox.core.epg.FreeSpace
import java.io.File

/** `StatFs`-backed free-space probe for the EPG disk precheck (§2.5.5). Commit-0 stub; WP-D owns this file. */
object AndroidFreeSpace : FreeSpace {
    override fun bytesFree(dir: File): Long = TODO("WP-D")
}
