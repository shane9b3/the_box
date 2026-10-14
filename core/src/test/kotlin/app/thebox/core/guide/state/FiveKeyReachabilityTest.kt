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
import kotlin.test.assertTrue

/**
 * §3.4.8 five-key rule (reviewed 2026-10-14, S11): a breadth-first search over the state graph with
 * UP/DOWN/LEFT/RIGHT/SELECT/BACK only (no CONTEXT_MENU) from the initial state, once per [ExitConfirmation], must reach
 * every [MenuItem], [PanelButton], [HeaderChip], overlay and [ScreenMode]. Stated exclusions: `Overlay.Number` (an
 * accelerator), `Overlay.Exit(dialog = true)` outside ASK, and the Groups group menu (long OK; M5's group manager).
 * States are replayed from scratch (the machine is not cloneable); a coarse signature dedupes them.
 */
class FiveKeyReachabilityTest {
    private val keys = listOf(BoxAction.UP, BoxAction.DOWN, BoxAction.LEFT, BoxAction.RIGHT, BoxAction.SELECT, BoxAction.BACK)

    private val channels = List(3) { i -> GuideFixtures.channel(i, archiveDays = if (i == 1) 0 else 7, number = 101 + i) }
    private val from = GuideFixtures.floor30(GuideWorld.NOW_MIN) - 1440
    private val to = GuideFixtures.floor30(GuideWorld.NOW_MIN) + 1440
    private val data = GuideFixtures.windowData(GuideFixtures.syntheticRows(3, from, to, durationsMin = intArrayOf(60, 90, 120)), fromMin = from, toMin = to)

    private fun world(exit: ExitConfirmation) =
        GuideWorld(channels = channels, data = data, settings = GuideSettings(exitConfirmation = exit), dataWindow = app.thebox.core.guide.GuideDataWindow(1, 1))

    private class Reached {
        val menu = HashSet<MenuItem>(); val buttons = HashSet<PanelButton>(); val chips = HashSet<HeaderChip>()
        val overlays = HashSet<String>(); val modes = HashSet<ScreenMode>(); var exited = false
    }

    private fun observe(w: GuideWorld, r: Reached) {
        val ui = w.ui
        r.modes += ui.mode
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
            ui.panel?.buttons, f?.row, f?.cellStart, f?.windowStartMin, w.favourites.size, w.reminders.size, ui.tuned.phase, ui.tuned.isArchive,
            w.exited, ui.sourceLabel,
        ).joinToString("|")
    }

    @ParameterizedTest
    @EnumSource(ExitConfirmation::class)
    fun everyFunctionReachableWithFiveKeys(exit: ExitConfirmation) {
        val r = Reached()
        val seen = HashSet<String>()
        var frontier = listOf(emptyList<BoxAction>())
        world(exit).also { observe(it, r); seen += signature(it) }
        val maxDepth = 10
        for (depth in 1..maxDepth) {
            val next = ArrayList<List<BoxAction>>()
            for (seq in frontier) for (k in keys) {
                val w = world(exit)
                for (a in seq) w.press(a)
                if (w.exited) continue
                w.press(k)
                observe(w, r)
                if (w.exited) continue
                if (seen.add(signature(w))) next += seq + k
            }
            frontier = next
            if (frontier.isEmpty() || seen.size > 60_000) break
        }
        val missing = buildList {
            addAll(MenuItem.entries.filter { it !in r.menu })
            addAll(PanelButton.entries.filter { it !in r.buttons })
            addAll(HeaderChip.entries.filter { it !in r.chips })
            addAll(ScreenMode.entries.filter { it !in r.modes })
            for (o in listOf("Groups", "Days", "Menu", "Sheet")) if (o !in r.overlays) add(o)
            when (exit) {
                ExitConfirmation.BACK_TWICE -> if ("ExitHint" !in r.overlays) add("ExitHint")
                ExitConfirmation.ASK -> if ("ExitDialog" !in r.overlays) add("ExitDialog")
                ExitConfirmation.NONE -> Unit
            }
            if (!r.exited) add("exit")
        }
        assertTrue(missing.isEmpty(), "$exit: not reachable with five keys: $missing (states ${seen.size})")
        if (exit != ExitConfirmation.ASK) assertTrue("ExitDialog" !in r.overlays, "the dialog only exists in ASK")
    }
}
