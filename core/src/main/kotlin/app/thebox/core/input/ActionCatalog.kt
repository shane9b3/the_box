package app.thebox.core.input

import app.thebox.core.input.ActionGroup.APP
import app.thebox.core.input.ActionGroup.CHANNELS
import app.thebox.core.input.ActionGroup.GUIDE
import app.thebox.core.input.ActionGroup.PLAYER_OVERLAYS
import app.thebox.core.input.ActionGroup.WATCHING
import app.thebox.core.input.BoxAction.*

/**
 * Every remappable [BoxAction] with the contexts a binding made on the mapping screen applies in and its group on the
 * screen (docs/design/m5-setup-settings.md §3.8.1; decision note 2026-10-14). The user never picks a context: a binding
 * goes to every context of its action. Labels come from `:ui` resources by [BoxAction.id].
 */
object ActionCatalog {
    private val GP = setOf(KeyContext.GUIDE, KeyContext.PLAYER)
    private val G = setOf(KeyContext.GUIDE)
    private val P = setOf(KeyContext.PLAYER)

    /** In screen order: the five groups of §3.8.1, actions in the decision note's order within each. */
    val all: List<ActionInfo> = listOf(
        // Watching
        ActionInfo(PLAY_PAUSE, GP, WATCHING), ActionInfo(PLAY, GP, WATCHING), ActionInfo(PAUSE, P, WATCHING),
        ActionInfo(SEEK_BACK, GP, WATCHING), ActionInfo(SEEK_FORWARD, GP, WATCHING), ActionInfo(START_OVER, GP, WATCHING),
        ActionInfo(JUMP_TO_LIVE, P, WATCHING), ActionInfo(TIME_SHIFT, P, WATCHING),
        ActionInfo(PREV_PROGRAMME, GP, WATCHING), ActionInfo(NEXT_PROGRAMME, GP, WATCHING),
        // Channels
        ActionInfo(ZAP_UP, GP, CHANNELS), ActionInfo(ZAP_DOWN, GP, CHANNELS), ActionInfo(PREVIOUS_CHANNEL, GP, CHANNELS),
        ActionInfo(NUMBER_ENTRY, GP, CHANNELS), ActionInfo(CHANNEL_LIST, P, CHANNELS), ActionInfo(GROUPS_PANEL, GP, CHANNELS),
        ActionInfo(GROUP_NEXT, P, CHANNELS), ActionInfo(GROUP_PREV, P, CHANNELS), ActionInfo(FAVOURITES, GP, CHANNELS),
        ActionInfo(TOGGLE_FAVOURITE, GP, CHANNELS),
        // Guide
        ActionInfo(OPEN_GUIDE, GP, GUIDE), ActionInfo(PAGE_UP, G, GUIDE), ActionInfo(PAGE_DOWN, G, GUIDE),
        ActionInfo(JUMP_TO_NOW, G, GUIDE), ActionInfo(DAY_STRIP, G, GUIDE), ActionInfo(REMIND, G, GUIDE),
        // Player overlays
        ActionInfo(INFO_BAR, P, PLAYER_OVERLAYS), ActionInfo(QUICK_MENU, P, PLAYER_OVERLAYS), ActionInfo(AUDIO, P, PLAYER_OVERLAYS),
        ActionInfo(SUBTITLES, P, PLAYER_OVERLAYS), ActionInfo(ASPECT, P, PLAYER_OVERLAYS), ActionInfo(VIDEO_DECODER, P, PLAYER_OVERLAYS),
        ActionInfo(STATS_OVERLAY, P, PLAYER_OVERLAYS), ActionInfo(SCREEN_OFF, P, PLAYER_OVERLAYS),
        // App
        ActionInfo(CONTEXT_MENU, GP, APP), ActionInfo(OPEN_SEARCH, GP, APP), ActionInfo(OPEN_SETTINGS, GP, APP), ActionInfo(EXIT, GP, APP),
    )

    private val byAction: Map<BoxAction, ActionInfo> = all.associateBy { it.action }
    private val byId: Map<String, BoxAction> = BoxAction.entries.associateBy { it.id }

    fun info(action: BoxAction): ActionInfo? = byAction[action]

    /** The contexts a binding of [action] applies in (empty for an action that is not remappable). */
    fun contexts(action: BoxAction): Set<KeyContext> = byAction[action]?.contexts ?: emptySet()

    fun inGroup(group: ActionGroup): List<ActionInfo> = all.filter { it.group == group }

    /** [BoxAction.id] → action; null for an unknown id (an override from a newer backup is ignored, §3.8.2). */
    fun actionOf(id: String?): BoxAction? = id?.let { byId[it] }
}

/**
 * Short key names for the guide's footer hints (§3.8.4), in English like `GuideTexts` (the footer is built in :core);
 * the capture overlay names keys from `:ui`'s own string table instead.
 */
object FooterKeyNames {
    fun of(keyCode: Int): String = when (keyCode) {
        Keys.PROG_RED -> "Red"
        Keys.PROG_GREEN -> "Green"
        Keys.PROG_YELLOW -> "Yellow"
        Keys.PROG_BLUE -> "Blue"
        Keys.CHANNEL_UP, Keys.CHANNEL_DOWN -> "CH±"
        Keys.PAGE_UP, Keys.PAGE_DOWN -> "Page"
        Keys.MEDIA_RECORD -> "Rec"
        Keys.MEDIA_NEXT -> "⏭"
        Keys.MEDIA_PREVIOUS -> "⏮"
        Keys.MEDIA_PLAY_PAUSE -> "⏯"
        Keys.MEDIA_FAST_FORWARD -> "⏩"
        Keys.MEDIA_REWIND -> "⏪"
        Keys.INFO -> "Info"
        Keys.GUIDE -> "Guide"
        Keys.MENU -> "Menu"
        Keys.SEARCH -> "Search"
        Keys.SETTINGS -> "Settings"
        Keys.LAST_CHANNEL -> "Last"
        Keys.CAPTIONS -> "CC"
        Keys.MEDIA_AUDIO_TRACK -> "Audio"
        Keys.TV_ZOOM_MODE -> "Zoom"
        else -> "#$keyCode"
    }
}
