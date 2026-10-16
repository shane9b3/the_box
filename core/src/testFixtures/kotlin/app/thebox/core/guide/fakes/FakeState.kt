package app.thebox.core.guide.fakes

import app.thebox.core.guide.GroupAction
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideEffects
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideFrame
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.guide.GuideStateMachineFactory
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.NumberEntry
import app.thebox.core.guide.ProgrammeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Records every [GuideEffects] call as a [Call] (name + arguments), in order. */
class RecordingGuideEffects : GuideEffects {
    data class Call(val name: String, val args: List<Any?>)
    val calls = ArrayList<Call>()
    private fun rec(name: String, vararg args: Any?) { calls += Call(name, args.toList()) }

    fun names(): List<String> = calls.map { it.name }
    fun last(name: String): Call? = calls.lastOrNull { it.name == name }

    override fun loadLineup(source: GuideSource) = rec("loadLineup", source)
    override fun ensureRows(from: Int, to: Int) = rec("ensureRows", from, to)
    override fun ensureWindow(topRow: Int, windowStartMin: Int, holdDirection: Int) = rec("ensureWindow", topRow, windowStartMin, holdDirection)
    override fun loadInfo(token: Long, channel: GuideChannel, rawStartMin: Int) = rec("loadInfo", token, channel, rawStartMin)
    override fun loadTunedNow(channel: GuideChannel, atMin: Int) = rec("loadTunedNow", channel, atMin)
    override fun tune(channel: GuideChannel, lineup: GuideLineup) = rec("tune", channel, lineup)
    override fun playArchive(channel: GuideChannel, info: ProgrammeInfo) = rec("playArchive", channel, info)
    override fun setFullScreen(full: Boolean) = rec("setFullScreen", full)
    override fun zap(delta: Int, isRepeat: Boolean) = rec("zap", delta, isRepeat)
    override fun previousChannel() = rec("previousChannel")
    override fun retry() = rec("retry")
    override fun setReminder(channel: GuideChannel, info: ProgrammeInfo) = rec("setReminder", channel, info)
    override fun cancelReminder(channel: GuideChannel, rawStartMin: Int) = rec("cancelReminder", channel, rawStartMin)
    /** Recorded as (reminderId, accept); [lastAlert] keeps the whole alert. */
    override fun answerReminder(alert: app.thebox.core.guide.ReminderAlert, accept: Boolean) { lastAlert = alert; rec("answerReminder", alert.reminderId, accept) }
    var lastAlert: app.thebox.core.guide.ReminderAlert? = null
    override fun queryFavourite(token: Long, channel: GuideChannel) = rec("queryFavourite", token, channel)
    override fun setFavourite(channel: GuideChannel, on: Boolean) = rec("setFavourite", channel, on)
    override fun hideChannel(channel: GuideChannel) = rec("hideChannel", channel)
    override fun groupAction(entry: GuideSourceEntry, action: GroupAction) = rec("groupAction", entry, action)
    override fun lookupNumber(token: Long, number: Int) = rec("lookupNumber", token, number)
    override fun persistSource(source: GuideSource) = rec("persistSource", source)
    override fun openSearch() = rec("openSearch")
    override fun openSettings() = rec("openSettings")
    override fun exitApp() = rec("exitApp")
    override fun toast(kind: GuideToast, arg: String?) = rec("toast", kind, arg)
    override fun announce(text: String) = rec("announce", text)
    // M5 commit 0 (docs/design/m5-setup-settings.md §2.12 item 2). verifyPin records the digit count only, then zeroes.
    override fun verifyPin(token: Long, digits: CharArray) { rec("verifyPin", token, digits.size); digits.fill('\u0000') }
    override fun openSettings(route: app.thebox.core.settings.SettingsRoute) = rec("openSettingsRoute", route)
    override fun stopPlayback() = rec("stopPlayback")
    override fun updateAction(action: app.thebox.core.update.UpdateAction) = rec("updateAction", action)
}

/** Records dispatched events; the test drives [ui] and [frame] directly. For WP-C's shell tests. */
class FakeGuideStateMachine(initial: GuideUiState = GuideUiState()) : GuideStateMachine {
    val events = ArrayList<GuideEvent>()
    val mutableUi = MutableStateFlow(initial)
    private val version = MutableStateFlow(0L)
    override val ui: StateFlow<GuideUiState> = mutableUi.asStateFlow()
    override val frame: GuideFrame = GuideFrame()
    override val frameVersion: StateFlow<Long> = version.asStateFlow()
    /** The deadline the host's ticker waits for (U-B1 drives a past one). */
    val deadline = MutableStateFlow(Long.MAX_VALUE)
    override val nextDeadlineMs: StateFlow<Long> get() = deadline
    /** Called after each dispatch (U-B1 counts Ticks). */
    var onDispatch: (GuideEvent) -> Unit = {}
    override fun dispatch(event: GuideEvent) { events += event; onDispatch(event) }
    /** What [handles] answers (code review 2026-10-14, H-S4). */
    var handlesFn: (app.thebox.core.input.BoxAction) -> Boolean = { true }
    override fun handles(action: app.thebox.core.input.BoxAction): Boolean = handlesFn(action)
    // M5 commit 0 (§2.12 items 2 and 5): the settings seam the router reads.
    override var settings: app.thebox.core.settings.SettingsMachine? = null
    var textInput = false
    override val textInputActive: Boolean get() = textInput
    fun bumpFrame() = version.update { it + 1 }

    companion object {
        /** A factory that hands out [machine] (or a fresh fake) and remembers the effects it was given. */
        fun factory(machine: FakeGuideStateMachine = FakeGuideStateMachine(), onCreate: (GuideEffects) -> Unit = {}): GuideStateMachineFactory =
            GuideStateMachineFactory { effects, _, _, _, _ -> onCreate(effects); machine }
    }
}

/** Plain digits buffer: max 4 digits, commits after [commitMs] of no input. Not the §3.9.4 D-pad rules. */
class FakeNumberEntry(private val commitMs: Long = 1_500) : NumberEntry {
    private val sb = StringBuilder()
    private var lastMs = 0L
    private var cursor = 0
    override val digits: String get() = sb.toString()
    override fun digit(d: Int, nowMs: Long): Boolean {
        if (sb.length >= 4) return false
        sb.append(d); lastMs = nowMs; cursor = sb.length - 1
        return true
    }
    override fun adjust(delta: Int) {
        if (sb.isEmpty()) sb.append('0')
        val c = sb[cursor].digitToInt()
        sb.setCharAt(cursor, ('0' + Math.floorMod(c + delta, 10)))
    }
    override fun advance() { if (sb.length < 4) { sb.append('0'); cursor = sb.length - 1 } }
    override fun commitValue(): Int? = sb.toString().toIntOrNull()
    override fun due(nowMs: Long): Boolean = sb.isNotEmpty() && nowMs - lastMs >= commitMs
    override fun clear() { sb.setLength(0); cursor = 0 }
}
