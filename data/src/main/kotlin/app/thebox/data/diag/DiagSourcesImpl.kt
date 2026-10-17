package app.thebox.data.diag

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.diag.DiagSources
import app.thebox.core.diag.ExportBundleBuilder
import app.thebox.core.diag.ExportDone
import app.thebox.core.diag.ExportTarget
import app.thebox.core.diag.PlaylistBrief
import app.thebox.core.diag.SyncRow
import app.thebox.core.diag.SyncRowState
import app.thebox.core.setup.lan.PhoneLink
import app.thebox.core.source.PlaylistType
import app.thebox.data.di.IoDispatcher
import app.thebox.data.repo.EpgWorkRequester
import app.thebox.data.repo.PlaylistRepository
import app.thebox.data.repo.SyncStatusRepository
import app.thebox.data.repo.Trigger
import app.thebox.data.work.RefreshScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** The data side of the About/Diagnostics/SyncStatus pages (docs/design/m5-setup-settings.md §3.11, §3.3.2). */
@Singleton
class DiagSourcesImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlists: PlaylistRepository,
    private val sync: SyncStatusRepository,
    private val scheduler: RefreshScheduler,
    private val epgWork: EpgWorkRequester,
    private val diagnostics: DiagnosticsImpl,
    private val phone: PhoneLink,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DiagSources {

    override suspend fun playlists(): List<PlaylistBrief> = withContext(io) {
        playlists.all().map { PlaylistBrief(it.id, it.name, it.type == PlaylistType.XTREAM, it.displaySource) }
    }

    override fun syncRows(): Flow<List<SyncRow>> = combine(playlists.observePlaylists(), sync.status) { list, s ->
        val rows = ArrayList<SyncRow>(list.size + 1 + s.failures.size)
        rows += SyncRow(
            key = "epg", playlistId = null, label = "",
            state = when { s.epgImporting || s.epgPatching -> SyncRowState.RUNNING; s.failures.any { it.targetType == DiagnosticsImpl.TARGET_EPG } -> SyncRowState.FAILED; else -> SyncRowState.DONE },
            errorCode = s.failures.firstOrNull { it.targetType == DiagnosticsImpl.TARGET_EPG }?.errorCode,
            progress = s.progress.takeIf { s.epgImporting || s.epgPatching },
            failureLogId = s.failures.firstOrNull { it.targetType == DiagnosticsImpl.TARGET_EPG }?.id,
        )
        for (p in list) {
            val failure = s.failures.firstOrNull { it.targetType == DiagnosticsImpl.TARGET_PLAYLIST && it.targetId == p.id }
            rows += SyncRow(
                key = "playlist:${p.id}", playlistId = p.id, label = p.name,
                state = when { p.id in s.playlistsRefreshing -> SyncRowState.RUNNING; failure != null -> SyncRowState.FAILED; else -> SyncRowState.DONE },
                errorCode = failure?.errorCode, progress = s.progress.takeIf { p.id in s.playlistsRefreshing }, failureLogId = failure?.id,
            )
        }
        rows
    }

    override fun updateGuideNow() = epgWork.importEpgNow(Trigger.MANUAL)

    override fun refreshPlaylist(playlistId: Long) = scheduler.refreshNow(playlistId, Trigger.MANUAL)

    override suspend fun acknowledge(failureLogId: Long) = sync.acknowledge(failureLogId)

    override suspend fun export(target: ExportTarget, nowMs: Long): Outcome<ExportDone> = withContext(io) {
        val name = "TheBox-logs-" + FILE_TIME.format(Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())) + ".txt"
        try {
            when (target) {
                ExportTarget.PHONE -> {
                    val dir = File(context.cacheDir, PHONE_DIR).apply { mkdirs() }
                    dir.listFiles { f -> f.name.startsWith("TheBox-logs-") }?.forEach { it.delete() }
                    val file = File(dir, name)
                    val summary = file.outputStream().use { out -> write(out, nowMs) }
                    phone.offerDownload(name, file.absolutePath, "text/plain")
                    Outcome.Ok(ExportDone(name, summary.first, summary.second, "phone"))
                }
                ExportTarget.DOWNLOADS -> toDownloads(name, nowMs)
            }
        } catch (e: IOException) {
            Outcome.Err(SourceError.DiskSpace(0, context.filesDir.usableSpace))
        } catch (e: SecurityException) {
            Outcome.Err(SourceError.Unsupported("storage permission"))
        }
    }

    private suspend fun write(out: OutputStream, nowMs: Long): Pair<Long, Int> {
        val s = ExportBundleBuilder().write(diagnostics.inputs(nowMs), out)
        return s.bytes to s.auditHits
    }

    /** API 29+: MediaStore Downloads under `Download/TheBox` (no permission). API 25–28: the public folder with WRITE_EXTERNAL_STORAGE. */
    private suspend fun toDownloads(name: String, nowMs: Long): Outcome<ExportDone> {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_DIR)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return Outcome.Err(SourceError.Unsupported("no Downloads folder"))
            val s = try {
                resolver.openOutputStream(uri)?.use { write(it, nowMs) } ?: return Outcome.Err(SourceError.Unsupported("no Downloads folder"))
            } catch (e: IOException) {
                resolver.delete(uri, null, null)
                throw e
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return Outcome.Ok(ExportDone(name, s.first, s.second, Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_DIR))
        }
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return Outcome.Err(SourceError.Unsupported("storage permission"))
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_DIR).apply { mkdirs() }
        val s = File(dir, name).outputStream().use { write(it, nowMs) }
        return Outcome.Ok(ExportDone(name, s.first, s.second, Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_DIR))
    }

    override fun resetApp(): Boolean = context.getSystemService(ActivityManager::class.java)?.clearApplicationUserData() ?: false

    companion object {
        /**
         * D-B3 (code review 2026-10-16): the log bundle for the phone lives in its own directory, never in the LAN
         * server's spool (`cacheDir/phone/`, emptied whenever a phone session starts or stops). The phone link deletes it
         * after its one download or when the session ends (D-S2).
         */
        const val PHONE_DIR = "phone-out"
        const val DOWNLOAD_DIR = "TheBox"
        private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
    }
}
