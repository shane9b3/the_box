// Contract (M5 commit 0): docs/design/m5-setup-settings.md §2.4. Verbatim; changes go through the lead.
package app.thebox.core.setup

import app.thebox.core.common.Outcome
import app.thebox.core.xtream.XtreamAccountInfo
import kotlinx.coroutines.flow.Flow

/** Result of normalising what the user typed or pasted (§3.4.3). Pure. */
sealed interface NormalizedInput {
    data class Xtream(val server: String, val username: String?, val password: String?, val output: String?, val filledFromLink: Boolean) : NormalizedInput
    data class M3u(val url: String, val looksLikeXtream: Xtream?) : NormalizedInput
    data class Invalid(val reason: InputProblem) : NormalizedInput
}
enum class InputProblem { EMPTY, NOT_A_URL, UNSUPPORTED_SCHEME, STALKER_PORTAL, SPACES_IN_HOST }

interface SourceInputNormalizer {
    fun server(raw: String): NormalizedInput
    fun m3uUrl(raw: String): NormalizedInput
    /** Removes zero-width, NBSP and trailing whitespace; never changes inner characters of a password. */
    fun field(raw: String): String
}

/** The result card (product S3) and every failure as reason + next step. */
data class SetupCard(
    val tone: app.thebox.core.settings.Tone,
    val headline: String,
    val lines: List<String>,
    val primary: SetupAction,
    val secondary: SetupAction?,
)
enum class SetupAction { CONTINUE, SAVE_ANYWAY, EDIT, RETRY, USE_XTREAM_INSTEAD, ADD_AS_SINGLE_CHANNEL, USE_SECURE_DNS, PHONE_SETUP, CANCEL }

data class XtreamForm(val name: String?, val server: String, val username: String, val password: String, val output: String = "AUTO", val userAgent: String? = null, val backups: List<String> = emptyList())
data class M3uForm(
    val name: String?, val url: String?, val fileUri: String?, val epgUrls: List<String>, val userAgent: String? = null,
    /** (M5 integration 2026-10-16, WP-B amendment) The picked document's display name (the playlist's source label). */
    val fileName: String? = null,
)

sealed interface SetupProgress {
    data class Stage(val stage: ImportStage, val done: Boolean, val count: String?) : SetupProgress
    data class ReadyToWatch(val playlistId: Long) : SetupProgress
    data class Failed(val card: SetupCard) : SetupProgress
    data class Finished(val report: ImportSummary) : SetupProgress
}
/** Product S5 stage rows. */
enum class ImportStage { DOWNLOADING_PLAYLIST, READING_CHANNELS, GROUPS, DOWNLOADING_GUIDE, MATCHING_GUIDE }
data class ImportSummary(val playlistId: Long, val live: Int, val vodKept: Int, val groups: Int, val adultGroups: Int, val epgMatched: Int, val epgTotal: Int, val uaNote: String?)

/** :data implements. All suspend calls are main-safe. */
interface SourceSetup {
    /** player_api.php login only; nothing is stored. */
    suspend fun testXtream(form: XtreamForm): Outcome<XtreamAccountInfo>
    /** Creates the playlist (or updates [existingId] in place, keeping playlist_id) and starts the import. */
    suspend fun saveXtream(form: XtreamForm, existingId: Long? = null): Outcome<Long>
    suspend fun saveM3u(form: M3uForm, existingId: Long? = null): Outcome<Long>
    /** Stage updates for [playlistId]'s current import; completes after MATCHING_GUIDE or a failure. */
    fun progress(playlistId: Long): Flow<SetupProgress>
    /** Groups review (S6) writes; one transaction. */
    suspend fun applyGroupsReview(playlistId: Long, hidden: Set<String>, locked: Set<String>, unlocked: Set<String>)
    suspend fun cancelImport(playlistId: Long)
}

/** One short stream open through the broker (UA probe, decision §3.4.5). :player implements. */
interface StreamProbe {
    /**
     * Opens [url] with [userAgent] through the playlist's broker (StreamBrokerRegistry → ConnectionBrokerCore.acquire(
     * LeaseKind.PROBE, ownerTuneId = PROBE_OWNER_ID, waitMs); S12: no `StreamConnectionBroker` type exists), reads up to PROBE_BYTES or
     * PROBE_MS, sniffs (0x47 TS sync / #EXTM3U / HTML), and **always** closes the call before returning.
     * Never sends Range. Fails fast with LIMIT when the broker has no slot (never pre-empts PLAYBACK).
     */
    suspend fun probe(playlistId: Long, url: String, headers: Map<String, String>, userAgent: String?): ProbeResult
}
data class ProbeResult(val verdict: ProbeVerdict, val httpStatus: Int?, val bytes: Long, val ms: Long)
enum class ProbeVerdict { MEDIA, HLS, HTML, FORBIDDEN, LIMIT, NOT_FOUND, TIMEOUT, NETWORK, OTHER }
