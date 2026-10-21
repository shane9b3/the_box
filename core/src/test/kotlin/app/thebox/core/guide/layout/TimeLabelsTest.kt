package app.thebox.core.guide.layout

import java.lang.management.ManagementFactory
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** docs/design/m3-guide.md §3.5, §5.1 "TimeLabelsTest"; review S12. */
class TimeLabelsTest {
    private val london = ZoneId.of("Europe/London")

    private fun min(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int): Int =
        (LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toEpochSecond() / 60).toInt()

    private fun tables(zone: ZoneId, clock24: Boolean = true, nowMin: Int = min(zone, 2026, 10, 14, 19, 47)) =
        TimeTables(zone, clock24, nowMs = { nowMin * 60_000L })

    @Test
    fun clock24And12() {
        val t = tables(london)
        val m = min(london, 2026, 10, 14, 19, 30)
        assertEquals("19:30", t.hm(m))
        assertEquals("00:05", t.hm(min(london, 2026, 10, 14, 0, 5)))
        t.reset(london, clock24 = false)
        assertEquals("7:30", t.hm(m))
        assertEquals("12:05", t.hm(min(london, 2026, 10, 14, 0, 5)))
        assertEquals("12:00", t.hm(min(london, 2026, 10, 14, 12, 0)))
        // Interned table entries: the same instance every call.
        assertSame(t.hm(m), t.hm(m + 1440))
    }

    @Test
    fun dstDaysInLondon() {
        val t = tables(london, nowMin = min(london, 2026, 3, 29, 12, 0))
        // 2026-03-29 01:00 GMT → 02:00 BST.
        val before = (LocalDateTime.of(2026, 3, 29, 0, 59).toEpochSecond(ZoneOffset.UTC) / 60).toInt()
        assertEquals("00:59", t.hm(before))
        assertEquals("02:00", t.hm(before + 1))
        assertEquals(120, t.minuteOfDay(before + 1))
        // 2026-10-25 02:00 BST → 01:00 GMT: 01:30 happens twice.
        val t2 = tables(london, nowMin = min(london, 2026, 10, 25, 12, 0))
        val firstOneThirty = (LocalDateTime.of(2026, 10, 25, 0, 30).toEpochSecond(ZoneOffset.UTC) / 60).toInt()
        assertEquals("01:30", t2.hm(firstOneThirty))
        assertEquals("01:30", t2.hm(firstOneThirty + 60))
        assertEquals(t2.dayOf(firstOneThirty), t2.dayOf(firstOneThirty + 60))
    }

    @Test
    fun agreesWithZoneRulesAcrossManyZones() {
        for (id in listOf("UTC", "Europe/London", "America/New_York", "Asia/Kolkata", "Asia/Kathmandu", "Australia/Lord_Howe", "Pacific/Chatham")) {
            val zone = ZoneId.of(id)
            val now = min(zone, 2026, 10, 14, 19, 47)
            val t = tables(zone, nowMin = now)
            var m = now - 20 * 1440
            while (m < now + 20 * 1440) {
                val ldt = LocalDateTime.ofEpochSecond(m * 60L, 0, zone.rules.getOffset(java.time.Instant.ofEpochSecond(m * 60L)))
                assertEquals(ldt.hour * 60 + ldt.minute, t.minuteOfDay(m), "$id at $m")
                assertEquals(ldt.toLocalDate().toEpochDay().toInt(), t.dayOf(m), "$id day at $m")
                m += 37
            }
        }
    }

    @Test
    fun contextLabelsAtEachBoundary() {
        val zone = ZoneOffset.UTC
        val now = min(zone, 2026, 10, 14, 19, 47)
        val t = tables(zone, nowMin = now)
        fun label(h: Int, m: Int, day: Int = 14): String = t.contextLabel(min(zone, 2026, 10, day, h, m) - 90, now)
        // mid = ws + 90; labels are judged at mid.
        assertEquals("TONIGHT", label(19, 0))
        assertEquals("EARLIER TODAY", label(18, 46))   // mid 18:46 < now − 60 (18:47)
        assertEquals("TONIGHT", label(18, 47))
        assertEquals("EARLIER TODAY", label(11, 59))
        val morning = tables(zone, nowMin = min(zone, 2026, 10, 14, 6, 0))
        assertEquals("THIS MORNING", morning.contextLabel(min(zone, 2026, 10, 14, 11, 59) - 90, min(zone, 2026, 10, 14, 6, 0)))
        assertEquals("THIS AFTERNOON", morning.contextLabel(min(zone, 2026, 10, 14, 12, 0) - 90, min(zone, 2026, 10, 14, 6, 0)))
        assertEquals("THIS AFTERNOON", morning.contextLabel(min(zone, 2026, 10, 14, 17, 59) - 90, min(zone, 2026, 10, 14, 6, 0)))
        assertEquals("TONIGHT", morning.contextLabel(min(zone, 2026, 10, 14, 18, 0) - 90, min(zone, 2026, 10, 14, 6, 0)))
        assertEquals("TOMORROW", label(0, 0, day = 15))
        assertEquals("YESTERDAY", label(23, 59, day = 13))
        assertEquals("FRIDAY", label(12, 0, day = 16))
        assertEquals("MONDAY", label(12, 0, day = 12))
    }

