package app.thebox.core.guide.layout

import app.thebox.core.guide.GuideMetrics
import app.thebox.core.guide.GuideMetricsFactory

/** WP-A stub (docs/design/m3-guide.md §1.2, §3.1): design px → device px, pixel-snapped. */
class GuideMetricsFactoryImpl : GuideMetricsFactory {
    override fun create(windowWidthPx: Int, windowHeightPx: Int): GuideMetrics = TODO("WP-A")
}
