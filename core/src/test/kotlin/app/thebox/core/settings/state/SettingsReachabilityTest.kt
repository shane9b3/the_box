package app.thebox.core.settings.state

import app.thebox.core.full.UpDownInPlayer
import app.thebox.core.input.BoxAction
import app.thebox.core.net.DnsChoice
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.tree.ChannelOptionsRows
import app.thebox.core.settings.tree.SettingsTree
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Five-key reachability of Settings by enumeration (docs/design/m5-setup-settings.md §3.15): breadth-first over the
 * machine's states with D-pad, OK, BACK and long OK only, fed by WP-A's real page builders (and [FixturePages] standing
 * in for the other WPs' pages), for simple and advanced views, PIN set and not set, and a stored configuration that
 * shows the conditional rows (custom DoH, daily guide update, invert direction, Restart now).
 * (a) every row of every reached page is focused in some state; (b) every row's intents are emitted (each Choice option
 * other than the selected one, Toggle both ways, Run, the editor and pad opened, each Link pushed, each menu option);
 * (c) from every state at most 12 BACKs leave Settings (FirstRun: exit); (d) every item-menu option is also reachable
 * without long OK (the item's route, or a row of its page).
 */
class SettingsReachabilityTest {
    private val actions = listOf(BoxAction.UP, BoxAction.DOWN, BoxAction.LEFT, BoxAction.RIGHT, BoxAction.SELECT, BoxAction.BACK, BoxAction.CONTEXT_MENU)

    private class Seed(val name: String, val pinSet: Boolean, val configure: (FakeSettingsStore, FakePageEnv) -> Unit, val advanced: Boolean)

    private val seeds = listOf(
        Seed("simple", pinSet = false, configure = { _, _ -> }, advanced = false),
        Seed("advanced-conditional-pin", pinSet = true, advanced = true, configure = { s, e ->
            s.update(NetworkDomain) { it.copy(dns = DnsChoice.CUSTOM) }
            s.update(DataDomain) { it.copy(epgDailyAtHour = 3) }
            s.update(PlayerDomain) { it.copy(upDown = UpDownInPlayer.ZAP) }
            e.running = false // the engine runs without FFmpeg while the store says on: Restart now shows
        }),
    )

    private fun world(seed: Seed): SettingsWorld {
        val store = FakeSettingsStore()
        val env = FakePageEnv()
        seed.configure(store, env)
        if (seed.advanced) store.update(GuideDomain) { it.copy(advancedSettings = true) }
        return SettingsWorld(store, env, pinSet = seed.pinSet, applyIntents = false, reloadAfterIntent = false).also { w ->
            w.advanced = seed.advanced
            w.machine.dispatch(app.thebox.core.settings.SettingsEvent.Advanced(seed.advanced))
        }
    }

    private data class Key(
        val route: SettingsRoute, val stack: List<SettingsRoute>, val pane: app.thebox.core.settings.Pane, val sectionCursor: Int, val cursor: Int,
        val advanced: Boolean, val rows: List<RowId>?, val choice: Any?, val menu: Any?, val confirm: Int?, val editor: RowId?,
        val pad: Triple<RowId, Int, Int>?, val move: Any?, val capture: Boolean,
    )

    private fun key(u: SettingsUiState) = Key(
        u.route, u.stack, u.pane, u.sectionCursor, u.cursor, u.advanced,
        // Row ids plus toggle values (so a toggle is flipped both ways); other values are not part of the state.
        u.page?.let { p ->
            p.rows.map { r ->
                when (val k = r.kind) {
                    is RowKind.Toggle -> RowId(r.id.key + "=" + k.on)
                    is RowKind.MultiChoice -> RowId(r.id.key + "=" + k.selected.sorted())
                    else -> r.id
                }
            } + listOf(RowId("route:${p.route}"))
        },
        u.choice, u.menu?.let { it.row to it.focused }, u.confirm?.focused, u.editor?.row,
        u.numberPad?.let { Triple(it.row, it.digits.length, it.focusedKey) }, u.move, u.capture != null,
    )

    private class Coverage {
        val rows = HashMap<SettingsRoute, MutableSet<RowId>>()
        val focused = HashMap<SettingsRoute, MutableSet<RowId>>()
        val intents = HashSet<String>()
        val opened = HashSet<String>()
        val routes = HashSet<SettingsRoute>()
    }

    private fun replay(seed: Seed, start: SettingsRoute, path: List<BoxAction>): Pair<SettingsWorld, Boolean> {
        val w = world(seed).open(start)
        var alive = true
        for (a in path) {
            val before = w.effects.exits
            if (!w.act(a) || w.effects.exits > before) { alive = false; break }
            if (w.ui.editor != null) w.machine.dispatch(app.thebox.core.settings.SettingsEvent.EditorClosed(w.ui.editor!!.row, null, false)).also { w.pump() }
        }
        return w to alive
    }

    private fun explore(seed: Seed, start: SettingsRoute, cov: Coverage) {
        val seen = HashSet<Key>()
        val queue = ArrayDeque<List<BoxAction>>()
        queue.addLast(emptyList())
        seen += key(replay(seed, start, emptyList()).first.ui)
        var states = 0
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            states++
            check(states < 60_000) { "state space too large (${seed.name}, $start)" }
            val (w0, _) = replay(seed, start, path)
            record(w0, cov)
            assertLeavesWithin12Backs(seed, start, path)
            for (a in actions) {
                val w = replay(seed, start, path).first
                val before = w.effects.intents.size
                val stay = w.act(a)
                w.effects.intents.drop(before).forEach { cov.intents += intentKey(it) }
                if (w.ui.editor != null) cov.opened += "editor:" + w.ui.editor!!.row.key
                if (w.ui.numberPad != null) cov.opened += "pad:" + w.ui.numberPad!!.row.key
                if (w.ui.capture != null) cov.opened += "capture"
                if (w.gate.requests.isNotEmpty()) cov.opened += "pin"
                if (!stay || w.effects.exits > 0) continue
                if (w.ui.editor != null) continue // Compose owns the editor's keys; its exits are tested on their own
                val k = key(w.ui)
                if (seen.add(k)) queue.addLast(path + a)
            }
        }
    }

    private fun record(w: SettingsWorld, cov: Coverage) {
        val u = w.ui
        val page = u.page ?: return
        cov.routes += u.route
        val all = cov.rows.getOrPut(page.route) { HashSet() }
        page.rows.forEach { all += it.id }
        if (u.pane == app.thebox.core.settings.Pane.PAGE && u.route == page.route) {
            SettingsMachineImpl.rowAt(page, u.cursor)?.let { cov.focused.getOrPut(page.route) { HashSet() } += it.id }
        }
        // (d) every item-menu option is reachable with the four keys
        for (r in page.rows) {
            val k = r.kind as? RowKind.Item ?: continue
            for (o in k.menu) {
                val ok = (o.id == "open" && k.route != null) || page.rows.any { (it.kind as? RowKind.Item)?.primary == o.id }
                assertTrue(ok, "menu option ${o.id} of ${r.id.key} has no five-key path on ${page.route}")
            }
        }
    }

    private fun assertLeavesWithin12Backs(seed: Seed, start: SettingsRoute, path: List<BoxAction>) {
        val (w, alive) = replay(seed, start, path)
        if (!alive) return
        repeat(12) {
            val before = w.effects.exits
            if (!w.act(BoxAction.BACK) || w.effects.exits > before) return
        }
        fail("12 BACKs do not leave Settings from ${path} (${seed.name}, $start): ${w.ui.route}")
    }

    private fun intentKey(i: RowIntent): String = when (i) {
        is RowIntent.Toggle -> "toggle:${i.row.key}:${i.on}"
        is RowIntent.Choose -> "choose:${i.row.key}:${i.optionId}"
        is RowIntent.ChooseMany -> "many:${i.row.key}"
        is RowIntent.Edit -> "edit:${i.row.key}"
        is RowIntent.SetNumber -> "number:${i.row.key}"
        is RowIntent.Run -> "run:${i.row.key}"
        is RowIntent.Menu -> "menu:${i.row.key}:${i.optionId}"
        is RowIntent.Move -> "move:${i.row.key}"
    }

    @Test
    fun everyRowIsFocusedAndEveryIntentIsEmittedWithFiveKeys() {
        val cov = Coverage()
        for (seed in seeds) {
            explore(seed, SettingsRoute.Root, cov)
            explore(seed, SettingsRoute.FirstRun, cov)
        }
        val problems = ArrayList<String>()
        for ((route, rows) in cov.rows) for (id in rows) {
            if (cov.focused[route]?.contains(id) != true) problems += "never focused: ${id.key} on $route"
        }
        // (b) intents, per WP-A row of every page reached (the fixtures' generic rows are covered by the key table)
        val pages = cov.rows.keys
        for (seed in seeds) for (route in pages) {
            val w = world(seed)
            val page = w.page(route)
            for (r in page.rows) {
                if (SettingsTree.spec(r.id)?.owner != "A" || !r.enabled) continue
                when (val k = r.kind) {
                    is RowKind.Toggle -> for (on in listOf(true, false)) if ("toggle:${r.id.key}:$on" !in cov.intents) problems += "toggle ${r.id.key} → $on"
                    is RowKind.Choice -> k.options.forEachIndexed { i, o ->
                        if (i != k.selected && o.enabled && "choose:${r.id.key}:${o.id}" !in cov.intents) problems += "choose ${r.id.key}:${o.id}"
                    }
                    is RowKind.MultiChoice -> if ("many:${r.id.key}" !in cov.intents) problems += "many ${r.id.key}"
                    is RowKind.Text -> if ("editor:${r.id.key}" !in cov.opened) problems += "editor ${r.id.key}"
                    is RowKind.Number -> if ("pad:${r.id.key}" !in cov.opened) problems += "pad ${r.id.key}"
                    is RowKind.Action -> if (k.confirm == null && !k.needsPin && "run:${r.id.key}" !in cov.intents) problems += "run ${r.id.key}"
                    is RowKind.Link -> if (k.route !in cov.routes) problems += "link ${r.id.key} → ${k.route}"
                    is RowKind.Item, is RowKind.Info -> Unit
                }
            }
        }
        // Channel options' confirm-guarded hand-off still runs from the confirm card.
        if ("run:${ChannelOptionsRows.EXTERNAL.key}" !in cov.intents) problems += "run ${ChannelOptionsRows.EXTERNAL.key} (confirm)"
        assertTrue(problems.isEmpty(), problems.distinct().sorted().joinToString("\n"))
    }
}
