package app.thebox.core.guide.layout

import app.thebox.core.guide.GridContext
import app.thebox.core.guide.GridFocus
import app.thebox.core.guide.GridMove
import app.thebox.core.guide.GridMoveResult
import app.thebox.core.guide.GuideNavigator

/** WP-A stub (docs/design/m3-guide.md §1.2, §3.3, §3.4.2). */
class GuideNavigatorImpl : GuideNavigator {
    override fun move(from: GridFocus, move: GridMove, arg: Int, ctx: GridContext): GridMoveResult = TODO("WP-A")
    override fun focusAt(row: Int, atMin: Int, ctx: GridContext): GridFocus = TODO("WP-A")
    override fun cellAt(row: Int, minute: Int, ctx: GridContext): Long = TODO("WP-A")
    override fun kindAt(row: Int, minute: Int, ctx: GridContext): Byte = TODO("WP-A")
}
