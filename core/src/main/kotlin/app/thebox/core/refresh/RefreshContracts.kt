package app.thebox.core.refresh

import app.thebox.core.common.CancelCheck
import app.thebox.core.model.StableKey
import app.thebox.core.source.EntryKind
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.PlaylistType
import kotlinx.serialization.Serializable

/** The only identity user data may reference. */
data class ChannelIdentity(val playlistId: Long, val stableKey: StableKey)

/** XTREAM covers the "xc:", "xcm:" and "xce:" prefixes (live, movie, episode). */
enum class KeyRule(val prefix: String) { XTREAM("xc"), TVG("tvg:"), URL("url:"), NAME_GROUP("ng:") }

data class DerivedKey(val key: StableKey, val rule: KeyRule)

/**
 * [credentials] (from pass 1) lets UrlKey drop credential path segments; [xtreamShaped] enables Xtream
 * URL detection for keys (reviewed 2026-10-13, blocker B6: long AND short form, and only when the parsed pair equals [credentials]).
 */
data class KeyContext(val playlistType: PlaylistType, val xtreamShaped: Boolean, val credentials: DetectedCredentials? = null)

/** Stub in commit 0 at identity/StableKeyDeriverImpl.kt; WP-A owns the body. */
interface StableKeyDeriver {
    /** Without occurrence suffix; the differ adds `~n` for duplicates. */
    fun derive(entry: ParsedChannel, ctx: KeyContext): DerivedKey
}

/** Credentials found by pass 1 in an Xtream-shaped M3U; the sink stores them in the playlist secret. */
data class DetectedCredentials(val base: String, val username: String, val password: String)

data class KeyedChannel(
    val stableKey: StableKey,
    val rule: KeyRule,
    /** "xcat:<id>" | "g:<NameNormalizer.forGroup(group)>" | "g:" when no group. (reviewed 2026-10-13, B5: forGroup, not forKey.) */
    val groupKey: String,
    /** Stored channel.url: credentials replaced by {{u}}/{{p}} when detected (§3.2.4). */
    val urlTemplate: String,
    val contentHash: Long,
    val urlHash: Long,
    val nameHash: Long,
    val sortIndex: Int,
    val channel: ParsedChannel,
)

/** One existing row, streamed from library.db by WP-D; the differ keeps only primitives (§3.3.2). */
class ExistingChannel(
    val id: Long,
    val stableKey: String,
    val contentHash: Long,
    val urlHash: Long,
    val nameHash: Long,
    val sortIndex: Int,
    val missingSince: Long?,
    val missingCount: Int,
)

fun interface ExistingChannels {
    fun forEach(block: (ExistingChannel) -> Unit)
}

/** Re-openable producer: the differ calls [produce] twice (pass 1 validate, pass 2 merge). */
fun interface EntryProducer {
    fun produce(consumer: (ParsedChannel) -> Unit)
}

data class GroupRow(val groupKey: String, val name: String, val kind: EntryKind, val sortIndex: Int, val adult: Boolean)

class ChannelUpdate(val id: Long, val channel: KeyedChannel, val restored: Boolean)

/** Content unchanged; only position and/or missing state changed. */
class ChannelTouch(val id: Long, val sortIndex: Int, val restored: Boolean)

class MergeBatch(val inserts: List<KeyedChannel>, val updates: List<ChannelUpdate>, val touches: List<ChannelTouch>)

class MergeFinish(
    val nowMs: Long,
    /** Seen last time, not seen now: set missing_since = now, missing_count = 1. */
    val newlyMissingIds: LongArray,
    /** Already missing and still missing: missing_count += 1. */
    val stillMissingIds: LongArray,
    /** Grace expired (§3.3.4): delete the channel rows (user data stays). */
    val purgeIds: LongArray,
    /** Groups not seen in this refresh get missing_since; seen ones are cleared. */
    val groupKeysSeen: Set<String>,
    /**
     * (reviewed 2026-10-13, S7) Single-channel key changes detected by the differ: a newly-missing slot
     * and an inserted entry with the same urlHash, unique on both sides. Typical cause: the provider added
     * or fixed a tvg-id. The sink applies them with remapUserData inside the finish transaction.
     */
    val autoRemaps: List<KeyRemap> = emptyList(),
    /** Purged keys: the sink writes purged_key tombstones (§2.1.2) for the 180-day orphan rule. */
    val purgedKeys: List<String> = emptyList(),
)

