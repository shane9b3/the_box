package app.thebox.ui.settings.vm

import android.content.Context
import app.thebox.core.common.BoxLog
import app.thebox.core.input.BoxAction
import app.thebox.core.input.EffectiveKeyMap
import app.thebox.core.input.KeyBindingStore
import app.thebox.core.input.Press
import app.thebox.core.input.RemappedKeyMap
import app.thebox.core.parental.ParentalSession
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.state.PageContext
import app.thebox.core.settings.state.SettingsPageSource
import app.thebox.core.settings.state.SettingsShellLink
import app.thebox.core.settings.tree.ButtonMappingRows
import app.thebox.core.settings.tree.ParentalRows
import app.thebox.core.settings.tree.RowTexts
import app.thebox.core.settings.tree.SectionPages
import app.thebox.core.setup.pages.PageAction
import app.thebox.core.setup.pages.PageHost
import app.thebox.core.setup.pages.SetupPageProvider
import app.thebox.ui.diag.DiagOutcome
import app.thebox.ui.diag.DiagPages
import app.thebox.ui.parental.MappingPageHost
import app.thebox.ui.parental.ParentalPageHost
import app.thebox.ui.parental.ParentalStrings
import app.thebox.ui.parental.ResourceMappingNames
import app.thebox.ui.setup.SetupPagesFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/*
 * (M5 integration 2026-10-16) Adapters that host the pages of WP-B, WP-C, WP-D and WP-E in WP-A's shell
 * (docs/design/m5-setup-settings.md §1.4a "Seams", §1.5a, §1.6a, §1.7a, §1.8a). Each WP built its pages against its
 * own seam (push, pull or flow); each adapter turns one of them into a [SettingsPageSource], the shell's only page seam:
 * - WP-B `SetupPageProvider` (push: `open` publishes through a `PageHost` until `close`) → [SetupPagesSource];
 * - WP-D `SectionPages` (pull + `invalidations`) → [SectionPagesSource];
 * - WP-C `ParentalRows` / `ButtonMappingRows` builders + `ParentalPageHost` / `MappingPageHost` → [ParentalPagesSource],
 *   [MappingPagesSource] (capture through `SettingsShellLink.startCapture`, i.e. WP-C's `CaptureFlow`);
 * - WP-E `DiagPages` (one `Flow<PageModel>` per route) → [DiagPagesSource].
 * Pushed or flowed pages are cached per open route; the source's `changes` makes the shell rebuild, which reads the
 * cache. Navigation goes through the machine's [SettingsShellLink] on the main dispatcher.
 */

/** Builds the integration adapters for one settings machine (the view model's scope). */
class SettingsPageAdapters @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupPages: SetupPagesFactory,
    private val sectionPages: Set<@JvmSuppressWildcards SectionPages>,
    private val diag: DiagPages,
    private val parental: ParentalSession,
    private val parentalHost: ParentalPageHost,
    private val mappingHost: MappingPageHost,
    private val bindings: KeyBindingStore,
    private val keyMap: EffectiveKeyMap,
    private val phone: app.thebox.core.setup.lan.PhoneLink,
    private val sourceSetup: app.thebox.core.setup.SourceSetup,
    private val normalizer: app.thebox.core.setup.SourceInputNormalizer,
    private val setupStrings: app.thebox.core.setup.SetupStrings,
    private val drafts: app.thebox.core.setup.SetupDraftStore,
) {
    class Built(val sources: List<SettingsPageSource>, val setup: SetupPagesSource, val mapping: MappingPagesSource, val phone: PhoneSetupBridge)

    fun create(scope: CoroutineScope, main: CoroutineDispatcher, work: CoroutineDispatcher, link: () -> SettingsShellLink?): Built {
        val res = context.resources
        val setup = SetupPagesSource(setupPages.create(scope), link, scope, main)
        val sources = ArrayList<SettingsPageSource>()
        sources += setup
        // Stable order (a Hilt set has none): by class name, as the shell orders the WP-A set.
        for (p in sectionPages.sortedBy { it.javaClass.name }) sources += SectionPagesSource(p, link, main)
        sources += ParentalPagesSource(parental, parentalHost, ParentalStrings.parental(res), link, scope, main)
        val mapping = MappingPagesSource(bindings, keyMap, mappingHost, ResourceMappingNames(res), ParentalStrings.mapping(res), link, main)
        sources += mapping
        sources += DiagPagesSource(diag, link, scope, main, work)
        // U-S4 (code review 2026-10-16): the bridge fills WP-B's Xtream draft and runs the form's Connect on a rejected login.
        val bridge = PhoneSetupBridge(
            context, phone, sourceSetup, normalizer, setupStrings, link, scope, main, kotlinx.coroutines.Dispatchers.IO, drafts,
            connect = { r -> setup.onIntent(r, RowIntent.Run(app.thebox.core.settings.RowId(app.thebox.core.setup.pages.XtreamFormPage.ROW_CONNECT)), PageContext(false, System.currentTimeMillis())) },
        )
        return Built(sources, setup, mapping, bridge)
    }
}

