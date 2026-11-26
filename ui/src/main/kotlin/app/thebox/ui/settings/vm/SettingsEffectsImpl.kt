package app.thebox.ui.settings.vm

import app.thebox.core.catchup.archive.LiveSettingsApplier
import app.thebox.core.catchup.archive.PanelApplyResult
import app.thebox.core.common.BoxLog
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.SettingsEffects
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsMachine
import app.thebox.core.settings.SettingsPresentation
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.PageContext
import app.thebox.core.settings.state.SettingsPageSource
import app.thebox.core.settings.state.SettingsPanelRules
import app.thebox.core.settings.state.SettingsShellEvent
import app.thebox.core.settings.state.SettingsShellLink
import app.thebox.core.settings.state.SettingsText
import app.thebox.core.settings.state.SettingsTexts
import app.thebox.core.settings.tree.SettingsTree
import app.thebox.ui.input.KeyRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * `:ui`'s [SettingsEffects] (docs/design/m5-setup-settings.md §2.3, §5 item 2): page builds and intents run on
 * [work] (`Dispatchers.Default`); their results reach the machine on [main] as [SettingsEvent]s. The page of a route
 * comes from the first [SettingsPageSource] that handles it (each WP binds its own into the Hilt set); a route nobody
 * builds yet shows a placeholder page with the reason. [onClosed] (S9) runs [LiveSettingsApplier] once when a REPREPARE
 * row changed during the visit and flushes the store.
 *
 * [machine] is set right after construction (the machine needs the effects first).
 *
 * In-player panel, stage A (docs/design/m6-player-settings-panel.md §4.2): pages are built with `PageContext.panel` while
 * the machine's presentation is PANEL; a REPREPARE row changed in the panel is applied [SettingsPanelRules.APPLY_DEBOUNCE_MS]
 * after the change through [LiveSettingsApplier.applyFromPanel], with an honest notice (never "Applied"); [onClosed] does
 * not apply again for a change the panel already applied ([skipNextReapply]).
 */
