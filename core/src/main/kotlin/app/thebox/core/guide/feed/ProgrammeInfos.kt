package app.thebox.core.guide.feed

import app.thebox.core.epg.ProgrammeDetail
import app.thebox.core.guide.ProgrammeInfo

/**
 * WP-C stub (docs/design/m4-player-ux.md §1.4, §3.3.3): the one `ProgrammeDetail → ProgrammeInfo` mapping, shared by
 * GuideWindowPagerImpl and ProgrammeFeedImpl (WP-C moves the pager's private `toInfo` here).
 */
fun ProgrammeDetail.toProgrammeInfo(): ProgrammeInfo = TODO("WP-C")
