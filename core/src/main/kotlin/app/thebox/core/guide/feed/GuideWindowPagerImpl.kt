package app.thebox.core.guide.feed

import app.thebox.core.epg.EpgGenerationInfo
import app.thebox.core.epg.EpgStore
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideMetrics
import app.thebox.core.guide.GuideRows
import app.thebox.core.guide.GuideTextMeasurer
import app.thebox.core.guide.GuideWindowData
import app.thebox.core.guide.GuideWindowPager
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.RowCellsBuilder
import app.thebox.core.guide.TimeLabels
import kotlinx.coroutines.flow.StateFlow

/** WP-B stub (docs/design/m3-guide.md §1.3, §3.2.3): coverage, prefetch, the `box-guide-epg` thread, snapshots. */
class GuideWindowPagerImpl(
    private val store: EpgStore,
    private val rows: GuideRows,
    private val metrics: () -> GuideMetrics?,
    private val measurer: () -> GuideTextMeasurer,
    private val builder: RowCellsBuilder,
    private val labels: TimeLabels,
) : GuideWindowPager {
    override val snapshots: StateFlow<GuideWindowData?> get() = TODO("WP-B")
    override val generation: StateFlow<EpgGenerationInfo?> get() = TODO("WP-B")
    override fun ensure(topRow: Int, windowStartMin: Int, holdDirection: Int): Unit = TODO("WP-B")
    override fun invalidate(): Unit = TODO("WP-B")
    override suspend fun info(channel: GuideChannel, rawStartMin: Int): ProgrammeInfo? = TODO("WP-B")
    override suspend fun at(channel: GuideChannel, atMin: Int): ProgrammeInfo? = TODO("WP-B")
    override fun close(): Unit = TODO("WP-B")
}
