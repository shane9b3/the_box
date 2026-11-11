package app.thebox.core.epg

import app.thebox.core.common.CancelCheck
import app.thebox.core.common.ImportGate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.time.ZoneId

/** Stable reference to an EPG channel: library.db epg_source.id + XMLTV channel id. Never an epg.db row id. */
data class EpgChannelRef(val sourceId: Long, val xmltvId: String)

data class EpgChannelInfo(
    val sourceId: Long,
    val xmltvId: String,
    /** All display-names, preferred language first. */
    val displayNames: List<String>,
    val iconUrl: String?,
    /** True when programmes for this channel were whitelisted in the current generation. */
    val imported: Boolean,
    val programmeCount: Int,
)

fun interface EpgWhitelist {
    fun accepts(xmltvId: String): Boolean

    companion object {
        val ALL = EpgWhitelist { true }
        val NONE = EpgWhitelist { false }
    }
}

/**
 * Called by the importer once per source, on the importer thread, after the source's leading
 * `<channel>` elements were stored and before the first `<programme>` is accepted. [channels] is
 * backed by the building database and is valid only during the call. Implemented by WP-D (matcher).
 */
fun interface EpgWhitelistResolver {
    fun whitelistFor(sourceId: Long, channels: Sequence<EpgChannelInfo>): EpgWhitelist
}

data class EpgSourceFile(
    val sourceId: Long,
    /** Raw downloaded bytes (any compression; the store sniffs). */
    val file: File,
    /** Zone for times without an offset; null = UTC (protocols §2.6). */
    val assumeZone: ZoneId? = null,
)

data class EpgBuildRequest(
    /** Priority order: first source with data wins per channel (resolved by WP-D after the build). */
    val sources: List<EpgSourceFile>,
    val nowMs: Long,
    val pastDays: Int,
    val futureDays: Int,
    val storeDescriptions: Boolean = true,
    val maxDescriptionChars: Int = 2048,
    val preferredLanguages: List<String> = emptyList(),
    /** Test/diagnostic switch: skip the disk check and use the in-place path. */
    val forceInPlace: Boolean = false,
    /**
     * (2026-11-10, deferred-items review S1) [EpgBuildInputs.fingerprint] of what this build was made from. Stored in
     * the generation's meta and returned as [EpgGenerationInfo.buildInputs]; null stores nothing.
     */
    val buildInputs: String? = null,
)

data class EpgPatchRequest(
    val sources: List<EpgSourceFile>,
    /** Channels whose programmes must be (re)imported into the current generation. */
    val refs: List<EpgChannelRef>,
    val nowMs: Long,
    val pastDays: Int,
    val futureDays: Int,
    val storeDescriptions: Boolean = true,
    val maxDescriptionChars: Int = 2048,
    val preferredLanguages: List<String> = emptyList(),
)

data class EpgProgress(val sourceId: Long, val stage: Stage, val bytesRead: Long, val bytesTotal: Long?, val programmesKept: Int) {
    enum class Stage { PARSING, REPAIRING, INDEXING, PUBLISHING }
}

/** (reviewed 2026-10-13, B2: @Serializable. WP-D stores it in refresh_log.report_json.) */
@Serializable
data class EpgSourceStat(
    val sourceId: Long,
    val channels: Int,
    val programmesSeen: Int,
    val programmesKept: Int,
    val skippedWhitelist: Int,
    val skippedWindow: Int,
    val clipped: Int,
    val split: Int,
    val badTimes: Int,
    val bytes: Long,
    val truncated: Boolean,
    val errorCode: String?,
)

@Serializable
data class EpgImportReport(
    val mode: Mode,
    val generation: Long,
    val sources: List<EpgSourceStat>,
    val programmes: Int,
    val texts: Int,
    val dbBytes: Long,
    val durationMs: Long,
    val fts: String,
    /** Set when a source failed and the build was abandoned (the old generation stays current). */
    val failedSourceId: Long? = null,
) {
    enum class Mode { REBUILD, IN_PLACE, PATCH }
}

data class EpgGenerationInfo(
    val generation: Long,
    val patchSeq: Int,
    val builtAtMs: Long,
    val programmes: Int,
    val channels: Int,
    val dbBytes: Long,
    val windowFromMin: Int,
    val windowToMin: Int,
    val fts: String,
    /** (2026-11-10, review S1) [EpgBuildRequest.buildInputs] of the last full build; null for a generation built before. */
    val buildInputs: String? = null,
)

fun interface FreeSpace {
    fun bytesFree(dir: File): Long
}

/** Stub in commit 0 at epg/store/EpgStoreImpl.kt; WP-B owns the body. */
interface EpgStore {
    /** Current generation; emits on publish and after every patch (patchSeq++). */
    val info: StateFlow<EpgGenerationInfo?>

    /** Ref-counted reader on the current generation, or null if none. Caller must close(). */
    fun openReader(): EpgReader?

    /** Full rebuild into a new generation (or in-place on low disk). Blocking; single writer (WP-D serialises). */
    fun build(
        request: EpgBuildRequest,
        whitelist: EpgWhitelistResolver,
        gate: ImportGate = ImportGate.Open,
        cancel: CancelCheck = CancelCheck.Never,
        progress: (EpgProgress) -> Unit = {},
    ): EpgImportReport

    /** Targeted import into the current generation (new mappings). Blocking. */
    fun patch(
        request: EpgPatchRequest,
        gate: ImportGate = ImportGate.Open,
        cancel: CancelCheck = CancelCheck.Never,
        progress: (EpgProgress) -> Unit = {},
    ): EpgImportReport

    /** Startup recovery (§2.5.4). Idempotent. Call once, off the main thread, before openReader(). */
    fun recover()

