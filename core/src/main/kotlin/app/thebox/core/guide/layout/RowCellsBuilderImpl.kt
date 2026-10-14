package app.thebox.core.guide.layout

import app.thebox.core.epg.EpgWindow
import app.thebox.core.guide.CellSink
import app.thebox.core.guide.RowCellsBuilder

/** WP-A stub (docs/design/m3-guide.md §1.2, §3.2.4). */
class RowCellsBuilderImpl : RowCellsBuilder {
    override fun build(w: EpgWindow, from: Int, to: Int, fromMin: Int, toMin: Int, noEpg: Boolean, out: CellSink): Int = TODO("WP-A")
}
