package app.thebox.core.full.state

import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.catchup.archive.ArchiveVisibility
import app.thebox.core.full.EnterReason
import app.thebox.core.full.PlayerEffects
import app.thebox.core.full.PlayerEvent
import app.thebox.core.full.PlayerSettings
import app.thebox.core.full.PlayerUiState
import app.thebox.core.full.TrackKey
import app.thebox.core.full.TrackMenu
import app.thebox.core.full.VideoFacts
import app.thebox.core.full.fakes.PlayerFixtures
import app.thebox.core.full.fakes.RecordingPlayerEffects
import app.thebox.core.guide.DeviceClass
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideDataWindow
import app.thebox.core.guide.GuideEffects
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.ReminderAlert
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.SourceSection
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.fakes.FakeGuideRenderer
import app.thebox.core.guide.fakes.FakeGuideRows
import app.thebox.core.guide.fakes.FakeGuideRowsCache
import app.thebox.core.guide.fakes.FakeNavigator
import app.thebox.core.guide.fakes.FakeTimeLabels
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.guide.fakes.RecordingGuideEffects
import app.thebox.core.guide.state.GuideStateMachineImpl
import app.thebox.core.input.BoxAction
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.VideoDecoderMode

/**
 * A simulated host around [PlayerStateMachineImpl] (and, with [withGuide], the guide machine it is composed into) for
 * the WP-A JVM tests (docs/design/m4-player-ux.md §5.1). The host answers like the real view models would: tunes play
 * (CONNECTING, then PLAYING unless [autoPlay] is off), the archive session starts and settles, programmes, favourites,
 * browse and list lineups load. Every `Tuned` goes through [ArchiveVisibility] as both VMs do (B1). Answers are queued
 * and dispatched after the current dispatch returns (the machines are not re-entrant, like the main-thread host).
 */
