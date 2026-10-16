package app.thebox.data.backup

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import app.thebox.core.common.BoxLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A backup file this app can open again: a `content://` URI (MediaStore), or an absolute path. */
data class StoredBackup(val location: String, val name: String, val where: Where) {
    enum class Where { DOWNLOADS, APP_STORAGE }
}

/** `.thebox` files found where backups go. [unreadable]: the location exists but could not be listed (F16, permissions). */
data class BackupListing(val files: List<StoredBackup>, val unreadable: Boolean)

/**
 * Where the "To Downloads" and weekly backups go (docs/design/m5-setup-settings.md §3.10.5, S6, F16):
 * API 29+: `MediaStore.Downloads` under `Download/TheBox/` (no permission); API 25–28: the public Download/TheBox
 * directory when `WRITE_EXTERNAL_STORAGE` is granted; otherwise app storage (`filesDir/backups`, survives updates only).
 *
 * **F16 (not measured by WP-D; the integration pass measures it on API 30/33):** expected behaviour is that on API 30+
 * a reinstalled app does not see files an earlier install wrote to Downloads; [list] then returns only this install's
 * files. Every failure to list (missing directory, `SecurityException`, a provider error) is answered with an empty
 * list and `unreadable = true`, never an exception, so the page offers the phone instead.
 */
interface BackupStorage {
    /** True when Downloads can be written on this TV now (MediaStore, or the legacy permission is granted). */
    fun downloadsWritable(): Boolean
    suspend fun write(name: String, body: suspend (OutputStream) -> Unit): StoredBackup
    fun list(): BackupListing
    /** Copies a stored backup into [dest] (the restore reads a plain file twice). */
    fun copyTo(location: String, dest: File): Boolean
    fun delete(location: String)
}

@Singleton
class AndroidBackupStorage @Inject constructor(@ApplicationContext private val context: Context) : BackupStorage {
    private val appDir get() = File(context.filesDir, "backups")

    private fun legacyDir(): File? {
        if (Build.VERSION.SDK_INT >= 29) return null
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return null
        @Suppress("DEPRECATION")
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
    }

    override fun downloadsWritable(): Boolean = Build.VERSION.SDK_INT >= 29 || legacyDir() != null

    override suspend fun write(name: String, body: suspend (OutputStream) -> Unit): StoredBackup {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return writeMediaStore(name, body)
            } catch (e: IOException) {
                BoxLog.w(TAG, "Downloads not writable (${e.javaClass.simpleName}); using app storage")
            } catch (e: SecurityException) {
                BoxLog.w(TAG, "Downloads not writable (SecurityException); using app storage")
            } catch (e: IllegalArgumentException) {
                BoxLog.w(TAG, "Downloads not writable (IllegalArgumentException); using app storage")
            }
        } else {
            legacyDir()?.let { dir ->
                if (dir.isDirectory || dir.mkdirs()) {
                    try {
                        return writeFile(File(dir, name), body, StoredBackup.Where.DOWNLOADS)
                    } catch (e: IOException) {
                        BoxLog.w(TAG, "Download/TheBox not writable (${e.javaClass.simpleName}); using app storage")
                    }
                }
            }
        }
        appDir.mkdirs()
        return writeFile(File(appDir, name), body, StoredBackup.Where.APP_STORAGE)
    }

    private suspend fun writeFile(f: File, body: suspend (OutputStream) -> Unit, where: StoredBackup.Where): StoredBackup {
        val part = File(f.parentFile, f.name + ".part")
        try {
            FileOutputStream(part).use { out -> body(out); out.fd.sync() }
            if (!part.renameTo(f)) throw IOException("rename failed")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        return StoredBackup(f.absolutePath, f.name, where)
    }

    @RequiresApi(29)
    private suspend fun writeMediaStore(name: String, body: suspend (OutputStream) -> Unit): StoredBackup {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("insert refused")
        try {
            (resolver.openOutputStream(uri) ?: throw IOException("no stream")).use { body(it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return StoredBackup(uri.toString(), name, StoredBackup.Where.DOWNLOADS)
    }

    override fun list(): BackupListing {
        val out = ArrayList<StoredBackup>()
        var unreadable = false
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                listMediaStore(out)
            } catch (e: RuntimeException) { // SecurityException, IllegalArgumentException, provider failures
                BoxLog.w(TAG, "Downloads not listable: ${e.javaClass.simpleName}")
                unreadable = true
            }
        } else {
            val dir = legacyDir()
            if (dir != null && dir.exists()) {
                val files = dir.listFiles()
                if (files == null) unreadable = true
                else files.filter { it.isFile && it.name.endsWith(EXT) }.forEach { out += StoredBackup(it.absolutePath, it.name, StoredBackup.Where.DOWNLOADS) }
            }
        }
        appDir.listFiles()?.filter { it.isFile && it.name.endsWith(EXT) }?.forEach { out += StoredBackup(it.absolutePath, it.name, StoredBackup.Where.APP_STORAGE) }
        return BackupListing(out.sortedByDescending { it.name }, unreadable)
    }

    @RequiresApi(29)
    private fun listMediaStore(out: MutableList<StoredBackup>) {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf(Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR + "%", "%$EXT")
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (c.moveToNext()) {
                val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(idCol).toString())
                out += StoredBackup(uri.toString(), c.getString(nameCol), StoredBackup.Where.DOWNLOADS)
            }
        } ?: throw IllegalStateException("no cursor")
    }

    override fun copyTo(location: String, dest: File): Boolean = try {
        val input = if (location.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(location)) else File(location).inputStream()
        input?.use { i -> FileOutputStream(dest).use { o -> i.copyTo(o, 64 * 1024) } } != null
    } catch (e: IOException) {
        BoxLog.w(TAG, "backup not readable: ${e.javaClass.simpleName}")
        false
    } catch (e: SecurityException) {
        BoxLog.w(TAG, "backup not readable: SecurityException")
        false
    }

    override fun delete(location: String) {
        try {
            if (location.startsWith("content://")) context.contentResolver.delete(Uri.parse(location), null, null) else File(location).delete()
        } catch (e: RuntimeException) {
            BoxLog.w(TAG, "could not delete an old automatic backup: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "BackupStorage"
        const val SUBDIR = "TheBox"
        const val EXT = ".thebox"
        const val MIME = "application/octet-stream"
    }
}