private fun signal() = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

/**
 * U-L2 (code review 2026-10-16): true while [origin] is what the user sees (the open route, or the page the sections
 * pane shows). Navigation a page asks for after a suspend (a save, an export, an import stage) is dropped once the user
 * has moved on or left Settings, instead of landing on whatever page is open then. A link that is not a machine (test
 * doubles without state) counts as current.
 */
internal fun SettingsShellLink.shows(origin: SettingsRoute): Boolean {
    val u = (this as? app.thebox.core.settings.SettingsMachine)?.ui?.value ?: return true
    return u.route == origin || u.page?.route == origin
}

/** U-L2: [SettingsShellLink.push] from a page of [origin], dropped unless [origin] is still shown. */
internal fun SettingsShellLink.pushFrom(origin: SettingsRoute, route: SettingsRoute, gated: Boolean = false) {
    if (!shows(origin)) return
    if (gated) pushGated(route) else push(route)
}
private fun noticeFlow() = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

/** WP-B's push-model pages (`SetupPageProvider`, main thread only) as a [SettingsPageSource]. */
class SetupPagesSource(
    private val provider: SetupPageProvider,
    private val link: () -> SettingsShellLink?,
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
) : SettingsPageSource {
    /** An open route: the last page it published (null until its first publish). */
    private class Slot {
        val page = MutableStateFlow<PageModel?>(null)
        /** U-S3: closed while a [page] call waited; the waiter gets a placeholder instead of waiting forever. */
        @Volatile var closed = false
    }

    private val open = ConcurrentHashMap<SettingsRoute, Slot>()
    private val _changes = signal()
    private val _notices = noticeFlow()
    private val _pickRequests = MutableSharedFlow<SettingsRoute>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val changes: Flow<Unit> get() = _changes
    override val notices: Flow<String> get() = _notices

    /** `PageAction.PickM3uFile`: the activity launches the document picker for the route and answers [filePicked]. */
    val pickRequests: SharedFlow<SettingsRoute> = _pickRequests.asSharedFlow()

    override fun handles(route: SettingsRoute): Boolean = provider.handles(route)

    override suspend fun page(route: SettingsRoute, ctx: PageContext): PageModel {
        val slot = open[route] ?: withContext(main) {
            open[route] ?: Slot().also { s ->
                open[route] = s
                provider.open(route, HostFor(route))
            }
        }
        val first = slot.page.filterNotNull().first()
        if (first.placeholder == null || slot.closed) return first
        // U-S1 (code review 2026-10-16): a push-model page publishes a placeholder at once and its data a few ms later
        // (WP-B's Playlists page: Room + sync status). Wait briefly for the data so the first page shown is the real one
        // and "Loading…" does not flash; a slower page is shown as its placeholder and rebuilt on its next publish.
        return withTimeoutOrNull(FIRST_DATA_WAIT_MS) { slot.page.filterNotNull().first { it.placeholder == null || slot.closed } }
            ?: slot.page.value ?: first
    }

    override suspend fun items(route: SettingsRoute, from: Int, to: Int, ctx: PageContext): List<SettingsRow> = withContext(main) {
        var out: List<SettingsRow> = emptyList()
        provider.loadItems(route, from, to, object : PageHost by HostFor(route) {
            override fun items(route: SettingsRoute, version: Long, from: Int, rows: List<SettingsRow>) { out = rows }
        })
        out
    }

    override suspend fun onIntent(route: SettingsRoute, intent: RowIntent, ctx: PageContext): Boolean {
        if (!provider.handles(route)) return false
        if (open[route] == null) {
            // A page the provider no longer holds (closed while the machine still showed it) is reopened first (X1).
            // U-L2 (code review 2026-10-16): only when it is the page shown; an intent for a page the user has left is
            // dropped (nothing would ever close a page reopened for it). Bounded: a provider that never publishes
            // cannot hold the intent queue (U-L1).
            if (link()?.shows(route) == false) return true
            withTimeoutOrNull(REOPEN_WAIT_MS) { page(route, ctx) } ?: return true
        }
        withContext(main) { provider.onIntent(route, intent, HostFor(route)) }
        return true
    }

    override fun onBack(route: SettingsRoute): Boolean = provider.onBack(route, HostFor(route))

    override fun closed(route: SettingsRoute) {
        val slot = open.remove(route) ?: return
        provider.close(route)
        // U-S3 (code review 2026-10-16): wake a page() call still waiting on this slot with a placeholder.
        slot.closed = true
        slot.page.value = PageModel(route, "", rows = emptyList(), version = CLOSED_VERSION, placeholder = "")
    }

    /** The document picker answered for [route] (main thread); [uri] null = cancelled. */
    fun filePicked(route: SettingsRoute, uri: String?, displayName: String?) {
        provider.onFilePicked(route, uri, displayName, HostFor(route))
    }

    private inner class HostFor(private val route: SettingsRoute) : PageHost {
        override fun publish(page: PageModel) {
            val slot = open[page.route] ?: open[route] ?: run { BoxLog.i("SetupPagesSource") { "publish after close: ${route::class.simpleName}" }; return }
            val first = slot.page.value == null
            slot.page.value = page
            // U-S1 (code review 2026-10-16): a route's first publish answers the pending page() call; signalling a change
            // as well only made the shell rebuild (and, with the old throttle, delay the data publish behind it).
            if (!first) _changes.tryEmit(Unit)
        }

        override fun act(action: PageAction) {
            // Pages act from the shell's main scope; an action from elsewhere is moved there.
            scope.launch(main) { run(action) }
        }

        private fun run(action: PageAction) {
            val l = link()
            // U-L2 (code review 2026-10-16): navigation from a page the user has left (a save or an import stage that
            // finished later) is dropped instead of acting on whatever page is open now.
            val current = l?.shows(route) ?: false
            when (action) {
                is PageAction.Push -> if (current) l?.push(action.route)
                is PageAction.Replace -> if (current) l?.replace(action.route)
                PageAction.Pop -> if (current) l?.pop()
                is PageAction.PopTo -> if (current) l?.popTo(action.route)
                PageAction.LeaveToGuide -> if (current) l?.leaveToGuide()
                is PageAction.Notice -> _notices.tryEmit(action.text)
                is PageAction.Confirm -> if (current) l?.confirm(action.spec, action.intent)
                PageAction.PickM3uFile -> _pickRequests.tryEmit(route)
                PageAction.CreatePin -> if (current) l?.requestPinSetup(change = false)
                PageAction.Reload -> _changes.tryEmit(Unit)
            }
        }
    }

    private companion object {
        /** U-S1: how long page() waits for a push-model page's data after its placeholder. */
        const val FIRST_DATA_WAIT_MS = 100L
        /** U-L2: the bound on reopening a page to deliver an intent. */
        const val REOPEN_WAIT_MS = 1_000L
        const val CLOSED_VERSION = Long.MIN_VALUE
    }
}

