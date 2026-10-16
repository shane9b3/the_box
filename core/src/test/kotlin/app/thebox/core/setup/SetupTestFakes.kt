package app.thebox.core.setup

import app.thebox.core.common.Outcome
import app.thebox.core.epg.EpgChannelInfo
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.match.MatchCandidate
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.setup.pages.PageAction
import app.thebox.core.setup.pages.PageHost
import app.thebox.core.source.PlaylistType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Records published pages and actions; [row] finds a row of the latest page. */
class RecordingHost : PageHost {
    val pages = ArrayList<PageModel>()
    val actions = ArrayList<PageAction>()
    val items = ArrayList<Pair<Int, List<SettingsRow>>>()

    val last: PageModel get() = pages.last()
    fun row(id: String): SettingsRow = last.rows.firstOrNull { it.id == RowId(id) } ?: error("no row $id in ${last.rows.map { it.id.key }}")
    fun rowOrNull(id: String): SettingsRow? = last.rows.firstOrNull { it.id == RowId(id) }
    fun ids(): List<String> = last.rows.map { it.id.key }

    override fun publish(page: PageModel) { pages += page }
    override fun act(action: PageAction) { actions += action }
    override fun items(route: SettingsRoute, version: Long, from: Int, rows: List<SettingsRow>) { items += from to rows }
}

fun summary(id: Long, name: String = "P$id", type: PlaylistType = PlaylistType.XTREAM, live: Int = 2_140, status: String? = "SUCCESS") = PlaylistSummary(
    id = id, uid = "uid$id", name = name, type = type, displaySource = "panel.example.com:8080", liveCount = live, enabled = true,
    needsReauth = false, lastRefreshAtMs = 1_000L, lastSuccessAtMs = 1_000L, lastStatus = status, expiresAtSec = null,
)

fun detail(s: PlaylistSummary) = PlaylistDetail(
    summary = s, account = null, serverZone = null, clockSkewSec = 0, updateOnStart = true, numberingMode = "PROVIDER",
    namePrefixMode = "AUTO", namePrefixCustom = null, refreshIntervalH = 24, orderMode = "PROVIDER", usePlaylistEpg = true,
    userAgent = null, headers = emptyMap(), secretHeaderCount = 0, xtreamOutput = "AUTO", xtreamOutputLearned = null,
    backupHosts = emptyList(), activeBaseIndex = 0, catchupModeSetting = "AUTO", catchupDaysOverride = null,
    tokenZoneSetting = "AUTO", paddingBeforeSec = 60, paddingAfterSec = 300, maxConnectionsOverride = null,
    ignoreTlsErrors = false, lastReport = null,
)

/** [PlaylistAdmin] over in-memory details; every write is recorded as a string. */
class FakePlaylistAdmin : PlaylistAdmin {
    val details = MutableStateFlow<Map<Long, PlaylistDetail>>(emptyMap())
    val calls = ArrayList<String>()
    var sources = HashMap<Long, EditableSource>()
    var preview = PrefixPreview(1_204, 2_140)

    fun put(d: PlaylistDetail) { details.value = details.value + (d.summary.id to d) }
    private fun edit(id: Long, f: (PlaylistDetail) -> PlaylistDetail) { details.value[id]?.let { put(f(it)) } }

