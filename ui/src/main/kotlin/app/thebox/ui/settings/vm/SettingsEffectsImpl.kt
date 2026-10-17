package app.thebox.ui.settings.vm

import app.thebox.core.catchup.archive.LiveSettingsApplier
import app.thebox.core.common.BoxLog
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.SettingsEffects
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsMachine
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.PageContext
import app.thebox.core.settings.state.SettingsPageSource
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

    private fun ctx() = PageContext(advanced = machine.ui.value.advanced, nowMs = nowMs())

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
        scope.launch(main) {
            // U-L1: OK twice on a toggle of a suspending source must store the second value, not whichever ends last.
            intents.withLock { runIntent(route, intent, ctx) }
            rebuildDisplayed()
        }
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
        if (EffectTiming.REPREPARE in changedTimings) {
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
