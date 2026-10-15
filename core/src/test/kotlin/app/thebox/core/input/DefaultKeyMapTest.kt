package app.thebox.core.input

import app.thebox.core.input.BoxAction.*
import app.thebox.core.input.KeyContext.GUIDE
import app.thebox.core.input.KeyContext.PLAYER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every row of §3.18.2. */
class DefaultKeyMapTest {
    private val m = DefaultKeyMap()

    private fun both(code: Int, a: BoxAction) { assertEquals(a, m.short(GUIDE, code), "guide $code"); assertEquals(a, m.short(PLAYER, code), "player $code") }

    @Test fun table() {
        both(Keys.DPAD_UP, UP); both(Keys.DPAD_DOWN, DOWN); both(Keys.DPAD_LEFT, LEFT); both(Keys.DPAD_RIGHT, RIGHT)
        assertNull(m.long(GUIDE, Keys.DPAD_LEFT)); assertNull(m.long(GUIDE, Keys.DPAD_RIGHT))
        assertEquals(GROUP_PREV, m.long(PLAYER, Keys.DPAD_LEFT)); assertEquals(GROUP_NEXT, m.long(PLAYER, Keys.DPAD_RIGHT))
        for (c in intArrayOf(Keys.DPAD_CENTER, Keys.ENTER, Keys.NUMPAD_ENTER, Keys.BUTTON_A)) {
            both(c, SELECT); assertEquals(CONTEXT_MENU, m.long(GUIDE, c)); assertEquals(CONTEXT_MENU, m.long(PLAYER, c))
        }
        both(Keys.MENU, CONTEXT_MENU)
        both(Keys.BACK, BACK); both(Keys.ESCAPE, BACK); both(Keys.BUTTON_B, BACK)
        both(Keys.CHANNEL_UP, ZAP_UP); both(Keys.CHANNEL_DOWN, ZAP_DOWN)
        assertEquals(PAGE_UP, m.short(GUIDE, Keys.PAGE_UP)); assertEquals(PAGE_DOWN, m.short(GUIDE, Keys.PAGE_DOWN))
        assertEquals(ZAP_UP, m.short(PLAYER, Keys.PAGE_UP)); assertEquals(ZAP_DOWN, m.short(PLAYER, Keys.PAGE_DOWN))
        both(Keys.MEDIA_PLAY_PAUSE, PLAY_PAUSE); both(Keys.MEDIA_PLAY, PLAY_PAUSE); both(Keys.MEDIA_PAUSE, PLAY_PAUSE)
        both(Keys.MEDIA_FAST_FORWARD, SEEK_FORWARD); both(Keys.MEDIA_REWIND, SEEK_BACK)
        for (i in 0..9) { both(Keys.DIGIT_0 + i, DIGIT); both(Keys.NUMPAD_0 + i, DIGIT) }
        both(Keys.PROG_RED, START_OVER); both(Keys.PROG_GREEN, FAVOURITES); both(Keys.PROG_YELLOW, AUDIO); both(Keys.PROG_BLUE, PREVIOUS_CHANNEL)
        both(Keys.LAST_CHANNEL, PREVIOUS_CHANNEL); both(Keys.GUIDE, OPEN_GUIDE); both(Keys.INFO, INFO_BAR)
        both(Keys.CAPTIONS, SUBTITLES); both(Keys.MEDIA_AUDIO_TRACK, AUDIO); both(Keys.TV_ZOOM_MODE, ASPECT)
        assertEquals(REMIND, m.short(GUIDE, Keys.MEDIA_RECORD)); assertNull(m.short(PLAYER, Keys.MEDIA_RECORD))
        // code review 2026-10-14 (H-S4/H-L14): ⏭/⏮ zap in the player, unbound in the guide
        assertEquals(ZAP_DOWN, m.short(PLAYER, Keys.MEDIA_NEXT)); assertEquals(ZAP_UP, m.short(PLAYER, Keys.MEDIA_PREVIOUS))
        assertNull(m.short(GUIDE, Keys.MEDIA_NEXT)); assertNull(m.short(GUIDE, Keys.MEDIA_PREVIOUS))
        assertNull(m.short(GUIDE, Keys.SEARCH), "SEARCH unbound in M3 (§8 Q3)")
        assertNull(m.short(GUIDE, 24), "volume is never bound")
    }

    @Test fun onlyOkFamilyAndPlayerLeftRightHaveLongBindings() {
        val guideLong = m.bindings(GUIDE).filter { it.long != null }.map { it.keyCode }.toSet()
        assertEquals(setOf(Keys.DPAD_CENTER, Keys.ENTER, Keys.NUMPAD_ENTER, Keys.BUTTON_A), guideLong)
        val playerLong = m.bindings(PLAYER).filter { it.long != null }.map { it.keyCode }.toSet()
        assertEquals(guideLong + setOf(Keys.DPAD_LEFT, Keys.DPAD_RIGHT), playerLong)
    }

    @Test fun digits() {
        for (i in 0..9) { assertEquals(i, m.digit(Keys.DIGIT_0 + i)); assertEquals(i, m.digit(Keys.NUMPAD_0 + i)) }
        assertEquals(-1, m.digit(Keys.DPAD_UP))
    }

    @Test fun everyRemappableDecisionNoteActionExists() {
        // decision note 2026-10-14 "Remote button mapping" list
        val ids = BoxAction.entries.filter { it.remappable }.map { it.id }.toSet()
        for (id in listOf("zap_up", "zap_down", "previous_channel", "guide", "channel_list", "groups_panel", "quick_menu", "audio",
            "subtitles", "aspect", "start_over", "favourites", "info_bar", "number_entry", "play_pause", "seek_back",
            "seek_forward", "jump_to_live", "stats_overlay", "screen_off", "exit")) assertTrue(id in ids, id)
    }
}
