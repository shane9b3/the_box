package app.thebox.core.guide.render

import app.thebox.core.guide.CellKind
import app.thebox.core.guide.GridFocus
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideFrame
import app.thebox.core.guide.GuideImages
import app.thebox.core.guide.GuideTextMeasurer
import app.thebox.core.guide.GuideTextStyle
import app.thebox.core.guide.GuideWindowData
import app.thebox.core.guide.ReminderIndex
import app.thebox.core.guide.fakes.FakeGuideRows
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.guide.layout.GuideMetricsImpl
import app.thebox.core.guide.layout.TimeTables
import java.time.ZoneId

/**
 * The §4.1 reference frame (WP-A, docs/design/m3-guide.md §4.1, §5.1): 6 full rows + 1 partial (scrollRow = 0.5,
 * mid-animation), a 180-min window at 19:00 with now = 19:47 UTC, 30-min programmes (≈ 7 visible per row), logos
 * on (every row has a URL), one tuned row, a reminder, an archive row every 5th, the now line visible. Titles and
 * time texts are measured with [measurer] the way the pager does it off-main, so the renderer runs its real path.
 * Shared by the JVM renderer tests and the Robolectric golden/adapter tests in `:ui`.
 */
object GuideReferenceFrame {
    const val ROWS = 40
    const val TUNED_ROW = 3
    const val FOCUS_ROW = 3
    val NOW = GuideFixtures.NOW_MIN
    val WINDOW_START = GuideFixtures.floor30(NOW) - 30

    fun labels(): TimeTables = TimeTables(ZoneId.of("UTC"), clock24 = true, nowMs = { NOW * 60_000L })

    fun channels(): List<GuideChannel> = List(ROWS) { i ->
        GuideFixtures.channel(
            i,
            name = if (i % 4 == 1) "Channel $i with a very long display name" else "Channel $i",
            archiveDays = if (i % 5 == 0) 7 else 0,
            logoUrl = "logo://$i",
        )
    }

    fun data(measurer: GuideTextMeasurer, labels: TimeTables, scale: Float): GuideWindowData {
        val from = WINDOW_START - 180
        val to = WINDOW_START + 360
        val rows = GuideFixtures.syntheticRows(18, from, to, durationsMin = intArrayOf(30), noEpgRows = setOf(9))
            .mapIndexed { r, cells ->
                cells.mapIndexed { k, c ->
                    if (c.kind == CellKind.PROGRAMME && k % 3 == 1) c.copy(title = "${c.title} — an unusually long programme title") else c
                }
            }
        val d = GuideFixtures.windowData(rows, fromMin = from, toMin = to, metricsScale = scale)
        for (c in 0 until d.cellCount) {
            val t = d.cellTitle[c] ?: continue
            d.cellTitleWidth[c] = measurer.width(t, 0, t.length, GuideTextStyle.CELL_TITLE) * (scale / measurer.scale)
            val time = labels.hm(d.cellStart[c]) + " – " + labels.hm(d.cellEnd[c])
            d.cellTimeText[c] = time
            d.cellTimeWidth[c] = measurer.width(time, 0, time.length, GuideTextStyle.CELL_TIME) * (scale / measurer.scale)
        }
        return d
    }

    /** The frame; [images] null = logos off for the frame (names drawn). */
    fun build(measurer: GuideTextMeasurer, scale: Float = 1f, images: GuideImages? = null, gridFocused: Boolean = true): GuideFrame {
        val labels = labels()
        val chans = channels()
        val d = data(measurer, labels, scale)
        val frame = GuideFrame()
        frame.metrics = GuideMetricsImpl(scale)
        frame.rows = FakeGuideRows(chans)
        frame.data = d
        frame.labels = labels
        frame.images = images
        frame.showLogos = images != null
        frame.nowMin = NOW
        frame.windowStartMin = WINDOW_START
        frame.topRow = 0
        frame.tunedHash = chans[TUNED_ROW].identityHash
        frame.contextLabel = labels.contextLabel(WINDOW_START, NOW)
        frame.gridFocused = gridFocused
        // Focus: the on-now cell of FOCUS_ROW.
        val r = FOCUS_ROW - d.firstRow
        val f = d.rowFirstCell[r]
        var focusCell = f
        for (c in f until f + d.rowCellCount[r]) if (d.cellStart[c] <= NOW && NOW < d.cellEnd[c]) focusCell = c
        frame.focus = GridFocus(FOCUS_ROW, d.cellStart[focusCell], d.cellEnd[focusCell], d.cellKind[focusCell], NOW, WINDOW_START, 0)
        // A reminder on the next programme of row 1.
        val r1 = 1 - d.firstRow
        var remind = -1
        for (c in d.rowFirstCell[r1] until d.rowFirstCell[r1] + d.rowCellCount[r1]) if (remind < 0 && d.cellStart[c] > NOW) remind = c
        frame.reminders = ReminderIndex.of(listOf(chans[1].identityHash to d.cellRawStart[remind]))
        return frame
    }

    /** scrollRow of the reference frame (one partial row during a vertical animation). */
    const val SCROLL_ROW = 0.5f
}