class PlayerWorld(
    val channels: List<GuideChannel> = DEFAULT_CHANNELS,
    settings: PlayerSettings = PlayerSettings(),
    tunedIndex: Int = 0,
    val withGuide: Boolean = false,
    guideSettings: GuideSettings = GuideSettings(),
    startMs: Long = NOW_MS,
    previousIndex: Int? = 2,
    tracks: TrackMenu = PlayerFixtures.trackMenu(),
    enter: Boolean = true,
    favouritesEntry: Boolean = true,
) {
    var nowMs = startMs
    /** U-S7: archive starts record the call and start nothing (a synchronous `ArchiveStart.Failed`). */
    var failArchiveStarts = false
    var settings: PlayerSettings = settings
        private set
    val fx = RecordingPlayerEffects()
    val gx = RecordingGuideEffects()
    private val queue = ArrayDeque<Any>()

    var autoPlay = true
    var settleSeeks = true
    var exited = false
    val favourites = HashSet<Long>()
    var tracks: TrackMenu = tracks
        private set
    var video = VideoFacts()
        private set

    var tunedRaw = TunedState()
        private set
    var tunedCh: GuideChannel? = null
        private set
    var status = ArchiveStatus()
        private set
    var previousCh: GuideChannel? = previousIndex?.let { channels[it] }
        private set
    private var tuneId = 0L
    var lastConsumed = true
        private set

    val allSource = GuideSource.All(1)
    val newsSource = GuideSource.Group(1, "news")
    val favSource = GuideSource.Favourites(7)
    val entries: List<GuideSourceEntry> = listOfNotNull(
        GuideSourceEntry(favSource, "Favourites", 2, SourceSection.FAVOURITES, null).takeIf { favouritesEntry },
        GuideSourceEntry(allSource, "All channels", channels.size, SourceSection.ALL, null),
        GuideSourceEntry(newsSource, "News", channels.size, SourceSection.GROUP, null),
    )
    val lineup = GuideLineup(allSource, 1, channels.map { it.rowId }.toLongArray(), 7)
    val listRows = FakeGuideRowsCache(channels)
    val schedules: Map<Long, List<ProgrammeInfo>> = channels.filter { it.epg != null }.associate {
        it.identityHash to PlayerFixtures.programmes(it, SCHEDULE_FROM, SCHEDULE_N, 30)
    }

    val player = PlayerStateMachineImpl(Host(), listRows, { nowMs })
    val guide: GuideStateMachineImpl? = if (withGuide) GuideStateMachineImpl(GuideHost(), FakeNavigator(), FakeTimeLabels(), FakeGuideRenderer(), { nowMs }) else null

    val ui: PlayerUiState get() = player.ui.value
    val gui: GuideUiState get() = guide!!.ui.value
    val tunedChannel: GuideChannel? get() = tunedCh

    init {
        player.dispatch(PlayerEvent.Settings(settings, lowEnd = false))
        player.dispatch(PlayerEvent.Sources(entries))
        player.dispatch(PlayerEvent.GuideSourceNow(allSource, lineup))
        player.dispatch(PlayerEvent.Tracks(tracks))
        player.dispatch(PlayerEvent.Previous(previousCh))
        guide?.let { g ->
            g.frame.rows = FakeGuideRows(channels)
            g.dispatch(GuideEvent.Settings(guideSettings, DeviceClass(lowRam = false, totalMemBytes = 4L shl 30, memoryClassMb = 512)))
            g.dispatch(GuideEvent.DataWindow(GuideDataWindow(pastDays = 2, futureDays = 2)))
            g.dispatch(GuideEvent.Generation(app.thebox.core.epg.EpgGenerationInfo(1, 0, 0, 0, 0, 0, GRID_FROM, GRID_TO, "fts5")))
            g.dispatch(GuideEvent.Player(settings))
            g.full = player
        }
        doTune(channels[tunedIndex], setPrevious = false)
        guide?.dispatch(GuideEvent.Sources(entries))
        flush()
        if (enter) {
            if (withGuide) { press(BoxAction.SELECT); check(gui.mode == ScreenMode.FULL) { "FULL from the grid" } }
            else { player.enterFull(nowMs, EnterReason.USER); flush() }
        }
    }

    // ---------------------------------------------------------------- driving

    fun send(e: PlayerEvent) { player.dispatch(e); flush() }
    fun sendGuide(e: GuideEvent) { guide!!.dispatch(e); flush() }

    fun flush() {
        var guard = 0
        while (queue.isNotEmpty()) {
            check(guard++ < 200) { "answer loop" }
            when (val e = queue.removeFirst()) {
                is GuideEvent -> guide?.dispatch(e)
                is PlayerEvent -> player.dispatch(e)
                is Runnable -> e.run()
            }
        }
    }

    fun press(a: BoxAction, repeat: Int = 0, heldMs: Long = 0, digit: Int = -1): PlayerWorld {
        val act = GuideEvent.Act(a, repeat, digit, heldMs)
        val g = guide
        if (g != null) g.dispatch(act) else lastConsumed = player.act(act)
        flush()
        return this
    }

    fun keys(vararg a: BoxAction): PlayerWorld { a.forEach { press(it) }; return this }

    fun release(a: BoxAction) {
        val g = guide
        if (g != null) g.dispatch(GuideEvent.Release(a)) else player.release(a)
        flush()
    }

    fun advance(ms: Long) {
        nowMs += ms
        player.dispatch(PlayerEvent.Tick(nowMs))
        guide?.dispatch(GuideEvent.Tick(nowMs))
        flush()
    }

    fun setSettings(s: PlayerSettings) {
        settings = s
        queue += PlayerEvent.Settings(s, lowEnd = false)
        guide?.let { queue += GuideEvent.Player(s) }
        flush()
    }

    fun mark(): Int = fx.calls.size
    fun effectsSince(mark: Int): List<String> = fx.calls.drop(mark).map { it.name }.filter { it !in BOOKKEEPING }
    fun callsSince(mark: Int): List<RecordingPlayerEffects.Call> = fx.calls.drop(mark).filter { it.name !in BOOKKEEPING }

    // ---------------------------------------------------------------- scripted host states

    /** Emits the raw controller state; both machines see it through ArchiveVisibility (B1). */
    fun setTuned(state: TunedState, ch: GuideChannel?) {
        tunedRaw = state
        tunedCh = ch
        val visible = ArchiveVisibility.visible(state, status)
        queue += PlayerEvent.Tuned(visible, ch)
        guide?.let { queue += GuideEvent.Tuned(visible, ch) }
    }

    fun setStatus(s: ArchiveStatus) {
        status = s
        queue += PlayerEvent.Archive(s)
        guide?.let { queue += GuideEvent.Archive(s) }
    }

    private fun tunedState(ch: GuideChannel, phase: MiniPhase, archive: Boolean, error: PlaybackError? = null, newCycle: Boolean = false): TunedState {
        if (newCycle) tuneId++
        return TunedState(ch.identity, ch.rowId, ch.identityHash, phase, archive, error?.code, errorDetail = error, tuneId = tuneId)
    }

    fun doTune(ch: GuideChannel, setPrevious: Boolean = true) {
        val old = tunedCh
        if (status.phase != ArchivePhase.IDLE) setStatus(ArchiveStatus())
        if (setPrevious && old != null && old.identityHash != ch.identityHash) {
            previousCh = old
            queue += PlayerEvent.Previous(old)
        }
        setTuned(tunedState(ch, MiniPhase.CONNECTING, archive = false, newCycle = true), ch)
        if (autoPlay) setTuned(tunedState(ch, MiniPhase.PLAYING, archive = false), ch)
    }

    /** The pending tune reaches first media (with [autoPlay] off). */
    fun play() { tunedCh?.let { setTuned(tunedState(it, MiniPhase.PLAYING, tunedRaw.isArchive), it) }; flush() }

    /** The tuned channel fails with [error] (ladder-shaped, PlayerFixtures.error). */
    fun fail(error: PlaybackError = PlayerFixtures.error(app.thebox.core.playback.PlaybackErrorCode.STREAM_INTERRUPTED), archive: Boolean = tunedRaw.isArchive) {
        tunedCh?.let { setTuned(tunedState(it, MiniPhase.ERROR, archive, error), it) }
        flush()
    }

    /** A tune the player did not issue (number entry, reminder switch) (S7). */
    fun externalTune(ch: GuideChannel) { doTune(ch); flush() }

    fun programmeAt(ch: GuideChannel, min: Int): ProgrammeInfo? = schedules[ch.identityHash]?.firstOrNull { min >= it.startMin && min < it.endMin }
    fun nowMin(): Int = (nowMs / 60_000L).toInt()

    fun archive(ch: GuideChannel, phase: ArchivePhase, wallMs: Long, timeShift: Boolean, settledSeq: Long = status.settledSeq, attempts: Int = 1): ArchiveStatus =
        ArchiveStatus(
            phase = phase, channel = ch, identityHash = ch.identityHash,
            programme = programmeAt(ch, (wallMs / 60_000L).toInt() + ch.epgShiftMin),
            wallMs = wallMs, atMs = nowMs, advancing = phase == ArchivePhase.PLAYING, liveEdgeMs = nowMs,
            archiveFromMs = nowMs - ch.archiveDays * 86_400_000L + 60_000L, displayShiftMs = ch.epgShiftMin * 60_000L,
            seekable = true, timeShift = timeShift, variant = "xc-path", attempt = 1, attempts = attempts,
            ownedTuneId = tuneId, settledSeq = settledSeq,
        )

    /** The archive session plays [ch] from [wallMs] (a started catch-up or time-shift request reached first media). */
    fun startArchive(ch: GuideChannel, wallMs: Long, timeShift: Boolean) {
        if (tunedCh?.identityHash != ch.identityHash && tunedCh != null) { previousCh = tunedCh; queue += PlayerEvent.Previous(tunedCh) }
        tuneId++
        setStatus(archive(ch, ArchivePhase.STARTING, wallMs, timeShift))
        setTuned(tunedState(ch, MiniPhase.CONNECTING, archive = true), ch)
        setStatus(archive(ch, ArchivePhase.PLAYING, wallMs, timeShift))
        setTuned(tunedState(ch, MiniPhase.PLAYING, archive = true), ch)
    }

    fun kind(startHash: Long): String {
        val active = status.phase != ArchivePhase.IDLE && status.phase != ArchivePhase.FAILED && status.identityHash == tunedRaw.identityHash
        return when {
            tunedRaw.identityHash != startHash -> "OTHER"
            active && status.timeShift -> "TIMESHIFT"
            active || tunedRaw.isArchive -> "ARCHIVE"
            else -> "LIVE"
        }
    }

    fun alert(startAtMs: Long = nowMs + 60_000) =
        sendGuide(GuideEvent.Alert(ReminderAlert(99, channels[3].identity, channels[3].displayName, "Show", startAtMs, late = false, autoSwitch = false)))

    // ---------------------------------------------------------------- the player host (PlayerEffectsImpl's role)

    private inner class Host : PlayerEffects by fx {
        override fun zap(delta: Int, isRepeat: Boolean) {
            fx.zap(delta, isRepeat)
            val cur = channels.indexOfFirst { it.identityHash == tunedRaw.identityHash }.coerceAtLeast(0)
            doTune(channels[Math.floorMod(cur + delta, channels.size)])
        }
        override fun tune(channel: GuideChannel, lineup: GuideLineup?) { fx.tune(channel, lineup); doTune(channel) }
        override fun previousChannel() { fx.previousChannel(); previousCh?.let { doTune(it) } }
        override fun retry() { fx.retry(); tunedCh?.let { ch -> setTuned(tunedState(ch, MiniPhase.CONNECTING, tunedRaw.isArchive, newCycle = true), ch); if (autoPlay) setTuned(tunedState(ch, MiniPhase.PLAYING, tunedRaw.isArchive), ch) } }
        override fun stopPlayback() { fx.stopPlayback(); tunedCh?.let { setTuned(tunedState(it, MiniPhase.STOPPED, tunedRaw.isArchive), it) } }
        override fun pausePlain() { fx.pausePlain(); tunedCh?.let { setTuned(tunedState(it, MiniPhase.PAUSED, false), it) } }
        override fun resumePlain() { fx.resumePlain(); tunedCh?.let { setTuned(tunedState(it, MiniPhase.PLAYING, false), it) } }
        override fun pauseLive(channel: GuideChannel) {
            fx.pauseLive(channel)
            setStatus(archive(channel, ArchivePhase.PAUSED_LIVE, nowMs, timeShift = true))
            setTuned(tunedState(channel, MiniPhase.PAUSED, archive = false), channel)
        }
        override fun archivePause() {
            fx.archivePause()
            val ch = status.channel ?: return
            setStatus(status.copy(phase = ArchivePhase.PAUSED, advancing = false, atMs = nowMs, wallMs = wallNow(), liveEdgeMs = nowMs))
            setTuned(tunedState(ch, MiniPhase.PAUSED, archive = true), ch)
        }
        override fun archiveResume() {
            fx.archiveResume()
            val ch = status.channel ?: return
            when (status.phase) {
                ArchivePhase.PAUSED_LIVE -> startArchive(ch, status.wallMs, timeShift = true)
                ArchivePhase.PAUSED -> {
                    setStatus(status.copy(phase = ArchivePhase.PLAYING, advancing = true, atMs = nowMs, liveEdgeMs = nowMs))
                    setTuned(tunedState(ch, MiniPhase.PLAYING, archive = true), ch)
                }
                else -> Unit
            }
        }
        override fun archiveSeek(programmeMs: Long, seq: Long) {
            fx.archiveSeek(programmeMs, seq)
            val ch = status.channel ?: return
            setStatus(archive(ch, ArchivePhase.SEEKING, wallNow(), status.timeShift))
            if (settleSeeks) setStatus(archive(ch, ArchivePhase.PLAYING, programmeMs, status.timeShift, settledSeq = seq))
        }
        override fun jumpToLive() {
            fx.jumpToLive()
            val ch = status.channel ?: tunedCh ?: return
            setStatus(ArchiveStatus())
            setTuned(tunedState(ch, MiniPhase.CONNECTING, false, newCycle = true), ch)
            if (autoPlay) setTuned(tunedState(ch, MiniPhase.PLAYING, false), ch)
        }
        override fun startOver(channel: GuideChannel) {
            fx.startOver(channel)
            val p = programmeAt(channel, nowMin()) ?: return
            startArchive(channel, p.rawStartMin * 60_000L, timeShift = true)
        }
        override fun rewindLive(channel: GuideChannel, backMs: Long) { fx.rewindLive(channel, backMs); startArchive(channel, nowMs - backMs, timeShift = true) }
        override fun playProgramme(channel: GuideChannel, info: ProgrammeInfo, timeShift: Boolean) {
            fx.playProgramme(channel, info, timeShift)
            if (failArchiveStarts) return // U-S7: the start failed synchronously; the test sends ArchiveStartFailed
            startArchive(channel, info.rawStartMin * 60_000L, timeShift = timeShift)
        }
        override fun openSettings() { fx.openSettings(); guide?.let { queue += GuideEvent.OpenSettings } }
        override fun loadProgrammes(token: Long, channel: GuideChannel, aroundMin: Int, before: Int, after: Int) {
            fx.loadProgrammes(token, channel, aroundMin, before, after)
            val all = schedules[channel.identityHash].orEmpty()
            val now = all.firstOrNull { aroundMin >= it.startMin && aroundMin < it.endMin }
            val earlier = all.filter { it.endMin > aroundMin - before && it.startMin < aroundMin && it !== now }.takeLast(12)
            val later = all.filter { it.startMin >= aroundMin && it !== now }.take(12)
            queue += PlayerEvent.Programmes(token, channel.identityHash, earlier + listOfNotNull(now) + later)
        }
        override fun loadChannel(token: Long, from: GuideChannel, delta: Int) {
            fx.loadChannel(token, from, delta)
            val i = channels.indexOfFirst { it.identityHash == from.identityHash }
            queue += PlayerEvent.BrowseChannel(token, channels[Math.floorMod(i + delta, channels.size)])
        }
        override fun loadListLineup(source: GuideSource) {
            fx.loadListLineup(source)
            val ids = if (source is GuideSource.Favourites) channels.filter { it.identityHash in favourites }.map { it.rowId } else channels.map { it.rowId }
            val l = GuideLineup(source, 1, ids.toLongArray(), 7)
            queue += Runnable { listRows.setLineup(l) }
            queue += PlayerEvent.ListLineup(l)
        }
        override fun queryFavourite(token: Long, channel: GuideChannel) {
            fx.queryFavourite(token, channel)
            queue += PlayerEvent.Favourite(token, channel.identityHash in favourites)
        }
        override fun setFavourite(channel: GuideChannel, on: Boolean) {
            fx.setFavourite(channel, on)
            if (on) favourites += channel.identityHash else favourites -= channel.identityHash
        }
        override fun selectAudio(key: TrackKey?) {
            fx.selectAudio(key)
            tracks = tracks.copy(audio = tracks.audio.map { it.copy(selected = it.key == key) })
            queue += PlayerEvent.Tracks(tracks)
        }
        override fun selectText(key: TrackKey?) {
            fx.selectText(key)
            tracks = tracks.copy(text = tracks.text.map { it.copy(selected = it.key == key) }, textEnabled = key != null)
            queue += PlayerEvent.Tracks(tracks)
        }
        override fun setAspect(mode: AspectMode, remember: Boolean) { fx.setAspect(mode, remember); video = video.copy(aspect = mode); queue += PlayerEvent.Video(video) }
        override fun setDecoder(mode: VideoDecoderMode?) { fx.setDecoder(mode); video = video.copy(decoderOverride = mode); queue += PlayerEvent.Video(video) }
    }

    private fun wallNow(): Long = status.wallMs + if (status.advancing) nowMs - status.atMs else 0L

    // ---------------------------------------------------------------- the guide host (composed tests)

    private inner class GuideHost : GuideEffects by gx {
        override fun loadLineup(source: GuideSource) {
            gx.loadLineup(source)
            val l = GuideLineup(source, 1, channels.map { it.rowId }.toLongArray(), 7)
            queue += GuideEvent.Lineup(l)
            queue += GuideEvent.Window(GRID_DATA)
            queue += PlayerEvent.GuideSourceNow(source, l)
        }
        override fun tune(channel: GuideChannel, lineup: GuideLineup) { gx.tune(channel, lineup); doTune(channel) }
        override fun playArchive(channel: GuideChannel, info: ProgrammeInfo) { gx.playArchive(channel, info); startArchive(channel, info.rawStartMin * 60_000L, timeShift = false) }
        override fun zap(delta: Int, isRepeat: Boolean) { gx.zap(delta, isRepeat) }
        override fun previousChannel() { gx.previousChannel(); previousCh?.let { doTune(it) } }
        override fun retry() { gx.retry() }
        override fun queryFavourite(token: Long, channel: GuideChannel) {
            gx.queryFavourite(token, channel)
            queue += GuideEvent.Favourite(token, channel.identityHash in favourites)
        }
        override fun lookupNumber(token: Long, number: Int) {
            gx.lookupNumber(token, number)
            val ch = channels.firstOrNull { it.number == number }
            queue += GuideEvent.Number(token, ch, if (ch == null) -1 else channels.indexOf(ch))
        }
        override fun answerReminder(alert: ReminderAlert, accept: Boolean) {
            gx.answerReminder(alert, accept)
            queue += GuideEvent.Alert(null)
            if (accept) channels.firstOrNull { it.identity == alert.identity }?.let { doTune(it) }
        }
        override fun exitApp() { gx.exitApp(); exited = true }
    }

    companion object {
        const val NOW_MIN = GuideFixtures.NOW_MIN
        /** 20 s into the minute, like GuideWorld. */
        const val NOW_MS = NOW_MIN * 60_000L + 20_000L
        val SCHEDULE_FROM = GuideFixtures.floor30(NOW_MIN) - 24 * 60
        const val SCHEDULE_N = 96
        /** Effects that only load or prefetch (the tables list what the key *does*). */
        val BOOKKEEPING = setOf("loadProgrammes", "ensureListRows", "ensureListNow", "announce", "queryFavourite")

        /** Even rows have 7-day archive; row 5 has no EPG. Numbers 101…106. */
        val DEFAULT_CHANNELS: List<GuideChannel> = List(6) { i ->
            GuideFixtures.channel(i, archiveDays = if (i % 2 == 0) 7 else 0, number = 101 + i, epg = if (i == 5) null else app.thebox.core.epg.EpgChannelRef(1, "ch$i"))
        }
        private val GRID_FROM = GuideFixtures.floor30(NOW_MIN) - 2 * 1440
        private val GRID_TO = GuideFixtures.floor30(NOW_MIN) + 2 * 1440
        val GRID_DATA = GuideFixtures.windowData(GuideFixtures.syntheticRows(6, GRID_FROM, GRID_TO, noEpgRows = setOf(5)), fromMin = GRID_FROM, toMin = GRID_TO)

        /** A past programme of [ch] inside its archive (two hours ago). */
        fun PlayerWorld.pastProgramme(ch: GuideChannel): ProgrammeInfo = programmeAt(ch, nowMin() - 120)!!

        /** Start states of the five-key proof (§3.1.6). */
        fun liveWithArchive(s: PlayerSettings = PlayerSettings()) = PlayerWorld(settings = s, tunedIndex = 0)
        fun liveWithoutArchive(s: PlayerSettings = PlayerSettings()) = PlayerWorld(settings = s, tunedIndex = 1)
        fun archive(s: PlayerSettings = PlayerSettings()) = PlayerWorld(settings = s, tunedIndex = 0).also { w ->
            w.startArchive(w.channels[0], w.pastProgramme(w.channels[0]).rawStartMin * 60_000L, timeShift = false); w.flush()
        }
        /** Time-shift as §3.1.6 builds it: pause on live (pauseLive), then resume (archiveResume). */
        fun timeShift(s: PlayerSettings = PlayerSettings()) = PlayerWorld(settings = s, tunedIndex = 0).also { w ->
            w.press(BoxAction.PLAY_PAUSE)
            w.advance(5_000)
            w.press(BoxAction.PLAY_PAUSE)
            check(w.status.phase == ArchivePhase.PLAYING && w.status.timeShift) { "time-shift start state: ${w.status.phase}" }
        }
    }
}
