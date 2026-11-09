package app.thebox.ui.search

import app.thebox.core.parental.legacyParentalAccess
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalRules
import app.thebox.core.parental.ParentalSession
import app.thebox.ui.parental.NoPinSession
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideLibrary
import app.thebox.core.guide.GuideSearch
import app.thebox.core.guide.GuideSessionStore
import app.thebox.core.guide.GuideSettingsSource
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.ProgrammeHit
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.ReminderIndex
import app.thebox.core.guide.SearchResults
import app.thebox.core.guide.ReminderBook
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the Search screen asks its host to do (the guide VM, WP-C, collects [SearchViewModel.effects]). */
sealed interface SearchEffect {
    /** Tune the channel (live); always followed by [Close] with `tuned = true`. */
    data class Tune(val channel: GuideChannel) : SearchEffect
    /** Catch-up from the programme's start (§3.13.6); followed by [Close] with `tuned = true, archive = true`. */
    data class PlayArchive(val channel: GuideChannel, val info: ProgrammeInfo) : SearchEffect
    data class Toast(val kind: GuideToast, val arg: String? = null) : SearchEffect
    /** Leave SEARCH (review B7: the state machine owns the mode; forward this to `GuideStateMachine.dispatch`). */
    data class Close(val event: GuideEvent.SearchClosed) : SearchEffect
}

data class SearchUiState(
    val query: String = "",
    /** Null before the first search of at least [SearchViewModel.MIN_CHARS] letters/digits. */
    val results: SearchResults? = null,
    val searching: Boolean = false,
    /** The last search had no EPG generation (§2.13 item 7): channels still show, the programmes row says "Programme search needs guide data." */
    val guideDataMissing: Boolean = false,
    val reminders: ReminderIndex = ReminderIndex.EMPTY,
    /** The 12/24-hour setting for the programme times (code review 2026-11-09, U5). */
    val clock24: Boolean = true,
)

/**
 * The S9 Search screen's view model (docs/design/m3-guide.md §3.10.3; review S15, B7). Debounces typing by
 * [DEBOUNCE_MS], searches from [MIN_CHARS] letters/digits, cancels the previous search when the text changes, and leaves
 * SEARCH only through [SearchEffect.Close]. Locked content is filtered by [GuideSearch] with the parental session's
 * access (M5, S10: OPEN while unlocked), or OPEN while the debug unlock switch (`GuideSettings.debugUnlocked`) is on.
 */
