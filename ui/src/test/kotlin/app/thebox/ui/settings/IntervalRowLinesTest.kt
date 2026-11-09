package app.thebox.ui.settings

import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.tree.SportsRows
import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/design/m6-sports-update-interval.md, fix pass 4: the focused interval row's detail wraps to 6 lines; nothing else. */
class IntervalRowLinesTest {
    @Test fun theFocusedIntervalRowWrapsToSixLinesAndOtherRowsStayOne() {
        val interval = row(SportsRows.INTERVAL)
        assertEquals(6, descLines(interval, focused = true))
        assertEquals(1, descLines(interval, focused = false))
        assertEquals(1, descLines(row(SportsRows.DELAY), focused = true))
    }

    private fun row(id: app.thebox.core.settings.RowId) =
        SettingsRow(id, "label", description = "detail", kind = RowKind.Choice(emptyList(), 0))
}
