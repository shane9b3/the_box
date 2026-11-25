package app.thebox.core.events

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** docs/design/m6-event-channels.md §3, §11.1: table-driven from `events/names.tsv`, plus the fuzz test. */
class EventNameParserTest {
    private val defaultNow = Instant.parse("2026-11-24T12:00:00Z").toEpochMilli()
    private val newYork = ZoneId.of("America/New_York")
    private val ctx = EventParseContext(defaultNow, newYork)

    private class Row(val line: Int, val c: List<String>) {
        operator fun get(i: Int): String = c.getOrElse(i) { "" }
        fun opt(i: Int): String? = get(i).let { if (it == "~" || it.isEmpty()) null else it }
    }

    private fun rows(): List<Row> {
        val text = javaClass.getResource("/events/names.tsv")!!.readText()
        return text.lines().mapIndexedNotNull { i, raw ->
            if (raw.startsWith("#")) null else Row(i + 1, raw.split('\t'))
        }.filter { it.c.size >= 4 }
    }

    @Test
    fun tableHoldsTheDesignRowsAndTheSyntheticCases() {
        val rows = rows()
        assertTrue(rows.size >= 150, "rows: ${rows.size}")
        // The §11.1 table's 60 real names plus the extra real formats come first, before the owner's examples.
        assertTrue(rows.count { it.line < rows.first { r -> r[1].isNotEmpty() }.line } >= 78)
    }