@HiltViewModel
class SearchViewModel internal constructor(
    private val search: GuideSearch,
    private val reminders: ReminderBook,
    private val library: GuideLibrary,
    private val settings: GuideSettingsSource,
    private val session: GuideSessionStore,
    /** (M5, S10) Search filters locked content with the session's access (OPEN while unlocked). */
    private val parental: ParentalSession = NoPinSession,
    /** EPG generations invalidate programme hits even if the library did not change. */
    private val contentChanges: Flow<*> = emptyFlow<Any>(),
    private val clock: () -> Long,
) : ViewModel() {
    @Inject constructor(
        search: GuideSearch,
        reminders: ReminderBook,
        library: GuideLibrary,
        settings: GuideSettingsSource,
        session: GuideSessionStore,
        parental: ParentalSession,
        epg: app.thebox.core.epg.EpgStore,
    ) : this(search, reminders, library, settings, session, parental, epg.info, System::currentTimeMillis)

    /** The debug unlock switch forces OPEN; otherwise the session's access (§4.3, S10). */
    private fun access(): ParentalAccess {
        val requested = legacyParentalAccess(settings.settings.value.debugUnlocked)
        return if (requested == ParentalAccess.OPEN) requested else ParentalRules.accessFor(parental.state.value, null)
    }

    private val mutable = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = mutable.asStateFlow()

    private val resultsFlow = MutableStateFlow<SearchResults?>(null)
    val results: StateFlow<SearchResults?> = resultsFlow.asStateFlow()

    private val effectsFlow = MutableSharedFlow<SearchEffect>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val effects: SharedFlow<SearchEffect> = effectsFlow.asSharedFlow()

    private var job: Job? = null
    private var contentJob: Job? = null
    private var active = false
    private var epoch = 0L
    private data class AccessKey(val access: ParentalAccess, val parentalQueryKey: Int)
    private fun accessKey() = AccessKey(access(), parental.state.value.queryKey)
    private var publishedAccess: AccessKey? = null

    /** True between [onEntered] (or typing) and [onLeft]: the screen is on a Search visit. */
    val isVisiting: Boolean get() = active

    /** Searches actually started (test hook). */
    @Volatile var started: Int = 0
        private set

    init {
        viewModelScope.launch { reminders.index.collect { idx -> mutable.update { it.copy(reminders = idx) } } }
        viewModelScope.launch { settings.settings.collect { s -> mutable.update { it.copy(clock24 = s.clock24) } } }
        val initialAccess = accessKey()
        viewModelScope.launch {
            var previous = initialAccess
            combine(parental.state, settings.settings) { _, _ -> accessKey() }.distinctUntilChanged().collect { key ->
                if (key != previous) { previous = key; refresh() }
            }
        }
    }

    /** Subscribe only while Search is visited; an Activity-owned VM must not duplicate guide queries offscreen. */
    private fun activate(): Boolean {
        if (active) return false
        active = true
        contentJob = viewModelScope.launch {
            coroutineScope {
                launch { library.rowContent.distinctUntilChanged().collect { refresh(keepResults = true) } }
                launch { library.sources(ParentalAccess.OPEN).distinctUntilChanged().drop(1).collect { refresh(keepResults = true) } }
                launch { contentChanges.drop(1).collect { refresh(keepResults = true) } }
            }
        }
        return true
    }

    fun onEntered() {
        if (activate()) refresh()
    }

    fun onLeft() {
        active = false
        refresh() // revoke the snapshot and cancel IO before disposing the content observers
        contentJob?.cancel()
        contentJob = null
    }

    fun onQuery(text: String) {
        if (text == mutable.value.query) return
        activate()
        mutable.update { it.copy(query = text) }
        refresh()
    }

    /**
     * Clear first, including modal targets; an old IO result must never republish after relocking.
     *
     * (code review 2026-11-09, U2) [keepResults] is for a content change with the same query and access (a library or
     * EPG refresh, or the user's own Add to favourites): the shown snapshot stays, with its cards and focus, until the
     * new result replaces it. An access change, a new query or a new visit still clears first.
     */
    fun refresh(keepResults: Boolean = false) {
        val requestEpoch = ++epoch
        job?.cancel()
        val text = mutable.value.query
        val tooShort = !active || text.count { it.isLetterOrDigit() } < MIN_CHARS
        if (!keepResults || tooShort) {
            publishedAccess = null
            publish(null, searching = false)
        }
        if (tooShort) {
            return
        }
        val key = accessKey()
        mutable.update { it.copy(searching = true) }
        job = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            started++
            try {
                // (M5 commit 0, S10) explicit access; WP-C feeds it from ParentalSession.state.
                val r = search.search(text, clock(), access = key.access)
                if (requestEpoch != epoch || key != accessKey()) return@launch
                publishedAccess = key
                publish(r, searching = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestEpoch == epoch && key == accessKey()) {
                    publishedAccess = key
                    publish(SearchResults(text, emptyList(), emptyList()), searching = false)
                }
            }
        }
    }

    /** UI actions carry the exact published snapshot, not just a channel that might now be protected. */
    fun isCurrent(results: SearchResults): Boolean = mutable.value.results === results && publishedAccess == accessKey()

    private fun allowed(channel: GuideChannel, snapshot: SearchResults): Boolean =
        (isCurrent(snapshot) && (channel in snapshot.channels || snapshot.programmes.any { it.channel == channel }))

    private fun allowed(hit: ProgrammeHit, snapshot: SearchResults): Boolean =
        (isCurrent(snapshot) && hit in snapshot.programmes)

    private fun publish(new: SearchResults?, searching: Boolean) {
        // U2: an equal answer after a kept snapshot keeps that instance, so an open menu's snapshot stays current.
        val r = mutable.value.results?.takeIf { it == new } ?: new
        resultsFlow.value = r
        mutable.update { it.copy(results = r, searching = searching, guideDataMissing = r?.guideDataMissing ?: it.guideDataMissing) }
    }

    /** OK on a channel: tune it and go FULL. */
    fun onChannel(channel: GuideChannel, snapshot: SearchResults) {
        if (!allowed(channel, snapshot)) return
        emit(SearchEffect.Tune(channel))
        close(channel, tuned = true)
    }

    /** OK on a programme: on now → tune; in archive → catch-up; future → toggle its reminder (toast). */
    fun onProgramme(hit: ProgrammeHit, snapshot: SearchResults) {
        if (!allowed(hit, snapshot)) return
        val now = (clock() / 60_000L).toInt()
        when {
            hit.info.startMin <= now && hit.info.endMin > now -> onChannel(hit.channel, snapshot)
            hit.inArchive -> {
                emit(SearchEffect.PlayArchive(hit.channel, hit.info))
                close(hit.channel, tuned = true, archive = true)
            }
            hit.info.startMin > now -> toggleReminder(hit, snapshot)
            else -> emit(SearchEffect.Toast(GuideToast.NO_CATCHUP))
        }
    }

    fun hasReminder(hit: ProgrammeHit): Boolean = mutable.value.reminders.contains(hit.channel.identityHash, hit.info.rawStartMin)

    fun toggleReminder(hit: ProgrammeHit, snapshot: SearchResults) {
        if (!allowed(hit, snapshot)) return
        viewModelScope.launch {
            if (!allowed(hit, snapshot)) return@launch
            if (hasReminder(hit)) {
                reminders.cancel(hit.channel.identity, hit.info.rawStartMin)
                if (!allowed(hit, snapshot)) return@launch
                emit(SearchEffect.Toast(GuideToast.REMINDER_CANCELLED))
            } else if (reminders.set(hit.channel, hit.info, settings.settings.value.reminderLeadSec)) {
                if (!allowed(hit, snapshot)) return@launch
                val first = !session.flag(FLAG_REMINDER_SEEN)
                if (first) session.setFlag(FLAG_REMINDER_SEEN)
                emit(SearchEffect.Toast(if (first) GuideToast.REMINDER_SET_FIRST_TIME else GuideToast.REMINDER_SET))
            }
        }
    }

    fun toggleFavourite(channel: GuideChannel, snapshot: SearchResults) {
        if (!allowed(channel, snapshot)) return
        viewModelScope.launch {
            if (!allowed(channel, snapshot)) return@launch
            val on = !library.isFavourite(channel.identity)
            if (!allowed(channel, snapshot)) return@launch
            library.setFavourite(channel.identity, on)
            if (!allowed(channel, snapshot)) return@launch
            emit(SearchEffect.Toast(if (on) GuideToast.ADDED_FAVOURITE else GuideToast.REMOVED_FAVOURITE, channel.displayName))
        }
    }

    suspend fun isFavourite(channel: GuideChannel): Boolean = library.isFavourite(channel.identity)

    /** BACK from the field (or with nothing focused): back to the guide, nothing tuned. */
    fun onBack() = close(null, tuned = false)

    private fun close(channel: GuideChannel?, tuned: Boolean, archive: Boolean = false) {
        onLeft()
        emit(SearchEffect.Close(GuideEvent.SearchClosed(channel, tuned, archive)))
    }

    private fun emit(e: SearchEffect) {
        effectsFlow.tryEmit(e)
    }

    companion object {
        const val DEBOUNCE_MS = 300L
        const val MIN_CHARS = 2
        /** Shared with the guide (§3.11.3): the first "Remind me" explains that reminders need the app open. */
        const val FLAG_REMINDER_SEEN = GuideSessionStore.FLAG_REMINDER_EXPLAINED
    }
}
