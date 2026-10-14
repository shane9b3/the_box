package app.thebox.core.epg.store

import app.thebox.core.epg.EpgReader
import app.thebox.core.epg.EpgWhitelist
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.store.StoreFixtures.at
import app.thebox.core.epg.store.StoreFixtures.request
import app.thebox.core.epg.store.StoreFixtures.store
import app.thebox.core.epg.testutil.EpgTestDb
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/design/m3-guide.md §2.7 / §3.10.2 (review B6): `search` per-text cap and CONTINUATION filter, `searchPast` newest
 * first with its own per-text cap, on FTS5 and FTS4. "Now" is 2026-10-13T12:00Z.
 */
class EpgSearchSplitTest {
    @TempDir
    lateinit var tmp: File

    private val now = at("12:00")

    private fun open(fts4: Boolean): EpgReader {
        val xml = EpgTestDb.xmltv {
            for (c in 0 until 20) {
                channel("n$c", "News $c")
                // Hourly "News" on 20 channels, 6 days back to 2 days ahead.
                var m = now - 6 * 1440
                while (m < now + 2 * 1440) {
                    programme("n$c", m, m + 60, "News")
                    m += 60
                }
            }
            channel("films", "Films")
            programme("films", at("03:00"), at("10:00"), "Marathon") // ended; split at 09:00
            programme("films", at("10:00"), at("17:00"), "Epic Saga") // on now; split at 16:00
            programme("films", at("17:00"), at("+1 00:00"), "Late Saga") // future; split at 23:00
        }.writeTo(File(tmp, "s.xml"))
        val store = store(File(tmp, if (fts4) "epg4" else "epg5"), fts4 = fts4)
        store.build(request(1L to xml, pastDays = 7, futureDays = 7), EpgWhitelistResolver { _, _ -> EpgWhitelist.ALL })
        return store.openReader()!!
    }

    private fun check(fts4: Boolean) = open(fts4).use { r ->
        val label = if (fts4) "FTS4" else "FTS5"

        val live = r.search("news", now, limit = 150, perText = 8)
        assertEquals(8, live.size, "$label: per-text cap on the only matching text")
        assertTrue(live.all { it.rawEndMin > now }, "$label: on now or upcoming only")
        assertEquals(now, live.first().rawStartMin, "$label: start order, on-now first")
        assertEquals(List(4) { now } + List(4) { now + 60 }, live.map { it.rawStartMin }, "$label: 20 simultaneous copies do not starve the next airing")
        assertEquals(List(8) { now }, r.search("news", now + 2 * 1440 - 30, limit = 150, perText = 8).map { it.rawStartMin - (now + 2 * 1440 - 60) + now }, "$label: no upcoming left → every slot on now")

        val past = r.searchPast("news", now, now - 7 * 1440, limit = 100, perText = 5)
        assertEquals(5, past.size, "$label: past per-text cap")
        assertTrue(past.all { it.rawEndMin <= now }, "$label: ended only")
        assertEquals(List(5) { now - 60 }, past.map { it.rawStartMin }, "$label: newest first (20 channels air it at 11:00)")
        val longPast = r.searchPast("news", now, now - 7 * 1440, limit = 100, perText = 45)
        assertEquals(45, longPast.size)
        assertEquals(longPast.map { it.rawStartMin }.sortedDescending(), longPast.map { it.rawStartMin }, "$label: non-increasing starts")
        assertEquals(now - 180, longPast.last().rawStartMin, "$label: 20 + 20 + 5 airings reach 09:00")

        assertEquals(3, r.search("news", now, limit = 3, perText = 8).size, "$label: overall limit")
        assertEquals(0, r.searchPast("news", now, now - 30, limit = 100, perText = 5).count { it.rawEndMin <= now - 30 }, "$label: look-back bound")

        // Split programmes: CONTINUATION segments never show; CONTINUED segments never show as past.
        assertEquals(listOf(at("10:00") to at("16:00")), r.search("epic", now).map { it.rawStartMin to it.rawEndMin }, "$label: on now, first segment only")
        assertEquals(listOf(at("17:00")), r.search("late saga", now).map { it.rawStartMin }, "$label: future split shows once")
        assertEquals(emptyList(), r.searchPast("marathon", now, now - 1440).map { it.rawStartMin }, "$label: ended split film is skipped")
        assertEquals(emptyList(), r.searchPast("epic", now, now - 1440).map { it.rawStartMin }, "$label: a film in progress never reads as finished")
        assertEquals(emptyList(), r.searchPast("news", now, now), "$label: empty look-back")
    }

    @Test fun fts5() = check(fts4 = false)

    @Test fun fts4() = check(fts4 = true)
}
