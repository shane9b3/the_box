package app.thebox.core.guide.state

import app.thebox.core.guide.NumberEntry

/** WP-D stub (docs/design/m3-guide.md §1.5, §3.9.4). */
class NumberEntryImpl : NumberEntry {
    override val digits: String get() = TODO("WP-D")
    override fun digit(d: Int, nowMs: Long): Boolean = TODO("WP-D")
    override fun adjust(delta: Int): Unit = TODO("WP-D")
    override fun advance(): Unit = TODO("WP-D")
    override fun commitValue(): Int? = TODO("WP-D")
    override fun due(nowMs: Long): Boolean = TODO("WP-D")
    override fun clear(): Unit = TODO("WP-D")
}
