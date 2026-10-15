package app.thebox.ui.guide.vm

import app.thebox.core.common.BoxLog
import app.thebox.core.guide.GroupAction
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideEffects
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideLibrary
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideRowsCache
import app.thebox.core.guide.GuideSessionStore
import app.thebox.core.guide.GuideSettingsSource
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.GuideWindowPager
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.ReminderAlert
import app.thebox.core.guide.ReminderAlerts
import app.thebox.core.guide.ReminderBook
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.TuneTarget
import app.thebox.ui.playback.CatchupLaunch
import app.thebox.ui.playback.CatchupLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI-level effects the shell (root composable / activity) performs. Main thread. */
interface ShellEffects {
    /** GUIDE ↔ FULL: the root runs the scrim sequence and the viewport follows the displayed mode (§3.13.5). */
    fun setFullScreen(full: Boolean)
    fun toast(kind: GuideToast, arg: String?)
    fun announce(text: String)
    fun exitApp()
    fun openSearch()
    fun openSettings()
}

/**
 * The state machine's side effects (docs/design/m3-guide.md §2.5, §3.16.3). Main-safe and non-blocking: suspend work
 * runs in [scope] (the view model's, main-immediate), results come back as [GuideEvent]s through [dispatch].
 *
 * Toast ownership: effects whose outcome is only known after an async call toast it themselves (reminder set/cancel,
 * favourite add/remove, hide, catch-up failure, reminder channel gone). The state machine toasts the rest
 * (NO_FAVOURITES, NO_CATCHUP, REMINDERS_MISSED, EXIT_HINT).
 */
