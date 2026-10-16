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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val clock: () -> Long,
) : ViewModel() {
    @Inject constructor(
        search: GuideSearch,
        reminders: ReminderBook,
        library: GuideLibrary,
        settings: GuideSettingsSource,
        session: GuideSessionStore,
        parental: ParentalSession,
    ) : this(search, reminders, library, settings, session, parental, System::currentTimeMillis)

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

    /** Searches actually started (test hook). */
    @Volatile var started: Int = 0
        private set

    init {
        viewModelScope.launch { reminders.index.collect { idx -> mutable.update { it.copy(reminders = idx) } } }
    }

    fun onQuery(text: String) {
        if (text == mutable.value.query) return
        mutable.update { it.copy(query = text) }
        job?.cancel()
        if (text.count { it.isLetterOrDigit() } < MIN_CHARS) {
            publish(null, searching = false)
            return
        }
        mutable.update { it.copy(searching = true) }
        job = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            started++
            try {
                // (M5 commit 0, S10) explicit access; WP-C feeds it from ParentalSession.state.
                val r = search.search(text, clock(), access = access())
                publish(r, searching = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publish(SearchResults(text, emptyList(), emptyList()), searching = false)
            }
        }
    }

    private fun publish(r: SearchResults?, searching: Boolean) {
        resultsFlow.value = r
        mutable.update { it.copy(results = r, searching = searching, guideDataMissing = r?.guideDataMissing ?: it.guideDataMissing) }
    }

    /** OK on a channel: tune it and go FULL. */
    fun onChannel(channel: GuideChannel) {
        emit(SearchEffect.Tune(channel))
        close(channel, tuned = true)
    }

    /** OK on a programme: on now → tune; in archive → catch-up; future → toggle its reminder (toast). */
    fun onProgramme(hit: ProgrammeHit) {
        val now = (clock() / 60_000L).toInt()
        when {
            hit.info.startMin <= now && hit.info.endMin > now -> onChannel(hit.channel)
            hit.inArchive -> {
                emit(SearchEffect.PlayArchive(hit.channel, hit.info))
                close(hit.channel, tuned = true, archive = true)
            }
            hit.info.startMin > now -> toggleReminder(hit)
            else -> emit(SearchEffect.Toast(GuideToast.NO_CATCHUP))
        }
    }

    fun hasReminder(hit: ProgrammeHit): Boolean = mutable.value.reminders.contains(hit.channel.identityHash, hit.info.rawStartMin)

    fun toggleReminder(hit: ProgrammeHit) {
        viewModelScope.launch {
            if (hasReminder(hit)) {
                reminders.cancel(hit.channel.identity, hit.info.rawStartMin)
                emit(SearchEffect.Toast(GuideToast.REMINDER_CANCELLED))
            } else if (reminders.set(hit.channel, hit.info, settings.settings.value.reminderLeadSec)) {
                val first = !session.flag(FLAG_REMINDER_SEEN)
                if (first) session.setFlag(FLAG_REMINDER_SEEN)
                emit(SearchEffect.Toast(if (first) GuideToast.REMINDER_SET_FIRST_TIME else GuideToast.REMINDER_SET))
            }
        }
    }

    fun toggleFavourite(channel: GuideChannel) {
        viewModelScope.launch {
            val on = !library.isFavourite(channel.identity)
            library.setFavourite(channel.identity, on)
            emit(SearchEffect.Toast(if (on) GuideToast.ADDED_FAVOURITE else GuideToast.REMOVED_FAVOURITE, channel.displayName))
        }
    }

    suspend fun isFavourite(channel: GuideChannel): Boolean = library.isFavourite(channel.identity)

    /** BACK from the field (or with nothing focused): back to the guide, nothing tuned. */
    fun onBack() = close(null, tuned = false)

    private fun close(channel: GuideChannel?, tuned: Boolean, archive: Boolean = false) {
        job?.cancel()
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
