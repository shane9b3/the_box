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
        both(Keys.MEDIA_PLAY_PAUSE, PLAY_PAUSE)
        // M4 §3.1.7: MEDIA_PLAY/MEDIA_PAUSE split in the player; the guide keeps PLAY_PAUSE for all three
        assertEquals(PLAY_PAUSE, m.short(GUIDE, Keys.MEDIA_PLAY)); assertEquals(PLAY_PAUSE, m.short(GUIDE, Keys.MEDIA_PAUSE))
        assertEquals(PLAY, m.short(PLAYER, Keys.MEDIA_PLAY)); assertEquals(PAUSE, m.short(PLAYER, Keys.MEDIA_PAUSE))
        both(Keys.MEDIA_FAST_FORWARD, SEEK_FORWARD); both(Keys.MEDIA_REWIND, SEEK_BACK)
        for (i in 0..9) { both(Keys.DIGIT_0 + i, DIGIT); both(Keys.NUMPAD_0 + i, DIGIT) }
        both(Keys.PROG_RED, START_OVER); both(Keys.PROG_GREEN, FAVOURITES); both(Keys.PROG_YELLOW, AUDIO); both(Keys.PROG_BLUE, PREVIOUS_CHANNEL)
        both(Keys.LAST_CHANNEL, PREVIOUS_CHANNEL); both(Keys.GUIDE, OPEN_GUIDE); both(Keys.INFO, INFO_BAR)
        both(Keys.CAPTIONS, SUBTITLES); both(Keys.MEDIA_AUDIO_TRACK, AUDIO); both(Keys.TV_ZOOM_MODE, ASPECT)
        assertEquals(REMIND, m.short(GUIDE, Keys.MEDIA_RECORD)); assertNull(m.short(PLAYER, Keys.MEDIA_RECORD))
        // code review 2026-10-14 (H-S4/H-L14): ⏭/⏮ zap in the player, unbound in the guide; M4 S6: ⏭ = ZAP_UP (+1)
        assertEquals(ZAP_UP, m.short(PLAYER, Keys.MEDIA_NEXT)); assertEquals(ZAP_DOWN, m.short(PLAYER, Keys.MEDIA_PREVIOUS))
        assertNull(m.short(GUIDE, Keys.MEDIA_NEXT)); assertNull(m.short(GUIDE, Keys.MEDIA_PREVIOUS))
        // M5 (docs/design/m5-setup-settings.md §2.12 item 1, §8 Q15): Search opens Search in the guide only; Settings in both.
        assertEquals(OPEN_SEARCH, m.short(GUIDE, Keys.SEARCH)); assertNull(m.short(PLAYER, Keys.SEARCH), "the player leaves SEARCH unbound")
        both(Keys.SETTINGS, OPEN_SETTINGS)
        assertNull(m.short(GUIDE, 24), "volume is never bound")
    }

    /** M4 §3.1.7 new PLAYER bindings; the guide leaves these keys unbound (media session). */
    @Test fun m4PlayerBindings() {
        assertEquals(JUMP_TO_LIVE, m.short(PLAYER, Keys.MEDIA_STOP)); assertNull(m.short(GUIDE, Keys.MEDIA_STOP))
        for (c in intArrayOf(Keys.MEDIA_SKIP_FORWARD, Keys.MEDIA_STEP_FORWARD)) { assertEquals(SEEK_FORWARD, m.short(PLAYER, c)); assertNull(m.short(GUIDE, c)) }
        for (c in intArrayOf(Keys.MEDIA_SKIP_BACKWARD, Keys.MEDIA_STEP_BACKWARD)) { assertEquals(SEEK_BACK, m.short(PLAYER, c)); assertNull(m.short(GUIDE, c)) }
        for (c in intArrayOf(Keys.MEDIA_STOP, Keys.MEDIA_SKIP_FORWARD, Keys.MEDIA_SKIP_BACKWARD, Keys.MEDIA_STEP_FORWARD, Keys.MEDIA_STEP_BACKWARD, Keys.MEDIA_PLAY, Keys.MEDIA_PAUSE)) {
            assertNull(m.long(PLAYER, c), "no long binding on $c")
        }
    }

    /** M4 S6 + owner ruling: CH+, ⏭ and PAGE_UP give the same action in PLAYER (+1); CH−, ⏮ and PAGE_DOWN the other. */
    @Test fun chPlusNextAndPageUpAgree() {
        val up = setOf(m.short(PLAYER, Keys.CHANNEL_UP), m.short(PLAYER, Keys.MEDIA_NEXT), m.short(PLAYER, Keys.PAGE_UP))
        val down = setOf(m.short(PLAYER, Keys.CHANNEL_DOWN), m.short(PLAYER, Keys.MEDIA_PREVIOUS), m.short(PLAYER, Keys.PAGE_DOWN))
        assertEquals(setOf<BoxAction?>(ZAP_UP), up)
        assertEquals(setOf<BoxAction?>(ZAP_DOWN), down)
        // GUIDE unchanged: CH± zap actions (the grid pages them towards lower/higher numbers), PAGE keys page.
        assertEquals(ZAP_UP, m.short(GUIDE, Keys.CHANNEL_UP)); assertEquals(PAGE_UP, m.short(GUIDE, Keys.PAGE_UP))
    }

    /** S5/F9: two contexts only; the M4 actions with no default key stay unbound in both. */
    @Test fun twoContextsAndUnboundM4Actions() {
        assertEquals(listOf(GUIDE, PLAYER), KeyContext.entries.toList())
        val bound = (m.bindings(GUIDE) + m.bindings(PLAYER)).flatMap { listOfNotNull(it.short, it.long) }.toSet()
        for (a in listOf(TOGGLE_FAVOURITE, VIDEO_DECODER, PREV_PROGRAMME, NEXT_PROGRAMME, TIME_SHIFT, STATS_OVERLAY, SCREEN_OFF, QUICK_MENU)) {
            assertTrue(a !in bound, "$a has no default key (quick bar / M5 mapping)")
        }
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
