package app.thebox.core.guide.state

import app.thebox.core.guide.DayItem
import app.thebox.core.guide.GuideDataWindow
import app.thebox.core.guide.TimeLabels

/** WP-D stub (docs/design/m3-guide.md §1.5, §3.3.4, §3.5): earliest/latest browsable minute and the day strip model. */
class GuideLimits(private val labels: TimeLabels) {
    fun earliestMin(nowMin: Int, archiveDaysUpperBound: Int, window: GuideDataWindow): Int = TODO("WP-D")
    fun latestMin(nowMin: Int, window: GuideDataWindow): Int = TODO("WP-D")
    fun days(nowMin: Int, archiveDaysUpperBound: Int, window: GuideDataWindow): List<DayItem> = TODO("WP-D")
}
