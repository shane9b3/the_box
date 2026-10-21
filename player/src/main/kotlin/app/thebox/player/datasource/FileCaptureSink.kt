package app.thebox.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import app.thebox.core.common.BoxLog
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * (diagnosis 2026-10-18) Debug capture behind the `TeeDataSource` seam: appends the continuous, packet-aligned TS of
 * one tune to `<dir>/tune-<tuneId>.ts` (reconnects append to the same file). Set through
 * `TsDebugOverrides.captureDir` (debug builds only); never used in release. Write failures end the capture, never
 * playback.
 */
@OptIn(UnstableApi::class)
internal class FileCaptureSink(dir: File, tuneId: Long) : DataSink {
    private val file = File(dir, "tune-$tuneId.ts")
    private var out: OutputStream? = null
    private var failed = false

    override fun open(dataSpec: DataSpec) {
        if (failed || out != null) return
        out = try {
            file.parentFile?.mkdirs()
            BufferedOutputStream(FileOutputStream(file, true), 256 * 1024)
        } catch (e: Exception) {
            failed = true
            BoxLog.w(TAG, "capture open failed: ${e.javaClass.simpleName}")
            null
        }
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val o = out ?: return
        try {
            o.write(buffer, offset, length)
        } catch (e: Exception) {
            failed = true
            BoxLog.w(TAG, "capture write failed: ${e.javaClass.simpleName}")
            close()
        }
    }

    override fun close() {
        val o = out ?: return
        out = null
        try { o.close() } catch (_: Exception) { }
    }

    private companion object { const val TAG = "TsCapture" }
}
