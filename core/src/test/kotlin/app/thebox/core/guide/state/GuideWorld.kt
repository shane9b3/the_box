package app.thebox.core.guide.state

import app.thebox.core.guide.layout.GuideNavigatorImpl
import app.thebox.core.epg.EpgGenerationInfo
import app.thebox.core.guide.CellKind
import app.thebox.core.guide.DeviceClass
import app.thebox.core.guide.ExitConfirmation
import app.thebox.core.guide.GroupAction
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideDataWindow
import app.thebox.core.guide.GuideEffects
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.GuideWindowData
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.ReminderAlert
import app.thebox.core.guide.ReminderIndex
import app.thebox.core.guide.SourceSection
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.fakes.FakeGuideRenderer
import app.thebox.core.guide.fakes.FakeGuideRows
import app.thebox.core.guide.fakes.FakeNavigator
import app.thebox.core.guide.fakes.FakeTimeLabels
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.guide.fakes.RecordingGuideEffects
import app.thebox.core.input.BoxAction
import app.thebox.core.playback.PlaybackErrorCode

/**
 * A small simulated world around [GuideStateMachineImpl] for the JVM tests (§5.4): the fake navigator and labels,
 * a fixed lineup with a shared snapshot, and effects that answer like the real host would (lineups load, favourites
 * and reminders toggle, tunes play, number lookups resolve). Answers are queued and dispatched after the current
 * dispatch returns (the machine is not re-entrant, like the real main-thread host).
 */