class SettingsEffectsImpl(
    private val sources: List<SettingsPageSource>,
    private val store: SettingsStore,
    private val applier: LiveSettingsApplier,
    private val texts: SettingsTexts,
    private val keyRouter: KeyRouter?,
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
    private val work: CoroutineDispatcher,
    private val nowMs: () -> Long,
) : SettingsEffects {
    lateinit var machine: SettingsMachine
    private val link: SettingsShellLink? get() = machine as? SettingsShellLink
    private val builds = HashMap<SettingsRoute, Job>()
    private val placeholderVersion = AtomicLong(0)

    private fun ctx() = PageContext(advanced = true, nowMs = nowMs(), panel = panelShown())

    private fun panelShown(): Boolean = machine.ui.value.presentation == SettingsPresentation.PANEL

    /** (§4.2) The debounced panel apply waiting to run, if any (main thread). */
    private var pendingApply: Job? = null

    /**
     * (§4.2 "Close", R9) True while every REPREPARE change of this visit has been applied by the panel: [onClosed] then
     * does not re-prepare a second time. Set when a panel apply ran (any result but CARD_SHOWN); cleared by a new
     * REPREPARE change (in either presentation) until its apply runs, and at the end of each visit.
     */
    private var skipNextReapply = false

    /** Counts visits ([onClosed]): an intent still writing when Settings closes schedules nothing into the next one. */
    private var visit = 0L

    private fun sourceFor(route: SettingsRoute): SettingsPageSource? = sources.firstOrNull { it.handles(route) }

    /** U-S1 (code review 2026-10-16): routes whose data changed while their build was in flight (main thread). */
    private val staleWhileBuilding = HashSet<SettingsRoute>()

    override fun loadPage(route: SettingsRoute) {
        builds[route]?.cancel()
        staleWhileBuilding.remove(route)
        val ctx = ctx()
        builds[route] = scope.launch(main) {
            val page = withContext(work) { build(route, ctx) }
            machine.dispatch(SettingsEvent.Page(page))
            // U-S1: a change that arrived before this page was shown had nothing to rebuild; build it once more now.
            if (staleWhileBuilding.remove(route) && page.placeholder != null) loadPage(route)
        }
    }

    private suspend fun build(route: SettingsRoute, ctx: PageContext): PageModel {
        val source = sourceFor(route)
        return try {
            source?.page(route, ctx) ?: placeholder(route)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.e(TAG, "page build failed: ${route::class.simpleName}", e)
            placeholder(route)
        }
    }

    private fun placeholder(route: SettingsRoute): PageModel {
        val section = (route as? SettingsRoute.Section)?.id ?: SettingsTree.sectionOf(route)
        return PageModel(
            route = route,
            title = section?.let { texts.section(it) } ?: "",
            rows = emptyList(),
            version = placeholderVersion.incrementAndGet(),
            placeholder = texts[SettingsText.PAGE_NOT_AVAILABLE],
        )
    }

    override fun loadItems(route: SettingsRoute, from: Int, to: Int) {
        val source = sourceFor(route) ?: return
        val version = machine.ui.value.page?.takeIf { it.route == route }?.version ?: return
        val ctx = ctx()
        scope.launch(main) {
            val rows = try {
                withContext(work) { source.items(route, from, to, ctx) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.e(TAG, "items failed", e)
                emptyList()
            }
            machine.dispatch(SettingsEvent.Items(route, version, from, rows))
        }
    }

    /** U-L1 (code review 2026-10-16): intents run one at a time, in the order the user gave them. */
    private val intents = Mutex()

    override fun onIntent(intent: RowIntent) {
        val route = machine.ui.value.page?.route ?: machine.ui.value.route
        val ctx = ctx()
        // (§4.2) Read on the main thread when the user made the change: the presentation it was made in, and its timing.
        val reprepare = effectOf(intent.row) == EffectTiming.REPREPARE
        // A REPREPARE change not applied yet: leaving Settings must apply it (unless the panel applies it first).
        if (reprepare) skipNextReapply = false
        val inVisit = visit
        scope.launch(main) {
            // U-L1: OK twice on a toggle of a suspending source must store the second value, not whichever ends last.
            intents.withLock { runIntent(route, intent, ctx) }
            rebuildDisplayed()
            // (§4.2) After the write: the re-prepare reads the stored value.
            if (reprepare && ctx.panel && inVisit == visit) scheduleApply()
        }
    }

    /**
     * (§4.2, S4) The registry timing of [id]: its own spec, else the nearest parent row's (`playlist.headers.h.2` →
     * `playlist.headers`), else the timing the displayed row carries.
     */
    private fun effectOf(id: RowId): EffectTiming? {
        var key = id.key
        while (true) {
            SettingsTree.spec(RowId(key))?.let { return it.effect }
            val dot = key.lastIndexOf('.')
            if (dot <= 0) break
            key = key.substring(0, dot)
        }
        val page = machine.ui.value.page ?: return null
        return page.rows.firstOrNull { it.id == id }?.effect
            ?: page.list?.loaded?.values?.firstNotNullOfOrNull { l -> l.firstOrNull { it.id == id } }?.effect
    }

    /** (§4.2) One panel apply [SettingsPanelRules.APPLY_DEBOUNCE_MS] after the last REPREPARE change (main thread). */
    private fun scheduleApply() {
        pendingApply?.cancel()
        pendingApply = scope.launch(main) {
            delay(SettingsPanelRules.APPLY_DEBOUNCE_MS)
            pendingApply = null
            // Expanded to full screen meanwhile: the video is covered, so leaving Settings applies it (skip stays clear).
            if (panelShown()) applyFromPanel(notify = true)
        }
    }

    /** Runs [LiveSettingsApplier.applyFromPanel] and shows its notice (§4.2); main thread. */
    private fun applyFromPanel(notify: Boolean) {
        val result = try {
            applier.applyFromPanel()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.e(TAG, "re-prepare from the panel failed", e)
            return
        }
        BoxLog.i(TAG) { "reapply from panel: $result" }
        // CARD_SHOWN applied nothing: leaving Settings still calls apply(), as today (Retry is the user's).
        skipNextReapply = result != PanelApplyResult.CARD_SHOWN
        if (!notify) return
        val notice = when (result) {
            PanelApplyResult.REPREPARING -> SettingsText.PANEL_NOTICE_REPREPARING
            PanelApplyResult.WAITS_FOR_RESUME -> SettingsText.PANEL_NOTICE_WAITS_FOR_RESUME
            PanelApplyResult.CARD_SHOWN -> SettingsText.PANEL_NOTICE_CARD_SHOWN
            PanelApplyResult.NOTHING_PLAYING -> null
        }
        if (notice != null) machine.dispatch(SettingsEvent.Notice(texts[notice]))
    }

    private suspend fun runIntent(route: SettingsRoute, intent: RowIntent, ctx: PageContext) {
        withContext(work) {
            try {
                val owner = sourceFor(route)
                val handled = owner?.onIntent(route, intent, ctx) == true
                // The advanced toggle (sections pane) and rows a source shows on another's route.
                if (!handled) for (s in sources) if (s !== owner && s.onIntent(route, intent, ctx)) break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.e(TAG, "intent failed: ${intent.row.key}", e)
            }
        }
    }

    /** Rebuilds the open page (after an intent, or when its source's data changed). */
    fun rebuildDisplayed() {
        val route = machine.ui.value.page?.route
        // U-S1: builds still in flight (the page not shown yet) are marked, so a placeholder they bring is rebuilt.
        for ((r, job) in builds) if (r != route && job.isActive) staleWhileBuilding += r
        if (route != null) loadPage(route)
    }

    override fun onClosed(changedTimings: Set<EffectTiming>) {
        val u = machine.ui.value
        // U-S3 (code review 2026-10-16): a page still loading when Settings closes (on Root the displayed section is in
        // none of stack/route/page until it arrives) is cancelled and its source closed, so no provider page keeps
        // observing after the visit and no page lands after onLeave.
        val loading = builds.keys.toList()
        for (job in builds.values) job.cancel()
        builds.clear()
        staleWhileBuilding.clear()
        for (r in (u.stack + u.route + listOfNotNull(u.page?.route) + loading).toSet()) sourceFor(r)?.closed(r)
        // (§4.2 "Close") A debounced panel apply still waiting runs now, in place of the apply below (no notice: the
        // panel is closing). One that is waiting while full screen is shown (expanded) is dropped: the apply below runs.
        pendingApply?.let { job ->
            job.cancel()
            pendingApply = null
            if (u.presentation == SettingsPresentation.PANEL) applyFromPanel(notify = false)
        }
        val skip = skipNextReapply
        skipNextReapply = false
        visit++
        if (EffectTiming.REPREPARE in changedTimings && !skip) {
            try {
                applier.apply()
            } catch (e: Exception) {
                BoxLog.e(TAG, "re-prepare on leaving Settings failed", e)
            }
        }
        scope.launch { store.flush() }
    }

    override fun armCapture(armed: Boolean) {
        val router = keyRouter ?: return
        if (armed) router.capture { key -> machine.dispatch(SettingsEvent.Captured(key)) } else router.capture(null)
    }

    override fun exitApp() {
        link?.post(SettingsShellEvent.Exit)
    }

    override fun announce(text: String) {
        link?.post(SettingsShellEvent.Announce(text))
    }

    private companion object {
        const val TAG = "SettingsEffects"
    }
}
