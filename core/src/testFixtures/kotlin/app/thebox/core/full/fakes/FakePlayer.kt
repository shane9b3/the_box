package app.thebox.core.full.fakes

import app.thebox.core.full.EnterReason
import app.thebox.core.full.PlayerEffects
import app.thebox.core.full.PlayerEvent
import app.thebox.core.full.PlayerSettings
import app.thebox.core.full.PlayerSettingsSource
import app.thebox.core.full.PlayerStateMachine
import app.thebox.core.full.PlayerStateMachineFactory
import app.thebox.core.full.PlayerToast
import app.thebox.core.full.PlayerUiState
import app.thebox.core.full.TrackKey
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.input.BoxAction
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.VideoDecoderMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Records every [PlayerEffects] call as a [Call] (name + arguments), in order (docs/design/m4-player-ux.md §1.8). */
class RecordingPlayerEffects : PlayerEffects {
    data class Call(val name: String, val args: List<Any?>)
    val calls = ArrayList<Call>()
    private fun rec(name: String, vararg args: Any?) { calls += Call(name, args.toList()) }

    fun names(): List<String> = calls.map { it.name }
    fun last(name: String): Call? = calls.lastOrNull { it.name == name }

    override fun zap(delta: Int, isRepeat: Boolean) = rec("zap", delta, isRepeat)
    override fun tune(channel: GuideChannel, lineup: GuideLineup?) = rec("tune", channel, lineup)
    override fun previousChannel() = rec("previousChannel")
    override fun retry() = rec("retry")
    override fun stopPlayback() = rec("stopPlayback")
    override fun pausePlain() = rec("pausePlain")
    override fun resumePlain() = rec("resumePlain")
    override fun pauseLive(channel: GuideChannel) = rec("pauseLive", channel)
    override fun archivePause() = rec("archivePause")
    override fun archiveResume() = rec("archiveResume")
    override fun archiveSeek(programmeMs: Long, seq: Long) = rec("archiveSeek", programmeMs, seq)
    override fun jumpToLive() = rec("jumpToLive")
    override fun startOver(channel: GuideChannel) = rec("startOver", channel)
    override fun rewindLive(channel: GuideChannel, backMs: Long) = rec("rewindLive", channel, backMs)
    override fun playProgramme(channel: GuideChannel, info: ProgrammeInfo, timeShift: Boolean) = rec("playProgramme", channel, info, timeShift)
    override fun tryNextCatchupFormat() = rec("tryNextCatchupFormat")
    override fun toggleReminder(channel: GuideChannel, info: ProgrammeInfo) = rec("toggleReminder", channel, info)
    override fun openSettings() = rec("openSettings")
    // (Score bug WP-4b) The score effects, recorded.
    override fun followGame(id: app.thebox.core.sports.GameId?) = rec("followGame", id)
    override fun setScoreCorner(c: app.thebox.core.sports.ScoreCorner) = rec("setScoreCorner", c)
    override fun setScoreDelay(sec: Int) = rec("setScoreDelay", sec)
    override fun setScoreLeague(league: app.thebox.core.sports.League) = rec("setScoreLeague", league)
    override fun scorePicker(open: Boolean) = rec("scorePicker", open)
    override fun retryScores() = rec("retryScores")
    override fun loadProgrammes(token: Long, channel: GuideChannel, aroundMin: Int, before: Int, after: Int) =
        rec("loadProgrammes", token, channel, aroundMin, before, after)
    override fun loadChannel(token: Long, from: GuideChannel, delta: Int) = rec("loadChannel", token, from, delta)
    override fun loadListLineup(source: GuideSource) = rec("loadListLineup", source)
    override fun ensureListRows(from: Int, to: Int) = rec("ensureListRows", from, to)
    override fun ensureListNow(from: Int, to: Int) = rec("ensureListNow", from, to)
    override fun queryFavourite(token: Long, channel: GuideChannel) = rec("queryFavourite", token, channel)
    override fun setFavourite(channel: GuideChannel, on: Boolean) = rec("setFavourite", channel, on)
    override fun hideChannel(channel: GuideChannel) = rec("hideChannel", channel)
    override fun selectAudio(key: TrackKey?) = rec("selectAudio", key)
    override fun selectText(key: TrackKey?) = rec("selectText", key)
    override fun setAspect(mode: AspectMode, remember: Boolean) = rec("setAspect", mode, remember)
    override fun setDecoder(mode: VideoDecoderMode?) = rec("setDecoder", mode)
    /** U-S12 (code review 2026-10-16): the AUDIO picker's Back to Auto. */
    override fun audioOutputAuto() = rec("audioOutputAuto")
    override fun setStatsEnabled(on: Boolean) = rec("setStatsEnabled", on)
    override fun setScreenOff(on: Boolean) = rec("setScreenOff", on)
    override fun toast(kind: PlayerToast, arg: String?) = rec("toast", kind, arg)
    override fun announce(text: String) = rec("announce", text)
}

/**
 * Records acts, releases, events and FULL entries; the test drives [mutableUi] and [horizontalRepeat] directly. For the
 * guide-delegation tests (WP-A) and the shell (WP-D). [actFn] decides what [act] returns (default: the player handles it).
 */
class FakePlayerStateMachine(initial: PlayerUiState = PlayerUiState()) : PlayerStateMachine {
    val acts = ArrayList<GuideEvent.Act>()
    val releases = ArrayList<BoxAction>()
    val events = ArrayList<PlayerEvent>()
    val entered = ArrayList<EnterReason>()
    var leaveCount = 0
    val mutableUi = MutableStateFlow(initial)
    val deadline = MutableStateFlow(Long.MAX_VALUE)
    var actFn: (GuideEvent.Act) -> Boolean = { true }
    var handlesFn: (BoxAction) -> Boolean = { true }

    override val ui: StateFlow<PlayerUiState> = mutableUi.asStateFlow()
    override val nextDeadlineMs: StateFlow<Long> = deadline.asStateFlow()
    override var horizontalRepeat: Boolean = false
    override fun act(a: GuideEvent.Act): Boolean { acts += a; return actFn(a) }
    override fun release(action: BoxAction) { releases += action }
    override fun handles(action: BoxAction): Boolean = handlesFn(action)
    override fun dispatch(event: PlayerEvent) { events += event }
    override fun enterFull(nowMs: Long, reason: EnterReason) { entered += reason }
    override fun leaveFull() { leaveCount++ }
    /** (Score bug commit 0) Every `scores(...)` the host sent. */
    val scoreFacts = ArrayList<app.thebox.core.sports.ScoreFacts>()
    override fun scores(facts: app.thebox.core.sports.ScoreFacts) { scoreFacts += facts }

    companion object {
        /** A factory that hands out [machine] and remembers the effects it was given. */
        fun factory(machine: FakePlayerStateMachine = FakePlayerStateMachine(), onCreate: (PlayerEffects) -> Unit = {}): PlayerStateMachineFactory =
            PlayerStateMachineFactory { effects, _, _ -> onCreate(effects); machine }
    }
}

/** In-memory [PlayerSettingsSource] (like FakeGuideSettings). */
class FakePlayerSettings(initial: PlayerSettings = PlayerSettings()) : PlayerSettingsSource {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<PlayerSettings> = state.asStateFlow()
    override fun update(transform: (PlayerSettings) -> PlayerSettings) { state.value = transform(state.value) }
}
