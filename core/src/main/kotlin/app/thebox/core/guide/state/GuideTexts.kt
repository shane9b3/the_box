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

    // (Event channels commit 0, docs/design/m6-event-channels.md §9.2; frozen) An event read from the channel name (§6).
    /** The panel's time line on an `EVENT_NAME` cell: "8:00 PM – about 11:00 PM · From the channel name" (U-E-N2). */
    const val FROM_NAME = "From the channel name"
    /** Before the estimated end time; carries its own trailing space. */
    const val ABOUT = "about "
    /** The synthesised description of an event with no guide listing (§6.2). */
    const val EVENT_FROM_NAME = "From the channel name: your guide has no listing for this channel. The end time is estimated."
    /** Appended (after a space) to [EVENT_FROM_NAME] when the event's zone was assumed. */
    const val EVENT_ZONE_ASSUMED = "The name has no time zone, so the time may be off."
    /** After an event's estimated end, for `EventConstants.EVENT_MAY_BE_ON_MIN` (§6.3). */
    const val EVENT_MAY_BE_ON = "The end time was estimated from the channel name, so this event may still be on."
    const val EVENT_ENDED = "This event has ended. The channel's next event appears when your playlist updates, or refresh the playlist in Settings › Playlists."
    /** The panel title over an empty slot or the time after an event (§6.3). */
    const val NO_EVENT = "No event scheduled"
    const val NO_EVENT_NEXT = "Nothing is scheduled on this channel. Its next event appears when your playlist updates, or refresh the playlist in Settings › Playlists."
    /** Before an event's start: "Next on this channel: Montreal @ Colorado at 9:30 PM." */
    fun nextEvent(title: String, time: String) = "Next on this channel: $title at $time."
    /** (Code review 2026-11-25, UI m2) Not today: [day] is "tomorrow" or a day label ("Wed 26 Nov"). */
    fun nextEvent(title: String, time: String, day: String?) =
        if (day == null) nextEvent(title, time) else "Next on this channel: $title, $day at $time."
    const val TOMORROW_WORD = "tomorrow"

    // Announcements (§3.6.6: other zones announce the focused element's label)
    fun tab(i: Int): String = when (i) { 0 -> "Guide"; 1 -> "Search"; 2 -> "Sports"; 3 -> "Weather"; else -> "Settings" } + ", tab"
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