    /** Deletes every generation (Settings → "Rebuild guide data"). */
    fun clear()
}

/** Bit flags stored in programme.flags. Append-only. */
object ProgrammeFlags {
    const val NEW = 1
    const val PREMIERE = 1 shl 1
    const val LAST_CHANCE = 1 shl 2
    const val PREVIOUSLY_SHOWN = 1 shl 3
    const val LIVE = 1 shl 4
    /** This row is a later segment of a programme split at 6 h. */
    const val CONTINUATION = 1 shl 5
    /** This row is followed by a continuation segment. */
    const val CONTINUED = 1 shl 6
    const val HAS_CATCHUP_ID = 1 shl 7
    const val SUBTITLES = 1 shl 8
    const val HD = 1 shl 9
    const val UHD = 1 shl 10
    /** End was clipped to the next programme's start. */
    const val CLIPPED = 1 shl 11
    /** Stop was missing and inferred. */
    const val STOP_INFERRED = 1 shl 12
}

/** Reusable struct-of-arrays buffer for the guide window (zero per-row allocation besides cache-miss titles). */
class EpgWindow(initialCapacity: Int = 1024) {
    var size: Int = 0
    /** Index into the `channels` array passed to [EpgReader.window]. Rows are sorted by (channelIndex, startMin). */
    var channelIndex = IntArray(initialCapacity)
    /** Display (shifted) epoch minutes. */
    var startMin = IntArray(initialCapacity)
    var endMin = IntArray(initialCapacity)
    /** Raw (unshifted) start: the catch-up and reminder key. */
    var rawStartMin = IntArray(initialCapacity)
    var textId = IntArray(initialCapacity)
    var flags = IntArray(initialCapacity)
    var titles = arrayOfNulls<String>(initialCapacity)

    fun clear() { size = 0 }

    fun ensureCapacity(n: Int) {
        if (n <= channelIndex.size) return
        val c = maxOf(n, channelIndex.size * 2)
        channelIndex = channelIndex.copyOf(c); startMin = startMin.copyOf(c); endMin = endMin.copyOf(c)
        rawStartMin = rawStartMin.copyOf(c); textId = textId.copyOf(c); flags = flags.copyOf(c); titles = titles.copyOf(c)
    }
}

data class ProgrammeDetail(
    /** Generation-local id: valid only for the reader that produced it. Never persist. */
    val channel: Int,
    val ref: EpgChannelRef,
    val rawStartMin: Int,
    val rawEndMin: Int,
    val startMin: Int,
    val endMin: Int,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val categories: List<String>,
    val episodeLabel: String?,
    val season: Int?,
    val episode: Int?,
    val iconUrl: String?,
    val rating: String?,
    val starRating: String?,
    val year: Int?,
    val flags: Int,
    val catchupId: String?,
    /** Hash64.of(title): with (stableKey, rawStartMin) the reminder/recording key. */
    val titleHash: Long,
)

data class EpgSearchHit(val channel: Int, val ref: EpgChannelRef, val rawStartMin: Int, val rawEndMin: Int, val title: String, val subtitle: String?)

/**
 * Read API on one generation. Not thread-safe per call site: the guide uses one reader from one
 * thread; other features open their own reader. Every Int "channel" id is generation-local.
 */
interface EpgReader : AutoCloseable {
    val info: EpgGenerationInfo

    /** epg_channel ids for [refs] (-1 = not in this generation). Case-insensitive fallback on xmltv_id. */
    fun resolve(refs: List<EpgChannelRef>, caseInsensitive: Boolean = true): IntArray

    /**
     * Fills [out] with programmes overlapping [fromMin, toMin) in DISPLAY minutes for each channel,
     * where display = raw + shiftMin[i]. [channels] may contain -1 (skipped).
     */
    fun window(channels: IntArray, shiftMin: IntArray, fromMin: Int, toMin: Int, out: EpgWindow)

    fun detail(channel: Int, rawStartMin: Int, shiftMin: Int): ProgrammeDetail?

    /** Programme airing at display minute [atMin], or null (gap). */
    fun at(channel: Int, atMin: Int, shiftMin: Int): ProgrammeDetail?

    /**
     * FTS over title+subtitle; airings ending after [nowMin] (raw), best text first, at most [perText] airings per
     * text in start order. Rows flagged ProgrammeFlags.CONTINUATION are never returned (M1 code review Later 10).
     * (M3 commit 0, docs/design/m3-guide.md §2.7; WP-B implements [perText] and the CONTINUATION filter.)
     */
    fun search(query: String, nowMin: Int, limit: Int = 50, perText: Int = Int.MAX_VALUE): List<EpgSearchHit>

    /**
     * Past airings for catch-up search: end_min in (pastFromMin, nowMin] (raw), best text first, at most [perText]
     * airings per text, newest first. CONTINUATION rows are never returned. Default body returns emptyList() so
     * fakes compile; EpgReaderImpl overrides it.
     */
    fun searchPast(query: String, nowMin: Int, pastFromMin: Int, limit: Int = 100, perText: Int = 5): List<EpgSearchHit> = emptyList()

    /**
     * All channels (optionally of one source), pushed to [block] on the calling thread.
     * (reviewed 2026-10-13, S13: callback instead of a lazy Sequence. An abandoned Sequence would keep
     * the statement and the connection lock held. The statement is reset in `finally`.)
     */
    fun forEachChannel(sourceId: Long? = null, block: (EpgChannelInfo) -> Unit)

    /** Picker search over EPG channel ids and display names (S13). */
    fun findChannels(text: String, limit: Int = 50): List<EpgChannelInfo>

    fun refOf(channel: Int): EpgChannelRef?
}
