package app.thebox.core.diag

import app.thebox.core.common.Outcome
import kotlinx.coroutines.flow.Flow

/** A playlist as the About/diagnostics pages show it (no URL, no login). */
data class PlaylistBrief(val id: Long, val name: String, val xtream: Boolean, val displayHost: String)

/** One row of the SyncStatus page (§3.3.2, product S15). [failureLogId] non-null for an unacknowledged failure. */
data class SyncRow(
    val key: String,
    val playlistId: Long?,
    val label: String,
    val state: SyncRowState,
    /** Stable error code (`http.403`, `net.dns`, …) when [state] is FAILED. */
    val errorCode: String? = null,
    val progress: String? = null,
    val failureLogId: Long? = null,
)

enum class SyncRowState { IDLE, QUEUED, RUNNING, DONE, FAILED }

/** Where the log export goes (§3.3.10 `about.export_logs`, §3.11.4). The SAF picker target is WP-D's flow (see §1.8a). */
enum class ExportTarget { PHONE, DOWNLOADS }

data class ExportDone(val fileName: String, val bytes: Long, val auditHits: Int, val where: String)

/**
 * The data side of the About, Diagnostics and SyncStatus pages (§3.11.1), implemented in `:data`, so `:ui`'s page
 * builders need no `:data` type. Main-safe; every suspend member runs its IO on the IO dispatcher.
 */
interface DiagSources {
    suspend fun playlists(): List<PlaylistBrief>
    fun syncRows(): Flow<List<SyncRow>>
    /** "Update guide now" (`EpgWorkRequester.importEpgNow(MANUAL)`). */
    fun updateGuideNow()
    fun refreshPlaylist(playlistId: Long)
    suspend fun acknowledge(failureLogId: Long)
    /** Writes the export to [target]. PHONE writes it to the app cache and offers it through `PhoneLink` (LOGS_DOWNLOAD). */
    suspend fun export(target: ExportTarget, nowMs: Long): Outcome<ExportDone>
    /** `about.reset` (§3.3.10, F25): `ActivityManager.clearApplicationUserData()`; the process ends when it succeeds. */
    fun resetApp(): Boolean
}

/**
 * The connection test's Stream-speed step (§3.11.2): samples the playing channel for [durationMs] when it belongs to
 * [playlistId], else null. Implemented in `:ui` over the playback controller's stats (the only module that sees both).
 */
fun interface StreamSampler {
    suspend fun sample(playlistId: Long, durationMs: Long): StreamSample?

    companion object {
        const val SAMPLE_MS = 10_000L
        val NONE = StreamSampler { _, _ -> null }
    }
}
