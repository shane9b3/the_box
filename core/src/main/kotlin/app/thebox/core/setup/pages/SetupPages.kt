package app.thebox.core.setup.pages

import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SourceKind
import app.thebox.core.settings.state.SetupDomain
import app.thebox.core.setup.EditableSource
import app.thebox.core.source.PlaylistType
import kotlinx.coroutines.launch

/**
 * WP-B's pages (docs/design/m5-setup-settings.md §3.4, §3.6): FirstRun, AddSource, ImportProgress, GroupsReview (+ the
 * matching report), Section(PLAYLISTS), Playlist, PlaylistSource and FixGuide. One instance per settings machine;
 * main thread. Drafts and import trackers outlive the pages (process lifetime, memory only).
 */
class SetupPages(private val d: SetupPageDeps) : SetupPageProvider {
    private val open = HashMap<SettingsRoute, SetupPage>()
    private val trackers = HashMap<Long, ImportTracker>()
    /** Playlists whose import was started from a PlaylistSource edit: Edit after a failure goes back there. */
    private val editedInPlace = HashSet<Long>()

    override fun handles(route: SettingsRoute): Boolean = when (route) {
        SettingsRoute.FirstRun, is SettingsRoute.AddSource, is SettingsRoute.ImportProgress, is SettingsRoute.GroupsReview, is SettingsRoute.MatchingReport,
        is SettingsRoute.Playlist, is SettingsRoute.PlaylistSource, is SettingsRoute.FixGuide -> true
        is SettingsRoute.Section -> route.id == SectionId.PLAYLISTS
        else -> false
    }

    override fun open(route: SettingsRoute, host: PageHost) {
        close(route)
        val page = create(route) ?: return
        page.host = host
        open[route] = page
        page.start()
    }

    override fun close(route: SettingsRoute) {
        open.remove(route)?.let { p ->
            p.close()
            (p as? ImportProgressPage)?.dispose()
        }
    }

    override fun onIntent(route: SettingsRoute, intent: RowIntent, host: PageHost) {
        val p = open[route] ?: return
        p.host = host
        p.onIntent(intent)
    }

    override fun onBack(route: SettingsRoute, host: PageHost): Boolean {
        val p = open[route] ?: return false
        p.host = host
        return p.onBack()
    }

    override fun loadItems(route: SettingsRoute, from: Int, to: Int, host: PageHost) {
        val p = open[route] as? FixUnmatchedPage ?: return
        host.items(route, p.lastVersion, from, p.rowsFor(from, to))
    }

    override fun onFilePicked(route: SettingsRoute, uri: String?, displayName: String?, host: PageHost) {
        val p = open[route] ?: return
        p.host = host
        p.onFilePicked(uri, displayName)
    }

    /** The import tracker of [playlistId] (created on first use; it keeps folding after its page closed). */
    fun tracker(playlistId: Long): ImportTracker = trackers.getOrPut(playlistId) {
        ImportTracker(playlistId, d.scope, d.setup::progress) { id ->
            // §3.4.1 and the lead ruling of §1.9a: the first import that reaches stage 3 completes the setup.
            d.store.update(SetupDomain) { it.copy(completed = true, lastAddedPlaylistId = id) }
        }
    }

    private fun create(route: SettingsRoute): SetupPage? = when (route) {
        SettingsRoute.FirstRun -> WelcomePage(d)
        is SettingsRoute.AddSource -> when (route.kind) {
            null -> AddSourceChooserPage(route, d)
            SourceKind.XTREAM -> XtreamFormPage(route, d, route.firstRun, onSaved = ::importStarted)
            SourceKind.M3U -> M3uFormPage(route, d, route.firstRun, onSaved = ::importStarted)
        }
        is SettingsRoute.ImportProgress -> ImportProgressPage(route, d, tracker(route.playlistId).also { it.start() }) { type ->
            if (route.playlistId in editedInPlace) SettingsRoute.PlaylistSource(route.playlistId) else formRoute(type, route.firstRun)
        }
        is SettingsRoute.GroupsReview -> GroupsReviewPage(route, d, trackers[route.playlistId])
        is SettingsRoute.MatchingReport -> MatchingReportPage(route, d, trackers[route.playlistId])
        is SettingsRoute.Section -> if (route.id == SectionId.PLAYLISTS) PlaylistsSectionPage(d) else null
        is SettingsRoute.Playlist -> PlaylistPage(route, d)
        is SettingsRoute.PlaylistSource -> PlaylistSourcePage(route, d) { id -> editedInPlace += id; importStarted(id) }
        is SettingsRoute.FixGuide -> if (route.channel == null) FixUnmatchedPage(d) else FixPickerPage(route, d)
        else -> null
    }

    /** A save started an import: a fresh tracker for it (a retry of an earlier import restarts the fold). */
    private fun importStarted(playlistId: Long) {
        trackers.remove(playlistId)?.stop()
    }
}

/**
 * PlaylistSource(id) (§3.3.1, product #6): edit the source in place. Loads the current source (with its secrets, in
 * memory only) and delegates to the Xtream or M3U form with `existingId`, so `playlist_id` and every
 * `(playlist_id, stable_key)` row stay.
 */
class PlaylistSourcePage(
    private val r: SettingsRoute.PlaylistSource,
    d: SetupPageDeps,
    private val onSaved: (Long) -> Unit,
) : SetupPage(r, d) {
    private var inner: SetupPage? = null

    override fun start() {
        publish()
        launch {
            val src = d.admin.editableSource(r.playlistId)
            val type = d.admin.summary(r.playlistId)?.type
            inner = when (src) {
                is EditableSource.Xtream -> XtreamFormPage(r, d, firstRun = false, existingId = r.playlistId, onSaved = onSaved).also { it.prefill(src.form) }
                is EditableSource.M3u -> M3uFormPage(r, d, firstRun = false, existingId = r.playlistId, onSaved = onSaved).also { it.prefill(src.form) }
                null -> if (type == PlaylistType.XTREAM) XtreamFormPage(r, d, firstRun = false, existingId = r.playlistId, onSaved = onSaved) else null
            }
            inner?.host = host
            publish()
        }
    }

    override fun build() = inner?.build() ?: page(t(app.thebox.core.setup.SetupText.SOURCE_TITLE), emptyList(), placeholder = t(app.thebox.core.setup.SetupText.LOADING))

    override fun onIntent(intent: RowIntent) {
        inner?.let { it.host = host; it.onIntent(intent) }
    }

    override fun onFilePicked(uri: String?, displayName: String?) {
        inner?.let { it.host = host; it.onFilePicked(uri, displayName) }
    }

    override fun close() {
        inner?.close()
        super.close()
    }
}
