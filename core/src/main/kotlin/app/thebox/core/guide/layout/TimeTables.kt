package app.thebox.core.guide.layout

import app.thebox.core.guide.TimeLabels
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The `TimeLabels` implementation (docs/design/m3-guide.md §2.2, §3.5; review S12).
 *
 * - [hm] returns entries of two pre-built 1,440-string tables (24 h and 12 h): no allocation, interned.
 * - [minuteOfDay]/[dayOf] are integer arithmetic over a precomputed table of UTC-offset transitions covering
 *   ±[SPAN_DAYS] around the last [reset] (a zone has at most ~2 transitions a year, so the table is tiny). Outside
 *   that span they fall back to `ZoneRules` (allocates; never hit by the guide, whose window is ≤ ±16 days).
 * - [reset] builds a new immutable [Tables] and publishes it through one `@Volatile` field: readers on main and
 *   on `box-guide-epg` always see one consistent snapshot.
 *
 * Labels are English (the guide's other strings, "TONIGHT" etc., are English in M3; [locale] only affects
 * [dayLabel]'s weekday/month names).
 */
class TimeTables(
    zone: ZoneId = ZoneId.systemDefault(),
    clock24: Boolean = true,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val locale: Locale = Locale.ENGLISH,
) : TimeLabels {

    private class Tables(
        val zone: ZoneId,
        val clock24: Boolean,
        /** Epoch minutes at which [offsetAfter] starts, ascending; offset before the first is [offsetBefore]. */
        val transitionMin: IntArray,
        val offsetAfter: IntArray,
        val offsetBefore: Int,
        val fromMin: Int,
        val toMin: Int,
        val firstDay: Int,
        val dayLabels: Array<String>,
    )

    @Volatile private var tables: Tables = build(zone, clock24)

    override fun reset(zone: ZoneId, clock24: Boolean) {
        tables = build(zone, clock24)
        version++
    }

    @Volatile override var version: Int = 0
        private set

    /** Current zone and clock form (for tests and the panel's 12 h clock). */
    val zone: ZoneId get() = tables.zone
    val clock24: Boolean get() = tables.clock24

    override fun hm(min: Int): String {
        val t = tables
        val m = minuteOfDay(t, min)
        return if (t.clock24) HM24[m] else HM12[m]
    }

    override fun minuteOfDay(min: Int): Int = minuteOfDay(tables, min)

    override fun dayOf(min: Int): Int = Math.floorDiv(min + offsetMin(tables, min), 1440)

    override fun dayLabel(day: Int): String {
        val t = tables
        val i = day - t.firstDay
        if (i >= 0 && i < t.dayLabels.size) return t.dayLabels[i]
        return formatDay(day, locale)
    }

    override fun contextLabel(windowStartMin: Int, nowMin: Int): String {
        val t = tables
        val mid = windowStartMin + 90
        val dMid = Math.floorDiv(mid + offsetMin(t, mid), 1440)
        val dNow = Math.floorDiv(nowMin + offsetMin(t, nowMin), 1440)
        return when (dMid - dNow) {
            0 -> {
                val hour = minuteOfDay(t, mid) / 60
                when {
                    mid < nowMin - 60 -> EARLIER_TODAY
                    hour < 12 -> THIS_MORNING
                    hour < 18 -> THIS_AFTERNOON
                    else -> TONIGHT
                }
            }
            1 -> TOMORROW
            -1 -> YESTERDAY
            else -> WEEKDAYS_UPPER[Math.floorMod(dMid + 3, 7)]
        }
    }

    override fun duration(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    /** Local :00/:30 boundary at or before [min] (§3.3.1 `floor30`). */
    fun floor30(min: Int): Int = min - minuteOfDay(min) % 30

    private fun minuteOfDay(t: Tables, min: Int): Int = Math.floorMod(min + offsetMin(t, min), 1440)

    private fun offsetMin(t: Tables, min: Int): Int {
        if (min < t.fromMin || min >= t.toMin) {
            return t.zone.rules.getOffset(Instant.ofEpochSecond(min.toLong() * 60)).totalSeconds / 60
        }
        val tr = t.transitionMin
        // Linear: a ±SPAN_DAYS table holds a handful of entries at most.
        var off = t.offsetBefore
        for (i in tr.indices) {
            if (min >= tr[i]) off = t.offsetAfter[i] else break
        }
        return off
    }

    private fun build(zone: ZoneId, clock24: Boolean): Tables {
        val nowMin = Math.floorDiv(nowMs(), 60_000L).toInt()
        val fromMin = nowMin - SPAN_DAYS * 1440
        val toMin = nowMin + SPAN_DAYS * 1440
        val rules = zone.rules
        val trMin = ArrayList<Int>()
        val trOff = ArrayList<Int>()
        val start = Instant.ofEpochSecond(fromMin.toLong() * 60)
        val offsetBefore = rules.getOffset(start).totalSeconds / 60
        var tr = rules.nextTransition(start)
        while (tr != null && tr.instant.epochSecond < toMin.toLong() * 60) {
            trMin += (tr.instant.epochSecond / 60).toInt()
            trOff += tr.offsetAfter.totalSeconds / 60
            tr = rules.nextTransition(tr.instant)
        }
        val firstDay = Math.floorDiv(nowMin + offsetBefore, 1440) - LABEL_DAYS
        val labels = Array(2 * LABEL_DAYS + 1) { formatDay(firstDay + it, locale) }
        return Tables(zone, clock24, trMin.toIntArray(), trOff.toIntArray(), offsetBefore, fromMin, toMin, firstDay, labels)
    }

    companion object {
        /** Transition table span around the last reset (days). */
        const val SPAN_DAYS = 400
        /** Pre-built day labels around the last reset (days each side). */
        const val LABEL_DAYS = 31

        const val EARLIER_TODAY = "EARLIER TODAY"
        const val THIS_MORNING = "THIS MORNING"
        const val THIS_AFTERNOON = "THIS AFTERNOON"
        const val TONIGHT = "TONIGHT"
        const val TOMORROW = "TOMORROW"
        const val YESTERDAY = "YESTERDAY"
        /** Monday first (epoch day 0, 1970-01-01, is a Thursday: index (day + 3) mod 7). */
        val WEEKDAYS_UPPER = arrayOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")

        /** "19:30" for every minute of the day. */
        val HM24: Array<String> = Array(1440) { m -> "%02d:%02d".format(Locale.ROOT, m / 60, m % 60).intern() }
        /** "7:30" for every minute of the day (the 12 h form drops AM/PM in the ruler and cells). */
        val HM12: Array<String> = Array(1440) { m ->
            val h = (m / 60) % 12
            "%d:%02d".format(Locale.ROOT, if (h == 0) 12 else h, m % 60).intern()
        }

        private fun formatDay(day: Int, locale: Locale): String {
            val d = LocalDate.ofEpochDay(day.toLong())
            return d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) + " " + d.dayOfMonth + " " +
                d.month.getDisplayName(TextStyle.SHORT, locale)
        }
    }
}
