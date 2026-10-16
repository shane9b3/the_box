// Contract (M3 commit 0): docs/design/m3-guide.md §2.10. Verbatim; changes go through the lead.
package app.thebox.core.input

/**
 * Every bindable action (decision note 2026-10-14 "Remote button mapping", in its order, plus M3's guide
 * accelerators). M5's Button mapping screen lists the [remappable] ones. Persisted by [id]; append-only.
 */
enum class BoxAction(val id: String, val remappable: Boolean) {
    // Reserved: D-pad and OK keep navigation outside the player, Back keeps "close top layer" (decision note).
    UP("up", false), DOWN("down", false), LEFT("left", false), RIGHT("right", false),
    SELECT("select", false), BACK("back", false),
    /** Long-press OK; the Menu key is an alias (product §2.1). */
    CONTEXT_MENU("context_menu", true),
    // Decision note list
    ZAP_UP("zap_up", true), ZAP_DOWN("zap_down", true), PREVIOUS_CHANNEL("previous_channel", true),
    OPEN_GUIDE("guide", true), CHANNEL_LIST("channel_list", true), GROUPS_PANEL("groups_panel", true),
    QUICK_MENU("quick_menu", true), AUDIO("audio", true), SUBTITLES("subtitles", true), ASPECT("aspect", true),
    START_OVER("start_over", true), FAVOURITES("favourites", true), INFO_BAR("info_bar", true),
    NUMBER_ENTRY("number_entry", true), PLAY_PAUSE("play_pause", true), SEEK_BACK("seek_back", true),
    SEEK_FORWARD("seek_forward", true), JUMP_TO_LIVE("jump_to_live", true), STATS_OVERLAY("stats_overlay", true),
    SCREEN_OFF("screen_off", true), EXIT("exit", true),
    // M3 additions
    GROUP_NEXT("group_next", true), GROUP_PREV("group_prev", true),
    PAGE_UP("page_up", true), PAGE_DOWN("page_down", true),
    REMIND("remind", true), JUMP_TO_NOW("jump_to_now", true), DAY_STRIP("day_strip", true),
    /** A digit key; the digit travels in GuideEvent.Act.digit. Not remappable (numbers are numbers). */
    DIGIT("digit", false),
    // M4 additions (docs/design/m4-player-ux.md §2.9)
    PLAY("play", true), PAUSE("pause", true),
    TOGGLE_FAVOURITE("favourite_toggle", true), VIDEO_DECODER("video_decoder", true),
    PREV_PROGRAMME("prev_programme", true), NEXT_PROGRAMME("next_programme", true),
    /** Opens the scrub bar on live at the live edge (pause/rewind entry, §3.5.1). */
    TIME_SHIFT("time_shift", true),
    // M5 additions (docs/design/m5-setup-settings.md §2.12 item 1; append-only ids). WP-C binds them in DefaultKeyMap.
    OPEN_SEARCH("open_search", true), OPEN_SETTINGS("open_settings", true),
}

// (reviewed 2026-10-15, S5/F9) KeyContext is unchanged: enum class KeyContext { GUIDE, PLAYER }
enum class KeyContext { GUIDE, PLAYER }

/** Android KeyEvent fields, copied without Android types. */
data class KeyInput(
    val keyCode: Int,
    val isDown: Boolean,
    val repeatCount: Int,
    val downTimeMs: Long,
    val eventTimeMs: Long,
    val canceled: Boolean = false,
)

sealed interface KeyGesture {
    val keyCode: Int
    /** Short press: on DOWN for keys without a long binding (repeat > 0 for auto-repeat), on UP for keys with one. */
    data class Press(override val keyCode: Int, val repeat: Int) : KeyGesture
    data class LongPress(override val keyCode: Int) : KeyGesture
    /** Key released after one or more repeats (ends holds). */
    data class ReleaseAfterRepeat(override val keyCode: Int) : KeyGesture
}

