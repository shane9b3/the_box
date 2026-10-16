package app.thebox.core.full.state

import app.thebox.core.full.QuickItem
import app.thebox.core.full.TrackMenu
import app.thebox.core.guide.GuideChannel

/** Which `QuickItem`s show, in order (docs/design/m4-player-ux.md §3.4.1; owner ruling 2026-10-15). */
object QuickBarModel {
    /**
     * [favourite]: the queryFavourite answer (null until it arrives: neither favourite item). [inArchive]: archive or
     * time-shift video (JUMP_TO_LIVE takes START_OVER's slot). [nowProgramme]: a programme airs now on the tuned channel.
     */
    fun items(
        menu: TrackMenu,
        archiveCapable: Boolean,
        inArchive: Boolean,
        nowProgramme: Boolean,
        favourite: Boolean?,
        previous: GuideChannel?,
    ): List<QuickItem> {
        val out = ArrayList<QuickItem>(QuickItem.entries.size)
        val selectedUnsupported = menu.audio.any { it.selected && !it.supported }
        if (menu.audio.size >= 2 || selectedUnsupported) out += QuickItem.AUDIO
        out += QuickItem.ASPECT
        if (menu.text.isNotEmpty()) out += QuickItem.SUBTITLES
        when (favourite) {
            false -> out += QuickItem.FAVOURITE_ADD
            true -> out += QuickItem.FAVOURITE_REMOVE
            null -> Unit
        }
        if (!inArchive && archiveCapable && nowProgramme) out += QuickItem.START_OVER
        if (inArchive) out += QuickItem.JUMP_TO_LIVE
        if (!inArchive && archiveCapable) out += QuickItem.TIME_SHIFT
        if (previous != null) out += QuickItem.PREVIOUS_CHANNEL
        out += QuickItem.STREAM_INFO
        out += QuickItem.VIDEO_DECODER
        out += QuickItem.SCREEN_OFF
        // (M5 integration, §3.6.2, E23) The Sources picker when the playlist has backup servers.
        if (menu.servers.size >= 2) out += QuickItem.SOURCES
        out += QuickItem.SETTINGS
        return out
    }
}