data class FinishStats(val purged: Int, val favouritesKept: Int, val favouritesOrphaned: Int, val removedSample: List<String>)

/**
 * Implemented by WP-D (RoomMergeSink). Each call is one transaction except [begin]+first batch may share one.
 * (reviewed 2026-10-13, S22: blocking by design. RoomMergeSink bridges with
 * `runBlocking { db.useWriterConnection { it.immediateTransaction { … } } }` on the importer thread,
 * never on Main. useWriterConnection refreshes the InvalidationTracker, so Flows see the batch.)
 */
interface MergeSink {
    fun begin(groups: List<GroupRow>, detected: DetectedCredentials?)
    fun apply(batch: MergeBatch)
    fun finish(finish: MergeFinish): FinishStats
    fun abort(error: Throwable)
}

data class MergeContext(
    val playlistId: Long,
    val playlistType: PlaylistType,
    val refreshSeq: Long,
    val nowMs: Long,
    /** Live+radio count after the previous successful refresh (shrink guard). */
    val previousLiveCount: Int,
    val forceAcceptShrink: Boolean = false,
    val graceMs: Long = 7L * 24 * 3600 * 1000,
    val purgeAfterMissingRefreshes: Int = 2,
    val batchSize: Int = 2_000,
)

/** Stub in commit 0 at refresh/PlaylistDifferImpl.kt; WP-A owns the body. */
interface PlaylistDiffer {
    /** Throws SourceException(SuspiciousShrink | NotASource(EMPTY)) after pass 1, before touching [sink]. */
    fun merge(entries: EntryProducer, existing: ExistingChannels, sink: MergeSink, ctx: MergeContext, cancel: CancelCheck = CancelCheck.Never): RefreshReport
}

data class RemapCandidate(val stableKey: String, val name: String, val groupName: String?)
data class KeyRemap(val oldKey: String, val newKey: String, val sameGroup: Boolean)

/** Stub in commit 0 at refresh/RemapPlannerImpl.kt; WP-A owns the body. */
interface RemapPlanner {
    fun plan(missing: List<RemapCandidate>, added: List<RemapCandidate>): List<KeyRemap>
}

@Serializable
enum class RefreshOutcome { SUCCESS, NOT_MODIFIED, FAILED, CANCELLED }

/** Persisted as refresh_log.report_json; shown on S12 ("+12 new · −3 removed · 2 URLs changed · favourites kept"). */
@Serializable
data class RefreshReport(
    val playlistId: Long,
    val refreshSeq: Long,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val outcome: RefreshOutcome,
    val errorCode: String? = null,
    val entries: Int = 0,
    val live: Int = 0,
    val radio: Int = 0,
    val movies: Int = 0,
    val series: Int = 0,
    val added: Int = 0,
    val updated: Int = 0,
    val urlChanged: Int = 0,
    val renamed: Int = 0,
    val moved: Int = 0,
    val unchanged: Int = 0,
    val restored: Int = 0,
    val newlyMissing: Int = 0,
    val purged: Int = 0,
    val groupsTotal: Int = 0,
    val groupsAdded: Int = 0,
    val groupsRemoved: Int = 0,
    val duplicateKeys: Int = 0,
    /** KeyRule.name → count. */
    val keyRules: Map<String, Int> = emptyMap(),
    val favouritesKept: Int = 0,
    val favouritesOrphaned: Int = 0,
    val massRemapSuggested: Boolean = false,
    /** (reviewed 2026-10-13, S7) User data moved by MergeFinish.autoRemaps. */
    val keysMigrated: Int = 0,
    val credentialsTemplated: Boolean = false,
    val charset: String? = null,
    val addedSample: List<String> = emptyList(),
    val removedSample: List<String> = emptyList(),
    /** "<WarningCode>@<line>", at most 50. */
    val warnings: List<String> = emptyList(),
)
