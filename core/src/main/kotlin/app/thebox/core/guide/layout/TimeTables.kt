package app.thebox.core.guide.layout

import app.thebox.core.guide.TimeLabels
import java.time.ZoneId

/** WP-A stub (docs/design/m3-guide.md §1.2, §3.5): the `TimeLabels` implementation. */
class TimeTables : TimeLabels {
    override fun hm(min: Int): String = TODO("WP-A")
    override fun minuteOfDay(min: Int): Int = TODO("WP-A")
    override fun dayOf(min: Int): Int = TODO("WP-A")
    override fun dayLabel(day: Int): String = TODO("WP-A")
    override fun contextLabel(windowStartMin: Int, nowMin: Int): String = TODO("WP-A")
    override fun duration(minutes: Int): String = TODO("WP-A")
    override fun reset(zone: ZoneId, clock24: Boolean): Unit = TODO("WP-A")
}
