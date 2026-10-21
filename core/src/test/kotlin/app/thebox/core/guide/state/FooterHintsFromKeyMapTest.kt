package app.thebox.core.guide.state

import app.thebox.core.guide.FooterHint
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.Zone
import app.thebox.core.input.BoxAction
import app.thebox.core.input.BoxAction.*
import app.thebox.core.input.Keys
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/design/m5-setup-settings.md §3.8.4: footer hints from the effective key map ∩ the keys seen. */
class FooterHintsFromKeyMapTest {
    private fun keys(w: GuideWorld) = w.ui.footer.map { it.keys }
    private fun labels(w: GuideWorld) = w.ui.footer.map { it.label }

    @Test fun defaultsMatchTheM3Footer() {
        val w = GuideWorld()
        w.send(GuideEvent.KeysSeen(setOf(Keys.PROG_RED, Keys.CHANNEL_UP)))
        val before = w.ui.footer
        w.send(GuideEvent.KeyHints(mapOf(BoxAction.START_OVER to listOf(Keys.PROG_RED), BoxAction.ZAP_UP to listOf(Keys.CHANNEL_UP))))
        assertEquals(before, w.ui.footer, "default bindings: the same hints as M3")
    }

    @Test fun remappedRedStopsAdvertisingAndTheNewKeyIsNamed() {
        val w = GuideWorld()
        w.send(GuideEvent.KeysSeen(setOf(Keys.PROG_RED, Keys.PROG_GREEN)))
        // Red moved to another action; Green now starts over.
        w.send(GuideEvent.KeyHints(mapOf(BoxAction.START_OVER to listOf(Keys.PROG_GREEN))))
        assertFalse(GuideTexts.KEYS_RED in keys(w))
        assertTrue("Green" in keys(w))
        assertTrue(GuideTexts.HINT_FROM_START in labels(w) || GuideTexts.HINT_REMIND in labels(w))
        // Nothing bound: no hint.
        w.send(GuideEvent.KeyHints(emptyMap()))
        assertFalse(keys(w).any { it == "Green" || it == GuideTexts.KEYS_RED })
    }

    @Test fun pageHintFollowsZapUp() {
        val w = GuideWorld()
        w.send(GuideEvent.KeysSeen(setOf(Keys.CHANNEL_UP, Keys.PROG_BLUE)))
        w.send(GuideEvent.KeyHints(mapOf(BoxAction.ZAP_UP to listOf(Keys.PROG_BLUE))))
        assertTrue("Blue" in keys(w)); assertTrue(GuideTexts.HINT_PAGE in labels(w))
        assertFalse(GuideTexts.KEYS_CH in keys(w))
    }

    // ---- decision 2026-10-19 ruling 8: the exact sets, in priority order (the UI drops from the end)
    private val T = GuideTexts
    private val browse = FooterHint(T.KEYS_ARROWS, T.HINT_BROWSE)
    private val watch = FooterHint(T.KEYS_OK, T.HINT_WATCH)
    private val leftGroups = FooterHint(T.KEYS_LEFT, T.HINT_GROUPS)
    private val backGroups = FooterHint(T.KEYS_BACK, T.HINT_GROUPS)
    private val backNow = FooterHint(T.KEYS_BACK, T.HINT_NOW)
    private val yellow = FooterHint(T.KEYS_YELLOW, T.HINT_GROUPS)
    private val options = FooterHint(T.KEYS_HOLD_OK, T.HINT_OPTIONS)
    private val full = FooterHint(T.KEYS_HOLD_BACK, T.HINT_FULL_SCREEN)

    @Test fun gridHomeLeftBrowses() {
        val w = GuideWorld() // tuned row 0, 7-day catch-up
        assertEquals(listOf(browse, watch, backGroups, options, full), w.ui.footer)
        w.send(GuideEvent.KeysSeen(setOf(Keys.PROG_RED)))
        assertFalse(yellow in w.ui.footer, "home: Back already opens Groups, Yellow is not named")
        assertEquals(listOf(browse, watch, backGroups, options, full, FooterHint(T.KEYS_RED, T.HINT_FROM_START)), w.ui.footer)
    }

    @Test fun gridHomeLeftOpensGroups() {
        val w = GuideWorld(tunedRow = 1) // no catch-up: Left on now opens Groups
        assertEquals(listOf(browse, watch, leftGroups, backGroups, options, full), w.ui.footer)
        val idle = GuideWorld(startTuned = false, tunedRow = 1)
        idle.press(DOWN)
        // Nothing tuned: home is the focused row at now, and there is no Hold Back.
        assertEquals(listOf(browse, watch, leftGroups, backGroups, options), idle.ui.footer)
    }

