package app.thebox.core.input

import app.thebox.core.input.BoxAction.*

/**
 * The default bindings (docs/design/m3-guide.md §3.18.2; decision note 2026-10-14 "Remote button mapping"). M5's
 * mapping screen edits them. Only the DPAD_CENTER family (both contexts) and, in PLAYER, DPAD_LEFT/RIGHT have long
 * bindings, so every other key acts on DOWN with auto-repeat (lowest latency for D-pad browsing).
 *
 * SEARCH was unbound in M3 (§8 Q3); M5 binds it to OPEN_SEARCH in the guide only (docs/design/m5-setup-settings.md
 * §2.12 item 1, §8 Q15: the player leaves it unbound, bindable) and SETTINGS to OPEN_SETTINGS in both contexts.
 * MEDIA_RECORD is REMIND in the guide only (v1; Record in v2).
 *
 * M4 (docs/design/m4-player-ux.md §3.1.7; owner ruling 2026-10-15): in PLAYER, CH+, PAGE_UP and ⏭ are ZAP_UP (+1, the
 * higher number); MEDIA_PLAY/MEDIA_PAUSE are PLAY/PAUSE; MEDIA_STOP is JUMP_TO_LIVE; skip/step keys seek. `KeyContext`
 * stays {GUIDE, PLAYER} (S5): the router drops LEFT/RIGHT's long binding while `GuideStateMachine.horizontalRepeat`.
 */
class DefaultKeyMap : KeyMap {
    private val guideShort = HashMap<Int, BoxAction>()
    private val guideLong = HashMap<Int, BoxAction>()
    private val playerShort = HashMap<Int, BoxAction>()
    private val playerLong = HashMap<Int, BoxAction>()

    init {
        fun both(code: Int, a: BoxAction) { guideShort[code] = a; playerShort[code] = a }
        both(Keys.DPAD_UP, UP); both(Keys.DPAD_DOWN, DOWN); both(Keys.DPAD_LEFT, LEFT); both(Keys.DPAD_RIGHT, RIGHT)
        playerLong[Keys.DPAD_LEFT] = GROUP_PREV; playerLong[Keys.DPAD_RIGHT] = GROUP_NEXT
        for (c in OK_KEYS) { both(c, SELECT); guideLong[c] = CONTEXT_MENU; playerLong[c] = CONTEXT_MENU }
        both(Keys.MENU, CONTEXT_MENU)
        for (c in intArrayOf(Keys.BACK, Keys.ESCAPE, Keys.BUTTON_B)) both(c, BACK)
        // Owner ruling 2026-10-17: holding Back in the guide returns to the full-screen player (PLAY = go FULL there).
        for (c in intArrayOf(Keys.BACK, Keys.BUTTON_B)) guideLong[c] = PLAY
        both(Keys.CHANNEL_UP, ZAP_UP); both(Keys.CHANNEL_DOWN, ZAP_DOWN)
        guideShort[Keys.PAGE_UP] = PAGE_UP; guideShort[Keys.PAGE_DOWN] = PAGE_DOWN
        playerShort[Keys.PAGE_UP] = ZAP_UP; playerShort[Keys.PAGE_DOWN] = ZAP_DOWN
        // (code review 2026-10-14, H-S4/H-L14) ⏭/⏮ zap in the player; in the guide they stay unbound (media session).
        // (M4, reviewed 2026-10-15, S6; owner ruling) In FULL ZAP_UP = +1 (the higher number), so ⏭ follows CH+.
        playerShort[Keys.MEDIA_NEXT] = ZAP_UP; playerShort[Keys.MEDIA_PREVIOUS] = ZAP_DOWN
        // M4 §3.1.7: MEDIA_PLAY / MEDIA_PAUSE split in the player; the guide keeps PLAY_PAUSE for all three.
        both(Keys.MEDIA_PLAY_PAUSE, PLAY_PAUSE)
        guideShort[Keys.MEDIA_PLAY] = PLAY_PAUSE; guideShort[Keys.MEDIA_PAUSE] = PLAY_PAUSE
        playerShort[Keys.MEDIA_PLAY] = PLAY; playerShort[Keys.MEDIA_PAUSE] = PAUSE
        both(Keys.MEDIA_FAST_FORWARD, SEEK_FORWARD); both(Keys.MEDIA_REWIND, SEEK_BACK)
        // M4 §3.1.7 (API notes §5, §8): keys that were unbound. PLAYER only; the guide leaves them to the media session.
        playerShort[Keys.MEDIA_STOP] = JUMP_TO_LIVE
        playerShort[Keys.MEDIA_SKIP_FORWARD] = SEEK_FORWARD; playerShort[Keys.MEDIA_SKIP_BACKWARD] = SEEK_BACK
        playerShort[Keys.MEDIA_STEP_FORWARD] = SEEK_FORWARD; playerShort[Keys.MEDIA_STEP_BACKWARD] = SEEK_BACK
        for (i in 0..9) { both(Keys.DIGIT_0 + i, DIGIT); both(Keys.NUMPAD_0 + i, DIGIT) }
        both(Keys.PROG_RED, START_OVER); both(Keys.PROG_GREEN, FAVOURITES); both(Keys.PROG_YELLOW, AUDIO); both(Keys.PROG_BLUE, PREVIOUS_CHANNEL)
        // Decision 2026-10-19 ruling 4: Yellow opens Groups in the guide; the player keeps Audio (AUDIO is player-only).
        guideShort[Keys.PROG_YELLOW] = GROUPS_PANEL
        both(Keys.LAST_CHANNEL, PREVIOUS_CHANNEL)
        both(Keys.GUIDE, OPEN_GUIDE)
        both(Keys.INFO, INFO_BAR)
        both(Keys.CAPTIONS, SUBTITLES); both(Keys.MEDIA_AUDIO_TRACK, AUDIO); both(Keys.TV_ZOOM_MODE, ASPECT)
        guideShort[Keys.MEDIA_RECORD] = REMIND
        // M5 (§2.12 item 1)
        guideShort[Keys.SEARCH] = OPEN_SEARCH
        both(Keys.SETTINGS, OPEN_SETTINGS)
    }