    override fun observeAll(): Flow<List<PlaylistSummary>> = details.map { m -> m.values.map { it.summary }.sortedBy { it.id } }
    override fun observe(playlistId: Long): Flow<PlaylistDetail?> = details.map { it[playlistId] }
    override suspend fun summary(playlistId: Long) = details.value[playlistId]?.summary
    override suspend fun editableSource(playlistId: Long) = sources[playlistId]
    override suspend fun removalCounts(playlistId: Long) = RemovalCounts(2_140, 14, 3)
    override suspend fun remove(playlistId: Long) { calls += "remove($playlistId)"; details.value = details.value - playlistId }
    override fun refreshNow(playlistId: Long, forceAcceptShrink: Boolean) { calls += "refreshNow($playlistId,$forceAcceptShrink)" }
    override fun refreshAll() { calls += "refreshAll" }
    override suspend fun addSingleStream(name: String?, url: String, userAgent: String?): Outcome<Long> { calls += "addSingleStream"; return Outcome.Ok(99) }
    override suspend fun rename(playlistId: Long, name: String) { calls += "rename($name)"; edit(playlistId) { it.copy(summary = it.summary.copy(name = name)) } }
    override suspend fun setUpdateOnStart(playlistId: Long, on: Boolean) { calls += "updateOnStart($on)"; edit(playlistId) { it.copy(updateOnStart = on) } }
    override suspend fun setNumbering(playlistId: Long, mode: String) { calls += "numbering($mode)"; edit(playlistId) { it.copy(numberingMode = mode) } }
    override suspend fun setNamePrefix(playlistId: Long, mode: String, customJson: String?) { calls += "prefix($mode,$customJson)"; edit(playlistId) { it.copy(namePrefixMode = mode, namePrefixCustom = customJson) } }
    override suspend fun previewNamePrefix(playlistId: Long, mode: String, customJson: String?) = preview
    override suspend fun setEnabled(playlistId: Long, on: Boolean) { calls += "enabled($on)" }
    override suspend fun setRefreshInterval(playlistId: Long, hours: Int) { calls += "interval($hours)"; edit(playlistId) { it.copy(refreshIntervalH = hours) } }
    override suspend fun setOrder(playlistId: Long, mode: String) { calls += "order($mode)" }
    override suspend fun setUsePlaylistEpg(playlistId: Long, on: Boolean) { calls += "playlistEpg($on)" }
    override suspend fun setUserAgent(playlistId: Long, userAgent: String?) { calls += "ua($userAgent)"; edit(playlistId) { it.copy(userAgent = userAgent) } }
    override suspend fun setHeader(playlistId: Long, name: String, value: String?) { calls += "header($name,$value)" }
    override suspend fun setStreamFormat(playlistId: Long, value: String) { calls += "format($value)" }
    override suspend fun setBackupServers(playlistId: Long, urls: List<String>): Outcome<Unit> { calls += "backups($urls)"; return Outcome.Ok(Unit) }
    override suspend fun setCatchupMode(playlistId: Long, value: String) { calls += "catchup($value)" }
    override suspend fun setCatchupDays(playlistId: Long, days: Int?) { calls += "catchupDays($days)" }
    override suspend fun setCatchupZone(playlistId: Long, value: String) { calls += "zone($value)" }
    override suspend fun setPadding(playlistId: Long, beforeSec: Int?, afterSec: Int?) { calls += "padding($beforeSec,$afterSec)" }
    override suspend fun setMaxConnections(playlistId: Long, max: Int?) { calls += "max($max)" }
    override suspend fun setIgnoreTls(playlistId: Long, on: Boolean) { calls += "tls($on)" }
}

class FakeGroups(var list: List<ReviewGroup> = emptyList()) : GroupsReviewSource {
    override suspend fun groups(playlistId: Long): List<ReviewGroup> = list
}

/** [GuideMatching] over in-memory lists; [chosen] records `choose`/`clearChoice`. */
class FakeMatching : GuideMatching {
    val unmatched = MutableStateFlow<List<UnmatchedChannel>>(emptyList())
    val noData = MutableStateFlow<List<UnmatchedChannel>>(emptyList())
    var totals = MatchTotals(2_140, 1_950, 1_912)
    val suggestions = HashMap<ChannelIdentity, List<MatchCandidate>>()
    val epgChannels = ArrayList<EpgChannelInfo>()
    val overrides = HashMap<ChannelIdentity, EpgChannelRef>()
    val shifts = HashMap<ChannelIdentity, Int>()
    val chosen = ArrayList<String>()
    val searches = ArrayList<String>()
    var accessSeen: ParentalAccess? = null
    var now: Map<EpgChannelRef, NowAiring> = emptyMap()

    override fun unmatched(access: ParentalAccess): Flow<List<UnmatchedChannel>> { accessSeen = access; return unmatched }
    override fun matchedWithoutData(access: ParentalAccess): Flow<List<UnmatchedChannel>> = noData
    override suspend fun totals(playlistId: Long) = totals
    override suspend fun channelName(channel: ChannelIdentity) = unmatched.value.firstOrNull { it.identity == channel }?.name
    override suspend fun suggestions(channel: ChannelIdentity, limit: Int) = suggestions[channel].orEmpty().take(limit)
    override suspend fun search(query: String, limit: Int): List<EpgChannelInfo> {
        searches += query
        return epgChannels.filter { c -> c.displayNames.any { it.contains(query, ignoreCase = true) } || c.xmltvId.startsWith(query, ignoreCase = true) }.take(limit)
    }
    override suspend fun nowAiring(refs: List<EpgChannelRef>, nowMs: Long) = now.filterKeys { it in refs }
    override suspend fun override(channel: ChannelIdentity) = overrides[channel]
    override suspend fun hasOverride(channel: ChannelIdentity) = channel in overrides
    override suspend fun choose(channel: ChannelIdentity, ref: EpgChannelRef) {
        chosen += "choose(${channel.stableKey.value},${ref.xmltvId})"
        overrides[channel] = ref
        unmatched.value = unmatched.value.filterNot { it.identity == channel }
    }
    override suspend fun clearChoice(channel: ChannelIdentity) { chosen += "clear(${channel.stableKey.value})"; overrides.remove(channel) }
    override suspend fun channelShift(channel: ChannelIdentity) = shifts[channel] ?: 0
    override suspend fun setChannelShift(channel: ChannelIdentity, minutes: Int) { shifts[channel] = minutes }
}