interface KeyGestureDetector {
    /** [hasLong]: the key has a long-press binding in the current context. Appends 0–1 gestures to [out]. */
    fun onKey(input: KeyInput, hasLong: Boolean, out: MutableList<KeyGesture>)
    /**
     * Called by the router LONG_PRESS_MS after the DOWN of a key with a long binding, for remotes that never
     * auto-repeat OK (API notes §2.2). Appends LongPress when the key is still down and none fired yet.
     */
    fun onTimer(keyCode: Int, nowMs: Long, out: MutableList<KeyGesture>)
    /** Focus left the guide root: Compose drops the matching KeyUp (API notes §2.1), so forget every key. */
    fun reset()
}

data class KeyBinding(val keyCode: Int, val short: BoxAction?, val long: BoxAction?)

interface KeyMap {
    fun short(context: KeyContext, keyCode: Int): BoxAction?
    fun long(context: KeyContext, keyCode: Int): BoxAction?
    /** 0–9 for digit keys (incl. numpad), else −1. */
    fun digit(keyCode: Int): Int
    fun bindings(context: KeyContext): List<KeyBinding>
}

/** Android key codes used by DefaultKeyMap (values from android.view.KeyEvent; :core cannot see Android). */
object Keys {
    const val DPAD_UP = 19; const val DPAD_DOWN = 20; const val DPAD_LEFT = 21; const val DPAD_RIGHT = 22; const val DPAD_CENTER = 23
    const val BACK = 4; const val MENU = 82; const val ENTER = 66; const val NUMPAD_ENTER = 160; const val ESCAPE = 111
    const val BUTTON_A = 96; const val BUTTON_B = 97
    const val CHANNEL_UP = 166; const val CHANNEL_DOWN = 167; const val PAGE_UP = 92; const val PAGE_DOWN = 93
    const val MEDIA_PLAY_PAUSE = 85; const val MEDIA_PLAY = 126; const val MEDIA_PAUSE = 127
    const val MEDIA_FAST_FORWARD = 90; const val MEDIA_REWIND = 89; const val MEDIA_RECORD = 130
    const val DIGIT_0 = 7 /* … 9 = 16 */; const val NUMPAD_0 = 144 /* … 9 = 153 */
    const val PROG_RED = 183; const val PROG_GREEN = 184; const val PROG_YELLOW = 185; const val PROG_BLUE = 186
    const val LAST_CHANNEL = 229; const val GUIDE = 172; const val INFO = 165; const val CAPTIONS = 175
    const val MEDIA_AUDIO_TRACK = 222; const val TV_ZOOM_MODE = 255; const val SEARCH = 84
    /** (code review 2026-10-14, H-L14) CEC and Bluetooth remotes send these for ⏭/⏮. */
    const val MEDIA_NEXT = 87; const val MEDIA_PREVIOUS = 88
    // M4 (docs/design/m4-player-ux.md §2.9)
    const val MEDIA_STOP = 86
    const val MEDIA_SKIP_FORWARD = 272; const val MEDIA_SKIP_BACKWARD = 273
    const val MEDIA_STEP_FORWARD = 274; const val MEDIA_STEP_BACKWARD = 275
    // M5 (docs/design/m5-setup-settings.md §2.12 item 1): SETTINGS is bindable (OPEN_SETTINGS); the rest are reserved
    // and never bound (the framework or the OS owns them; the mapping screen refuses them).
    const val SETTINGS = 176
    const val VOLUME_UP = 24; const val VOLUME_DOWN = 25; const val VOLUME_MUTE = 164; const val MUTE = 91
    const val HOME = 3; const val POWER = 26; const val TV_POWER = 177; const val SLEEP = 223; const val WAKEUP = 224
}

/** Keys the device has actually reported (product §2.1). :ui implements (SharedPreferences `keys_seen`; writes only a newly seen code, N7). */
interface KeyCapabilities {
    val seen: kotlinx.coroutines.flow.StateFlow<Set<Int>>
    fun record(keyCode: Int)
}
