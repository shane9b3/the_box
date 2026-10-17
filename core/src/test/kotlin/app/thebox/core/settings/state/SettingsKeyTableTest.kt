package app.thebox.core.settings.state

import app.thebox.core.input.BoxAction
import app.thebox.core.input.CapturedKey
import app.thebox.core.settings.Pane
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.tree.InterfaceRows
import app.thebox.core.settings.tree.RemoteRows
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Table-driven key test of the settings machine (docs/design/m5-setup-settings.md §3.2.2, §6.1 `settings_keys.csv`):
 * every pane, row kind and overlay × action, through the real WP-A page builders.
 */
class SettingsKeyTableTest {
    data class Row(val line: Int, val section: String, val pre: String, val action: String, val pane: Pane, val route: String,
                   val overlay: String, val effect: String, val check: String)

    private fun rows(): List<Row> = javaClass.getResource("/settings_keys.csv")!!.readText().lines().mapIndexedNotNull { i, raw ->
        val l = raw.trim()
        if (l.isEmpty() || l.startsWith("#") || l.startsWith("section,")) return@mapIndexedNotNull null
        val c = l.split(',')
        require(c.size == 8) { "settings_keys.csv:${i + 1}: 8 columns expected: $l" }
        Row(i + 1, c[0], c[1], c[2], Pane.valueOf(c[3]), c[4], c[5], c[6], c[7])
    }

    @TestFactory
    fun keyTable(): List<DynamicTest> = rows().map { r -> DynamicTest.dynamicTest("${r.line} ${r.section}: ${r.pre} + ${r.action}") { run(r) } }

    @Test
    fun everyPreconditionIsUsedAndEveryLayerIsCovered() {
        val all = rows()
        assertEquals(PRE.keys, all.map { it.pre }.toSet(), "unused or missing preconditions")
        for (s in listOf("sections", "page", "choice", "multi", "menu", "confirm", "pad", "move", "editor", "capture", "firstrun", "detached")) {
            assertTrue(all.any { it.section == s }, "section $s")
        }
        for (a in listOf("UP", "DOWN", "LEFT", "RIGHT", "SELECT", "BACK")) for (s in listOf("sections", "page", "choice", "menu", "confirm", "pad")) {
            assertTrue(all.any { it.section == s && it.action == a } || (s == "choice" && a == "RIGHT") || (s == "page" && a == "RIGHT"), "$s × $a")
        }
    }

    private fun run(r: Row) {
        val w = PRE.getValue(r.pre)()
        val intentsBefore = w.effects.intents.size
        val exitsBefore = w.effects.exits
        val action = if (r.action.startsWith("DIGIT")) BoxAction.DIGIT else BoxAction.valueOf(r.action)
        val digit = if (r.action.startsWith("DIGIT")) r.action.removePrefix("DIGIT").toInt() else -1
        val answer = w.act(action, digit = digit)
        val ui = w.ui
        val where = "line ${r.line}"
        assertEquals(r.pane, ui.pane, "$where pane")
        assertEquals(r.route, routeName(ui.route), "$where route")
        assertEquals(r.overlay, overlayOf(w), "$where overlay")
        val emitted = w.effects.intents.drop(intentsBefore)
        when {
            r.effect == "-" -> { assertTrue(answer, "$where consumed"); assertTrue(emitted.isEmpty(), "$where no intent: $emitted") }
            r.effect == "false" -> assertEquals(false, answer, "$where answer")
            r.effect == "exit" -> assertEquals(exitsBefore + 1, w.effects.exits, "$where exit")
            r.effect == "armCapture" -> assertTrue(w.effects.armed, "$where armed")
            r.effect.startsWith("intent:") -> {
                val kind = r.effect.removePrefix("intent:")
                assertTrue(emitted.any { it::class.simpleName == kind }, "$where intent $kind in $emitted")
            }
            else -> error("$where unknown effect ${r.effect}")
        }
        if (r.check != "-") for (c in r.check.split(';')) {
            val (k, v) = if ('=' in c) c.substringBefore('=') to c.substringAfter('=') else c to ""
            when (k) {
                "sc" -> assertEquals(v.toInt(), ui.sectionCursor, "$where sc")
                "cur" -> assertEquals(v.toInt(), ui.cursor, "$where cur")
                "cf" -> assertEquals(v.toInt(), ui.choice?.focused ?: ui.menu?.focused ?: ui.confirm?.focused, "$where cf")
                "digits" -> assertEquals(v, assertNotNull(ui.numberPad, where).digits, "$where digits")
                "key" -> assertEquals(v.toInt(), assertNotNull(ui.numberPad, where).focusedKey, "$where key")
                "err" -> assertNotNull(ui.numberPad?.error, "$where error line")
                "adv" -> assertEquals(v.toBoolean(), ui.advanced, "$where advanced")
                "pin" -> assertEquals(v.toInt(), w.gate.requests.size, "$where pin requests")
                "mi" -> assertEquals(v.toInt(), assertNotNull(ui.move, where).index, "$where move index")
                "armed" -> assertEquals(v.toBoolean(), w.effects.armed, "$where armed")
                else -> error("$where unknown check $k")
            }
        }
    }

