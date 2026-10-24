// WP-S1 (docs/design/m6-storage-locations.md §3.5 "deviceId"; §18 R7).
package app.thebox.data.storage

import app.thebox.core.storage.StorageConstants
import java.io.File
import java.security.SecureRandom

/**
 * This install's id on shared folders: 12 lowercase hex digits from [SecureRandom] in `noBackupFilesDir/device-id`
 * (§3.5). Never backed up, so two boxes restored from one backup differ; not `ANDROID_ID` (R7). Read (or made) once, on
 * first use, from a tiny local file; a file that cannot be written still gives a stable id for this process.
 */
class DeviceIdentity(private val dir: File) {
    val id: String by lazy { load() }

    private fun load(): String {
        val f = File(dir, FILE_NAME)
        try {
            if (f.isFile) {
                val s = f.readText(Charsets.US_ASCII).trim()
                if (valid(s)) return s
            }
        } catch (_: Exception) { /* unreadable: make a new one */ }
        val fresh = generate()
        try {
            dir.mkdirs()
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(fresh, Charsets.US_ASCII)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Exception) { /* not persisted: this process keeps the id */ }
        return fresh
    }

    companion object {
        const val FILE_NAME = "device-id"

        fun valid(s: String): Boolean = s.length == StorageConstants.DEVICE_ID_HEX_CHARS && s.all { it in '0'..'9' || it in 'a'..'f' }

        fun generate(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(StorageConstants.DEVICE_ID_HEX_CHARS / 2).also(random::nextBytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
