package app.thebox.core.guide.state

import app.thebox.core.guide.ExitConfirmation
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.HeaderChip
import app.thebox.core.guide.MenuItem
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.PanelButton
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.guide.state.GuideKeyTableTest.Companion.overlayName
import app.thebox.core.input.BoxAction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §3.4.8 five-key rule (reviewed 2026-10-14, S11): a breadth-first search over the state graph with
 * UP/DOWN/LEFT/RIGHT/SELECT/BACK only (no CONTEXT_MENU) from the initial state, once per [ExitConfirmation], must reach
 * every [HeaderChip], overlay and [ScreenMode]. Stated exclusions: `Overlay.Number` (an accelerator),
 * `Overlay.Exit(dialog = true)` outside ASK, and the Groups group menu (long OK; M5's group manager).
 *
 * (owner ruling 2026-11-01, docs/decisions/2026-11-01-guide-panel-and-mini-player.md) The panel has no buttons, so the
 * "More…" button that made the channel menu five-key reachable is gone: the Menu overlay and every [MenuItem] are now
 * reached by CONTEXT_MENU (long OK / Menu) and are checked by a second search that adds it. [PanelButton] is retired
 * (never offered) and the PANEL zone is never entered, by either search.
 * States are replayed from scratch (the machine is not cloneable); a coarse signature dedupes them.
 */
class FiveKeyReachabilityTest {
    private val keys = listOf(BoxAction.UP, BoxAction.DOWN, BoxAction.LEFT, BoxAction.RIGHT, BoxAction.SELECT, BoxAction.BACK)
    private val keysWithMenu = keys + BoxAction.CONTEXT_MENU

    private val channels = List(3) { i -> GuideFixtures.channel(i, archiveDays = if (i == 1) 0 else 7, number = 101 + i) }
    private val from = GuideFixtures.floor30(GuideWorld.NOW_MIN) - 1440
    private val to = GuideFixtures.floor30(GuideWorld.NOW_MIN) + 1440
    private val data = GuideFixtures.windowData(GuideFixtures.syntheticRows(3, from, to, durationsMin = intArrayOf(60, 90, 120)), fromMin = from, toMin = to)

    /**
     * (M5, WP-C) The session is unlocked (a PIN is set): the channel menu then offers both Lock and Unlock (§3.9.1),
     * so every MenuItem is reachable in one world.
     */
    private fun world(exit: ExitConfirmation) =
        GuideWorld(channels = channels, data = data, settings = GuideSettings(exitConfirmation = exit), dataWindow = app.thebox.core.guide.GuideDataWindow(1, 1))
            .also { it.send(app.thebox.core.guide.GuideEvent.Parental(app.thebox.core.parental.ParentalState(pinSet = true, unlocked = true, policy = app.thebox.core.parental.ParentalPolicy()))) }

    private class Reached {
        val menu = HashSet<MenuItem>(); val buttons = HashSet<PanelButton>(); val chips = HashSet<HeaderChip>()
        val overlays = HashSet<String>(); val modes = HashSet<ScreenMode>(); var exited = false; var panelZone = false
        /** (code review 2026-10-14, test gap 3) What SELECT on each reached item did: item → effect names seen. */
        val selected = HashMap<String, HashSet<String>>()
    }

    /** The item SELECT acts on in this state, or null (grid cells, chips and tabs are covered by keys.csv). */
    private fun selectable(w: GuideWorld): String? {
        val ui = w.ui
        if (ui.reminder != null) return null
        return when (val o = ui.overlay) {
            is Overlay.Menu -> "menu:" + o.items[o.focused]
            is Overlay.Groups -> o.entries.getOrNull(o.focused - o.shortcuts.size)?.let { "groups:" + it.section } ?: "shortcut:" + o.shortcuts[o.focused]
            is Overlay.Sheet -> "sheet:" + o.focused
            is Overlay.Exit -> if (o.dialog) "exit:" + o.focused else null
            null -> null
            else -> null
        }
    }

    private fun observe(w: GuideWorld, r: Reached) {
        val ui = w.ui
        r.modes += ui.mode
        if (ui.zone == app.thebox.core.guide.Zone.PANEL) r.panelZone = true
        ui.panel?.buttons?.let { r.buttons += it }
        r.chips += ui.headerChips
        (ui.overlay as? Overlay.Menu)?.let { r.menu += it.items }
        r.overlays += overlayName(w)
        if (w.exited) r.exited = true
    }

    private fun signature(w: GuideWorld): String {
        val ui = w.ui
        val f = w.machine.frame.focus
        return listOf(
            ui.mode, ui.zone, overlayName(w), (ui.overlay as? Overlay.Menu)?.let { "${it.items}${it.focused}" },
            (ui.overlay as? Overlay.Groups)?.focused, (ui.overlay as? Overlay.Days)?.focused, (ui.overlay as? Overlay.Sheet)?.focused,
            (ui.overlay as? Overlay.Exit)?.focused, ui.tabFocused, ui.headerFocused, ui.panelFocused, ui.headerChips.size,
            f?.row, f?.cellStart, f?.windowStartMin, w.favourites.size, w.reminders.size, ui.tuned.phase, ui.tuned.isArchive,
            w.exited, ui.sourceLabel,
        ).joinToString("|")
    }

    private companion object {
        /** Item → effects SELECT may produce (alternatives depend on the cell; at least one must be seen). */
        val EXPECTED: Map<String, Set<String>> = buildMap {
            put("menu:" + MenuItem.WATCH, setOf("playArchive"))
            put("menu:" + MenuItem.WATCH_FROM_START, setOf("playArchive"))
            put("menu:" + MenuItem.REMIND, setOf("setReminder"))
            put("menu:" + MenuItem.CANCEL_REMINDER, setOf("cancelReminder"))
            put("menu:" + MenuItem.ADD_FAVOURITE, setOf("setFavourite"))
            put("menu:" + MenuItem.REMOVE_FAVOURITE, setOf("setFavourite"))
            put("menu:" + MenuItem.HIDE_CHANNEL, setOf("hideChannel"))
            // M5 (§3.9.1, §3.2.5)
            put("menu:" + MenuItem.LOCK_CHANNEL, setOf("setChannelLocked"))
            put("menu:" + MenuItem.UNLOCK_CHANNEL, setOf("setChannelLocked"))
            // FIX_GUIDE_DATA and CHANNEL_OPTIONS sit past the search depth; PinRoutingTest checks what SELECT does on them.
            put("sheet:0", setOf("setReminder", "cancelReminder"))
            put("sheet:1", setOf("tune"))
            put("groups:FAVOURITES", setOf("loadLineup:Favourites"))
            put("groups:ALL", setOf("loadLineup:All"))
            put("groups:GROUP", setOf("loadLineup:Group"))
            put("shortcut:SEARCH", setOf("openSearch"))
            put("shortcut:SETTINGS", setOf("openSettings"))
        }
    }

    /** One breadth-first search with [with] from the initial state; returns what it reached and the states seen. */
    private fun search(exit: ExitConfirmation, with: List<BoxAction>, maxDepth: Int): Pair<Reached, Int> {
        val r = Reached()
        val seen = HashSet<String>()
        var frontier = listOf(emptyList<BoxAction>())
        world(exit).also { observe(it, r); seen += signature(it) }
        for (depth in 1..maxDepth) {
            val next = ArrayList<List<BoxAction>>()
            for (seq in frontier) for (k in with) {
                val w = world(exit)
                for (a in seq) w.press(a)
                if (w.exited) continue
                val item = if (k == BoxAction.SELECT) selectable(w) else null
                val mark = w.calls.calls.size
                w.press(k)
                if (item != null) r.selected.getOrPut(item) { HashSet() } += w.calls.calls.drop(mark).map { c ->
                    if (c.name == "loadLineup") "loadLineup:" + c.args[0]!!.javaClass.simpleName else c.name
                }
                observe(w, r)
                if (w.exited) continue
                if (seen.add(signature(w))) next += seq + k
            }
            frontier = next
            if (frontier.isEmpty() || seen.size > 60_000) break
        }
        return r to seen.size
    }

    @ParameterizedTest
    @EnumSource(ExitConfirmation::class)
    fun everyFunctionReachableWithFiveKeys(exit: ExitConfirmation) {
        val (r, states) = search(exit, keys, maxDepth = 10)
        val missing = buildList {
            addAll(HeaderChip.entries.filter { it !in r.chips })
            // (Score bug commit 0) SPORTS becomes reachable with WP-4a's tab, which drops this exception.
            addAll(ScreenMode.entries.filter { it !in r.modes && it != ScreenMode.SPORTS })
            for (o in listOf("Groups", "Days", "Sheet")) if (o !in r.overlays) add(o)
            when (exit) {
                ExitConfirmation.BACK_TWICE -> if ("ExitHint" !in r.overlays) add("ExitHint")
                ExitConfirmation.ASK -> if ("ExitDialog" !in r.overlays) add("ExitDialog")
                ExitConfirmation.NONE -> Unit
            }
            if (!r.exited) add("exit")
        }
        assertTrue(missing.isEmpty(), "$exit: not reachable with five keys: $missing (states $states)")
        assertTrue(r.buttons.isEmpty(), "$exit: the panel offered buttons ${r.buttons} (owner ruling 2026-11-01)")
        assertFalse(r.panelZone, "$exit: focus reached the PANEL zone (no panel buttons, owner ruling 2026-11-01)")
        // Reaching an item is not enough: SELECT on it must do what it says (any one of the alternatives).
        val wrong = EXPECTED.filterKeys { !it.startsWith("menu:") }.filter { (item, effects) -> r.selected[item]?.any { it in effects } != true }
            .map { (item, effects) -> "$item → expected one of $effects, saw ${r.selected[item]}" }
        assertTrue(wrong.isEmpty(), "$exit: SELECT without its effect: $wrong")
        if (exit != ExitConfirmation.ASK) assertTrue("ExitDialog" !in r.overlays, "the dialog only exists in ASK")
    }

    /**
     * (owner ruling 2026-11-01) The channel menu (Watch, Watch from start, Remind / Cancel reminder, favourites, Hide,
     * Lock / Unlock) with the five keys plus CONTEXT_MENU (long OK / Menu), now that the panel's More… button is gone.
     */
    @org.junit.jupiter.api.Test
    fun everyMenuItemReachableWithLongOk() {
        val (r, states) = search(ExitConfirmation.BACK_TWICE, keysWithMenu, maxDepth = MENU_DEPTH)
        val missing = buildList {
            addAll(MenuItem.entries.filter { it !in r.menu })
            if ("Menu" !in r.overlays) add("Menu")
        }
        assertTrue(missing.isEmpty(), "not reachable with five keys + long OK: $missing (states $states)")
        assertTrue(r.buttons.isEmpty(), "the panel offered buttons ${r.buttons}")
        assertFalse(r.panelZone, "focus reached the PANEL zone")
        val wrong = EXPECTED.filterKeys { it.startsWith("menu:") }.filter { (item, effects) -> r.selected[item]?.any { it in effects } != true }
            .map { (item, effects) -> "$item → expected one of $effects, saw ${r.selected[item]}" }
        assertTrue(wrong.isEmpty(), "SELECT without its effect: $wrong")
    }

}

private const val MENU_DEPTH = 7
