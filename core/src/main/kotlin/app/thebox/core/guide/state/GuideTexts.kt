package app.thebox.core.guide.state

import app.thebox.core.guide.GroupsShortcut
import app.thebox.core.guide.HeaderChip
import app.thebox.core.guide.MenuItem
import app.thebox.core.guide.PanelButton

/**
 * English texts the state machine puts into [app.thebox.core.guide.GuideUiState] (§3.9.1, §3.18.3). One place, so
 * M5's localisation pass can swap them for resource lookups without touching the logic. Composables render the
 * enums (buttons, chips, menu items) from `strings_guide.xml`; only free text built here lives here.
 */
object GuideTexts {
    const val NO_INFORMATION = "No information"
    const val NO_GUIDE_DATA_CHANNEL = "No guide data for this channel."
    const val NO_GUIDE_DATA_TIME = "No guide data for this time."
    const val NOT_IN_CATCHUP = "Not in catch-up"
    const val LOADING = ""
    const val SEP = " · "
    const val DASH = " – "

    // Footer hints (§3.18.3)
    const val KEYS_ARROWS = "← → ↑ ↓"; const val HINT_BROWSE = "Browse"
    const val KEYS_OK = "OK"; const val HINT_WATCH = "Watch"
    const val KEYS_HOLD_OK = "Hold OK"; const val HINT_OPTIONS = "Options"
    const val KEYS_RED = "Red"; const val HINT_REMIND = "Remind"; const val HINT_FROM_START = "From start"
    const val KEYS_CH = "CH±"; const val HINT_PAGE = "Page"
    const val KEYS_DIGITS = "0–9"; const val HINT_CHANNEL = "Channel"

    // Announcements (§3.6.6: other zones announce the focused element's label)
    fun tab(i: Int): String = when (i) { 0 -> "Guide"; 1 -> "Search"; else -> "Settings" } + ", tab"
    fun chip(c: HeaderChip, source: String, day: String): String = when (c) {
        HeaderChip.GROUP -> "Group: $source, button"
        HeaderChip.NOW -> "Now, button"
        HeaderChip.DAY -> "Day: $day, button"
    }
    fun button(b: PanelButton): String = when (b) {
        PanelButton.WATCHING -> "Watching"
        PanelButton.WATCH_NOW -> "Watch now"
        PanelButton.WATCH -> "Watch"
        PanelButton.FROM_START -> "From start"
        PanelButton.REMIND -> "Remind me"
        PanelButton.REMINDER_SET -> "Reminder set"
        PanelButton.WATCH_CHANNEL_NOW -> "Watch channel now"
        PanelButton.MORE -> "More"
    } + ", button"
    fun menu(m: MenuItem): String = when (m) {
        MenuItem.WATCH -> "Watch"
        MenuItem.WATCH_FROM_START -> "Watch from start"
        MenuItem.REMIND -> "Remind me"
        MenuItem.CANCEL_REMINDER -> "Cancel reminder"
        MenuItem.ADD_FAVOURITE -> "Add to favourites"
        MenuItem.REMOVE_FAVOURITE -> "Remove from favourites"
        MenuItem.HIDE_CHANNEL -> "Hide channel"
    }
    fun shortcut(s: GroupsShortcut): String = if (s == GroupsShortcut.SEARCH) "Search" else "Settings"
}
