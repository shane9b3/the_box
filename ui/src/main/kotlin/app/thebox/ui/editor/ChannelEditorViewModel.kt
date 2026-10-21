package app.thebox.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thebox.core.editor.ChannelEditorEffects
import app.thebox.core.editor.ChannelEditorEvent
import app.thebox.core.editor.ChannelEditorMachine
import app.thebox.core.editor.ChannelEditorMachineFactory
import app.thebox.core.editor.ChannelEditorRepository
import app.thebox.core.editor.ChannelTarget
import app.thebox.core.editor.EditorFilter
import app.thebox.core.editor.EditorList
import app.thebox.core.editor.EditorPane
import app.thebox.core.editor.EditorText
import app.thebox.core.editor.EditorTexts
import app.thebox.core.editor.EditorWrite
import app.thebox.core.editor.WriteFailure
import app.thebox.core.editor.WriteResult
import app.thebox.core.guide.GuideEvent
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalSession
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.TextHint
import app.thebox.core.settings.state.GuideDomain
import app.thebox.core.settings.state.SettingsShellEvent
import app.thebox.core.settings.state.SettingsShellLink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The channel editor's view model (docs/design/m6-channel-editor.md §3.3, §7.4): hosts the editor machine (created by
 * [ChannelEditorMachineFactory], normally `ChannelEditorMachineImpl.FACTORY`) and implements its effects over
 * [ChannelEditorRepository], [ParentalSession] and the [SettingsShellLink] seams (text editor, number pad, PIN). Lives
 * as long as the activity, like the settings view model; [open] / [close] bound one visit of the editor page.
 *
 * Rules (from the machine's contract):
 * - every load gets a new, increasing version (one counter for both panes); pages are only sent when the machine asks,
 *   and a page asked for an old version is not read;
 * - `keepKey` is answered with its new index ([ChannelEditorRepository.indexOf] on IO; groups by key), null = keep the
 *   index; a data change seen through [ChannelEditorRepository.changes] re-runs both lists with `cursorIndex = null`;
 * - writes run one at a time, in order, in the application scope ([EditorWriteRunner]): leaving the editor never
 *   cancels one;
 * - [ChannelEditorEvent.Access] follows the parental session (also before [open]); Ticks follow `nextDeadlineMs`.
 *
 * Results the machine reacts to are always posted (never dispatched inside the machine's own call).
 */
@HiltViewModel
class ChannelEditorViewModel internal constructor(
    factory: ChannelEditorMachineFactory,
    private val repo: ChannelEditorRepository,
    private val parental: ParentalSession,
    /** The editor's texts (the screen draws with them too). */
    val texts: EditorTexts,
    private val writer: EditorWriteRunner,
    store: SettingsStore?,
    scope: CoroutineScope?,
    private val main: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    private val nowMs: () -> Long,
) : ViewModel(), ChannelEditorEffects {
    @Inject constructor(
        factory: ChannelEditorMachineFactory,
        repo: ChannelEditorRepository,
        parental: ParentalSession,
        texts: EditorTexts,
        writer: EditorWriteRunner,
        store: SettingsStore,
    ) : this(factory, repo, parental, texts, writer, store, null, Dispatchers.Main, Dispatchers.IO, android.os.SystemClock::uptimeMillis)

    private val scope: CoroutineScope = scope ?: viewModelScope

    val machine: ChannelEditorMachine = factory.create(this, texts, nowMs)

    /** "Channel logos in guide" (§2.2: the logo slot is off when it is off). */
    val showLogos: StateFlow<Boolean> = store?.flow(GuideDomain)?.map { it.showLogos }?.distinctUntilChanged()
        ?.stateIn(this.scope, SharingStarted.Eagerly, store.flow(GuideDomain).value.showLogos) ?: MutableStateFlow(true)

    private var link: SettingsShellLink? = null

    /** One visit's work (loads, pages, the change feed); cancelled by [close]. Writes are not in it. */
    private var session: Job? = null
    private var sessionScope: CoroutineScope? = null
    private var openRoute: SettingsRoute.ChannelEditor? = null
    private var groupsJob: Job? = null
    private var channelsJob: Job? = null

    private var versions = 0L
    private var groupsVersion = -1L
    private var groupsPid = 0L
    private var groupKeys: List<String> = emptyList()
    private var channelsVersion = -1L
    private var channelIds = LongArray(0)
    private var summaryJob: Job? = null

    init {
        val s = this.scope
        // Access may reach the machine before open() (the machine keeps it while closed).
        s.launch(main) {
            parental.state.map { it.pinSet to it.unlocked }.distinctUntilChanged().collect { (pinSet, unlocked) ->
                machine.dispatch(ChannelEditorEvent.Access(pinSet, unlocked))
            }
        }
        s.launch(main) {
            // Review S4: a summary is only wanted while its menu is open and loading; closing the menu cancels it.
            machine.ui.map { it.menu?.loading == true }.distinctUntilChanged().collect { loading ->
                if (!loading) { summaryJob?.cancel(); summaryJob = null }
            }
        }
        s.launch(main) {
            machine.nextDeadlineMs.collectLatest { at ->
                if (at == Long.MAX_VALUE) return@collectLatest
                // Review N6: re-arm until the deadline has really passed (a deadline is never served early).
                while (nowMs() < at) delay(at - nowMs())
                machine.dispatch(ChannelEditorEvent.Tick(nowMs()))
            }
        }
    }

    /** The settings machine's shell seams (MainActivity attaches it with the key handler). */
    fun attach(link: SettingsShellLink?) { this.link = link }

    /** The editor page is shown for [route] (the slot's composition). Opening the same route again is a no-op. */
    fun open(route: SettingsRoute.ChannelEditor) {
        if (openRoute == route) return
        close()
        openRoute = route
        val job = SupervisorJob(scope.coroutineContext.job)
        session = job
        val ss = CoroutineScope(scope.coroutineContext + job)
        sessionScope = ss
        machine.open(route.playlistId, route.groupKey)
        ss.launch(main) {
            repo.changes(route.playlistId).conflate().collect {
                delay(CHANGE_GAP_MS)
                // Review S2: the machine reloads with its own keepKey, so no load of ours competes with it.
                machine.dispatch(ChannelEditorEvent.Changed)
            }
        }
    }

    /** The editor page left the screen (popped, Settings closed). Queued writes keep running. */
    fun close() {
        if (openRoute == null) return
        openRoute = null
        session?.cancel()
        session = null
        sessionScope = null
        groupsJob = null; channelsJob = null
        groupsVersion = -1L; channelsVersion = -1L
        groupKeys = emptyList(); channelIds = LongArray(0)
        summaryJob = null
        machine.close()
    }

    /** A key while the editor route is on top (the settings machine's key handler); false = the shell handles it. */
    fun act(action: GuideEvent.Act): Boolean = machine.act(action)

    override fun onCleared() {
        close()
    }

    // ---------------------------------------------------------------- ChannelEditorEffects

    private fun access(): ParentalAccess = parental.state.value.access

    private fun post(block: () -> Unit) {
        scope.launch(main) { block() }
    }

    override fun loadGroups(playlistId: Long, showHidden: Boolean, keepKey: String?) {
        val ss = sessionScope ?: return
        groupsJob?.cancel()
        val v = ++versions
        groupsJob = ss.launch(main) {
            try {
                val a = access()
                val list = withContext(io) { if (!repo.playlistExists(playlistId)) null else repo.groups(playlistId, showHidden, a) }
                if (list == null) {
                    machine.dispatch(ChannelEditorEvent.PlaylistGone)
                    return@launch
                }
                groupsVersion = v
                groupsPid = playlistId
                groupKeys = list.keys
                val index = keepKey?.let { k -> list.keys.indexOf(k).takeIf { it >= 0 } }
                machine.dispatch(ChannelEditorEvent.Groups(v, list.keys.size, list.hidden, index))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                machine.dispatch(ChannelEditorEvent.LoadFailed(EditorPane.GROUPS))
            }
        }
    }

    override fun loadGroupRows(version: Long, from: Int, to: Int) {
        val ss = sessionScope ?: return
        if (version != groupsVersion) return
        val keys = groupKeys
        val f = from.coerceIn(0, keys.size)
        val t = to.coerceIn(f, keys.size)
        if (f >= t) return
        val slice = keys.subList(f, t).toList()
        val pid = groupsPid
        ss.launch(main) {
            try {
                val a = access()
                val rows = withContext(io) { repo.groupRows(pid, slice, a) }
                machine.dispatch(ChannelEditorEvent.GroupRows(version, f, rows))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (version == groupsVersion) machine.dispatch(ChannelEditorEvent.LoadFailed(EditorPane.GROUPS))
            }
        }
    }

    override fun loadChannels(playlistId: Long, list: EditorList, filter: EditorFilter, keepKey: String?) {
        val ss = sessionScope ?: return
        channelsJob?.cancel()
        val v = ++versions
        channelsJob = ss.launch(main) {
            try {
                val a = access()
                val (ids, index) = withContext(io) {
                    val l = repo.channels(playlistId, list, filter, a)
                    l to keepKey?.let { repo.indexOf(l.ids, playlistId, it) }
                }
                channelsVersion = v
                channelIds = ids.ids
                machine.dispatch(ChannelEditorEvent.Channels(list, filter, v, ids.ids.size, ids.hidden, ids.lockedOut, ids.listLocked, index))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                machine.dispatch(ChannelEditorEvent.LoadFailed(EditorPane.CHANNELS))
            }
        }
    }

    override fun loadChannelRows(version: Long, from: Int, to: Int) {
        val ss = sessionScope ?: return
        if (version != channelsVersion) return
        val ids = channelIds
        val f = from.coerceIn(0, ids.size)
        val t = to.coerceIn(f, ids.size)
        if (f >= t) return
        ss.launch(main) {
            try {
                val rows = withContext(io) { repo.channelRows(ids, f, t) }
                machine.dispatch(ChannelEditorEvent.ChannelRows(version, f, rows))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (version == channelsVersion) machine.dispatch(ChannelEditorEvent.LoadFailed(EditorPane.CHANNELS))
            }
        }
    }

    override fun summarize(token: Long, playlistId: Long, target: ChannelTarget) {
        val ss = sessionScope ?: return
        summaryJob?.cancel()
        summaryJob = ss.launch(main) {
            try {
                val a = access()
                val s = withContext(io) { repo.summarize(playlistId, target, a) }
                machine.dispatch(ChannelEditorEvent.Summary(token, s))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                // Review S4: a count failure is the menu's, never the list's; the machine ignores a stale token.
                machine.dispatch(ChannelEditorEvent.SummaryFailed(token))
            }
        }
    }

    override fun locate(playlistId: Long, list: EditorList, filter: EditorFilter, number: Int) {
        val ss = sessionScope ?: return
        ss.launch(main) {
            val index = try {
                val a = access()
                withContext(io) { repo.locate(playlistId, list, filter, a, number) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                null
            }
            machine.dispatch(ChannelEditorEvent.Located(number, index))
        }
    }

    override fun write(write: EditorWrite) {
        // The access the action ran under (an AllOf target re-runs its list under it, WP-B).
        val a = access()
        writer.submit(write, { a }) { result -> post { machine.dispatch(ChannelEditorEvent.Written(write, result)) } }
    }

    override fun editText(label: EditorText, initial: String, allowEmpty: Boolean) {
        val l = link ?: return post { machine.dispatch(ChannelEditorEvent.TextResult(null)) }
        // Rename passes `{ null }` so an empty name ("use the provider's name") commits; Find keeps the hint's rule.
        val validate: ((String) -> String?)? = if (allowEmpty) { _ -> null } else null
        l.editText(texts.get(label), initial, TextHint.NAME, validate = validate) { text -> post { machine.dispatch(ChannelEditorEvent.TextResult(text)) } }
    }

    override fun editNumber(label: EditorText, value: Int?) {
        val l = link ?: return post { machine.dispatch(ChannelEditorEvent.NumberResult(null)) }
        l.editNumber(texts.get(label), value, NUMBER_MIN, NUMBER_MAX) { n -> post { machine.dispatch(ChannelEditorEvent.NumberResult(n)) } }
    }

    override fun requestPin() {
        val l = link ?: return post { machine.dispatch(ChannelEditorEvent.PinResult(false)) }
        l.requestPin { granted -> post { machine.dispatch(ChannelEditorEvent.PinResult(granted)) } }
    }

    override fun announce(text: String) {
        link?.post(SettingsShellEvent.Announce(text))
    }

    internal companion object {
        /** The number pad's range (§5.2 "the pad's existing ERR_NUMBER_RANGE (range 1–99999)"). */
        const val NUMBER_MIN = 1
        const val NUMBER_MAX = 99_999
        /** A burst of invalidations (one write touches several tables) re-reads the lists once. */
        const val CHANGE_GAP_MS = 100L
    }
}

/**
 * The editor's process-wide write FIFO (§3.3 "Write queue"): one write at a time, in order, on IO, in the
 * application-lifetime [EditorWriteScope], so leaving the editor (or the activity) never cancels a queued write. A
 * write that throws reads as [WriteFailure.STORAGE].
 */
@Singleton
class EditorWriteRunner internal constructor(
    private val repo: ChannelEditorRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    @Inject constructor(repo: ChannelEditorRepository, @EditorWriteScope scope: CoroutineScope) : this(repo, scope, Dispatchers.IO)

    private class Pending(val write: EditorWrite, val access: () -> ParentalAccess, val done: (WriteResult) -> Unit)

    private val queue = Channel<Pending>(Channel.UNLIMITED)
    private val started = AtomicBoolean(false)

    fun submit(write: EditorWrite, access: () -> ParentalAccess, done: (WriteResult) -> Unit) {
        if (started.compareAndSet(false, true)) scope.launch(io) {
            for (p in queue) {
                // Review S3: the loop never ends while the runner's scope lives. A write that throws (a cancellation
                // from inside the write included) reads as STORAGE, and every write is answered exactly once, so
                // "Saving…" always clears and later writes still run.
                var result: WriteResult = WriteResult.Failed(WriteFailure.STORAGE)
                try {
                    result = repo.write(p.write, p.access())
                } catch (c: CancellationException) {
                    if (!isActive) throw c
                } catch (e: Exception) {
                    // STORAGE (above)
                } finally {
                    try { p.done(result) } catch (e: Exception) { /* a caller's failure never stops the runner */ }
                }
            }
        }
        queue.trySend(Pending(write, access, done))
    }
}

/** The application-lifetime scope the editor's writes run in (`:ui` cannot see `:data`'s `@ApplicationScope`). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EditorWriteScope
