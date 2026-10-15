package app.thebox.core.full.state

import app.thebox.core.full.QuickItem
import app.thebox.core.full.TrackMenu
import app.thebox.core.guide.GuideChannel

/** WP-A stub (docs/design/m4-player-ux.md §1.2, §3.4.1; owner ruling 2026-10-15): which `QuickItem`s show, in order. */
object QuickBarModel {
    /** [favourite]: the queryFavourite answer (null until it arrives). */
    fun items(
        menu: TrackMenu,
        archiveCapable: Boolean,
        inArchive: Boolean,
        nowProgramme: Boolean,
        favourite: Boolean?,
        previous: GuideChannel?,
    ): List<QuickItem> = TODO("WP-A")
}
