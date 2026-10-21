package app.thebox.core.editor

import app.thebox.core.input.BoxAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Five-key reachability of the channel editor by enumeration (docs/design/m6-channel-editor.md §6.1, as
 * `SettingsReachabilityTest`): breadth-first over the machine's states with D-pad, OK, BACK and CONTEXT_MENU (long OK),
 * from `open(pid, null)` and `open(pid, groupKey)`, with the PIN set (session locked) and unset, and with 0, 1 and many
 * groups. Checks: every [EditorAction] runs; every layer opens (menu, summary, picker, move, confirm, each choice list,
 * toolbar, the text editor, the number pad, the PIN prompt); from every state at most 12 BACKs return false.
 * A second run without CONTEXT_MENU checks that every channel action is still reached (→ opens the channel menu).
 */
class ChannelEditorReachabilityTest {
    private val five = listOf(BoxAction.UP, BoxAction.DOWN, BoxAction.LEFT, BoxAction.RIGHT, BoxAction.SELECT, BoxAction.BACK)
    private val alphabet = five + BoxAction.CONTEXT_MENU

    private class Seed(val name: String, val groups: List<FG>, val pin: Boolean, val groupKey: String?)

    private val news = FG("g:news", "News", listOf(
        FC("r1", "Alpha", 1, customNumber = 9, favorite = true),
        FC("r2", "Beta", 2, hidden = true),
    ))
    private val sport = FG("g:sport", "Sport", listOf(FC("s1", "Sigma", 3, locked = true)))
    private val adult = FG("g:adult", "Adult", listOf(FC("a1", "Late", 4)), locked = true)

    private val seeds = listOf(
        Seed("many", listOf(news, sport, adult), pin = false, groupKey = null),
        Seed("many-from-guide", listOf(news, sport, adult), pin = false, groupKey = "g:news"),
        Seed("many-pin", listOf(news, sport, adult), pin = true, groupKey = null),
        Seed("many-pin-from-guide", listOf(news, sport, adult), pin = true, groupKey = "g:sport"),
        Seed("one", listOf(news), pin = false, groupKey = null),
        Seed("one-pin-from-guide", listOf(news), pin = true, groupKey = "g:news"),
        Seed("none", emptyList(), pin = false, groupKey = null),
        Seed("none-pin", emptyList(), pin = true, groupKey = null),
    )

    private class Coverage {
        val actions = HashSet<EditorAction>()
        val layers = HashSet<String>()
        var states = 0
    }

    private fun world(seed: Seed, cov: Coverage?): EditorWorld {
        val w = EditorWorld(groups = seed.groups, pinSet = seed.pin, unlocked = !seed.pin)
        w.textAnswer = { if (it == EditorText.EDIT_FIND) "a" else "Renamed" }
        if (cov != null) w.machine.trace = { cov.actions += it }
        return w.open(seed.groupKey)
    }

    /**
     * The state, for de-duplication. Ticks count by kind and size, and only in the pane that shows them; the other
     * pane's cursor is left out (it is kept as is and comes back unchanged). States that differ only there behave alike
     * for every key of the alphabet, so one representative is explored (and its Back ladder checked).
     */
    private fun key(w: EditorWorld): List<Any?> {
        val u = w.ui
        fun sel(s: Selection, n: Int) = when (s) {
            Selection.None -> "0"
            is Selection.Keys -> "k" + s.keys.size
            is Selection.AllExcept -> "a" + EditorMenus.selectedCount(s, n)
        }
        val groupsPane = u.pane == EditorPane.GROUPS
        return listOf(
            u.pane, u.toolbar, u.groups.head, if (groupsPane || u.move != null) u.groups.cursor else null, u.groups.groups?.count, u.showHiddenGroups,
            if (groupsPane) sel(u.groupSelection, u.groups.groups?.count ?: 0) else null,
            u.list, u.filter, u.channels?.count, if (!groupsPane) u.channels?.cursor else null,
            if (!groupsPane || u.menu != null || u.picker != null) sel(u.channelSelection, u.channels?.count ?: 0) else null,
            u.lockedList, u.lockedOut,
            u.menu?.let { listOf(it.lines, it.focused, it.loading) },
            u.picker?.cursor, u.move?.let { it.index to it.lifted }, u.confirm?.let { it.kind to it.focused }, u.choice,
            w.unlocked,
        )
    }

