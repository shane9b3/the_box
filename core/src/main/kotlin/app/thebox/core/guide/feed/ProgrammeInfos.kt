package app.thebox.core.guide.feed

import app.thebox.core.epg.ProgrammeDetail
import app.thebox.core.guide.ProgrammeInfo

/**
 * The one `ProgrammeDetail → ProgrammeInfo` mapping (docs/design/m4-player-ux.md §1.4, §3.3.3), shared by
 * GuideWindowPagerImpl and ProgrammeFeedImpl. Times are display minutes (the detail already carries the shift).
 */
fun ProgrammeDetail.toProgrammeInfo(): ProgrammeInfo = ProgrammeInfo(
    rawStartMin = rawStartMin, startMin = startMin, endMin = endMin, title = title, subtitle = subtitle,
    description = description, genre = categories.firstOrNull(), episodeLabel = episodeLabel, flags = flags, titleHash = titleHash,
    iconUrl = iconUrl,
)
