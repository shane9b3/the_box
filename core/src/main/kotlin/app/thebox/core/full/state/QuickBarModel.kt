package app.thebox.core.full.state

import app.thebox.core.full.PickerChoice
import app.thebox.core.full.QuickItem
import app.thebox.core.full.TrackMenu
import app.thebox.core.guide.GuideChannel

/**
 * (Score bug WP-4b, docs/design/m6-score-bug.md §6, U-S5) Where "Score bug" sits: HIDDEN while Show Sports is off;
 * AFTER_SUBTITLES (after Subtitles, or after Picture shape without subtitles) while a game is followed; BEFORE_SETTINGS
 * otherwise. Settings stays last.
 */
enum class ScoreBugItem { HIDDEN, AFTER_SUBTITLES, BEFORE_SETTINGS }

/**
 * Which `QuickItem`s show, in order (docs/design/m4-player-ux.md §3.4.1; owner ruling 2026-10-15; M6
 * docs/design/m6-timeshift.md §11.3, U-B3, lead ruling 1).
 *
 * | State | Order |
 * |---|---|
 * | Live (every live channel: TS, HLS, buffer off, no space) | **Pause** · Audio · Picture shape · Subtitles · Favourite · Start over · Previous · Stream info · Decoder · Screen off · (Sources) · Settings |
 * | Behind live (LOCAL or provider time-shift, catch-up) | **Back to live** · Audio · Picture shape · Subtitles · Favourite · Previous · Stream info · Decoder · Screen off · (Sources) · Settings |
 *
 * `QuickItem.TIME_SHIFT` keeps its id (append-only) and is labelled "Pause"; the display order is no longer the enum
 * order (the M6 amendment to decisions/2026-10-15-player-keys-and-quick-bar.md). Settings is always last.
 */
object QuickBarModel {
    /**
     * [favourite]: the queryFavourite answer (null until it arrives: neither favourite item). [inArchive]: archive or
     * time-shift video (Back to live first, no Start over, no Pause). [startOverCapable]: the channel has catch-up, or
     * the time-shift buffer covers the start of the programme on now (§11.3). [nowProgramme]: a programme airs now on
     * the tuned channel. [live]: a live channel is tuned (Pause is offered; OK pauses it, or freezes with the reason).
     */
    fun items(
        menu: TrackMenu,
        startOverCapable: Boolean,
        inArchive: Boolean,
        nowProgramme: Boolean,
        favourite: Boolean?,
        previous: GuideChannel?,
        live: Boolean = true,
        scoreBug: ScoreBugItem = ScoreBugItem.HIDDEN,
    ): List<QuickItem> {
        val out = ArrayList<QuickItem>(QuickItem.entries.size)
        if (inArchive) out += QuickItem.JUMP_TO_LIVE else if (live) out += QuickItem.TIME_SHIFT
        val selectedUnsupported = menu.audio.any { it.selected && !it.supported }
        if (menu.audio.size >= 2 || selectedUnsupported) out += QuickItem.AUDIO
        out += QuickItem.ASPECT
        if (menu.text.isNotEmpty()) out += QuickItem.SUBTITLES
        if (scoreBug == ScoreBugItem.AFTER_SUBTITLES) out += QuickItem.SCORE_BUG // (score bug U-S5) right after Subtitles / Picture shape
        when (favourite) {
            false -> out += QuickItem.FAVOURITE_ADD
            true -> out += QuickItem.FAVOURITE_REMOVE
            null -> Unit
        }
        if (!inArchive && startOverCapable && nowProgramme) out += QuickItem.START_OVER
        if (previous != null) out += QuickItem.PREVIOUS_CHANNEL
        out += QuickItem.STREAM_INFO
        out += QuickItem.VIDEO_DECODER
        out += QuickItem.SCREEN_OFF
        // (M5 integration, §3.6.2, E23) The Sources picker when the playlist has backup servers.
        if (menu.servers.size >= 2) out += QuickItem.SOURCES
        if (scoreBug == ScoreBugItem.BEFORE_SETTINGS) out += QuickItem.SCORE_BUG // (score bug U-S5) not following: just before Settings
        out += QuickItem.SETTINGS
        return out
    }
}

/**
 * (Score bug WP-4b, docs/design/m6-score-bug.md section 6, U-N9) False for the sheet's "Today" divider and "Loading games"
 * line: focus skips them and OK does nothing. Every other row of every picker can take focus.
 */
val PickerChoice.selectable: Boolean
    get() = this !is PickerChoice.ScoreTodayDivider && this !is PickerChoice.ScoreLoading
