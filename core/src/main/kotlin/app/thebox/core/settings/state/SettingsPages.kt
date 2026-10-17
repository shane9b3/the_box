package app.thebox.core.settings.state

import app.thebox.core.guide.GuideEvent
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * What every page builder sees (docs/design/m5-setup-settings.md §3.2.3): the simple/advanced switch and the clock.
 * Data a builder needs beyond that it reads from its own injected sources.
 */
data class PageContext(val advanced: Boolean, val nowMs: Long)

/**
 * (WP-A seam, §1.3a: one owner per page) Builds the pages of some routes. `:ui`'s `SettingsEffectsImpl` asks the
 * sources in order (WP-A's own first) and uses the first whose [handles] is true; a route nobody handles shows a
 * placeholder page. Each WP binds its sources into the Hilt set `Set<SettingsPageSource>` (`@IntoSet`) from its own
 * module; WP-A declares the set (`@Multibinds`) in `SettingsUiModule`.
 *
 * Builders run on `Dispatchers.Default` (§5 item 2) and must not block; [onIntent] may suspend for IO (store writes are
 * main-safe and return at once).
 */
interface SettingsPageSource {
    fun handles(route: SettingsRoute): Boolean

    suspend fun page(route: SettingsRoute, ctx: PageContext): PageModel

    /** Rows `[from, to)` of a paged list (PagedList); empty when [route] has none. */
    suspend fun items(route: SettingsRoute, from: Int, to: Int, ctx: PageContext): List<SettingsRow> = emptyList()

    /** Runs [intent] of a row on [route]; true when this source owns the row (the effects then rebuild the page). */
    suspend fun onIntent(route: SettingsRoute, intent: RowIntent, ctx: PageContext): Boolean = false

    /** Emits when pages this source built may be stale (data changed elsewhere); the effects rebuild the open page. */
    val changes: Flow<Unit> get() = emptyFlow()

    /** One-line notices for the page's notice line (an action's result: "Locked…", "Choose an external player…"). */
    val notices: Flow<String> get() = emptyFlow()

    /** BACK on [route] before the shell pops it (main thread). True = consumed (WP-B's import page asks first, §3.4.5). */
    fun onBack(route: SettingsRoute): Boolean = false

    /** [route] left the open path (popped, replaced, Settings closed): stop observing its data. Main thread. */
    fun closed(route: SettingsRoute) = Unit
}

/**
 * The navigation a page source may ask for (WP-B's `PageAction` maps 1:1 onto it; `SettingsShellLink` implements it).
 * Main thread.
 */
interface SettingsPageNavigation {
    fun push(route: SettingsRoute)
    /** Replaces the open page with [route] (BACK from it skips the old page). */
    fun replace(route: SettingsRoute)
    /** As BACK on the page pane (overlays closed first). */
    fun pop()
    /** Pops until [route] is on top, or pushes it when it is not on the path. */
    fun popTo(route: SettingsRoute)
    /** Leaves Settings to the guide ("Start watching", "Later"). */
    fun leaveToGuide()
    /** The confirm card for [intent] (focus on the safe button). */
    fun confirm(spec: app.thebox.core.settings.ConfirmSpec, intent: app.thebox.core.settings.RowIntent)
}

/** One-shot events the settings layer hands to the root composition (toast slot, accessibility, exit). */
sealed interface SettingsShellEvent {
    data class Toast(val text: String) : SettingsShellEvent
    data class Announce(val text: String) : SettingsShellEvent
    data object Exit : SettingsShellEvent
}

/**
 * (WP-A) The link between the settings machine and the shell that owns it (`AppRoot` and the view models). The guide
 * machine composes the settings machine but never tells it who it is, and `AppRoot` is the one place that sees both,
 * so `AppRoot` links the guide's dispatch here; `SettingsNavigatorImpl` uses it to open Settings from FULL.
 * Implemented by [SettingsMachineImpl]. Main thread only.
 */
interface SettingsShellLink : SettingsPageNavigation {
    /** Consulted on BACK at a page (no overlay open) before it pops; true = consumed. The effects route it to the page's source. */
    var backHandler: ((SettingsRoute) -> Boolean)?

    /** `AppRoot` passes the guide machine's `dispatch` (null when its composition leaves). */
    fun attachGuide(dispatch: ((GuideEvent) -> Unit)?)

    /**
     * FULL → Settings on [route] (error-card routes, §3.14), detached by default (S2). (C-S1, code review 2026-10-16) A
     * detached route goes through the guide's ungated `GuideEvent.OpenSettingsDetached(route)`, which enters SETTINGS
     * (BACK returns to FULL) and opens this machine on the route; otherwise the guide's M4 quick-bar path (PIN-gated with
     * "Lock settings") enters SETTINGS and the machine then opens [route].
     */
    fun openFromFull(route: SettingsRoute, detached: Boolean = true)

    /** Pushes [route] on top of the current page (the editor's "Set up from phone", the no-keyboard fallback). */
    override fun push(route: SettingsRoute)

    /**
     * (U-S6, code review 2026-10-16; additive) As [push], but asks for the PIN first when one is set, like a
     * `RowKind.Link(needsPin = true)` row: for a page source's own navigation into a gated route (Sync status → Edit
     * opens `PlaylistSource`, S2). The caller decides when the target is gated (e.g. "Lock settings" on for playlist
     * edits). The default (test doubles) pushes ungated.
     */
    fun pushGated(route: SettingsRoute) = push(route)

    /** Shell events for `AppRoot` (toast, announcement, exit). */
    val shellEvents: SharedFlow<SettingsShellEvent>

    fun post(event: SettingsShellEvent)

    /**
     * (S3) Every `SettingsEvent.EditorBack` the router dispatched while the editor is open. Focus lives in Compose, so
     * the editor overlay decides: focus on the chips or the buttons → back to the field; focus on the field (or
     * nowhere) → Cancel, i.e. it dispatches `SettingsEvent.EditorClosed(row, null, committed = false)`.
     */
    val editorBacks: SharedFlow<Unit>

    /**
     * (M5 integration) Keys on a page drawn by another WP's screen (WP-D's phone page): consulted before the page's own
     * key table (after overlays); true = consumed. Set by the shell (`MainActivity`).
     */
    var keyHandler: ((SettingsRoute, GuideEvent.Act) -> Boolean)?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) = Unit

    /**
     * (M5 integration) Opens WP-C's capture overlay (`CaptureFlow`) for [action]/[press]; [test] = "Test a key". Save
     * calls [captureSaved] (the mapping page source writes `KeyBindingStore.bind`).
     */
    fun startCapture(action: app.thebox.core.input.BoxAction, press: app.thebox.core.input.Press, test: Boolean = false) = Unit

    var captureSaved: ((app.thebox.core.input.BoxAction, app.thebox.core.input.CapturedKey, app.thebox.core.input.Press) -> Unit)?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) = Unit

    /** (M5 integration) Set a PIN ([change] = change it) through the guide's one prompt (`PinPurpose.CreatePin`). */
    fun requestPinSetup(change: Boolean) = Unit
}
