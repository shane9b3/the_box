package app.thebox.core.setup.pages

import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.SettingsRoute

/*
 * The seam between WP-B's page builders and WP-A's settings shell (docs/design/m5-setup-settings.md §2.1, §2.3, §3.4,
 * §3.6). §2 defines the machine and its effects but not how a WP's pages plug in, so WP-B states it here, in its own
 * package: `SettingsEffects.loadPage(route)` on a route [SetupPageProvider.handles] → [SetupPageProvider.open];
 * `SettingsEffects.onIntent(intent)` on such a route → [SetupPageProvider.onIntent]; the host turns every published
 * [PageModel] into `SettingsEvent.Page` and every [PageAction] into the machine's navigation. Everything here is
 * main-safe and non-blocking; the provider does its IO on its own scope.
 */

/** What a page asks the shell to do (WP-A maps these onto the machine's route stack and effects). */
sealed interface PageAction {
    /** Push [route] on top of the current page. */
    data class Push(val route: SettingsRoute) : PageAction
    /** Replace the current page with [route] (AddSource → ImportProgress; Back from the new page skips the old one). */
    data class Replace(val route: SettingsRoute) : PageAction
    /** Pop the current page (as BACK). */
    data object Pop : PageAction
    /** Pop until [route] is on top (or push it when it is not in the stack). */
    data class PopTo(val route: SettingsRoute) : PageAction
    /** Leave Settings to the guide ("Start watching", "Later"). */
    data object LeaveToGuide : PageAction
    /** Transient notice line ("Filled from the link", "Still reading channels…"). */
    data class Notice(val text: String) : PageAction
    /** Show the confirm card for [intent] (Back on the import page: "Stop the import?"). */
    data class Confirm(val spec: app.thebox.core.settings.ConfirmSpec, val intent: RowIntent) : PageAction
    /** Open the system document picker for an M3U file; the host answers with [SetupPageProvider.onFilePicked]. */
    data object PickM3uFile : PageAction
    /**
     * Start WP-C's PIN creation (§3.4.6, §3.9.3). Commit 0 has no PinPurpose for "create": until the lead adds one,
     * WP-A maps this onto [SettingsRoute.Section] PARENTAL, whose first row creates the PIN.
     */
    data object CreatePin : PageAction
    /** Re-open the current route's page (the provider republished nothing but the cursor should stay). */
    data object Reload : PageAction
}

/** Where a provider publishes pages and asks for navigation. Implemented by WP-A's `SettingsEffectsImpl`. */
interface PageHost {
    fun publish(page: PageModel)
    fun act(action: PageAction)
    /** Rows [from, from + rows.size) of the paged list of [route]'s page [version] (`SettingsEvent.Items`). */
    fun items(route: SettingsRoute, version: Long, from: Int, rows: List<app.thebox.core.settings.SettingsRow>) = Unit
}

/** A WP's pages, keyed by route. */
interface SetupPageProvider {
    fun handles(route: SettingsRoute): Boolean
    /** Builds [route]'s page and publishes it (again whenever its data changes) until [close]. */
    fun open(route: SettingsRoute, host: PageHost)
    /** The page left the screen (popped, replaced or Settings closed): stop observing. Drafts stay. */
    fun close(route: SettingsRoute)
    fun onIntent(route: SettingsRoute, intent: RowIntent, host: PageHost)
    /** BACK on [route] before the shell pops it. True = consumed (the import page asks first, §3.4.5). */
    fun onBack(route: SettingsRoute, host: PageHost): Boolean = false
    /** `SettingsEffects.loadItems`: the host wants rows [from, to) of [route]'s paged list. */
    fun loadItems(route: SettingsRoute, from: Int, to: Int, host: PageHost) = Unit
    /** The document picker returned [uri] (or null when cancelled) for [route]. */
    fun onFilePicked(route: SettingsRoute, uri: String?, displayName: String?, host: PageHost) = Unit
}