    @Test
    fun everyTableRow() {
        val failures = ArrayList<String>()
        for (row in rows()) {
            try {
                check(row)
            } catch (e: AssertionError) {
                failures += "line ${row.line} `${row[0]}`: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun check(row: Row) {
        val name = row[0]
        val now = row.opt(1)?.let { Instant.parse(it).toEpochMilli() } ?: defaultNow
        val assumed = row.opt(2)?.let { ZoneId.of(it) } ?: newYork
        val r = EventNameParser.parse(name, EventParseContext(now, assumed))
        when (row[3]) {
            "NONE" -> {
                assertEquals(ParsedName.None, r)
                assertFalse(EventNameParser.isEmptySlot(name), "isEmptySlot")
                assertEquals(row.opt(4), EventNameParser.slotOf(name), "slotOf")
            }
            "EMPTY" -> {
                assertEquals(ParsedName.Empty(row.opt(4)), r)
                assertTrue(EventNameParser.isEmptySlot(name), "isEmptySlot")
                assertEquals(row.opt(4), EventNameParser.slotOf(name), "slotOf")
            }
            "EVENT" -> {
                val e = assertIs<ParsedName.Event>(r, "kind")
                assertEquals(row.opt(4), e.slot, "slot")
                assertEquals(row[5], e.title, "title")
                assertEquals(row.opt(6), e.subtitle, "subtitle")
                assertEquals(row[8], e.zoneId, "zone id")
                val local = Instant.ofEpochMilli(e.startUtcMs).atZone(ZoneId.of(e.zoneId))
                val pattern = if (row[7].length > 16) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm"
                assertEquals(row[7], local.format(DateTimeFormatter.ofPattern(pattern)), "local start")
                assertEquals(EventZoneSource.valueOf(row[9]), e.zone, "source")
                assertEquals(EventShape.valueOf(row[10]), e.shape, "shape")
                row.opt(11)?.let { assertEquals(Instant.parse(it).toEpochMilli(), e.startUtcMs, "utc start") }
                assertEquals(EventDuration.minutes(e.title) * 60_000L, e.endUtcMs - e.startUtcMs, "end")
                assertFalse(EventNameParser.isEmptySlot(name), "isEmptySlot")
                assertEquals(e.slot, EventNameParser.slotOf(name), "slotOf")
            }
            else -> throw AssertionError("bad kind ${row[3]}")
        }
    }

    /**
     * Code review 2026-11-25, data m1: the bare forms are slots only in an event-like provider group; "NO EVENT" and the
     * event shapes in any group. The one-argument answers (the table above) are unchanged.
     */
    @Test
    fun bareFormsNeedAnEventLikeGroup() {
        for (name in listOf("Sky Sports 1 |", "beIN Sports 3:", "BBC 1 | Offline", "Movies 24/7 | Offline")) {
            assertTrue(EventNameParser.isBareSlot(name), name)
            assertFalse(EventNameParser.isEmptySlot(name, "UK | Entertainment"), name)
            assertFalse(EventNameParser.isEmptySlot(name, null), name)
            assertEquals(null, EventNameParser.slotOf(name, "UK | Entertainment"), name)
        }
        for ((name, group) in listOf("NHL 02:" to "Sports | NHL", "NFL GP 02 | Offline" to "NFL Game Pass", "MLS 04 |" to "LIVE EVENTS", "ESPN UNLTD 254:" to "US| ESPN+ PPV")) {
            assertTrue(EventNameParser.isEmptySlot(name, group), name)
            assertTrue(EventNameParser.slotOf(name, group) != null, name)
        }
        assertFalse(EventNameParser.isBareSlot("Sportsnet+ 11: NO EVENT"))
        assertTrue(EventNameParser.isEmptySlot("Sportsnet+ 11: NO EVENT", null))
        assertFalse(EventNameParser.isBareSlot("Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET"))
        assertEquals("Sportsnet+ 09", EventNameParser.slotOf("Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET", "Misc"))
        assertFalse(EventGroups.eventLike(null))
        assertFalse(EventGroups.eventLike("UK | Entertainment"))
        assertTrue(EventGroups.eventLike("Sports | OHL/WHL/QMJHL"))
        assertTrue(EventGroups.eventLike("NHL GP"))
    }

    /** Code review 2026-11-25, owner item 17: the conservative title case of an ALL-CAPS bare-shape title. */
    @Test
    fun titleCaseOfAnAllCapsTitle() {
        assertEquals("London Knights vs. Erie Otters", EventNameParser.titleCase("LONDON KNIGHTS VS ERIE OTTERS"))
        assertEquals("Val-D'or vs. Gatineau", EventNameParser.titleCase("VAL-D'OR VS GATINEAU"))
        assertEquals("OHL Showcase: USA vs. Canada", EventNameParser.titleCase("OHL SHOWCASE: USA VS CANADA"))
        assertEquals("St. Louis vs. Utah", EventNameParser.titleCase("ST. LOUIS VS. UTAH"))
        // Mixed case is left alone (only the bare shape applies it, and only to an ALL-CAPS title).
        val mixed = assertIs<ParsedName.Event>(EventNameParser.parse("OHL 09 | Owen Sound Attack VS Guelph Storm NOV 25 07:00PM ET", ctx))
        assertEquals("Owen Sound Attack VS Guelph Storm", mixed.title)
        val m = EventMatchup.of(assertIs<ParsedName.Event>(EventNameParser.parse("OHL 03 | 2026 LONDON KNIGHTS VS ERIE OTTERS NOV 25 07:00PM ET", ctx)).title)
        assertEquals(Matchup(listOf("london", "knights"), listOf("erie", "otters"), ordered = false), m)
    }

    @Test
    fun deterministicForTheSameInputs() {
        for (row in rows()) {
            assertEquals(EventNameParser.parse(row[0], ctx), EventNameParser.parse(row[0], ctx), row[0])
        }
    }

    @Test
    fun tabsCountAsWhitespace() {
        val e = assertIs<ParsedName.Event>(EventNameParser.parse("NHL 01:\tGolden Knights\tvs. Oilers\t(11.24 8:00 PM ET)", ctx))
        assertEquals("NHL 01", e.slot)
        assertEquals("Golden Knights vs. Oilers", e.title)
    }

    @Test
    fun estimatedEnds() {
        fun minutes(name: String) = (EventNameParser.parse(name, ctx) as ParsedName.Event).let { (it.endUtcMs - it.startUtcMs) / 60_000L }
        assertEquals(180, minutes("Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET"))
        assertEquals(210, minutes("ESPN UNLTD 001: NFL: NFL Football: 2026 Week 11 Cincinnati Bengals vs. Washington Commanders (11.24 5:00 AM ET)"))
        assertEquals(135, minutes("Paramount+ 05: UEFA Champions League: Bodø/Glimt vs. LASK @ 24 Nov 01:00 PM ET"))
        // §3.5 reads the title only: the slot's "UEFA Champions League" does not count.
        assertEquals(180, minutes("UEFA Champions League 01: Galatasaray - Aston Villa @ 24 Nov 05:00 PM London"))
        assertEquals(60, minutes("Sportsnet+ 01: Sportsnet Central: Nov. 23 @ 24 Nov 05:00 AM ET"))
    }

    @Test
    fun junkNeverParses() {
        assertEquals(ParsedName.None, EventNameParser.parse("", ctx))
        assertEquals(ParsedName.None, EventNameParser.parse(" \t ", ctx))
        val long = "Sportsnet+ 01: " + "x".repeat(2_000) + " @ 24 Nov 08:00 PM ET"
        assertEquals(ParsedName.None, EventNameParser.parse(long, ctx))
        assertNull(EventNameParser.slotOf(long))
        assertFalse(EventNameParser.isEmptySlot("x".repeat(2_000) + " 01:"))
    }

    @Test
    fun extremeClocksNeverThrow() {
        for (now in longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L)) {
            val c = EventParseContext(now, newYork)
            EventNameParser.parse("Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET", c)
            EventNameParser.parse("NHL 01: Golden Knights vs. Oilers (11.24 8:00 PM ET)", c)
            EventNameParser.parse("(PDC 01) | A (0000-01-01 00:00:00)", c)
            EventNameParser.parse("(PDC 01) | A (9999-12-31 23:59:59)", c)
        }
    }

    // ------------------------------------------------------------------ fuzz (§3.1: never throws)

    private val alphabet = "0123456789 :|@()[].-/#•AaPpMmOoETZNovDecJanSeptTue,`’\tLondonUTCAEST+_éø"

    @Test
    fun fuzzRandomStrings() {
        val rnd = Random(20261125)
        repeat(10_000) {
            val n = rnd.nextInt(0, 90)
            val s = buildString { repeat(n) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            exercise(s)
        }
        repeat(500) {
            val n = rnd.nextInt(0, 60)
            val s = buildString { repeat(n) { append(rnd.nextInt(0, 0x3000).toChar()) } }
            exercise(s)
        }
    }

    @Test
    fun fuzzMutationsOfRealNames() {
        val real = rows().map { it[0] }.filter { it.isNotEmpty() }
        val rnd = Random(424242)
        repeat(10_000) {
            val base = StringBuilder(real[rnd.nextInt(real.size)])
            repeat(rnd.nextInt(1, 4)) {
                if (base.isEmpty()) return@repeat
                val at = rnd.nextInt(base.length)
                when (rnd.nextInt(4)) {
                    0 -> base.deleteCharAt(at)
                    1 -> base.insert(at, alphabet[rnd.nextInt(alphabet.length)])
                    2 -> base.setCharAt(at, alphabet[rnd.nextInt(alphabet.length)])
                    else -> base.setLength(at)
                }
            }
            exercise(base.toString())
        }
    }

    private fun exercise(s: String) {
        val r = try {
            EventNameParser.parse(s, ctx)
        } catch (e: Throwable) {
            fail("threw on `$s`: $e")
        }
        val slot = EventNameParser.slotOf(s)
        val empty = EventNameParser.isEmptySlot(s)
        EventMatchup.of(s)
        EventDuration.minutes(s)
        EpgFiller.isFiller(s)
        when (r) {
            is ParsedName.Event -> {
                assertTrue(r.title.isNotBlank(), s)
                assertTrue(r.endUtcMs > r.startUtcMs, s)
                assertEquals(r.slot, slot, s)
                assertFalse(empty, s)
            }
            is ParsedName.Empty -> {
                assertTrue(empty, s)
                assertEquals(r.slot, slot, s)
            }
            ParsedName.None -> assertFalse(empty, s)
        }
    }
}