/** WP-D's pull-model pages (`SectionPages`: channels and groups management, backup and restore). */
class SectionPagesSource(
    private val pages: SectionPages,
    private val link: () -> SettingsShellLink?,
    private val main: CoroutineDispatcher,
) : SettingsPageSource {
    private val _notices = noticeFlow()
    override val notices: Flow<String> get() = _notices
    override val changes: Flow<Unit> get() = pages.invalidations.map { }

    override fun handles(route: SettingsRoute): Boolean = pages.handles(route)
    override suspend fun page(route: SettingsRoute, ctx: PageContext): PageModel = pages.page(route, ctx.advanced)
    override suspend fun items(route: SettingsRoute, from: Int, to: Int, ctx: PageContext): List<SettingsRow> = pages.items(route, from, to)

    override suspend fun onIntent(route: SettingsRoute, intent: RowIntent, ctx: PageContext): Boolean {
        if (!pages.handles(route)) return false
        val a = pages.onIntent(route, intent)
        a.notice?.let { _notices.tryEmit(it) }
        // U-S6 audit (code review 2026-10-16): WP-D opens FavouriteLists and RestorePreview, neither gated (the restore
        // itself asks at `restore.go`, C-B2); U-L2: dropped when the user has left the page while the intent ran.
        a.open?.let { r -> withContext(main) { link()?.pushFrom(route, r) } }
        // C-S8 (code review 2026-10-16): "Start watching" after a restore, and its PIN offer.
        if (a.leaveToGuide) withContext(main) { link()?.takeIf { it.shows(route) }?.leaveToGuide() }
        if (a.setPin) withContext(main) { link()?.takeIf { it.shows(route) }?.requestPinSetup(change = false) }
        return true
    }
}