    companion object {
        fun routeName(r: SettingsRoute): String = when (r) {
            SettingsRoute.Root -> "Root"
            is SettingsRoute.Section -> "S:" + r.id.name
            else -> r::class.simpleName!!
        }

        fun overlayOf(w: SettingsWorld): String {
            val u = w.ui
            return when {
                u.editor != null -> "editor"
                u.capture != null -> "capture"
                u.numberPad != null -> "pad"
                u.confirm != null -> "confirm"
                u.menu != null -> "menu"
                u.move != null -> "move"
                u.choice != null -> if (w.focusedRowKindIsMulti()) "multi" else "choice"
                else -> "none"
            }
        }

        private fun SettingsWorld.focusedRowKindIsMulti() = focusedRow()?.kind is app.thebox.core.settings.RowKind.MultiChoice

        private fun root() = SettingsWorld().open()
        private fun lists(pinSet: Boolean = false): SettingsWorld = SettingsWorld(pinSet = pinSet).open().apply {
            enter(SectionId.CHANNELS); focus(RowId("fx.lists")); act(BoxAction.RIGHT)
        }
        private fun listsAt(id: String, pinSet: Boolean = false) = lists(pinSet).apply { focus(RowId(id)) }

        val PRE: Map<String, () -> SettingsWorld> = mapOf(
            "SECTIONS_ROOT" to { root() },
            "SECTIONS_GUIDE" to { root().apply { act(BoxAction.DOWN) } },
            "SECTIONS_LAST" to { root().apply { repeat(8) { act(BoxAction.DOWN) } } },
            "SECTIONS_TOGGLE" to { root().apply { repeat(9) { act(BoxAction.DOWN) } } },
            "PAGE_PLAYBACK_TOP" to { root().apply { enter(SectionId.PLAYBACK) } },
            "PAGE_INTERFACE_LOGOS" to {
                root().apply { repeat(9) { act(BoxAction.DOWN) }; act(BoxAction.SELECT); act(BoxAction.UP); repeat(5) { act(BoxAction.UP) }; enter(SectionId.INTERFACE); focus(InterfaceRows.LOGOS) }
            },
            "PAGE_REMOTE_MAPPING" to { root().apply { enter(SectionId.REMOTE); focus(RemoteRows.MAPPING) } },
            "PAGE_LISTS_NUMBER" to { listsAt("fx.number") },
            "PAGE_LISTS_TEXT" to { listsAt("fx.text") },
            "PAGE_LISTS_MULTI" to { listsAt("fx.multi") },
            "PAGE_LISTS_CONFIRM" to { listsAt("fx.confirm") },
            "PAGE_LISTS_PIN" to { listsAt("fx.pin") },
            "PAGE_LISTS_PIN_SET" to { listsAt("fx.pin", pinSet = true) },
            "PAGE_LISTS_INFO" to { listsAt("fx.info") },
            "PAGE_LISTS_DISABLED" to { listsAt("fx.disabled") },
            "PAGE_LISTS_CAPTURE" to { listsAt(FixturePages.CAPTURE_ROW.key) },
            "PAGE_LISTS_ITEM" to { listsAt("fx.list.1") },
            "PAGE_LISTS_PRIMARY" to { listsAt("fx.list.move") },
            "CHOICE_OPEN" to { root().apply { enter(SectionId.PLAYBACK); act(BoxAction.SELECT) } },
            "CHOICE_DOWN1" to { root().apply { enter(SectionId.PLAYBACK); act(BoxAction.SELECT); act(BoxAction.DOWN) } },
            "MULTI_OPEN" to { listsAt("fx.multi").apply { act(BoxAction.SELECT) } },
            "MULTI_DOWN1" to { listsAt("fx.multi").apply { act(BoxAction.SELECT); act(BoxAction.DOWN) } },
            "MULTI_TOGGLED" to { listsAt("fx.multi").apply { act(BoxAction.SELECT); act(BoxAction.SELECT) } },
            "MENU_OPEN" to { listsAt("fx.list.1").apply { act(BoxAction.CONTEXT_MENU) } },
            "MENU_MOVE" to { listsAt("fx.list.1").apply { act(BoxAction.CONTEXT_MENU); act(BoxAction.DOWN) } },
            "CONFIRM_OPEN" to { listsAt("fx.confirm").apply { act(BoxAction.SELECT) } },
            "CONFIRM_LEFT" to { listsAt("fx.confirm").apply { act(BoxAction.SELECT); act(BoxAction.LEFT) } },
            "PAD_OPEN" to { listsAt("fx.number").apply { act(BoxAction.SELECT) } },
            "PAD_DIGITS" to { listsAt("fx.number").apply { act(BoxAction.DIGIT, digit = 7) } },
            "PAD_DIGITS_OK" to { listsAt("fx.number").apply { act(BoxAction.DIGIT, digit = 7); act(BoxAction.DOWN); act(BoxAction.DOWN); act(BoxAction.RIGHT) } },
            "PAD_BIG_OK" to { listsAt("fx.number").apply { act(BoxAction.DIGIT, digit = 9); act(BoxAction.DIGIT, digit = 9); act(BoxAction.DOWN); act(BoxAction.DOWN); act(BoxAction.RIGHT) } },
            "MOVE" to { listsAt("fx.list.1").apply { act(BoxAction.CONTEXT_MENU); act(BoxAction.DOWN); act(BoxAction.SELECT) } },
            "MOVE_DOWN1" to { listsAt("fx.list.1").apply { act(BoxAction.CONTEXT_MENU); act(BoxAction.DOWN); act(BoxAction.SELECT); act(BoxAction.DOWN) } },
            "EDITOR" to { listsAt("fx.text").apply { act(BoxAction.SELECT) } },
            "CAPTURE" to { listsAt(FixturePages.CAPTURE_ROW.key).apply { act(BoxAction.SELECT) } },
            "FIRSTRUN" to { SettingsWorld().open(SettingsRoute.FirstRun) },
            "DETACHED" to { SettingsWorld().open(SettingsRoute.SyncStatus, detached = true) },
        )
    }