    @Test
    fun windowsCentredAfterMidnightReadTonight() {
        // Decision 2026-10-19, ruling 9 (owner's screenshot: "12:22 AM" beside "THIS MORNING 11:30").
        val zone = ZoneOffset.UTC
        val late = min(zone, 2026, 10, 15, 0, 22)
        val t = tables(zone, nowMin = late)
        val ws = min(zone, 2026, 10, 14, 23, 30) // window 23:30 → 02:30, mid 01:00 on now's day
        assertEquals("TONIGHT", t.contextLabel(ws, late))
        assertEquals("TONIGHT", t.contextLabel(min(zone, 2026, 10, 15, 4, 59) - 90, late)) // mid 04:59
        assertEquals("THIS MORNING", t.contextLabel(min(zone, 2026, 10, 15, 5, 0) - 90, late)) // mid 05:00
        // An early-hours window seen later in the day is still "earlier today".
        val nine = min(zone, 2026, 10, 15, 9, 0)
        val t9 = tables(zone, nowMin = nine)
        assertEquals("EARLIER TODAY", t9.contextLabel(min(zone, 2026, 10, 15, 0, 30), nine))
    }

    @Test
    fun dayLabelsAndDurations() {
        val t = tables(ZoneOffset.UTC)
        val day = t.dayOf(min(ZoneOffset.UTC, 2026, 10, 13, 12, 0))
        assertEquals("Tue 13 Oct", t.dayLabel(day))
        assertEquals("Thu 15 Oct", t.dayLabel(day + 2))
        assertEquals("Thu 1 Jan", t.dayLabel(LocalDateTime.of(2032, 1, 1, 0, 0).toLocalDate().toEpochDay().toInt())) // outside the table
        assertSame(t.dayLabel(day), t.dayLabel(day))
        assertEquals("30 min", t.duration(30))
        assertEquals("1 h", t.duration(60))
        assertEquals("1 h 30 min", t.duration(90))
        assertEquals("5 min", t.duration(5))
    }

    @Test
    fun minuteOfDayAndDayOfAllocateNothing() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val t = tables(london)
        val base = min(london, 2026, 10, 14, 0, 0)
        var sink = 0
        repeat(20_000) { sink += t.minuteOfDay(base + it) + t.dayOf(base + it) + t.hm(base + it).length }
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        for (i in 0 until 100_000) sink += t.minuteOfDay(base + i) + t.dayOf(base + i) + t.hm(base + i).length + t.contextLabel(base + i, base).length
        val after = bean.getThreadAllocatedBytes(id)
        assertTrue(after - before < 1024, "allocated ${after - before} bytes (sink $sink)")
    }

    @Test
    fun concurrentResetIsConsistent() {
        val t = tables(london)
        val base = min(london, 2026, 10, 14, 0, 0)
        val error = AtomicReference<Throwable?>(null)
        val stop = CountDownLatch(1)
        val reader = thread {
            try {
                while (stop.count > 0L) {
                    for (i in 0 until 1440) {
                        val s = t.hm(base + i)
                        // Either clock form, never a torn value.
                        assertTrue(s.length in 4..5 && s[s.length - 3] == ':', s)
                        assertTrue(t.minuteOfDay(base + i) in 0 until 1440)
                    }
                }
            } catch (e: Throwable) { error.set(e) }
        }
        repeat(2_000) { t.reset(if (it % 2 == 0) london else ZoneId.of("Asia/Kolkata"), it % 3 != 0) }
        stop.countDown()
        reader.join()
        assertNull(error.get())
    }
}
