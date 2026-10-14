package app.thebox.core.guide.state

import app.thebox.core.guide.DayItem
import app.thebox.core.guide.GuideDataWindow
import app.thebox.core.guide.TimeLabels

/**
 * Earliest/latest browsable minute and the day strip model (docs/design/m3-guide.md §3.3.4, §3.5.1). Pure; local time
 * comes only from [TimeLabels] (its offset tables), so day arithmetic stays correct on DST days (N1: a target is the
 * local date at the anchor's local time of day, corrected by the label tables, never `localMidnight + minuteOfDay`).
 */
class GuideLimits(private val labels: TimeLabels) {

    /** `D = max(pastDays, archiveDaysUpperBound)`; `earliest = localMidnight(now − D days)`, raised to the generation's start. */
    fun earliestMin(nowMin: Int, archiveDaysUpperBound: Int, window: GuideDataWindow, generationFromMin: Int? = null): Int {
        val d = maxOf(window.pastDays, archiveDaysUpperBound, 0)
        val back = nowMin - d * MIN_PER_DAY
        var e = back - labels.minuteOfDay(back)
        if (generationFromMin != null) e = maxOf(e, floor30(generationFromMin))
        return minOf(e, nowMin)
    }

    /** `latest = now + futureDays`, lowered to the generation's end, never below `now + 180`. */
    fun latestMin(nowMin: Int, window: GuideDataWindow, generationToMin: Int? = null): Int {
        var l = nowMin + maxOf(window.futureDays, 0) * MIN_PER_DAY
        if (generationToMin != null) l = minOf(l, generationToMin)
        return maxOf(l, nowMin + WINDOW_MIN)
    }

    /** `floor30` to a local :00/:30 boundary (zones with :15/:45 offsets still get local :00/:30). */
    fun floor30(min: Int): Int = min - Math.floorMod(labels.minuteOfDay(min), 30)

    fun ceil30(min: Int): Int {
        val r = Math.floorMod(labels.minuteOfDay(min), 30)
        return if (r == 0) min else min + (30 - r)
    }

    /** `clampWs(m) = clamp(m, floor30(earliest), max(floor30(earliest), ceil30(latest) − 180))`. */
    fun clampWs(m: Int, earliest: Int, latest: Int): Int {
        val lo = floor30(earliest)
        val hi = maxOf(lo, ceil30(latest) - WINDOW_MIN)
        return m.coerceIn(lo, hi)
    }

    /** The prototype's window for now: `floor30(now) − 30`. */
    fun nowWindow(nowMin: Int): Int = floor30(nowMin) - 30

    /** Epoch minute of local [day] at the local time of day of [anchorMin] (DST-safe through the label tables). */
    fun atDayKeepingTime(day: Int, anchorMin: Int): Int =
        atLocal(anchorMin + (day - labels.dayOf(anchorMin)) * MIN_PER_DAY, labels.minuteOfDay(anchorMin))

    /** Today at 20:00 local ("Tonight", product §3.2). */
    fun tonightMin(nowMin: Int): Int = atLocal(nowMin, PRIME_TIME_MOD)

    /** Start (local midnight) of [day], approximately (DST days may be an hour off; used for coverage tests only). */
    fun dayStartMin(day: Int, nowMin: Int): Int {
        val g = nowMin + (day - labels.dayOf(nowMin)) * MIN_PER_DAY
        return g - labels.minuteOfDay(g)
    }

    /**
     * Day strip items (§3.5.1): every local day from `day(earliest)` to `day(latest − 1)`, with "Tonight 20:00" right
     * after Today. A day whose span does not intersect `[coverFrom, coverTo)` is disabled; Today is always enabled.
     * Without a generation ([coverFrom] null) only Today and Tonight are enabled.
     */
    fun days(nowMin: Int, earliest: Int, latest: Int, coverFrom: Int?, coverTo: Int?): List<DayItem> {
        val today = labels.dayOf(nowMin)
        val first = labels.dayOf(earliest)
        val last = labels.dayOf(maxOf(earliest, latest - 1))
        val out = ArrayList<DayItem>(last - first + 2)
        for (d in first..last) {
            val from = dayStartMin(d, nowMin)
            val to = dayStartMin(d + 1, nowMin)
            val covered = coverFrom != null && coverTo != null && from < coverTo && to > coverFrom
            out += DayItem(d, dayLabel(d, today), enabled = d == today || covered)
            if (d == today) {
                val tonight = tonightMin(nowMin)
                if (tonight < latest) out += DayItem(d, TONIGHT_LABEL, enabled = true, primeTime = true)
            }
        }
        return out
    }

    /** "Today", "Yesterday", "Tomorrow", else `TimeLabels.dayLabel` ("Wed 15 Oct"). */
    fun dayLabel(day: Int, today: Int): String = when (day - today) {
        0 -> TODAY_LABEL
        -1 -> YESTERDAY_LABEL
        1 -> TOMORROW_LABEL
        else -> labels.dayLabel(day)
    }

    /** Target minute of a day-strip item (§3.5.1): Today → now; Tonight → today 20:00; else the anchor's clock time. */
    fun dayTarget(item: DayItem, anchorMin: Int, nowMin: Int): Int = when {
        item.primeTime -> tonightMin(nowMin)
        item.day == labels.dayOf(nowMin) -> nowMin
        else -> atDayKeepingTime(item.day, anchorMin)
    }

    /** Moves [guess] to local minute-of-day [mod] on the same local day (two correction passes cover a DST jump). */
    private fun atLocal(guess: Int, mod: Int): Int {
        var g = guess
        repeat(2) {
            var diff = mod - labels.minuteOfDay(g)
            if (diff > 720) diff -= MIN_PER_DAY
            if (diff < -720) diff += MIN_PER_DAY
            g += diff
        }
        return g
    }

    companion object {
        const val MIN_PER_DAY = 1_440
        const val WINDOW_MIN = 180
        const val PRIME_TIME_MOD = 20 * 60
        const val TODAY_LABEL = "Today"
        const val YESTERDAY_LABEL = "Yesterday"
        const val TOMORROW_LABEL = "Tomorrow"
        const val TONIGHT_LABEL = "Tonight 20:00"
    }
}
