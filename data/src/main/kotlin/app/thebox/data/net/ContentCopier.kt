package app.thebox.data.net

import android.content.Context
import android.net.Uri
import app.thebox.core.common.BoxLog
import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.data.contracts.FetchResult
import app.thebox.data.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * `M3U_FILE` playlists: copies a SAF `content://` URI (or a `file://` / plain path) to the cache file the
 * parser reads, through `dest.part` + rename like the fetcher. The persisted URI permission is taken by
 * the UI when the user picks the file.
 */
class ContentCopier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun copy(uri: String, dest: File, maxBytes: Long = 2L * 1024 * 1024 * 1024): Outcome<FetchResult> = withContext(io) {
        val part = File(dest.path + ".part")
        try {
            open(uri).use { input ->
                part.parentFile?.mkdirs()
                var total = 0L
                part.outputStream().buffered(BUFFER).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) {
                            out.close(); part.delete()
                            return@withContext Outcome.Err(SourceError.Unsupported("file larger than $maxBytes bytes"))
                        }
                        out.write(buf, 0, n)
                        coroutineContext.ensureActive()
                    }
                }
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) throw IOException("cannot rename")
                Outcome.Ok(FetchResult(false, total, null, null, null, null))
            }
        } catch (e: CancellationException) {
            part.delete(); throw e
        } catch (e: FileNotFoundException) {
            part.delete()
            Outcome.Err(SourceError.NotASource(NotASourceKind.EMPTY))
        } catch (e: SecurityException) {
            part.delete()
            BoxLog.w(TAG, "no permission for the playlist file")
            Outcome.Err(SourceError.Unsupported("no permission to read the file; pick it again"))
        } catch (e: IOException) {
            part.delete()
            Outcome.Err(SourceError.Internal("copy failed: ${e.javaClass.simpleName}"))
        }
    }

    private fun open(uri: String): InputStream {
        val parsed = Uri.parse(uri)
        return when (parsed.scheme) {
            "content", "android.resource" -> context.contentResolver.openInputStream(parsed) ?: throw FileNotFoundException(uri)
            "file" -> File(parsed.path ?: throw FileNotFoundException(uri)).inputStream()
            null -> File(uri).inputStream()
            else -> context.contentResolver.openInputStream(parsed) ?: throw FileNotFoundException(uri)
        }
    }

    companion object {
        private const val TAG = "ContentCopier"
        private const val BUFFER = 64 * 1024
    }
}
