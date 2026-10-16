package app.thebox.core.guide.fakes

import app.thebox.core.epg.EpgGenerationInfo
import app.thebox.core.guide.DeviceClass
import app.thebox.core.guide.DeviceClassSource
import app.thebox.core.guide.GroupAction
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideDataWindow
import app.thebox.core.guide.GuideDataWindowSource
import app.thebox.core.guide.GuideDebugProbe
import app.thebox.core.guide.GuideLibrary
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideRowsCache
import app.thebox.core.guide.GuideSessionStore
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.GuideSettingsSource
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.guide.GuideWindowData
import app.thebox.core.guide.GuideWindowPager
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.SourceSection
import app.thebox.core.guide.SyncFeed
import app.thebox.core.guide.SyncFeedState
import app.thebox.core.refresh.ChannelIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [GuideLibrary]: one "All" source over [channels] plus whatever [lineups] holds. Mutations re-emit the
 * affected lineups with a new version, like the real one after a favourite edit or hide.
 */
class FakeGuideLibrary(channels: List<GuideChannel> = GuideFixtures.channels(50), playlistId: Long = 1) : GuideLibrary {
    private val byRowId = LinkedHashMap<Long, GuideChannel>().apply { channels.forEach { put(it.rowId, it) } }
    val favourites = LinkedHashSet<ChannelIdentity>()
    val hidden = HashSet<ChannelIdentity>()
    val groupActions = ArrayList<Pair<GuideSourceEntry, GroupAction>>()
    var lastWatched: GuideChannel? = channels.firstOrNull()
    private val all = GuideSource.All(playlistId)
    private val version = MutableStateFlow(1L)
    val sourceEntries = MutableStateFlow(listOf(GuideSourceEntry(all, "All channels", channels.size, SourceSection.ALL, null)))
    /** Extra lineups by source; [GuideSource.All] and [GuideSource.Favourites] are derived. */
    val lineups = HashMap<GuideSource, List<Long>>()
    private val rowContentTicks = MutableStateFlow(0L)

    fun tickRowContent() { rowContentTicks.update { it + 1 } }
    fun replace(channel: GuideChannel) { byRowId[channel.rowId] = channel; tickRowContent() }

    override fun sources(access: app.thebox.core.parental.ParentalAccess): Flow<List<GuideSourceEntry>> = sourceEntries

    override fun lineup(source: GuideSource, access: app.thebox.core.parental.ParentalAccess): Flow<GuideLineup> = version.map { v ->
        val ids = when (source) {
            is GuideSource.All -> byRowId.values.filter { it.identity !in hidden }.map { it.rowId }
            is GuideSource.Favourites -> byRowId.values.filter { it.identity in favourites && it.identity !in hidden }.map { it.rowId }
            else -> lineups[source].orEmpty().filter { id -> byRowId[id]?.identity !in hidden }
        }
        GuideLineup(source, v, ids.toLongArray(), ids.maxOfOrNull { byRowId[it]?.archiveDays ?: 0 } ?: 0)
    }

    override suspend fun channels(rowIds: LongArray, from: Int, to: Int): List<GuideChannel?> = (from until to).map { byRowId[rowIds[it]] }
    override suspend fun rowIdOf(identity: ChannelIdentity): Long? = byRowId.values.firstOrNull { it.identity == identity }?.rowId
    override suspend fun byNumber(playlistId: Long?, number: Int, access: app.thebox.core.parental.ParentalAccess): GuideChannel? =
        byRowId.values.firstOrNull { it.number == number && it.identity !in hidden }
    override suspend fun isFavourite(identity: ChannelIdentity): Boolean = identity in favourites
    override suspend fun setFavourite(identity: ChannelIdentity, on: Boolean) {
        if (on) favourites += identity else favourites -= identity
        version.update { it + 1 }
    }
    override suspend fun hideChannel(identity: ChannelIdentity) { hidden += identity; version.update { it + 1 } }
    override suspend fun groupAction(entry: GuideSourceEntry, action: GroupAction) { groupActions += entry to action; version.update { it + 1 } }
    override suspend fun lastWatched(): GuideChannel? = lastWatched
    override val rowContent: Flow<Long> = rowContentTicks
}

/** Synchronous [GuideRowsCache]: every row is "loaded" as soon as the lineup is set (from [source]). */
class FakeGuideRowsCache(private val source: Map<Long, GuideChannel>) : GuideRowsCache {
    constructor(channels: List<GuideChannel>) : this(channels.associateBy { it.rowId })