class GuideWorld(
    val channels: List<GuideChannel> = DEFAULT_CHANNELS,
    val settings: GuideSettings = GuideSettings(),
    val favouritesList: Boolean = true,
    startTuned: Boolean = true,
    tunedRow: Int = 0,
    lastSourceHint: app.thebox.core.guide.GuideSource? = null,
    sendSources: Boolean = true,
    val data: GuideWindowData = DEFAULT_DATA,
    val dataWindow: GuideDataWindow = GuideDataWindow(pastDays = 2, futureDays = 2),
    startMs: Long = NOW_MIN * 60_000L + 20_000L,
    realNavigator: Boolean = REAL_NAVIGATOR,
) {
    var nowMs = startMs
    val calls = RecordingGuideEffects()
    private val queue = ArrayDeque<GuideEvent>()
    val favourites = HashSet<Long>()
    val reminders = HashSet<Pair<Long, Int>>()
    var tunedHash = 0L
    var exited = false
    var archiveSucceeds = true
    val rows = FakeGuideRows(channels)
    val labels = FakeTimeLabels()
    /**
     * §1.7 step 2 / §2.13 item 20: the machine's tests run on both navigators. `-Dthebox.realNavigator=true` (the
     * `stateOnRealNavigator` Gradle test task) swaps in WP-A's [GuideNavigatorImpl], recorded through [RecordingNavigator].
     */
    val nav: RecordingNavigator = RecordingNavigator(if (realNavigator) GuideNavigatorImpl(labels) else FakeNavigator())
    val machine = GuideStateMachineImpl(Sim(), nav, labels, FakeGuideRenderer(), { nowMs })
    val ui: GuideUiState get() = machine.ui.value
    private var lineupVersion = 1L

    val allSource = GuideSource.All(1)
    val groupSource = GuideSource.Group(1, "news")
    val group2Source = GuideSource.Group(1, "sport")
    val favSource = GuideSource.Favourites(7)
    val entries: List<GuideSourceEntry> = buildList {
        if (favouritesList) add(GuideSourceEntry(favSource, "Favourites", 2, SourceSection.FAVOURITES, null))
        add(GuideSourceEntry(allSource, "All channels", channels.size, SourceSection.ALL, null))
        add(GuideSourceEntry(groupSource, "News", channels.size, SourceSection.GROUP, null))
        add(GuideSourceEntry(group2Source, "Sport", channels.size, SourceSection.GROUP, null))
    }

    init {
        machine.frame.rows = rows
        send(GuideEvent.Settings(settings, DeviceClass(lowRam = false, totalMemBytes = 4L shl 30, memoryClassMb = 512)))
        send(GuideEvent.DataWindow(dataWindow))
        send(GuideEvent.Generation(GENERATION))
        machine.lastSourceHint = lastSourceHint
        if (startTuned) tuneTo(channels[tunedRow])
        if (sendSources) send(GuideEvent.Sources(entries))
        flush()
    }

    fun send(e: GuideEvent) { machine.dispatch(e); flush() }

    fun flush() {
        var guard = 0
        while (queue.isNotEmpty()) {
            check(guard++ < 100) { "answer loop" }
            machine.dispatch(queue.removeFirst())
        }
    }

    fun press(a: BoxAction, repeat: Int = 0, digit: Int = -1, heldMs: Long = 0) = send(GuideEvent.Act(a, repeat, digit, heldMs))
    fun release(a: BoxAction) = send(GuideEvent.Release(a))
    fun advance(ms: Long) { nowMs += ms; send(GuideEvent.Tick(nowMs)) }

    fun tuneTo(ch: GuideChannel, phase: MiniPhase = MiniPhase.PLAYING, archive: Boolean = false, error: PlaybackErrorCode? = null) {
        tunedHash = ch.identityHash
        queue += GuideEvent.Tuned(TunedState(ch.identity, ch.rowId, ch.identityHash, phase, archive, error), ch)
    }

    fun idle() { tunedHash = 0; send(GuideEvent.Tuned(TunedState(), null)) }

    fun alert(id: Long = 99, startAtMs: Long = nowMs + 60_000, autoSwitch: Boolean = false) =
        send(GuideEvent.Alert(ReminderAlert(id, channels[1].identity, channels[1].displayName, "Show", startAtMs, late = false, autoSwitch = autoSwitch)))

    /** Names of effect calls since [from], without the bookkeeping ones. */
    fun effectsSince(from: Int): List<String> = calls.calls.drop(from).map { it.name }.filter { it !in BOOKKEEPING }

    private fun lineupFor(source: GuideSource): GuideLineup {
        val ids = when (source) {
            is GuideSource.Favourites -> channels.filter { it.identityHash in favourites }.map { it.rowId }
            else -> channels.map { it.rowId }
        }
        return GuideLineup(source, lineupVersion, ids.toLongArray(), archiveDaysUpperBound = channels.maxOf { it.archiveDays })
    }

    private inner class Sim : GuideEffects by calls {
        override fun loadLineup(source: GuideSource) {
            calls.loadLineup(source)
            val l = lineupFor(source)
            if (source is GuideSource.Favourites) {
                // Favourites lineups are a different row order: give them their own snapshot-free lineup.
                rows.rows = l.rowIds.map { id -> channels.first { it.rowId == id } }
            } else {
                rows.rows = channels
            }
            queue += GuideEvent.Lineup(l)
            if (source !is GuideSource.Favourites) queue += GuideEvent.Window(data)
        }
        override fun tune(channel: GuideChannel, lineup: GuideLineup) { calls.tune(channel, lineup); tuneTo(channel) }
        override fun playArchive(channel: GuideChannel, info: ProgrammeInfo) { calls.playArchive(channel, info); if (archiveSucceeds) tuneTo(channel, MiniPhase.CONNECTING, archive = true) }
        override fun queryFavourite(token: Long, channel: GuideChannel) {
            calls.queryFavourite(token, channel)
            queue += GuideEvent.Favourite(token, channel.identityHash in favourites)
        }
        override fun setFavourite(channel: GuideChannel, on: Boolean) {
            calls.setFavourite(channel, on)
            if (on) favourites += channel.identityHash else favourites -= channel.identityHash
        }
        override fun setReminder(channel: GuideChannel, info: ProgrammeInfo) {
            calls.setReminder(channel, info)
            reminders += channel.identityHash to info.rawStartMin
            queue += GuideEvent.Reminders(ReminderIndex.of(reminders.toList()))
        }
        override fun cancelReminder(channel: GuideChannel, rawStartMin: Int) {
            calls.cancelReminder(channel, rawStartMin)
            reminders -= channel.identityHash to rawStartMin
            queue += GuideEvent.Reminders(ReminderIndex.of(reminders.toList()))
        }
        override fun lookupNumber(token: Long, number: Int) {
            calls.lookupNumber(token, number)
            val ch = channels.firstOrNull { it.number == number }
            queue += GuideEvent.Number(token, ch, if (ch == null) -1 else rows.indexOf(ch.rowId))
        }
        override fun answerReminder(reminderId: Long, accept: Boolean) {
            calls.answerReminder(reminderId, accept)
            queue += GuideEvent.Alert(null)
            if (accept) tuneTo(channels[1])
        }
        override fun groupAction(entry: GuideSourceEntry, action: GroupAction) = calls.groupAction(entry, action)
        override fun exitApp() { calls.exitApp(); exited = true }
        override fun toast(kind: GuideToast, arg: String?) = calls.toast(kind, arg)
    }

    companion object {
        const val NOW_MIN = GuideFixtures.NOW_MIN
        val BOOKKEEPING = setOf("ensureRows", "ensureWindow", "announce", "loadInfo", "loadTunedNow")

        /** Rows 0 and 5 have 7-day archive; row 3 has no EPG. */
        val DEFAULT_CHANNELS: List<GuideChannel> = List(10) { i ->
            GuideFixtures.channel(i, archiveDays = if (i % 5 == 0) 7 else 0, number = 101 + i)
        }
        val FROM = GuideFixtures.floor30(NOW_MIN) - 2 * 1440
        val TO = GuideFixtures.floor30(NOW_MIN) + 2 * 1440
        val DEFAULT_DATA: GuideWindowData = GuideFixtures.windowData(
            GuideFixtures.syntheticRows(10, FROM, TO, noEpgRows = setOf(3)), fromMin = FROM, toMin = TO,
        )
        val GENERATION = EpgGenerationInfo(1, 0, 0, 0, 0, 0, FROM, TO, "fts5")

        fun cellKindAt(data: GuideWindowData, row: Int, minute: Int): Byte {
            val f = data.rowFirstCell[row]
            for (i in f until f + data.rowCellCount[row]) if (minute >= data.cellStart[i] && minute < data.cellEnd[i]) return data.cellKind[i]
            return CellKind.LOADING
        }

        fun exitWorld(mode: ExitConfirmation) = GuideWorld(settings = GuideSettings(exitConfirmation = mode))
    }
}

/** Records the moves of the wrapped navigator (the tests assert on [moves]). */
class RecordingNavigator(private val inner: app.thebox.core.guide.GuideNavigator) : app.thebox.core.guide.GuideNavigator by inner {
    val moves = ArrayList<app.thebox.core.guide.GridMove>()
    override fun move(from: app.thebox.core.guide.GridFocus, move: app.thebox.core.guide.GridMove, arg: Int, ctx: app.thebox.core.guide.GridContext): app.thebox.core.guide.GridMoveResult {
        moves += move
        return inner.move(from, move, arg, ctx)
    }
}

/** `-Dthebox.realNavigator=true`: run the state-machine tests on GuideNavigatorImpl. */
val REAL_NAVIGATOR: Boolean = System.getProperty("thebox.realNavigator") == "true"