class GuideEffectsImpl(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val controller: PlaybackController,
    private val library: GuideLibrary,
    private val rows: () -> GuideRowsCache?,
    private val pager: () -> GuideWindowPager?,
    private val reminders: ReminderBook,
    private val alerts: ReminderAlerts,
    private val catchup: CatchupLauncher,
    private val session: GuideSessionStore,
    private val settings: GuideSettingsSource,
    private val dispatch: (GuideEvent) -> Unit,
    private val shell: ShellEffects,
    /** Called with every lineup the current source emits (the view model updates the zap list, §3.16.3). */
    private val onLineup: (GuideLineup) -> Unit = {},
) : GuideEffects {

    private var lineupJob: Job? = null

    /** The source whose lineup is being collected. */
    var currentSource: GuideSource? = null
        private set

    /** The lineup last delivered for [currentSource]. */
    var currentLineup: GuideLineup? = null
        private set

    override fun loadLineup(source: GuideSource) {
        lineupJob?.cancel()
        currentSource = source
        currentLineup = null
        val unlocked = settings.settings.value.debugUnlocked
        lineupJob = scope.launch {
            library.lineup(source, unlocked).collect { lineup ->
                currentLineup = lineup
                rows()?.setLineup(lineup)
                pager()?.invalidate()
                dispatch(GuideEvent.Lineup(lineup))
                onLineup(lineup)
            }
        }
    }

    override fun ensureRows(from: Int, to: Int) {
        rows()?.ensure(from, to)
    }

    override fun ensureWindow(topRow: Int, windowStartMin: Int, holdDirection: Int) {
        pager()?.ensure(topRow, windowStartMin, holdDirection)
    }

    override fun loadInfo(token: Long, channel: GuideChannel, rawStartMin: Int) {
        val p = pager() ?: return
        launchSafely("info") { dispatch(GuideEvent.Info(token, p.info(channel, rawStartMin))) }
    }

    override fun loadTunedNow(channel: GuideChannel, atMin: Int) {
        val p = pager() ?: return
        launchSafely("tunedNow") { dispatch(GuideEvent.TunedNow(p.at(channel, atMin))) }
    }

    override fun tune(channel: GuideChannel, lineup: GuideLineup) {
        controller.tune(TuneTarget.Channel(channel.identity), LineupZapList(lineup, library))
    }

    override fun playArchive(channel: GuideChannel, info: ProgrammeInfo) {
        val rawEnd = info.rawStartMin + (info.endMin - info.startMin)
        launchSafely("catchup") {
            when (val r = catchup.play(channel, info, rawEnd)) {
                CatchupLaunch.Started -> Unit
                is CatchupLaunch.Failed -> shell.toast(GuideToast.CATCHUP_FAILED, r.reason.name)
            }
        }
    }

    override fun setFullScreen(full: Boolean) = shell.setFullScreen(full)

    override fun zap(delta: Int, isRepeat: Boolean) = controller.zap(delta, isRepeat)

    override fun previousChannel() {
        controller.previousChannel()
    }

    override fun retry() = controller.retry()

    override fun setReminder(channel: GuideChannel, info: ProgrammeInfo) {
        launchSafely("remind") {
            val first = withContext(io) { !session.flag(FLAG_REMINDER_EXPLAINED) }
            val set = reminders.set(channel, info, settings.settings.value.reminderLeadSec)
            if (!set) return@launchSafely
            if (first) {
                withContext(io) { session.setFlag(FLAG_REMINDER_EXPLAINED) }
                shell.toast(GuideToast.REMINDER_SET_FIRST_TIME, null)
            } else {
                shell.toast(GuideToast.REMINDER_SET, null)
            }
        }
    }

    override fun cancelReminder(channel: GuideChannel, rawStartMin: Int) {
        launchSafely("cancelReminder") {
            reminders.cancel(channel.identity, rawStartMin)
            shell.toast(GuideToast.REMINDER_CANCELLED, null)
        }
    }

    /**
     * "Switch now" / auto-switch accepts (marks fired) and tunes the channel; the state machine goes FULL on that
     * channel's first active Tuned. A channel that is gone is marked orphaned and toasted instead, and the guide stays
     * (§3.11.3, S13). (code review 2026-10-14, H-S7) Works from the [alert] the card showed, never from
     * `alerts.current`, which may have moved on (or cleared) between the key and this coroutine.
     */
    override fun answerReminder(alert: ReminderAlert, accept: Boolean) {
        val reminderId = alert.reminderId
        launchSafely("answerReminder") {
            if (!accept) {
                alerts.dismiss(reminderId)
                return@launchSafely
            }
            if (library.rowIdOf(alert.identity) == null) {
                reminders.markOrphaned(reminderId)
                alerts.dismiss(reminderId)
                shell.toast(GuideToast.REMINDER_CHANNEL_GONE, alert.channelName)
                return@launchSafely
            }
            alerts.accept(reminderId)
            val zap = currentLineup?.let { LineupZapList(it, library) }
            controller.tune(TuneTarget.Channel(alert.identity), zap)
        }
    }

    override fun queryFavourite(token: Long, channel: GuideChannel) {
        launchSafely("isFavourite") { dispatch(GuideEvent.Favourite(token, library.isFavourite(channel.identity))) }
    }

    override fun setFavourite(channel: GuideChannel, on: Boolean) {
        launchSafely("favourite") {
            library.setFavourite(channel.identity, on)
            shell.toast(if (on) GuideToast.ADDED_FAVOURITE else GuideToast.REMOVED_FAVOURITE, channel.displayName)
        }
    }

    override fun hideChannel(channel: GuideChannel) {
        launchSafely("hide") {
            library.hideChannel(channel.identity)
            shell.toast(GuideToast.CHANNEL_HIDDEN, channel.displayName)
        }
    }

    override fun groupAction(entry: GuideSourceEntry, action: GroupAction) {
        launchSafely("groupAction") { library.groupAction(entry, action) }
    }

    /** In the source's playlist; Favourites/Recent use the tuned channel's playlist, else the first enabled one (§3.9.4). */
    override fun lookupNumber(token: Long, number: Int) {
        val playlistId = when (val s = currentSource) {
            is GuideSource.All -> s.playlistId
            is GuideSource.Group -> s.playlistId
            else -> controller.state.value.target?.identity?.playlistId
        }
        val unlocked = settings.settings.value.debugUnlocked
        launchSafely("number") {
            val ch = library.byNumber(playlistId, number, unlocked)
            val index = ch?.let { rows()?.indexOf(it.rowId) } ?: -1
            dispatch(GuideEvent.Number(token, ch, index))
        }
    }

    override fun persistSource(source: GuideSource) {
        scope.launch(io) {
            try {
                session.setLastSource(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "persistSource failed: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun openSearch() = shell.openSearch()

    override fun openSettings() = shell.openSettings()

    override fun exitApp() = shell.exitApp()

    override fun toast(kind: GuideToast, arg: String?) = shell.toast(kind, arg)

    override fun announce(text: String) = shell.announce(text)

    fun close() {
        lineupJob?.cancel()
        lineupJob = null
    }

    private inline fun launchSafely(what: String, crossinline block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "$what failed: ${e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private const val TAG = "GuideEffects"
        /** GuideSessionStore flag: the first "Remind me" explained that reminders need the app open (§3.11.3). */
        const val FLAG_REMINDER_EXPLAINED = GuideSessionStore.FLAG_REMINDER_EXPLAINED
    }
}