    private var lineup: GuideLineup? = null
    private val versionFlow = MutableStateFlow(0L)
    val ensured = ArrayList<IntRange>()
    var reloads = 0
    /** When false, [channel] returns null (skeleton rows) until [loadAll]. */
    var loaded = true

    override val size: Int get() = lineup?.rowIds?.size ?: 0
    override val version: Long get() = versionFlow.value
    override fun channel(index: Int): GuideChannel? = if (!loaded) null else lineup?.rowIds?.getOrNull(index)?.let(source::get)
    override fun indexOf(rowId: Long): Int = lineup?.rowIds?.indexOf(rowId) ?: -1
    override fun setLineup(lineup: GuideLineup) { this.lineup = lineup; versionFlow.update { it + 1 } }
    override fun ensure(from: Int, to: Int) { ensured += from until to }
    override val changes: StateFlow<Long> = versionFlow.asStateFlow()
    override suspend fun channelAt(index: Int): GuideChannel? = channel(index)
    override fun reloadLoaded() { reloads++; versionFlow.update { it + 1 } }
    override val lineupVersion: Long get() = lineup?.version ?: 0L
    var trims = ArrayList<IntRange>()
    override fun trim(keepFrom: Int, keepTo: Int) { trims += keepFrom until keepTo }
    fun loadAll() { loaded = true; versionFlow.update { it + 1 } }
}

/** [GuideWindowPager] whose snapshots the test publishes with [publish]; records every [ensure]. */
class FakeGuideWindowPager : GuideWindowPager {
    data class Ensure(val topRow: Int, val windowStartMin: Int, val holdDirection: Int)
    private val snaps = MutableStateFlow<GuideWindowData?>(null)
    private val gen = MutableStateFlow<EpgGenerationInfo?>(null)
    val ensures = ArrayList<Ensure>()
    var invalidations = 0
    var closed = false
    /** Keyed by (rowId, rawStartMin) for [info] and by rowId for [at]. */
    val infos = HashMap<Pair<Long, Int>, ProgrammeInfo>()
    val airing = HashMap<Long, ProgrammeInfo>()

    override val snapshots: StateFlow<GuideWindowData?> = snaps.asStateFlow()
    override val generation: StateFlow<EpgGenerationInfo?> = gen.asStateFlow()
    override fun ensure(topRow: Int, windowStartMin: Int, holdDirection: Int) { ensures += Ensure(topRow, windowStartMin, holdDirection) }
    override fun invalidate() { invalidations++ }
    override suspend fun info(channel: GuideChannel, rawStartMin: Int): ProgrammeInfo? = infos[channel.rowId to rawStartMin]
    override suspend fun at(channel: GuideChannel, atMin: Int): ProgrammeInfo? = airing[channel.rowId]
    override fun close() { closed = true }

    fun publish(data: GuideWindowData?) { snaps.value = data }
    fun publishGeneration(info: EpgGenerationInfo?) { gen.value = info }
}

class FakeGuideSettings(initial: GuideSettings = GuideSettings()) : GuideSettingsSource {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<GuideSettings> = state.asStateFlow()
    override fun update(transform: (GuideSettings) -> GuideSettings) = state.update(transform)
}

class FakeGuideSessionStore : GuideSessionStore {
    var source: GuideSource? = null
    val flags = HashSet<String>()
    override fun lastSource(): GuideSource? = source
    override fun setLastSource(source: GuideSource) { this.source = source }
    override fun flag(name: String): Boolean = name in flags
    override fun setFlag(name: String) { flags += name }
}

class FakeGuideDataWindowSource(initial: GuideDataWindow = GuideDataWindow()) : GuideDataWindowSource {
    val state = MutableStateFlow(initial)
    override val window: StateFlow<GuideDataWindow> = state.asStateFlow()
}

class FakeSyncFeed(initial: SyncFeedState = SyncFeedState(busy = false, progress = null, unacknowledgedFailures = 0)) : SyncFeed {
    val mutable = MutableStateFlow(initial)
    override val state: StateFlow<SyncFeedState> = mutable.asStateFlow()
}

class FakeDeviceClassSource(var device: DeviceClass = DeviceClass(lowRam = false, totalMemBytes = 2L shl 30, memoryClassMb = 256)) : DeviceClassSource {
    override fun get(): DeviceClass = device
}

class FakeGuideDebugProbe : GuideDebugProbe {
    override var current: GuideStateMachine? = null
    override fun register(machine: GuideStateMachine?) { current = machine }
}

/** Convenience for tests that only need the latest non-null snapshot. */
fun GuideWindowPager.snapshotsNotNull(): Flow<GuideWindowData> = snapshots.filterNotNull()
