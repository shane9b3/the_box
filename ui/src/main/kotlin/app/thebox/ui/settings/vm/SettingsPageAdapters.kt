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
        val bridge = PhoneSetupBridge(context, phone, sourceSetup, normalizer, setupStrings, link, scope, main, kotlinx.coroutines.Dispatchers.IO)
        return Built(sources, setup, mapping, bridge)
    }
}

private fun signal() = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
private fun noticeFlow() = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

/** WP-B's push-model pages (`SetupPageProvider`, main thread only) as a [SettingsPageSource]. */
class SetupPagesSource(
    private val provider: SetupPageProvider,
    private val link: () -> SettingsShellLink?,
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
) : SettingsPageSource {
    /** The last page each open route published (null until its first publish). */
    private val open = ConcurrentHashMap<SettingsRoute, MutableStateFlow<PageModel?>>()
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
            open[route] ?: MutableStateFlow<PageModel?>(null).also { s ->
                open[route] = s
                provider.open(route, HostFor(route))
            }
        }
        return slot.filterNotNull().first()
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
        // A page the provider no longer holds (closed while the machine still showed it) is reopened first.
        if (open[route] == null) page(route, ctx)
        withContext(main) { provider.onIntent(route, intent, HostFor(route)) }
        return true
    }

    override fun onBack(route: SettingsRoute): Boolean = provider.onBack(route, HostFor(route))

    override fun closed(route: SettingsRoute) {
        if (open.remove(route) != null) provider.close(route)
    }

    /** The document picker answered for [route] (main thread); [uri] null = cancelled. */
    fun filePicked(route: SettingsRoute, uri: String?, displayName: String?) {
        provider.onFilePicked(route, uri, displayName, HostFor(route))
    }

    private inner class HostFor(private val route: SettingsRoute) : PageHost {
        override fun publish(page: PageModel) {
            val slot = open[page.route] ?: open[route] ?: return
            slot.value = page
            _changes.tryEmit(Unit)
        }

        override fun act(action: PageAction) {
            // Pages act from the shell's main scope; an action from elsewhere is moved there.
            scope.launch(main) { run(action) }
        }

        private fun run(action: PageAction) {
            val l = link()
            when (action) {
                is PageAction.Push -> l?.push(action.route)
                is PageAction.Replace -> l?.replace(action.route)
                PageAction.Pop -> l?.pop()
                is PageAction.PopTo -> l?.popTo(action.route)
                PageAction.LeaveToGuide -> l?.leaveToGuide()
                is PageAction.Notice -> _notices.tryEmit(action.text)
                is PageAction.Confirm -> l?.confirm(action.spec, action.intent)
                PageAction.PickM3uFile -> _pickRequests.tryEmit(route)
                PageAction.CreatePin -> l?.requestPinSetup(change = false)
                PageAction.Reload -> _changes.tryEmit(Unit)
            }
        }
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
        a.open?.let { r -> withContext(main) { link()?.push(r) } }
        // C-S8 (code review 2026-10-16): "Start watching" after a restore, and its PIN offer.
        if (a.leaveToGuide) withContext(main) { link()?.leaveToGuide() }
        if (a.setPin) withContext(main) { link()?.requestPinSetup(change = false) }
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
                is DiagOutcome.Open -> scope.launch(main) { link()?.push(out.route) }
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
) {
    private val mapper = app.thebox.core.setup.SetupResultMapper(strings)
    private var job: Job? = null
    private var firstRun = false

    /** The open route changed (main thread): collect submissions while the setup phone page is open. */
    fun onRoute(route: SettingsRoute) {
        val setupPage = route is SettingsRoute.PhoneSetup && route.purpose == app.thebox.core.settings.PhonePurpose.SETUP
        if (!setupPage) { job?.cancel(); job = null; return }
        firstRun = (route as SettingsRoute.PhoneSetup).firstRun
        if (job?.isActive == true) return
        job = scope.launch(main) { phone.submissions.collect { onSubmission(it) } }
    }

    private suspend fun onSubmission(s: app.thebox.core.setup.lan.PhoneSubmission) {
        val r: app.thebox.core.common.Outcome<Long> = when (s) {
            is app.thebox.core.setup.lan.PhoneSubmission.Xtream -> setup.saveXtream(s.form)
            is app.thebox.core.setup.lan.PhoneSubmission.M3u -> setup.saveM3u(s.form)
            is app.thebox.core.setup.lan.PhoneSubmission.Link -> when (val n = normalizer.m3uUrl(s.url)) {
                is app.thebox.core.setup.NormalizedInput.M3u -> setup.saveM3u(app.thebox.core.setup.M3uForm(s.name, n.url, null, emptyList()))
                is app.thebox.core.setup.NormalizedInput.Xtream -> {
                    val user = n.username
                    val pass = n.password
                    if (user.isNullOrEmpty() || pass.isNullOrEmpty()) {
                        phone.reportStatus(mapper.input(app.thebox.core.setup.InputProblem.NOT_A_URL).headline, done = false)
                        return
                    }
                    setup.saveXtream(app.thebox.core.setup.XtreamForm(s.name, n.server, user, pass, n.output ?: "AUTO"))
                }
                is app.thebox.core.setup.NormalizedInput.Invalid -> {
                    phone.reportStatus(mapper.input(n.reason).headline, done = false)
                    return
                }
            }
            is app.thebox.core.setup.lan.PhoneSubmission.M3uFileUploaded -> {
                val kept = withContext(io) { keep(java.io.File(s.path)) }
                if (kept == null) {
                    phone.reportStatus(mapper.error(app.thebox.core.common.SourceError.Internal("spool")).headline, done = false)
                    return
                }
                val name = s.name?.takeIf { it.isNotBlank() }
                setup.saveM3u(app.thebox.core.setup.M3uForm(name?.substringBeforeLast('.'), null, "file://" + kept.absolutePath, emptyList(), fileName = name))
            }
            // Backups and passphrases are WP-D's (`PhoneBackupFlow`).
            else -> return
        }
        when (r) {
            is app.thebox.core.common.Outcome.Ok -> {
                phone.reportStatus(context.getString(app.thebox.ui.R.string.backup_phone_received), done = true)
                val l = link()
                l?.replace(SettingsRoute.ImportProgress(r.value, firstRun))
                (l as? app.thebox.core.settings.SettingsMachine)?.dispatch(app.thebox.core.settings.SettingsEvent.Notice(context.getString(app.thebox.ui.R.string.backup_phone_received_tv)))
            }
            is app.thebox.core.common.Outcome.Err -> phone.reportStatus(mapper.error(r.error).headline, done = false)
        }
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
}