    @Test fun gridNotHomeLeftBrowses() {
        val w = GuideWorld()
        w.press(RIGHT)
        assertEquals(listOf(browse, watch, backNow, options, full), w.ui.footer, "no colour key seen: no Yellow")
        w.send(GuideEvent.KeysSeen(setOf(Keys.PROG_GREEN)))
        // Review 2026-10-19 L2: the fallback applies the family to Red as well (as GuideViewModel.keyHints does).
        assertEquals(listOf(browse, watch, backNow, yellow, options, full, FooterHint(GuideTexts.KEYS_RED, GuideTexts.HINT_REMIND)), w.ui.footer, "colour family: Green seen names Yellow and Red")
        w.send(GuideEvent.KeysSeen(setOf(Keys.CHANNEL_UP)))
        assertFalse(yellow in w.ui.footer)
    }

    @Test fun gridNotHomeLeftOpensGroups() {
        val w = GuideWorld()
        w.send(GuideEvent.KeysSeen(setOf(Keys.PROG_YELLOW)))
        w.press(DOWN) // row 1, no catch-up, on now
        assertEquals(listOf(browse, watch, leftGroups, backNow, options, full, FooterHint(GuideTexts.KEYS_RED, GuideTexts.HINT_FROM_START)), w.ui.footer, "← Groups, so no Yellow; Red by the colour family")
    }

    @Test fun gridEmptySource() {
        val w = GuideWorld()
        w.press(FAVOURITES) // no favourites: an empty lineup
        assertEquals(null, w.machine.frame.focus)
        assertEquals(listOf(leftGroups, backGroups), w.ui.footer)
    }

    @Test fun groupsPanelSets() {
        val w = GuideWorld()
        w.press(BACK)
        assertEquals(listOf(FooterHint(T.KEYS_UD, T.HINT_CHOOSE), FooterHint(T.KEYS_OK, T.HINT_OPEN), FooterHint(T.KEYS_BACK, T.HINT_TOP), FooterHint(T.KEYS_RIGHT, T.HINT_GUIDE)), w.ui.footer)
        w.press(RIGHT)
        w.press(GROUPS_PANEL)
        assertEquals(listOf(FooterHint(T.KEYS_UD, T.HINT_CHOOSE), FooterHint(T.KEYS_OK, T.HINT_OPEN), FooterHint(T.KEYS_BACK, T.HINT_GUIDE)), w.ui.footer)
        w.press(BACK)
        assertEquals(listOf(browse, watch, backGroups, options, full), w.ui.footer, "closed: the grid set again")
    }

    @Test fun tabsSet() {
        val w = GuideWorld()
        w.press(BACK); w.press(BACK)
        assertEquals(Zone.TABS, w.ui.zone)
        val tabs = listOf(FooterHint(T.KEYS_LR, T.HINT_CHOOSE), FooterHint(T.KEYS_OK, T.HINT_OPEN), FooterHint(T.KEYS_BACK, T.HINT_EXIT))
        assertEquals(tabs, w.ui.footer)
        w.press(RIGHT); assertEquals(tabs, w.ui.footer); w.press(LEFT); w.press(LEFT); assertEquals(tabs, w.ui.footer)
    }

    @Test fun panelAndHeaderKeepTheM3Set() {
        val w = GuideWorld()
        w.press(UP)
        assertEquals(listOf(browse, watch, options, full), w.ui.footer)
        w.press(UP)
        assertEquals(Zone.PANEL, w.ui.zone)
        assertEquals(listOf(browse, watch, options, full), w.ui.footer)
    }

    /** Ruling 4 through the key map: the bound key is named, and nothing when Groups has no seen key. */
    @Test fun groupsKeyFollowsTheKeyMap() {
        val w = GuideWorld()
        w.press(RIGHT)
        w.send(GuideEvent.KeyHints(mapOf(BoxAction.GROUPS_PANEL to listOf(Keys.PROG_YELLOW))))
        assertTrue(yellow in w.ui.footer)
        w.send(GuideEvent.KeyHints(mapOf(BoxAction.GROUPS_PANEL to listOf(Keys.PROG_BLUE))))
        assertTrue(FooterHint("Blue", T.HINT_GROUPS) in w.ui.footer)
        w.send(GuideEvent.KeyHints(emptyMap()))
        assertFalse(w.ui.footer.any { it.label == T.HINT_GROUPS })
        w.press(BACK) // home
        w.send(GuideEvent.KeyHints(mapOf(BoxAction.GROUPS_PANEL to listOf(Keys.PROG_YELLOW))))
        assertFalse(yellow in w.ui.footer, "home: Back · Groups is enough")
    }
}
