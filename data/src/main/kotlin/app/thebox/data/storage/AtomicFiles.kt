// Q-U7 (save-location closure fix pass, 2026-10-28): the one writer of the feature's tiny device-state files.
package app.thebox.data.storage

import java.io.File
import java.io.IOException

/**
 * Replaces [file] with [text] through `<file>.tmp` + rename, so a reader (or a crash mid-write) never sees half a file.
 * When the rename onto an existing file fails (some filesystems refuse to replace), the old file is deleted and the
 * rename tried again. Creates the parent directory. Throws [IOException] when the file could not be written; the
 * callers treat the in-memory value as good for this process.
 *
 * Used by `DeviceIdentity`, `LastGoodStore`, `StorageLocationsImpl` (seen USB drives) and `:app`'s sign-in toast counters.
 */
@Throws(IOException::class)
fun writeTextAtomically(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.path + ".tmp")
    tmp.writeText(text, Charsets.UTF_8)
    if (!tmp.renameTo(file)) {
        file.delete()
        if (!tmp.renameTo(file)) throw IOException("rename failed: ${file.name}")
    }
}
