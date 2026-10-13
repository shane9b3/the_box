package app.thebox.core.epg.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * File layout of the epg directory (§2.5.1): `epg-<gen>.db`, `epg-<gen>.db.building`,
 * `epg-<gen>.db-journal`, the pointer `epg-current` and its temp file.
 *
 * Renames use `File.renameTo` (POSIX `rename(2)`: atomic and replacing within one directory) because
 * `java.nio.file` is API 26+ and minSdk is 25.
 */
internal class GenerationFiles(val dir: File) {
    val pointer = File(dir, POINTER)
    val pointerTmp = File(dir, "$POINTER.tmp")

    fun db(gen: Long) = File(dir, "epg-$gen.db")
    fun building(gen: Long) = File(dir, "epg-$gen.db.building")
    fun journal(db: File) = File(db.path + "-journal")

    fun ensureDir() {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create $dir")
    }

    /** Generations with a published-name file (`epg-<gen>.db`), highest first. */
    fun publishedGenerations(): List<Long> =
        (dir.listFiles() ?: emptyArray()).mapNotNull { DB.matchEntire(it.name)?.groupValues?.get(1)?.toLongOrNull() }.sortedDescending()

    /** Highest generation number used by any file (published, building or journal), or 0. */
    fun maxGeneration(): Long =
        (dir.listFiles() ?: emptyArray()).mapNotNull { ANY.matchEntire(it.name)?.groupValues?.get(1)?.toLongOrNull() }.maxOrNull() ?: 0L

    fun readPointer(): Long? = try {
        if (pointer.isFile) pointer.readText().trim().toLongOrNull()?.takeIf { it > 0 } else null
    } catch (_: IOException) {
        null
    }

    /** temp + fsync + rename: the rename is the commit point of a build (§2.5.2 step 6). */
    fun writePointer(gen: Long) {
        FileOutputStream(pointerTmp).use { out ->
            out.write("$gen\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            out.fd.sync()
        }
        rename(pointerTmp, pointer)
    }

    fun deletePointer() {
        pointer.delete()
        pointerTmp.delete()
    }

    fun fsync(file: File) {
        RandomAccessFile(file, "rw").use { it.fd.sync() }
    }

    fun rename(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("rename ${from.name} -> ${to.name} failed")
    }

    /**
     * Deletes every epg file except the generations in [keep]: other `.db` files, every `.building`,
     * the pointer temp file, and journals whose database is not kept.
     */
    fun cleanup(keep: Set<Long>) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            val name = f.name
            when {
                name == pointerTmp.name -> f.delete()
                name.endsWith(".db.building") -> f.delete()
                else -> {
                    val m = ANY.matchEntire(name) ?: continue
                    val gen = m.groupValues[1].toLongOrNull() ?: continue
                    if (gen !in keep) f.delete()
                }
            }
        }
    }

    fun deleteGeneration(gen: Long) {
        val db = db(gen)
        journal(db).delete()
        db.delete()
    }

    companion object {
        const val POINTER = "epg-current"
        private val DB = Regex("""epg-(\d+)\.db""")
        private val ANY = Regex("""epg-(\d+)\.db(?:\.building|-journal|-wal|-shm)?""")
    }
}