    override fun short(context: KeyContext, keyCode: Int): BoxAction? =
        (if (context == KeyContext.GUIDE) guideShort else playerShort)[keyCode]

    override fun long(context: KeyContext, keyCode: Int): BoxAction? =
        (if (context == KeyContext.GUIDE) guideLong else playerLong)[keyCode]

    override fun digit(keyCode: Int): Int = when (keyCode) {
        in Keys.DIGIT_0..Keys.DIGIT_0 + 9 -> keyCode - Keys.DIGIT_0
        in Keys.NUMPAD_0..Keys.NUMPAD_0 + 9 -> keyCode - Keys.NUMPAD_0
        else -> -1
    }

    override fun bindings(context: KeyContext): List<KeyBinding> {
        val s = if (context == KeyContext.GUIDE) guideShort else playerShort
        val l = if (context == KeyContext.GUIDE) guideLong else playerLong
        return (s.keys + l.keys).sorted().map { KeyBinding(it, s[it], l[it]) }
    }

    companion object {
        val OK_KEYS = intArrayOf(Keys.DPAD_CENTER, Keys.ENTER, Keys.NUMPAD_ENTER, Keys.BUTTON_A)
        /** Red, Green, Yellow, Blue. */
        val COLOUR_KEYS: Set<Int> = setOf(Keys.PROG_RED, Keys.PROG_GREEN, Keys.PROG_YELLOW, Keys.PROG_BLUE)

        /**
         * (decision 2026-10-19 ruling 4) For the footer the four colour keys are one family: once any was seen, all four
         * count as seen, so a remote whose Yellow was never pressed still learns that Yellow opens Groups.
         */
        fun seenWithColourFamily(seen: Set<Int>): Set<Int> = if (seen.any { it in COLOUR_KEYS }) seen + COLOUR_KEYS else seen
    }
}
