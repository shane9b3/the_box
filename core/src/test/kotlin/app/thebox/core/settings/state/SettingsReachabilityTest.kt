package app.thebox.core.settings.state

import app.thebox.core.full.UpDownInPlayer
import app.thebox.core.input.BoxAction
import app.thebox.core.net.DnsChoice
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.tree.ChannelOptionsRows
import app.thebox.core.settings.tree.SettingsTree
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private class Seed(
        val name: String, val pinSet: Boolean, val configure: (FakeSettingsStore, FakePageEnv) -> Unit, val advanced: Boolean,
        /** C-S9: the other WPs' real builders with this many playlists (null: [FixturePages]). */
        val playlists: Int? = null,
        /** (WP-S4) Fills the world's fake locations, drives and found computers. */
        val configureWorld: (SettingsWorld) -> Unit = {},
    )

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
        val real = seed.playlists?.let { RealPages(it, seed.pinSet) }
        return SettingsWorld(store, env, pinSet = seed.pinSet, applyIntents = false, reloadAfterIntent = false, real = real).also { w ->
            w.advanced = seed.advanced
            w.machine.dispatch(app.thebox.core.settings.SettingsEvent.Advanced(seed.advanced))
            seed.configureWorld(w)
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

    /** [expand]: states on routes it refuses are recorded and checked but not expanded (C-S9 bounds the real pages). */
    private fun explore(seed: Seed, start: SettingsRoute, cov: Coverage, expand: (SettingsRoute) -> Boolean = { true }) {
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
                if (!expand(w.ui.route)) continue
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
        // (d) every item-menu option is reachable with the four keys: (C-S4) OK opens the menu of an item that has no route
        // and no primary; otherwise an option needs a row of its own (the item's page, or a `primary` row).
        for (r in page.rows + page.list?.loaded?.values?.flatten().orEmpty()) {
            val k = r.kind as? RowKind.Item ?: continue
            for (o in k.menu) {
                val ok = (k.route == null && k.primary == null) || (o.id == "open" && k.route != null) || o.id == k.primary ||
                    page.rows.any { (it.kind as? RowKind.Item)?.primary == o.id } || (k.route != null && optionRowOnItsPage(w, k.route, o.id)) ||
                    // (WP-S4) A found computer's "Sign in first": OK opens its shares, whose "Sign in" row is the same step.
                    (k.primary?.startsWith("pick:") == true && o.id == "signin")
                if (!ok) dGaps += "menu option ${o.id} of ${r.id.key} has no five-key path on ${page.route}"
            }
        }
    }

    /** (d) gaps found; the M5 code review's known one is listed in [KNOWN_D_GAPS]. */
    private val dGaps = LinkedHashSet<String>()

    /** An item's menu option that is also a row of the page its route opens (Playlists: Edit is the Source row, §3.3.1). */
    private fun optionRowOnItsPage(w: SettingsWorld, route: SettingsRoute, option: String): Boolean {
        val target = runCatching { w.page(route) }.getOrNull() ?: return false
        val alias = mapOf("edit" to "source", "test" to "network_test")[option] ?: option
        return target.rows.any { it.id.key.substringAfterLast('.') == alias }
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

    /**
     * C-S9 (M5 code review 2026-10-16): §3.15 (a), (c) and (d) on the other WPs' real page builders (WP-B's setup pages,
     * WP-C's Parental and button-mapping pages, WP-D's Channels and Backup pages) for 0, 1 and 2 playlists. Each WP's
     * section is explored from its page; routes of other sections are reached but not expanded.
     */
    @Test
    fun everyWpsRealPagesAreReachableWithFiveKeys() {
        val cov = Coverage()
        val sections = listOf(SectionId.PLAYLISTS, SectionId.CHANNELS, SectionId.PARENTAL, SectionId.BACKUP, SectionId.REMOTE)
        for (n in 0..2) {
            val seed = Seed("real-$n", pinSet = n == 2, configure = { _, _ -> }, advanced = n == 1, playlists = n)
            for (s in sections) {
                val start = if (s == SectionId.REMOTE) SettingsRoute.ButtonMapping else SettingsRoute.Section(s)
                explore(seed, start, cov) { r -> r != SettingsRoute.Root && SettingsTree.sectionOf(r) == s && r !is SettingsRoute.ActionBindings || r == SettingsRoute.ActionBindings(BoxAction.ZAP_UP) }
            }
            explore(seed, SettingsRoute.FirstRun, cov) { r -> r == SettingsRoute.FirstRun || r is SettingsRoute.AddSource }
        }
        val problems = ArrayList<String>()
        for ((route, rows) in cov.rows) for (id in rows) {
            // X3 (integration): Welcome's footer note is not a card; the cursor moves between the cards only.
            if (id.key == "setup.welcome.note") continue
            if (cov.focused[route]?.contains(id) != true) problems += "never focused: ${id.key} on $route"
        }
        val expected = listOf(
            SettingsRoute.Playlist(1), SettingsRoute.PlaylistSource(1), SettingsRoute.AddSource(), SettingsRoute.Groups(1), SettingsRoute.HiddenChannels(1),
            SettingsRoute.HideRules(1), SettingsRoute.FavouriteList(1), SettingsRoute.Section(SectionId.BACKUP), SettingsRoute.Section(SectionId.PARENTAL),
            SettingsRoute.ButtonMapping, SettingsRoute.ActionBindings(BoxAction.ZAP_UP),
        )
        for (r in expected) if (r !in cov.routes) problems += "never reached: $r"
        assertTrue(problems.isEmpty(), problems.distinct().sorted().joinToString("\n"))
        val gaps = dGaps.filter { g -> KNOWN_D_GAPS.none { it in g } }
        assertTrue(gaps.isEmpty(), "(d) " + gaps.joinToString("\n"))
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
        assertTrue(dGaps.isEmpty(), "(d) " + dGaps.joinToString("\n"))
        // Channel options' confirm-guarded hand-off still runs from the confirm card.
        if ("run:${ChannelOptionsRows.EXTERNAL.key}" !in cov.intents) problems += "run ${ChannelOptionsRows.EXTERNAL.key} (confirm)"
        assertTrue(problems.isEmpty(), problems.distinct().sorted().joinToString("\n"))
    }

    /**
     * (WP-S4, docs/design/m6-storage-locations.md section 9.3, 16.2) Five-key reachability of the save-location pages: Pause
     * and rewind, Save to, a stored location's detail, its sign-in and Test, and the Find page, for a stored network folder
     * (in use, signed out), a mounted drive and a found computer. (a) every row is focused in some state, (c) at most 12
     * BACKs leave Settings from every state, (d) every item-menu option is also a row of the page the item opens.
     */
    @Test
    fun saveLocationPagesAreReachableWithFiveKeys() {
        val nas = app.thebox.core.storage.StorageLocation(
            "smb-1", app.thebox.core.storage.StorageKind.SMB, "NAS",
            smb = app.thebox.core.storage.SmbAddress("nas", 445, "TV", "Rewind", user = "tv"),
            lastTest = app.thebox.core.storage.LocationTestResult(app.thebox.core.storage.TestVerdict.GOOD_HD, 4_000_000, 4_100_000, 6, 0, 1L shl 40, true, 0L),
        )
        val seed = Seed("storage", pinSet = false, advanced = true, configure = { _, _ -> }, configureWorld = { w ->
            kotlinx.coroutines.runBlocking { w.locations.saveSmb(nas.smb!!, nas.name, null, nas.lastTest) }
            w.locations.setState("smb-1", app.thebox.core.storage.LocationState.SIGN_IN_NEEDED)
            w.volumes.volumes.value = listOf(app.thebox.core.storage.VolumeCatalog.Volume("U1", "SanDisk", java.io.File("x"), 64L shl 30, 41L shl 30))
            w.folders.findStates = listOf(
                app.thebox.core.storage.FindState(
                    listOf(app.thebox.core.storage.FoundComputer("NAS", "192.168.1.20", app.thebox.core.storage.NameSource.NSD)),
                    searching = false, scannedNetwork = "192.168.1.x", vpnUp = true,
                ),
            )
        })
        val cov = Coverage()
        explore(seed, SettingsRoute.PauseRewind, cov) { r -> r != SettingsRoute.Root && r !is SettingsRoute.Section }
        val problems = ArrayList<String>()
        for ((route, rows) in cov.rows) for (id in rows) {
            if (cov.focused[route]?.contains(id) != true) problems += "never focused: ${id.key} on $route"
        }
        for (r in listOf(
            SettingsRoute.PauseRewind, SettingsRoute.SaveLocation(), SettingsRoute.LocationDetail("smb-1"), SettingsRoute.FindComputers,
            SettingsRoute.LocationSignIn("smb-1"), SettingsRoute.LocationTest(locationId = "smb-1"),
        )) if (r !in cov.routes) problems += "never reached: $r"
        assertTrue(problems.isEmpty(), problems.distinct().sorted().joinToString("\n"))
        val gaps = dGaps.filter { g -> KNOWN_D_GAPS.none { it in g } }
        assertTrue(gaps.isEmpty(), "(d) " + gaps.joinToString("\n"))
    }

    /**
     * (WP-S4, section 9.3, U-B3) The folder walk eight levels deep: BACK leaves the whole browse at once (the path is session
     * state, not routes), so Settings is left within 12 BACKs from every depth of the walk.
     */
    @Test
    fun aBrowseEightLevelsDeepLeavesWithinTwelveBacks() {
        for (depth in 0..8) {
            val w = SettingsWorld(applyIntents = true)
            w.folders.foldersAnswer = { _, share, path -> app.thebox.core.storage.FolderListing(share, path, listOf("d")) }
            w.folders.findStates = listOf(
                app.thebox.core.storage.FindState(listOf(app.thebox.core.storage.FoundComputer("NAS", "192.168.1.20", app.thebox.core.storage.NameSource.NSD)), false, "192.168.1.x", false),
            )
            w.open(SettingsRoute.FindComputers)
            w.focus(RowId("storage.find.pc.192.168.1.20")); w.act(BoxAction.SELECT)
            w.focus(RowId("storage.share.item.TV")); w.act(BoxAction.SELECT)
            repeat(depth) { w.focus(RowId("storage.folder.item.d")); w.act(BoxAction.SELECT) }
            val before = w.ui.stack.size
            var backs = 0
            while (backs < 12) {
                val exits = w.effects.exits
                if (!w.act(BoxAction.BACK) || w.effects.exits > exits) break
                backs++
            }
            assertTrue(backs < 12, "depth $depth: still in Settings after 12 BACKs (${w.ui.route}, $before routes deep)")
            assertEquals(0, w.storage.openSessionCount(), "depth $depth: the browse session ended when BACK left it")
        }
    }

    private companion object {
        /**
         * (d) gaps the M5 code review leaves open (reported, not fixed in this pass): reordering favourite lists is a menu
         * option of a list whose OK opens the list, and no row of the list's page moves it (long OK only).
         */
        val KNOWN_D_GAPS = listOf("menu option move of favlist/")
    }
}