/** WP-C's Parental controls page (§3.3.8): `ParentalRows` built from the session; commands through `ParentalPageHost`. */
class ParentalPagesSource(
    private val session: ParentalSession,
    private val host: ParentalPageHost,
    private val texts: RowTexts,
    private val link: () -> SettingsShellLink?,
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
) : SettingsPageSource {
    private val version = AtomicLong()
    private val route = SettingsRoute.Section(SectionId.PARENTAL)
    override val changes: Flow<Unit> get() = session.state.drop(1).map { }

    override fun handles(route: SettingsRoute): Boolean = route == this.route
    override suspend fun page(route: SettingsRoute, ctx: PageContext): PageModel =
        ParentalRows.page(session.state.value, texts, version.incrementAndGet(), ctx.advanced)

    override suspend fun onIntent(route: SettingsRoute, intent: RowIntent, ctx: PageContext): Boolean {
        if (route != this.route) return false
        val cmd = ParentalRows.command(intent, session.state.value) ?: return false
        withContext(main) {
            if (cmd is ParentalRows.Command.SetupPin) link()?.requestPinSetup(cmd.change) else host.run(cmd, null, scope)
        }
        return true
    }
}

/** WP-C's button-mapping pages (§3.8.3); capture through WP-C's `CaptureFlow` inside the settings machine. */
class MappingPagesSource(
    private val bindings: KeyBindingStore,
    private val keyMap: EffectiveKeyMap,
    private val host: MappingPageHost,
    private val names: ButtonMappingRows.Names,
    private val texts: RowTexts,
    private val link: () -> SettingsShellLink?,
    private val main: CoroutineDispatcher,
) : SettingsPageSource {
    private val version = AtomicLong()
    override val changes: Flow<Unit> get() = bindings.overrides.drop(1).map { }

    override fun handles(route: SettingsRoute): Boolean = route == SettingsRoute.ButtonMapping || route is SettingsRoute.ActionBindings

    /** The pages read the stored overrides at once (the router's snapshot is rebuilt asynchronously after a save). */
    private fun mapNow(): EffectiveKeyMap = RemappedKeyMap(keyMap.defaults, null, null).also { it.apply(bindings.overrides.value) }

    override suspend fun page(route: SettingsRoute, ctx: PageContext): PageModel {
        val o = bindings.overrides.value
        val map = mapNow()
        return when (route) {
            is SettingsRoute.ActionBindings -> ButtonMappingRows.actionPage(route.action, map, o, names, texts, version.incrementAndGet())
            else -> ButtonMappingRows.mappingPage(map, o, names, texts, version.incrementAndGet())
        }
    }

    override suspend fun onIntent(route: SettingsRoute, intent: RowIntent, ctx: PageContext): Boolean {
        if (!handles(route)) return false
        val cmd = ButtonMappingRows.command(intent, (route as? SettingsRoute.ActionBindings)?.action) ?: return false
        when (cmd) {
            is ButtonMappingRows.Command.Capture -> withContext(main) { link()?.startCapture(cmd.action, cmd.press) }
            ButtonMappingRows.Command.TestKey -> withContext(main) { link()?.startCapture(BoxAction.SELECT, Press.SHORT, test = true) }
            else -> host.run(cmd)
        }
        return true
    }

    /** Save on the capture overlay (the machine calls it on main). */
    fun save(action: BoxAction, key: app.thebox.core.input.CapturedKey, press: Press) {
        bindings.bind(action, key, press, System.currentTimeMillis())
    }
}

