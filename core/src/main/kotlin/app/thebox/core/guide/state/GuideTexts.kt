package app.thebox.core.guide.state

import app.thebox.core.guide.GroupsShortcut
import app.thebox.core.guide.HeaderChip
import app.thebox.core.guide.MenuItem

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

    // Announcements (§3.6.6: other zones announce the focused element's label)
    fun tab(i: Int): String = when (i) { 0 -> "Guide"; 1 -> "Search"; 2 -> "Sports"; else -> "Settings" } + ", tab"
    fun chip(c: HeaderChip, source: String, day: String): String = when (c) {
        HeaderChip.GROUP -> "Group: $source, button"
        HeaderChip.NOW -> "Now, button"
        HeaderChip.DAY -> "Day: $day, button"
    }
    fun menu(m: MenuItem): String = when (m) {
        MenuItem.WATCH -> "Watch"
        MenuItem.WATCH_FROM_START -> "Watch from start"
        MenuItem.REMIND -> "Remind me"
        MenuItem.CANCEL_REMINDER -> "Cancel reminder"
        MenuItem.ADD_FAVOURITE -> "Add to favourites"
        MenuItem.REMOVE_FAVOURITE -> "Remove from favourites"
        MenuItem.HIDE_CHANNEL -> "Hide channel"
        // M5 (announcements only; WP-C/WP-A own the visible labels)
        MenuItem.LOCK_CHANNEL -> "Lock channel"
        MenuItem.UNLOCK_CHANNEL -> "Unlock channel"
        MenuItem.FIX_GUIDE_DATA -> "Fix guide data"
        MenuItem.CHANNEL_OPTIONS -> "Channel options"
    }
    fun shortcut(s: GroupsShortcut): String = if (s == GroupsShortcut.SEARCH) "Search" else "Settings"
}