    private fun replay(seed: Seed, path: List<BoxAction>, cov: Coverage? = null): Pair<EditorWorld, Boolean> {
        val w = world(seed, cov)
        for (a in path) {
            if (!w.press(a)) return w to false
            w.settle()
        }
        return w to true
    }

    private fun record(w: EditorWorld, cov: Coverage) {
        w.layer().let { if (it != "none") cov.layers += it }
        if (w.texts.isNotEmpty()) w.texts.forEach { cov.layers += "text:" + it.name }
        if (w.numbers.isNotEmpty()) cov.layers += "number"
        if (w.pinRequests > 0) cov.layers += "pin"
        if (w.summaries.isNotEmpty()) cov.layers += "summary"
    }

    private fun explore(seed: Seed, actions: List<BoxAction>, cov: Coverage, checkBacks: Boolean) {
        val seen = HashSet<List<Any?>>()
        val queue = ArrayDeque<List<BoxAction>>()
        seen += key(replay(seed, emptyList()).first)
        queue.addLast(emptyList())
        var states = 0
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            cov.states++
            check(++states < 40_000) { "state space too large (${seed.name}): depth ${path.size}" }
            if (checkBacks) assertLeavesWithin12Backs(seed, path)
            for (a in actions) {
                val (w, alive) = replay(seed, path + a, cov)
                record(w, cov)
                if (!alive) continue
                if (seen.add(key(w))) queue.addLast(path + a)
            }
        }
    }

    private fun assertLeavesWithin12Backs(seed: Seed, path: List<BoxAction>) {
        val (w, alive) = replay(seed, path)
        if (!alive) return
        for (i in 1..12) {
            if (!w.press(BoxAction.BACK)) return
            w.settle()
        }
        throw AssertionError("${seed.name}: 12 BACKs did not leave the editor after $path (state ${w.ui})")
    }

    @Test
    fun everyActionAndLayerIsReachableAndBackAlwaysLeaves() {
        val cov = Coverage()
        val t0 = System.nanoTime()
        for (s in seeds) { val b = cov.states; val t = System.nanoTime(); explore(s, alphabet, cov, checkBacks = true); println("seed ${s.name}: ${cov.states - b} states, ${(System.nanoTime() - t) / 1_000_000} ms") }
        println("ChannelEditorReachabilityTest: ${cov.states} states in ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertEquals(EditorAction.entries.toSet(), cov.actions, "actions never run: ${EditorAction.entries.toSet() - cov.actions}")
        val layers = setOf(
            "menu", "picker", "move", "confirm", "toolbar", "choice:FILTER", "choice:CHANNEL_SORT", "choice:GROUP_SORT",
            "text:EDIT_FIND", "text:EDIT_RENAME_CHANNEL", "text:EDIT_RENAME_GROUP", "number", "pin", "summary",
        )
        assertTrue(cov.layers.containsAll(layers), "layers never opened: ${layers - cov.layers}")
    }

    @Test
    fun everyChannelActionIsReachedWithoutTheMenuKey() {
        val cov = Coverage()
        for (s in seeds.filter { it.name == "many" || it.name == "many-pin-from-guide" }) explore(s, five, cov, checkBacks = false)
        val channelActions = EditorAction.entries.toSet() - EditorAction.DROP_RESULTS - EditorAction.EDIT_CHANNELS
        assertTrue(cov.actions.containsAll(channelActions), "not reached without Menu: ${channelActions - cov.actions}")
        assertTrue("menu" in cov.layers && "picker" in cov.layers && "move" in cov.layers, "layers without Menu: ${cov.layers}")
    }
}