/** WP-E's About, Diagnostics, connection test, Sync status and Licences pages (`DiagPages`, one flow per route). */
class DiagPagesSource(
    private val diag: DiagPages,
    private val link: () -> SettingsShellLink?,
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
    private val work: CoroutineDispatcher,
) : SettingsPageSource {
    private class Live(val latest: MutableStateFlow<PageModel?>, var job: Job? = null)
    private val live = ConcurrentHashMap<SettingsRoute, Live>()
    private val _changes = signal()
    private val _notices = noticeFlow()
    override val changes: Flow<Unit> get() = _changes
    override val notices: Flow<String> get() = _notices

    override fun handles(route: SettingsRoute): Boolean = diag.handles(route)

    override suspend fun page(route: SettingsRoute, ctx: PageContext): PageModel {
        val l = live.computeIfAbsent(route) { r ->
            Live(MutableStateFlow(null)).also { slot ->
                slot.job = scope.launch(work) {
                    try {
                        diag.page(r).collect { p ->
                            slot.latest.value = p
                            _changes.tryEmit(Unit)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        BoxLog.e(TAG, "diag page failed: ${r::class.simpleName}", e)
                    }
                }
            }
        }
        return l.latest.filterNotNull().first()
    }

    override suspend fun onIntent(route: SettingsRoute, intent: RowIntent, ctx: PageContext): Boolean {
        if (!diag.handles(route)) return false
        return diag.onIntent(intent, scope) { out ->
            when (out) {
                is DiagOutcome.Notice -> _notices.tryEmit(out.text)
                // U-S6 (code review 2026-10-16): a gated target asks for the PIN (`pushGated`); U-L2: an export that
                // finishes after the user moved on does not push the phone page onto whatever is open.
                is DiagOutcome.Open -> scope.launch(main) { link()?.pushFrom(route, out.route, gated = out.needsPin) }
            }
        }
    }

    override fun closed(route: SettingsRoute) {
        live.remove(route)?.job?.cancel()
    }

    private companion object {
        const val TAG = "DiagPagesSource"
    }
}

/**
 * (M5 integration) The phone page's setup submissions (§3.5.3, I-M5-9): while `PhoneSetup(SETUP)` is open, a login, a
 * link or an uploaded M3U file the phone sent is saved like the TV forms would save it, and the shell moves on to the
 * import progress. WP-D's server spools uploads in `cacheDir/phone/` and deletes the spool when it stops, so an uploaded
 * M3U file is moved to `filesDir/playlists/` first (WP-D §1.7a item 5). Failures go back to the phone page as text.
 *
 * Code review 2026-10-16: an Xtream login (or a link that normalises to one) runs Connect first (U-S4): a rejected login
 * creates no playlist; the phone gets the reason and the next step (U-S5) with `done = false`, and the TV opens the
 * Xtream form filled from the phone and runs Connect there, so its result card offers the choices. `done = true` only
 * after the save succeeded. A save in flight finishes and is reported even when the user leaves the phone page (U-S15).
 */
class PhoneSetupBridge(
    private val context: Context,
    private val phone: app.thebox.core.setup.lan.PhoneLink,
    private val setup: app.thebox.core.setup.SourceSetup,
    private val normalizer: app.thebox.core.setup.SourceInputNormalizer,
    strings: app.thebox.core.setup.SetupStrings,
    private val link: () -> SettingsShellLink?,
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    /** U-S4: WP-B's drafts (the Xtream form is filled from the phone). Null in tests that do not open the form. */
    private val drafts: app.thebox.core.setup.SetupDraftStore? = null,
    /** U-S4: runs the Xtream form's Connect on [SettingsRoute] (the setup page source's intent path). */
    private val connect: (suspend (SettingsRoute) -> Unit)? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** U-S4: how long the phone page stays up after a failure so its 1 s poll reads the reason before the TV moves on. */
    private val failureGraceMs: Long = PHONE_READ_GRACE_MS,
) {
    private val mapper = app.thebox.core.setup.SetupResultMapper(strings)
    private var job: Job? = null
    private var firstRun = false
    private var route: SettingsRoute? = null

    /** The open route changed (main thread): collect submissions while the setup phone page is open. */
    fun onRoute(route: SettingsRoute) {
        val setupPage = route is SettingsRoute.PhoneSetup && route.purpose == app.thebox.core.settings.PhonePurpose.SETUP
        // U-S15: leaving only stops *collecting*; a save already running finishes in its own NonCancellable block.
        if (!setupPage) { job?.cancel(); job = null; this.route = null; return }
        firstRun = (route as SettingsRoute.PhoneSetup).firstRun
        this.route = route
        if (job?.isActive == true) return
        job = scope.launch(main) { phone.submissions.collect { onSubmission(it) } }
    }

    private fun text(card: app.thebox.core.setup.SetupCard): String =
        // U-S5: the reason and its next step, as the TV's result card shows them.
        card.lines.firstOrNull()?.let { "${card.headline} $it" } ?: card.headline

    private fun xtreamContext(server: String): app.thebox.core.setup.SetupResultMapper.Context {
        val u = runCatching { java.net.URI(server) }.getOrNull()
        return app.thebox.core.setup.SetupResultMapper.Context(app.thebox.core.settings.SourceKind.XTREAM, u?.host, u?.port?.takeIf { it > 0 })
    }

    private suspend fun onSubmission(s: app.thebox.core.setup.lan.PhoneSubmission) {
        val origin = route ?: return
        when (s) {
            is app.thebox.core.setup.lan.PhoneSubmission.Xtream -> xtream(s.form, origin)
            is app.thebox.core.setup.lan.PhoneSubmission.M3u -> save(origin) { setup.saveM3u(s.form) }
            is app.thebox.core.setup.lan.PhoneSubmission.Link -> when (val n = normalizer.m3uUrl(s.url)) {
                is app.thebox.core.setup.NormalizedInput.M3u -> {
                    val x = n.looksLikeXtream
                    val user = x?.username
                    val pass = x?.password
                    when {
                        // U-S4: a link that carries an Xtream login (get.php, a stream URL) is that login: Connect first.
                        x != null && !user.isNullOrEmpty() && pass != null ->
                            xtream(app.thebox.core.setup.XtreamForm(s.name, x.server, user, pass, x.output ?: "AUTO"), origin)
                        // U-S5: an Xtream-shaped link without its login gets a text that says so and what to do.
                        XTREAM_PHP.containsMatchIn(n.url) ->
                            phone.reportStatus(context.getString(app.thebox.ui.R.string.phone_status_link_no_login), done = false)
                        else -> save(origin) { setup.saveM3u(app.thebox.core.setup.M3uForm(s.name, n.url, null, emptyList())) }
                    }
                }
                // `m3uUrl` answers M3u or Invalid; a bare server is not a playlist link.
                is app.thebox.core.setup.NormalizedInput.Xtream ->
                    phone.reportStatus(context.getString(app.thebox.ui.R.string.phone_status_link_no_login), done = false)
                is app.thebox.core.setup.NormalizedInput.Invalid -> phone.reportStatus(text(mapper.input(n.reason)), done = false)
            }
            is app.thebox.core.setup.lan.PhoneSubmission.M3uFileUploaded -> {
                val kept = withContext(io) { keep(java.io.File(s.path)) }
                if (kept == null) {
                    // U-S5: a step for the spool failure.
                    phone.reportStatus(context.getString(app.thebox.ui.R.string.phone_status_spool_failed), done = false)
                    return
                }
                val name = s.name?.takeIf { it.isNotBlank() }
                // U-S15: the kept copy is deleted when the save fails (nothing else would ever read it).
                save(origin, onFail = { withContext(io) { kept.delete() } }) {
                    setup.saveM3u(app.thebox.core.setup.M3uForm(name?.substringBeforeLast('.'), null, "file://" + kept.absolutePath, emptyList(), fileName = name))
                }
            }
            // Backups and passphrases are WP-D's (`PhoneBackupFlow`).
            else -> return
        }
    }

    /** U-S4: Connect (the login test) before anything is saved, as the TV form does (§3.4.4). */
    private suspend fun xtream(form: app.thebox.core.setup.XtreamForm, origin: SettingsRoute) {
        phone.reportStatus(context.getString(app.thebox.ui.R.string.backup_phone_received), done = false)
        val card = withContext(kotlinx.coroutines.NonCancellable) {
            when (val t = setup.testXtream(form)) {
                is app.thebox.core.common.Outcome.Ok -> mapper.login(t.value, nowMs())
                is app.thebox.core.common.Outcome.Err -> mapper.error(t.error, xtreamContext(form.server))
            }
        }
        if (card.primary == app.thebox.core.setup.SetupAction.CONTINUE) {
            save(origin) { setup.saveXtream(form) }
            return
        }
        // Rejected, expired, disabled, unreachable: no playlist. The phone gets the reason and the step; the TV shows the
        // form filled from the phone with Connect's result card (Edit / Phone setup / Use secure DNS).
        phone.reportStatus(text(card), done = false)
        drafts?.let { d ->
            d.xtream = app.thebox.core.setup.XtreamDraft(
                name = form.name.orEmpty(), server = form.server, username = form.username, password = form.password,
                output = form.output, userAgent = form.userAgent.orEmpty(), backups = form.backups.joinToString(", "),
            )
        }
        val target = SettingsRoute.AddSource(app.thebox.core.settings.SourceKind.XTREAM, firstRun)
        scope.launch(main) {
            delay(failureGraceMs)
            val l = link() ?: return@launch
            if (!l.shows(origin)) return@launch // U-L2: the user has moved on
            l.replace(target)
            (l as? app.thebox.core.settings.SettingsMachine)?.dispatch(app.thebox.core.settings.SettingsEvent.Notice(context.getString(app.thebox.ui.R.string.backup_phone_received_tv)))
            connect?.invoke(target)
        }
    }

    /**
     * U-S15: [op] and its report run to the end even when the collector is cancelled (BACK during the save); the shell
     * moves to the import progress only while the phone page is still the open route.
     */
    private suspend fun save(origin: SettingsRoute, onFail: suspend () -> Unit = {}, op: suspend () -> app.thebox.core.common.Outcome<Long>) {
        val r = withContext(kotlinx.coroutines.NonCancellable) {
            val r = op()
            when (r) {
                is app.thebox.core.common.Outcome.Ok -> phone.reportStatus(context.getString(app.thebox.ui.R.string.backup_phone_received), done = true)
                is app.thebox.core.common.Outcome.Err -> { onFail(); phone.reportStatus(text(mapper.error(r.error)), done = false) }
            }
            r
        }
        if (r !is app.thebox.core.common.Outcome.Ok) return
        val l = link() ?: return
        if (!l.shows(origin)) return
        l.replace(SettingsRoute.ImportProgress(r.value, firstRun))
        (l as? app.thebox.core.settings.SettingsMachine)?.dispatch(app.thebox.core.settings.SettingsEvent.Notice(context.getString(app.thebox.ui.R.string.backup_phone_received_tv)))
    }

    /** Moves the spooled upload out of `cacheDir/phone/` (copy when a rename across directories fails). */
    private fun keep(spooled: java.io.File): java.io.File? = try {
        val dir = java.io.File(context.filesDir, "playlists").apply { mkdirs() }
        val dest = java.io.File(dir, "phone-${java.util.UUID.randomUUID()}.m3u")
        if (!spooled.renameTo(dest)) { spooled.copyTo(dest, overwrite = true); spooled.delete() }
        dest
    } catch (e: Exception) {
        BoxLog.w("PhoneSetupBridge", "upload not kept: ${e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val PHONE_READ_GRACE_MS = 1_500L
        /** An Xtream panel's playlist or API path (`get.php`, `player_api.php`). */
        val XTREAM_PHP = Regex("""/(get|player_api)\.php(\?|$)""", RegexOption.IGNORE_CASE)
    }
}