    @Test
    fun captureDeliversTheKeyAndOkBindsIt() {
        val w = listsAt(FixturePages.CAPTURE_ROW.key)
        w.act(BoxAction.SELECT)
        w.machine.dispatch(SettingsEvent.Captured(CapturedKey(183, "KEYCODE_PROG_RED", "Remote", 3, false)))
        assertEquals(false, w.effects.armed)
        assertEquals(183, w.ui.capture?.captured?.keyCode)
        w.act(BoxAction.SELECT)
        // C-L2: the one save path is the shell link's captureSaved (WP-C's mapping source binds the key).
        assertEquals(183, w.saved.single().second.keyCode)
        assertEquals(null, w.ui.capture)
    }

    @Test
    fun captureTimesOutAfterTenSeconds() {
        val w = listsAt(FixturePages.CAPTURE_ROW.key)
        w.act(BoxAction.SELECT)
        assertEquals(w.now + SettingsMachineImpl.CAPTURE_TIMEOUT_MS, w.machine.nextDeadlineMs.value)
        w.now += SettingsMachineImpl.CAPTURE_TIMEOUT_MS
        w.machine.dispatch(SettingsEvent.Tick(w.now))
        assertEquals(null, w.ui.capture)
        assertEquals(false, w.effects.armed)
        assertEquals(SettingsText.CAPTURE_TIMED_OUT.name, w.ui.notice)
    }

    @Test
    fun noticeClearsAfterNoticeMs() {
        val w = root()
        w.machine.dispatch(SettingsEvent.Notice("Saved"))
        assertEquals("Saved", w.ui.notice)
        w.machine.dispatch(SettingsEvent.Tick(w.now + SettingsMachineImpl.NOTICE_MS))
        assertEquals(null, w.ui.notice)
    }

    @Test
    fun heldDownAcceleratesOnPagedLists() {
        val w = SettingsWorld().open().apply { enter(SectionId.CHANNELS); focus(RowId("fx.hidden")); act(BoxAction.RIGHT) }
        assertEquals("HiddenChannels", routeName(w.ui.route))
        w.act(BoxAction.DOWN, repeat = 3, heldMs = 700)
        assertEquals(3, w.ui.cursor)
        w.act(BoxAction.DOWN, repeat = 1, heldMs = 100)
        assertEquals(4, w.ui.cursor)
        assertNotNull(SettingsMachineImpl.rowAt(w.ui.page!!, 4), "items loaded around the cursor")
    }
}
